package com.majdi.cinematicai;

/**
 * Geometry and frame of an edit, applied after colour on the display-oriented image, only when
 * the user asked for them (the default state is the identity and touches nothing).
 *
 * Order: quarter turns + flips (exact pixel permutation) -> perspective (keystone; the chosen
 * trapezoid is stretched to the full canvas, so no empty areas appear) -> straighten (rotation
 * with the minimum zoom that keeps the corners filled) -> crop (rounded to whole pixels) -> frame.
 * Resolution is never reduced: one output pixel per source pixel along the crop, and the frame
 * adds canvas around the photo instead of cutting into it.
 *
 * Pure-integer cases (turns, flips, crop) copy pixels exactly; rotation and perspective use
 * bilinear sampling.
 */
final class Geometry {

    final int srcW, srcH;        // colour-graded image as stored (sensor layout at export)
    final int dispW, dispH;      // the same image in display orientation
    private final int orient;    // EXIF orientation of the stored pixels (1 = already upright)
    final int rw, rh;            // after quarter turns
    final int cx0, cy0, cw, ch;  // crop rectangle in the rw x rh canvas (whole pixels)
    final boolean exact;         // only turns/flips/crop
    final boolean crop;
    private final int rot;
    private final boolean fh, fv;
    private final double cosT, sinT, zoom;
    private final double[] H;    // unit square -> source quad (in rotated space), or null

    // frame
    final int outW, outH;        // final canvas
    final int px0, py0;          // where the photo goes on the canvas
    private final String frame;
    private final int color, holeColor;
    private final int barT;      // film band / sprocket geometry
    private final EditState st;

    Geometry(EditState s, int w, int h, boolean applyCrop) {
        this(s, w, h, 1, applyCrop);
    }

    /** w x h are the stored pixels; orientation maps them to display (EXIF 1..8). */
    Geometry(EditState s, int w, int h, int orientation, boolean applyCrop) {
        st = s;
        srcW = w; srcH = h;
        orient = orientation < 1 || orientation > 8 ? 1 : orientation;
        dispW = orient >= 5 ? h : w;
        dispH = orient >= 5 ? w : h;
        rot = s.rot90 & 3;
        fh = s.flipH; fv = s.flipV;
        rw = (rot & 1) == 1 ? dispH : dispW;
        rh = (rot & 1) == 1 ? dispW : dispH;
        double th = Math.toRadians(s.straighten);
        cosT = Math.cos(th); sinT = Math.sin(th);
        double a = Math.abs(th);
        zoom = a < 1e-9 ? 1.0 : Math.max((rw * Math.cos(a) + rh * Math.sin(a)) / rw, (rw * Math.sin(a) + rh * Math.cos(a)) / rh);
        boolean persp = Math.abs(s.perspV) > 1e-4f || Math.abs(s.perspH) > 1e-4f;
        H = persp ? quad(s.perspV, s.perspH) : null;
        exact = !persp && Math.abs(s.straighten) < 1e-4f;
        crop = applyCrop;
        if (applyCrop) {
            int x0 = Math.round(s.cropL * rw), y0 = Math.round(s.cropT * rh);
            int x1 = Math.round(s.cropR * rw), y1 = Math.round(s.cropB * rh);
            x0 = clamp(x0, 0, rw - 1); y0 = clamp(y0, 0, rh - 1);
            x1 = clamp(x1, x0 + 1, rw); y1 = clamp(y1, y0 + 1, rh);
            cx0 = x0; cy0 = y0; cw = x1 - x0; ch = y1 - y0;
        } else {
            cx0 = 0; cy0 = 0; cw = rw; ch = rh;
        }

        // ---- frame: canvas around the photo, never covering it
        frame = applyCrop ? s.frame : "none";
        color = s.frameColor;
        int lum = ((color >> 16 & 255) * 3 + (color >> 8 & 255) * 6 + (color & 255)) / 10;
        holeColor = lum < 128 ? 0xFFEDE8DF : 0xFF1A1A1A;
        int shortSide = Math.min(cw, ch);
        int t = Math.max(1, Math.round(s.frameThickness * shortSide));
        switch (frame) {
            case "border":
                outW = cw + 2 * t; outH = ch + 2 * t; px0 = t; py0 = t; barT = 0;
                break;
            case "canvas": {
                float ar = aspect(s.canvasAspect);
                int m = Math.round(s.frameMargin * Math.max(cw, ch));
                int needW = cw + 2 * m, needH = ch + 2 * m;
                int W = Math.max(needW, Math.round(needH * ar));
                int Hh = Math.max(needH, Math.round(W / ar));
                W = Math.max(W, Math.round(Hh * ar));
                outW = W; outH = Hh;
                px0 = m + Math.round((W - needW) * s.framePosX);
                py0 = m + Math.round((Hh - needH) * s.framePosY);
                barT = 0;
                break;
            }
            case "film": {
                // 35 mm strip: sprocket bands along the long edges, thin rebate on the short edges
                boolean land = cw >= ch;
                int band = Math.max(8, Math.round(0.11f * shortSide * (0.6f + 4f * s.frameThickness)));
                int side = Math.max(2, band / 4);
                if (land) { outW = cw + 2 * side; outH = ch + 2 * band; px0 = side; py0 = band; }
                else { outW = cw + 2 * band; outH = ch + 2 * side; px0 = band; py0 = side; }
                barT = band;
                break;
            }
            case "cinematic": {
                // letterbox mattes added above and below (the photo itself is not cropped)
                int bar = Math.max(2, Math.round(ch * (0.06f + 1.5f * s.frameThickness)));
                int side = Math.round(t * 0.25f);
                outW = cw + 2 * side; outH = ch + 2 * bar; px0 = side; py0 = bar; barT = bar;
                break;
            }
            default:
                outW = cw; outH = ch; px0 = 0; py0 = 0; barT = 0;
        }
    }

    boolean identity() {
        return rot == 0 && !fh && !fv && exact && cx0 == 0 && cy0 == 0 && cw == dispW && ch == dispH && frame.equals("none");
    }

    static float aspect(String a) {
        switch (a) {
            case "4:5": return 4f / 5f;
            case "5:4": return 5f / 4f;
            case "3:2": return 1.5f;
            case "2:3": return 2f / 3f;
            case "16:9": return 16f / 9f;
            case "9:16": return 9f / 16f;
            case "4:3": return 4f / 3f;
            case "3:4": return 3f / 4f;
            case "2.39:1": return 2.39f;
            default: return 1f;
        }
    }

    /** Projective map from the unit square to the keystone trapezoid (Heckbert), rotated space. */
    private double[] quad(float pv, float ph) {
        double dx = 0.22 * Math.abs(pv) * rw, dy = 0.22 * Math.abs(ph) * rh;
        double x0 = 0, y0 = 0, x1 = rw, y1 = 0, x2 = rw, y2 = rh, x3 = 0, y3 = rh;   // TL TR BR BL
        if (pv > 0) { x0 += dx; x1 -= dx; } else if (pv < 0) { x3 += dx; x2 -= dx; }
        if (ph > 0) { y1 += dy; y2 -= dy; } else if (ph < 0) { y0 += dy; y3 -= dy; }
        double sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
        double a, b, c, d, e, f, g, h;
        double dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
        double den = dx1 * dy2 - dx2 * dy1;
        g = (sx * dy2 - dx2 * sy) / den;
        h = (dx1 * sy - sx * dy1) / den;
        a = x1 - x0 + g * x1; b = x3 - x0 + h * x3; c = x0;
        d = y1 - y0 + g * y1; e = y3 - y0 + h * y3; f = y0;
        return new double[]{a, b, c, d, e, f, g, h};
    }

    /**
     * Source coordinate (pixel units, centres at +0.5) for a point of the rw x rh canvas.
     * out[0..1] = x, y in the colour image.
     */
    private void canvasToSource(double X, double Y, double[] out) {
        double x = X, y = Y;
        if (zoom != 1.0) {   // straighten: inverse rotation about the centre, then un-zoom
            double ux = (x - rw * 0.5) / zoom, uy = (y - rh * 0.5) / zoom;
            x = cosT * ux + sinT * uy + rw * 0.5;
            y = -sinT * ux + cosT * uy + rh * 0.5;
        }
        if (H != null) {
            double s = x / rw, t = y / rh;
            double w = H[6] * s + H[7] * t + 1.0;
            double qx = (H[0] * s + H[1] * t + H[2]) / w;
            double qy = (H[3] * s + H[4] * t + H[5]) / w;
            x = qx; y = qy;
        }
        // undo flips (applied after the turn) and the quarter turn
        if (fh) x = rw - x;
        if (fv) y = rh - y;
        double sx, sy;
        switch (rot) {
            case 1: sx = y; sy = dispH - x; break;           // 90 cw
            case 2: sx = dispW - x; sy = dispH - y; break;
            case 3: sx = dispW - y; sy = x; break;           // 270 cw
            default: sx = x; sy = y;
        }
        // display -> stored pixels (EXIF orientation)
        switch (orient) {
            case 2: out[0] = srcW - sx; out[1] = sy; break;
            case 3: out[0] = srcW - sx; out[1] = srcH - sy; break;
            case 4: out[0] = sx; out[1] = srcH - sy; break;
            case 5: out[0] = sy; out[1] = sx; break;
            case 6: out[0] = sy; out[1] = srcH - sx; break;
            case 7: out[0] = srcW - sy; out[1] = srcH - sx; break;
            case 8: out[0] = srcW - sy; out[1] = sx; break;
            default: out[0] = sx; out[1] = sy;
        }
    }

    /** Renders output rows [y0, y1) of the final canvas from the whole colour image. */
    void renderRows(int[] src, int y0, int y1, int[] out) {
        double[] q = new double[2];
        for (int y = y0; y < y1; y++) {
            int o = (y - y0) * outW;
            int yy = y - py0;
            for (int x = 0; x < outW; x++) {
                int xx = x - px0;
                if (xx < 0 || yy < 0 || xx >= cw || yy >= ch) { out[o + x] = framePixel(x, y); continue; }
                if (exact) {
                    canvasToSource(cx0 + xx + 0.5, cy0 + yy + 0.5, q);
                    int sx = clamp((int) Math.floor(q[0]), 0, srcW - 1), sy = clamp((int) Math.floor(q[1]), 0, srcH - 1);
                    out[o + x] = src[sy * srcW + sx] | 0xFF000000;
                } else {
                    canvasToSource(cx0 + xx + 0.5, cy0 + yy + 0.5, q);
                    out[o + x] = bilinear(src, q[0] - 0.5, q[1] - 0.5);
                }
            }
        }
    }

    int[] render(int[] src) {
        int[] out = new int[outW * outH];
        renderRows(src, 0, outH, out);
        return out;
    }

    /** Source rows needed for output rows [y0,y1) (inclusive range; for streaming exact cases). */
    int[] sourceRows(int y0, int y1) {
        double[] q = new double[2];
        double lo = 1e18, hi = -1e18;
        int ya = clamp(y0 - py0, 0, ch - 1), yb = clamp(y1 - 1 - py0, 0, ch - 1);
        for (int yy : new int[]{ya, yb}) for (int xx : new int[]{0, cw - 1}) {
            canvasToSource(cx0 + xx + 0.5, cy0 + yy + 0.5, q);
            lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]);
        }
        return new int[]{clamp((int) Math.floor(lo) - 1, 0, srcH - 1), clamp((int) Math.ceil(hi) + 1, 0, srcH - 1)};
    }

    private int bilinear(int[] s, double x, double y) {
        if (x < 0) x = 0; if (y < 0) y = 0;
        if (x > srcW - 1) x = srcW - 1; if (y > srcH - 1) y = srcH - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(srcW - 1, x0 + 1), y1 = Math.min(srcH - 1, y0 + 1);
        double fx = x - x0, fy = y - y0;
        int a = s[y0 * srcW + x0], b = s[y0 * srcW + x1], c = s[y1 * srcW + x0], d = s[y1 * srcW + x1];
        int r = mix(a >> 16 & 255, b >> 16 & 255, c >> 16 & 255, d >> 16 & 255, fx, fy);
        int g = mix(a >> 8 & 255, b >> 8 & 255, c >> 8 & 255, d >> 8 & 255, fx, fy);
        int bl = mix(a & 255, b & 255, c & 255, d & 255, fx, fy);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static int mix(int a, int b, int c, int d, double fx, double fy) {
        double v = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        return (int) Math.min(255, Math.max(0, Math.round(v)));
    }

    private int framePixel(int x, int y) {
        int base = color | 0xFF000000;
        if (frame.equals("film")) {
            boolean land = cw >= ch;
            int along = land ? x : y, across = land ? y : x;
            int len = land ? outW : outH, thick = land ? outH : outW;
            int band = barT;
            int pos;                     // distance into the band (0 at the outer edge)
            if (across < band) pos = across;
            else if (across >= thick - band) pos = thick - 1 - across;
            else return base;
            // sprocket holes: rounded rectangles centred in the band, pitch ~ 0.75 band
            int pitch = Math.max(6, Math.round(band * 0.75f));
            int hw = Math.max(2, Math.round(pitch * 0.42f)), hh = Math.max(2, Math.round(band * 0.38f));
            int c0 = (band - hh) / 2;
            int k = Math.floorMod(along - (len % pitch) / 2, pitch);
            if (pos >= c0 && pos < c0 + hh && k < hw) {
                int rr = Math.max(1, hh / 5);
                int dx = Math.min(k, hw - 1 - k), dy = Math.min(pos - c0, c0 + hh - 1 - pos);
                if (dx < rr && dy < rr && (rr - dx) * (rr - dx) + (rr - dy) * (rr - dy) > rr * rr) return base;
                return holeColor;
            }
            return base;
        }
        return base;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
