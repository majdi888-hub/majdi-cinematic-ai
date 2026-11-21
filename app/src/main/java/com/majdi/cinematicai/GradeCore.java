package com.majdi.cinematicai;

/**
 * Pure-Java color grading core (no Android dependencies), working strip by strip.
 * Only tone and color change: no pixel is moved, cropped, warped or generated.
 * Faces (feathered ellipses) and skin tones receive only a small share of the grade.
 */
final class GradeCore {

    /** Share of the grade that faces receive (0 = untouched, 1 = full effect). */
    static final float FACE_KEEP = 0.2f;

    private final float contrast, lift, exposure, sat, vibrance, vig, sharpen, warm;
    private final float[] shadow, high;
    private final int[] lut = new int[256];
    private final float strength;
    private final int w, h;
    private final float cx, cy, maxD;
    private final int n;
    private final float[] fcx, fcy, frx, fry;

    private int[] gr = new int[0];
    private float[] eff = new float[0];

    /**
     * @param faces normalized rectangles {left, top, right, bottom} in this image's pixel space
     */
    GradeCore(String style, float strength, float[][] faces, int width, int height) {
        switch (style) {
            case "dramatic":
                contrast = 0.85f; lift = 0f; exposure = 0.96f;
                sat = 0.78f; vibrance = 0f; vig = 0.55f; sharpen = 0.45f; warm = 0f;
                shadow = new float[]{-6f, -2f, 10f};
                high = new float[]{10f, 6f, -4f};
                break;
            case "dslr":
                contrast = 0.35f; lift = 0f; exposure = 1.02f;
                sat = 1.0f; vibrance = 0.35f; vig = 0.15f; sharpen = 0.7f; warm = 4f;
                shadow = new float[]{0f, 0f, 2f};
                high = new float[]{5f, 3f, -3f};
                break;
            case "cinematic":
            default:
                contrast = 0.5f; lift = 16f; exposure = 1.0f;
                sat = 0.86f; vibrance = 0f; vig = 0.35f; sharpen = 0.15f; warm = 0f;
                shadow = new float[]{-14f, 4f, 20f};
                high = new float[]{20f, 8f, -16f};
                break;
        }
        this.strength = Math.max(0f, Math.min(1f, strength));
        this.w = width;
        this.h = height;
        cx = w / 2f;
        cy = h / 2f;
        maxD = (float) Math.sqrt(cx * cx + cy * cy);

        float lf = lift / 255f;
        for (int i = 0; i < 256; i++) {
            float x = i / 255f * exposure;
            if (x > 1f) x = 1f;
            float s = x * x * (3f - 2f * x);
            float y = x + (s - x) * contrast * 1.6f;
            if (y < 0f) y = 0f;
            if (y > 1f) y = 1f;
            y = lf + y * (1f - lf);
            lut[i] = clamp(Math.round(y * 255f));
        }

        n = faces == null ? 0 : faces.length;
        fcx = new float[n]; fcy = new float[n]; frx = new float[n]; fry = new float[n];
        for (int k = 0; k < n; k++) {
            float[] f = faces[k];
            fcx[k] = (f[0] + f[2]) / 2f * w;
            fcy[k] = (f[1] + f[3]) / 2f * h;
            frx[k] = Math.max(1f, Math.abs(f[2] - f[0]) / 2f * w);
            fry[k] = Math.max(1f, Math.abs(f[3] - f[1]) / 2f * h);
        }
    }

    /**
     * Grades output rows [y0, y1).
     * @param in  ARGB pixels of rows [ys, ys + rows), full width. Must include one row above y0
     *            (if y0 > 0) and one row below y1 (if y1 < height) for sharpening.
     * @param out receives rows [y0, y1), full width, starting at index 0.
     */
    void processStrip(int[] in, int ys, int rows, int y0, int y1, int[] out) {
        int cap = w * rows;
        if (gr.length < cap) {
            gr = new int[cap];
            eff = new float[cap];
        }
        for (int r = 0; r < rows; r++) {
            int y = ys + r;
            float dy = y - cy;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                int c = in[idx];
                int R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;

                float prot = 0f;
                for (int k = 0; k < n; k++) {
                    float ux = (x - fcx[k]) / frx[k];
                    float uy = (y - fcy[k]) / fry[k];
                    float d = ux * ux + uy * uy;
                    if (d < 1f) { prot = 1f; break; }
                    if (d < 2f) { float t = 2f - d; if (t > prot) prot = t; }
                }

                float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                if (prot < 0.45f) {
                    float cb = 128f - 0.168736f * R - 0.331264f * G + 0.5f * B;
                    float cr = 128f + 0.5f * R - 0.418688f * G - 0.081312f * B;
                    if (cb >= 80f && cb <= 125f && cr >= 135f && cr <= 170f && Y > 50f) prot = 0.45f;
                }
                float e = strength * (1f - prot * (1f - FACE_KEEP));
                eff[idx] = e;

                float s = sat;
                if (vibrance > 0f) {
                    int mx = Math.max(R, Math.max(G, B));
                    int mn = Math.min(R, Math.min(G, B));
                    s *= 1f + vibrance * (1f - (mx - mn) / 255f);
                }
                float r2 = lut[clamp(Math.round(Y + (R - Y) * s))];
                float g2 = lut[clamp(Math.round(Y + (G - Y) * s))];
                float b2 = lut[clamp(Math.round(Y + (B - Y) * s))];

                float lum = Y / 255f;
                float sh = (1f - lum) * (1f - lum);
                float hi = lum * lum;
                r2 += shadow[0] * sh + high[0] * hi + warm;
                g2 += shadow[1] * sh + high[1] * hi;
                b2 += shadow[2] * sh + high[2] * hi - warm;

                if (vig > 0f) {
                    float dx = x - cx;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / maxD;
                    float m = 1f - vig * smooth(0.45f, 1.05f, d);
                    r2 *= m; g2 *= m; b2 *= m;
                }

                int rr = clamp(Math.round(R + (r2 - R) * e));
                int gg = clamp(Math.round(G + (g2 - G) * e));
                int bb = clamp(Math.round(B + (b2 - B) * e));
                gr[idx] = (c & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
            }
        }

        for (int y = y0; y < y1; y++) {
            int r = y - ys;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                int c = gr[idx];
                float a = sharpen * eff[idx];
                if (a > 0.001f) {
                    int up = r > 0 ? gr[idx - w] : c;
                    int dn = r < rows - 1 ? gr[idx + w] : c;
                    int lf2 = x > 0 ? gr[idx - 1] : c;
                    int rt = x < w - 1 ? gr[idx + 1] : c;
                    c = (c & 0xFF000000)
                            | (sharpCh(c, up, dn, lf2, rt, 16, a) << 16)
                            | (sharpCh(c, up, dn, lf2, rt, 8, a) << 8)
                            | sharpCh(c, up, dn, lf2, rt, 0, a);
                }
                out[(y - y0) * w + x] = c;
            }
        }
    }

    private static int sharpCh(int c, int u, int d, int l, int r, int shift, float a) {
        int v = (c >> shift) & 255;
        float avg = (((u >> shift) & 255) + ((d >> shift) & 255) + ((l >> shift) & 255) + ((r >> shift) & 255)) / 4f;
        return clamp(Math.round(v + a * (v - avg)));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
