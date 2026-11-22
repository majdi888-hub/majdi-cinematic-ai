package com.majdi.cinematicai;

/**
 * Photographic grading engine (pure Java, strip-based, float precision throughout).
 *
 * Pipeline per pixel, in linear light:
 *  1. Adaptive local tone mapping: edge-aware illumination (ToneModel) is compressed
 *     asymmetrically (shadows lifted, highlights held) according to the scene's dynamic range;
 *     local detail is kept, and enhanced only above a noise threshold (soft coring).
 *     Applied as a luminance ratio, so hue and saturation are untouched at this stage.
 *  2. Filmic tone curve on luminance: log contrast around middle grey, toe (black level),
 *     soft shoulder normalized to the scene white point, highlight desaturation into gamut.
 *  3. Color grading: luminance-preserving split toning and vibrance, with smooth
 *     (never thresholded) skin-tone and face protection.
 *  4. Gentle vignette (faces excluded), strength blend with the original in linear light.
 *  5. Luminance-only sharpening with noise coring and overshoot clamping (no halos),
 *     disabled on faces; triangular dither before 8-bit quantization (no banding).
 *
 * Every per-pixel quantity depends only on the pixel, its 3x3 neighbourhood and global
 * data from ToneModel, so splitting the image into strips gives a bit-identical result.
 * No pixel is moved, warped or synthesized.
 */
final class GradeCore {

    static final class Style {
        float expo, shadowLift, highComp, detail, contrast, shoulder, blackLift, toe;
        float[] shadowGain, highGain;
        float sat, vib, vig, sharpen;
    }

    static Style style(String key) {
        Style p = new Style();
        switch (key) {
            case "cinematic":
                // teal shadows / warm highlights, softened blacks, restrained saturation
                p.expo = 0.20f; p.shadowLift = 0.30f; p.highComp = 0.32f; p.detail = 1.15f;
                p.contrast = 1.16f; p.shoulder = 0.68f; p.blackLift = 0.008f; p.toe = 0f;
                p.shadowGain = new float[]{0.84f, 1.00f, 1.20f};
                p.highGain = new float[]{1.14f, 1.00f, 0.82f};
                p.sat = 0.88f; p.vib = 0.0f; p.vig = 0.14f; p.sharpen = 0.30f;
                break;
            case "dramatic":
                // strong local contrast (clarity), deep blacks, cool desaturated palette
                p.expo = 0.08f; p.shadowLift = 0.22f; p.highComp = 0.48f; p.detail = 1.50f;
                p.contrast = 1.30f; p.shoulder = 0.66f; p.blackLift = 0.0f; p.toe = 0.010f;
                p.shadowGain = new float[]{0.93f, 0.98f, 1.12f};
                p.highGain = new float[]{1.04f, 1.00f, 0.95f};
                p.sat = 0.70f; p.vib = 0.0f; p.vig = 0.28f; p.sharpen = 0.45f;
                break;
            case "dslr":
                // clean and crisp: micro contrast, vibrance, accurate neutral colour
                p.expo = 0.25f; p.shadowLift = 0.22f; p.highComp = 0.28f; p.detail = 1.28f;
                p.contrast = 1.10f; p.shoulder = 0.80f; p.blackLift = 0.0f; p.toe = 0.002f;
                p.shadowGain = new float[]{1.00f, 1.00f, 1.00f};
                p.highGain = new float[]{1.015f, 1.00f, 0.985f};
                p.sat = 1.02f; p.vib = 0.32f; p.vig = 0.04f; p.sharpen = 0.80f;
                break;
            case "natural":
            default:
                // Natural Cinematic: realistic, gentle filmic response and light split toning
                p.expo = 0.20f; p.shadowLift = 0.24f; p.highComp = 0.22f; p.detail = 1.08f;
                p.contrast = 1.06f; p.shoulder = 0.78f; p.blackLift = 0.003f; p.toe = 0f;
                p.shadowGain = new float[]{0.95f, 1.00f, 1.06f};
                p.highGain = new float[]{1.05f, 1.00f, 0.95f};
                p.sat = 0.97f; p.vib = 0.06f; p.vig = 0.06f; p.sharpen = 0.35f;
                break;
        }
        return p;
    }

    private static final float LOG2_MID = -2.4739312f; // log2(0.18)

    private final Style p;
    private final float s;           // strength 0..1
    private final ToneModel m;
    private final int w, h, orient;
    private final float dispAspect;

    // adaptive parameters derived from the scene
    private final float anchor, gainEV, cS, cH, coreT0, coreTShadow, sharpenAmt;
    private final float whiteC, shoulderA, shoulderS0, pivot, toeEff;
    private final boolean shoulderOn;

    private final float[] fcx, fcy, frx, fry;

    // strip buffers
    private float[] fr = new float[0], fg = new float[0], fb = new float[0], fl = new float[0], fmask = new float[0];

    /**
     * @param width       stored pixel width of the image being processed
     * @param height      stored pixel height
     * @param orientation EXIF orientation (1..8) mapping stored pixels to the displayed image
     *                    in which ToneModel and faces are expressed
     */
    GradeCore(String styleKey, float strength, ToneModel model, int width, int height, int orientation) {
        p = style(styleKey);
        s = Math.max(0f, Math.min(1f, strength));
        m = model;
        w = width;
        h = height;
        orient = (orientation >= 1 && orientation <= 8) ? orientation : 1;
        boolean swap = orient >= 5;
        dispAspect = swap ? (float) h / w : (float) w / h;

        float dr = m.p99 - Math.max(m.p01, -9f);        // scene dynamic range in EV (pure black ignored)
        float night = ColorMath.smooth(-4.5f, -6.5f, m.p50);
        float k = 0.4f + 0.6f * ColorMath.smooth(4f, 10f, dr);
        anchor = m.p50;
        float g = p.expo * (-2.6f - m.p50);
        float gMax = 0.6f - 0.25f * night;
        gainEV = Math.max(-0.35f, Math.min(gMax, g));
        cS = 1f - p.shadowLift * k * (1f - 0.5f * night);
        cH = 1f - p.highComp * k;
        coreT0 = 0.10f * (1f + 1.5f * night);
        coreTShadow = 0.25f * (1f + night);
        sharpenAmt = p.sharpen * (1f - 0.5f * night);

        // white point after exposure gain and log contrast; shoulder maps it exactly to 1
        // contrast pivots on the scene's own mid-tone (adaptive), never brighter than middle grey,
        // so dark and night scenes keep their brightness instead of sinking
        pivot = Math.max(-7f, Math.min(LOG2_MID, anchor + gainEV));
        toeEff = p.toe * (1f - 0.8f * night);
        whiteC = ColorMath.exp2(pivot + (Math.max(0f, gainEV) - pivot) * p.contrast + 0.1f);
        shoulderS0 = p.shoulder;
        if (whiteC > shoulderS0 + 1e-3f) {
            float slope = (whiteC - shoulderS0) / (1f - shoulderS0);
            shoulderOn = slope > 1.0001f;
            shoulderA = shoulderOn ? 1f / (slope - 1f) : 0f;
        } else {
            shoulderOn = false;
            shoulderA = 0f;
        }

        int n = m.faces.length;
        fcx = new float[n]; fcy = new float[n]; frx = new float[n]; fry = new float[n];
        for (int i = 0; i < n; i++) {
            float[] f = m.faces[i];
            fcx[i] = (f[0] + f[2]) * 0.5f;
            fcy[i] = (f[1] + f[3]) * 0.5f;
            frx[i] = Math.max(1e-3f, Math.abs(f[2] - f[0]) * 0.5f);
            fry[i] = Math.max(1e-3f, Math.abs(f[3] - f[1]) * 0.5f);
        }
    }

    private float shoulder(float x) {
        if (!shoulderOn || x <= shoulderS0) return x;
        float t = (x - shoulderS0) / (whiteC - shoulderS0);
        float gT = t * (1f + shoulderA) / (t + shoulderA);
        return shoulderS0 + (1f - shoulderS0) * gT;
    }

    /**
     * Grades output rows [y0, y1).
     * @param in  ARGB pixels of rows [ys, ys + rows), full width; must include one row above
     *            y0 (if y0 > 0) and one row below y1 (if y1 < height)
     * @param out receives rows [y0, y1), full width, starting at index 0
     */
    void processStrip(int[] in, int ys, int rows, int y0, int y1, int[] out) {
        int cap = w * rows;
        if (fr.length < cap) {
            fr = new float[cap]; fg = new float[cap]; fb = new float[cap];
            fl = new float[cap]; fmask = new float[cap];
        }
        final float[] lin = ColorMath.SRGB_TO_LINEAR;
        final float iw = 1f / w, ih = 1f / h;

        for (int r = 0; r < rows; r++) {
            int y = ys + r;
            float vy = (y + 0.5f) * ih;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                int c = in[idx];
                int r8 = (c >> 16) & 255, g8 = (c >> 8) & 255, b8 = c & 255;
                float R = lin[r8], G = lin[g8], B = lin[b8];

                // display-normalized coordinates of this stored pixel
                float ux = (x + 0.5f) * iw, ud, vd;
                switch (orient) {
                    case 2: ud = 1f - ux; vd = vy; break;
                    case 3: ud = 1f - ux; vd = 1f - vy; break;
                    case 4: ud = ux; vd = 1f - vy; break;
                    case 5: ud = vy; vd = ux; break;
                    case 6: ud = 1f - vy; vd = ux; break;
                    case 7: ud = 1f - vy; vd = 1f - ux; break;
                    case 8: ud = vy; vd = 1f - ux; break;
                    default: ud = ux; vd = vy; break;
                }

                // ---- face mask (smooth)
                float fm = 0f;
                for (int k = 0; k < fcx.length; k++) {
                    float dx = (ud - fcx[k]) / frx[k], dy = (vd - fcy[k]) / fry[k];
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float v = 1f - ColorMath.smooth(0.85f, 1.6f, d);
                    if (v > fm) fm = v;
                }

                // ---- skin likelihood (smooth Gaussian in CbCr, from the original pixel)
                float Y8 = 0.299f * r8 + 0.587f * g8 + 0.114f * b8;
                float cb = -0.168736f * r8 - 0.331264f * g8 + 0.5f * b8;
                float cr = 0.5f * r8 - 0.418688f * g8 - 0.081312f * b8;
                float dcb = cb + 14f, dcr = cr - 20f;
                float skin = (float) Math.exp(-(dcb * dcb + dcr * dcr) / 162f) * ColorMath.smooth(20f, 60f, Y8);
                float protect = Math.max(0.7f * skin, 0.9f * fm);

                // ---- 1. adaptive local tone mapping (luminance ratio)
                float Y = 0.2126f * R + 0.7152f * G + 0.0722f * B;
                final float E = ToneModel.EPS;
                float Z = Y + E;
                float I = ColorMath.log2(Z);
                float A = m.sample(m.meanA, ud, vd), Bc = m.sample(m.meanB, ud, vd);
                float base = A * I + Bc;
                float det = I - base;
                float db = base - anchor;
                float sig = 1f / (1f + (float) Math.exp(-db * 1.5f));
                float base2 = anchor + db * (cS + (cH - cS) * sig);
                float shadowness = ColorMath.smooth(-4f, -8f, base);
                float t = coreT0 + coreTShadow * shadowness;
                float core = det * det / (det * det + t * t);
                // detail above the noise floor is enhanced (clarity) and receives the global
                // contrast; noise-level detail and all face texture pass through unchanged
                float nf = 1f - fm;
                float det2 = det * (1f + ((p.detail - 1f) + (p.contrast - 1f)) * core * nf);
                float Bil = base2 + gainEV;
                float I2 = Bil + det2;

                // ---- 2. filmic curve: log contrast on the illumination layer around an adaptive pivot
                float Yc = ColorMath.exp2(pivot + (Bil - pivot) * p.contrast + det2);
                float Yf = shoulder(Yc);
                if (toeEff > 0f) Yf = Yf * Yf * (1f + toeEff) / (Yf + toeEff);   // filmic toe: deeper blacks
                float ratio = Yf / Z;
                if (fm > 0f) {
                    // On faces the tone change is a smooth gain taken from the illumination layer
                    // only, so every skin detail keeps its exact relative value; and faces are
                    // never darkened.
                    float sb = m.sample(m.smoothI, ud, vd);
                    float sdb = sb - anchor;
                    float ssig = 1f / (1f + (float) Math.exp(-sdb * 1.5f));
                    float sB = anchor + sdb * (cS + (cH - cS) * ssig) + gainEV;
                    float yb = shoulder(ColorMath.exp2(pivot + (sB - pivot) * p.contrast));
                    if (toeEff > 0f) yb = yb * yb * (1f + toeEff) / (yb + toeEff);
                    float rb = yb / ColorMath.exp2(sb);  // sb is log2(Y + EPS) illumination
                    if (rb < 1f) rb = 1f;
                    ratio += (rb - ratio) * fm;
                }
                // ratio applied in the offset domain: near-black chroma noise is not magnified
                float R2 = Math.max(0f, (R + E) * ratio - E);
                float G2 = Math.max(0f, (G + E) * ratio - E);
                float B2 = Math.max(0f, (B + E) * ratio - E);
                float Yn = 0.2126f * R2 + 0.7152f * G2 + 0.0722f * B2;

                // ---- 3. color grading (luminance preserving)
                float ev = I2 - LOG2_MID;
                float sw = 1f - ColorMath.smooth(-4f, 0.5f, ev);
                float hw = ColorMath.smooth(-1.5f, 2f, ev);
                float ga = 1f - protect;
                float gr = 1f + ((p.shadowGain[0] - 1f) * sw + (p.highGain[0] - 1f) * hw) * ga;
                float gg = 1f + ((p.shadowGain[1] - 1f) * sw + (p.highGain[1] - 1f) * hw) * ga;
                float gb = 1f + ((p.shadowGain[2] - 1f) * sw + (p.highGain[2] - 1f) * hw) * ga;
                float gl = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb;
                R2 *= gr / gl; G2 *= gg / gl; B2 *= gb / gl;

                float mx = Math.max(R2, Math.max(G2, B2)), mn = Math.min(R2, Math.min(G2, B2));
                float satN = mx > 1e-6f ? (mx - mn) / mx : 0f;
                float sf = p.sat * (1f + p.vib * (1f - satN));
                sf += (1f - sf) * protect;
                R2 = Yn + (R2 - Yn) * sf; G2 = Yn + (G2 - Yn) * sf; B2 = Yn + (B2 - Yn) * sf;

                // gamut: bring channels into [0,1] by desaturating toward luminance
                mx = Math.max(R2, Math.max(G2, B2));
                mn = Math.min(R2, Math.min(G2, B2));
                float Yl = Math.min(Yn, 1f);
                float kk = 1f;
                if (mx > 1f && mx - Yl > 1e-6f) kk = Math.min(kk, (1f - Yl) / (mx - Yl));
                if (mn < 0f && Yl - mn > 1e-6f) kk = Math.min(kk, Yl / (Yl - mn));
                if (kk < 1f) {
                    R2 = Yl + (R2 - Yl) * kk; G2 = Yl + (G2 - Yl) * kk; B2 = Yl + (B2 - Yl) * kk;
                }

                // neutral film black level (additive, equal on all channels: no colour speckle)
                if (p.blackLift > 0f) {
                    R2 = p.blackLift + (1f - p.blackLift) * R2;
                    G2 = p.blackLift + (1f - p.blackLift) * G2;
                    B2 = p.blackLift + (1f - p.blackLift) * B2;
                }

                // ---- 4. vignette (faces excluded)
                if (p.vig > 0f) {
                    float dx = (ud - 0.5f) * dispAspect, dy = vd - 0.5f;
                    float rr = (float) Math.sqrt(dx * dx + dy * dy) / (0.5f * (float) Math.sqrt(dispAspect * dispAspect + 1f));
                    float v = 1f - p.vig * ColorMath.smooth(0.45f, 1.25f, rr) * (1f - fm);
                    R2 *= v; G2 *= v; B2 *= v;
                }

                // ---- strength blend in linear light
                R2 = R + (R2 - R) * s; G2 = G + (G2 - G) * s; B2 = B + (B2 - B) * s;

                float er = ColorMath.encode(R2), eg = ColorMath.encode(G2), eb = ColorMath.encode(B2);
                fr[idx] = er; fg[idx] = eg; fb[idx] = eb;
                fl[idx] = 0.299f * er + 0.587f * eg + 0.114f * eb;
                fmask[idx] = fm;
            }
        }

        // ---- 5. sharpening (luminance only, cored, overshoot clamped) + dither + quantize
        float amt = sharpenAmt * s;
        for (int y = y0; y < y1; y++) {
            int r = y - ys;
            int up = r > 0 ? -w : 0, dn = r < rows - 1 ? w : 0;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                float dl = 0f;
                float c0 = fl[idx];
                // no sharpening on faces, and fading out in deep shadows (noise, not detail)
                float a = amt * (1f - fmask[idx]) * ColorMath.smooth(12f, 55f, c0);
                if (a > 1e-3f) {
                    int lf = x > 0 ? -1 : 0, rt = x < w - 1 ? 1 : 0;
                    float n0 = fl[idx + up + lf], n1 = fl[idx + up], n2 = fl[idx + up + rt];
                    float n3 = fl[idx + lf], n5 = fl[idx + rt];
                    float n6 = fl[idx + dn + lf], n7 = fl[idx + dn], n8 = fl[idx + dn + rt];
                    float blur = (4f * c0 + 2f * (n1 + n3 + n5 + n7) + (n0 + n2 + n6 + n8)) / 16f;
                    float d = c0 - blur;
                    float d2 = d * d;
                    d = d * d2 / (d2 + 2.25f);                 // coring: ~1.5 code values
                    float nl = c0 + a * 1.6f * d;
                    float lo = Math.min(c0, Math.min(Math.min(Math.min(n1, n3), Math.min(n5, n7)), Math.min(Math.min(n0, n2), Math.min(n6, n8))));
                    float hi = Math.max(c0, Math.max(Math.max(Math.max(n1, n3), Math.max(n5, n7)), Math.max(Math.max(n0, n2), Math.max(n6, n8))));
                    if (nl < lo) nl = lo;
                    if (nl > hi) nl = hi;
                    dl = nl - c0;
                }
                float dither = tri(x, y);
                int rr = q(fr[idx] + dl + dither), gg = q(fg[idx] + dl + dither), bb = q(fb[idx] + dl + dither);
                out[(y - y0) * w + x] = (in[idx] & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    private static int q(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Deterministic triangular dither in [-0.5, 0.5] code values, keyed by absolute position. */
    private static float tri(int x, int y) {
        int hsh = x * 0x27d4eb2d ^ y * 0x165667b1;
        hsh ^= hsh >>> 15; hsh *= 0x2c1b3c6d; hsh ^= hsh >>> 12;
        float a = (hsh & 0xFFFF) / 65535f, b = ((hsh >>> 16) & 0xFFFF) / 65535f;
        return (a + b - 1f) * 0.5f;
    }
}
