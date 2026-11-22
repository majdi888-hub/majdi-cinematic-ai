package com.majdi.cinematicai;

/**
 * Full-resolution renderer (pure Java, strip-based, float precision throughout).
 *
 * Per pixel, in linear light:
 *  1. Adaptive multi-scale tone mapping: an edge-aware illumination layer (ToneModel, fast
 *     guided filter) is compressed according to the scene's dynamic range; the mid-scale layer
 *     (local contrast / clarity) and fine detail are enhanced only above the noise floor.
 *     Depth-aware light shaping darkens what is far and lifts the subject (Dramatic).
 *  2. Filmic curve on luminance: log contrast around an adaptive pivot, toe, normalized
 *     shoulder, highlight desaturation into gamut.
 *  3. Luminance-preserving colour grading with smooth skin/face protection.
 *  4. Vignette, film black level, strength blend with the original.
 *  5. Lens composite: depth-aware bokeh render (working resolution) + original full-resolution
 *     detail where in focus, with the sharp weight upsampled by a guided filter (hair edges).
 *  6. Halation glow around highlights.
 * Then in display space: 3D LUT film look (tetrahedral), skin-tone refinement, cored
 * luminance-only sharpening (in-focus areas only, never on faces), film grain, dither.
 *
 * Faces: tone change is a smooth gain from a smooth illumination layer (relative skin detail
 * preserved exactly), never darker; no local contrast, sharpening or vignette on faces.
 * Every quantity depends only on the pixel, its 3x3 neighbourhood and plan data, so strips
 * give a bit-identical result to whole-image processing. No pixel is moved or synthesized.
 */
final class GradeCore {

    private static final float LOG2_MID = -2.4739312f; // log2(0.18)

    private final RenderPlan plan;
    private final Style p;
    private final float s;
    private final ToneModel m;
    private final SceneMaps maps;
    private final int w, h, orient;
    private final float dispAspect, grainCell;

    private final float anchor, gainEV, cS, cH, coreT0, coreTShadow, sharpenAmt;
    private final float whiteC, shoulderA, shoulderS0, pivot, toeEff;
    private final boolean shoulderOn;
    private final float[] fcx, fcy, frx, fry;

    private float[] fr = new float[0], fg = new float[0], fb = new float[0], fl = new float[0];
    private float[] fmask = new float[0], sharpW = new float[0];
    private final float[] lutOut = new float[3];

    GradeCore(RenderPlan plan, int width, int height, int orientation) {
        this.plan = plan;
        p = plan.style;
        s = Math.max(0f, Math.min(1f, plan.strength));
        m = plan.tone;
        maps = plan.maps;
        w = width;
        h = height;
        orient = (orientation >= 1 && orientation <= 8) ? orientation : 1;
        boolean swap = orient >= 5;
        dispAspect = swap ? (float) h / w : (float) w / h;
        grainCell = Math.max(1f, Math.max(w, h) / 2600f);

        float dr = m.p99 - Math.max(m.p01, -9f);
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

    private float toneY(float x) {
        float y = shoulder(x);
        if (toeEff > 0f) y = y * y * (1f + toeEff) / (y + toeEff);
        return y;
    }

    /** Stages 1-4 for a whole (working-resolution, display-oriented) image: linear planes. */
    float[][] gradeLinear(int[] px) {
        int n = w * h;
        float[][] outp = {new float[n], new float[n], new float[n]};
        float[] tmp = new float[4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                stage1(px[i], x, y, tmp, null);
                outp[0][i] = tmp[0]; outp[1][i] = tmp[1]; outp[2][i] = tmp[2];
            }
        }
        return outp;
    }

    /** Scratch values passed out of stage1. */
    private float lastFm, lastSkin, lastUd, lastVd, lastProtect;

    /**
     * Stages 1-4. Writes linear R,G,B (strength-blended) into out[0..2].
     * @param orig if non-null receives the original linear RGB
     */
    private void stage1(int c, int x, int y, float[] out, float[] orig) {
        final float[] lin = ColorMath.SRGB_TO_LINEAR;
        int r8 = (c >> 16) & 255, g8 = (c >> 8) & 255, b8 = c & 255;
        float R = lin[r8], G = lin[g8], B = lin[b8];
        if (orig != null) { orig[0] = R; orig[1] = G; orig[2] = B; }

        float ux = (x + 0.5f) / w, vy = (y + 0.5f) / h, ud, vd;
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
        lastUd = ud; lastVd = vd;

        float fm = 0f;
        for (int k = 0; k < fcx.length; k++) {
            float dx = (ud - fcx[k]) / frx[k], dy = (vd - fcy[k]) / fry[k];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float v = 1f - ColorMath.smooth(0.85f, 1.6f, d);
            if (v > fm) fm = v;
        }
        float Y8 = 0.299f * r8 + 0.587f * g8 + 0.114f * b8;
        float cb = -0.168736f * r8 - 0.331264f * g8 + 0.5f * b8;
        float cr = 0.5f * r8 - 0.418688f * g8 - 0.081312f * b8;
        float dcb = cb + 14f, dcr = cr - 20f;
        float skin = (float) Math.exp(-(dcb * dcb + dcr * dcr) / 162f) * ColorMath.smooth(20f, 60f, Y8);
        // skin-coloured walls, wood or sand are not skin: when the subject network is available,
        // full skin protection applies only on the subject and faces
        if (maps != null && maps.hasSubject) {
            float sub = Guided.sample(maps.subject, maps.w, maps.h, ud, vd);
            skin *= Math.max(Math.max(sub, fm), 0.25f);
        }
        float protect = Math.max(0.7f * skin, 0.9f * fm);
        lastFm = fm; lastSkin = skin; lastProtect = protect;

        // ---- 1. adaptive multi-scale tone mapping
        final float E = ToneModel.EPS;
        float Y = 0.2126f * R + 0.7152f * G + 0.0722f * B;
        float Z = Y + E;
        float I = ColorMath.log2(Z);
        float base = m.sample(m.meanA, ud, vd) * I + m.sample(m.meanB, ud, vd);
        float baseMid = Guided.sample(m.midA, m.gw2, m.gh2, ud, vd) * I + Guided.sample(m.midB, m.gw2, m.gh2, ud, vd);
        float mid = baseMid - base, fine = I - baseMid;
        float db = base - anchor;
        float sig = 1f / (1f + (float) Math.exp(-db * 1.5f));
        float base2 = anchor + db * (cS + (cH - cS) * sig);
        float shadowness = ColorMath.smooth(-4f, -8f, base);
        float t = coreT0 + coreTShadow * shadowness;
        float coreF = fine * fine / (fine * fine + t * t);
        float tm = 0.06f + 0.5f * t;
        float coreM = mid * mid / (mid * mid + tm * tm);
        // faces (detected, or skin on the subject when the detector misses a face): texture untouched
        float nf = 1f - Math.max(fm, 0.8f * skin);
        float cboost = p.contrast - 1f;
        float det2 = mid * (1f + ((p.midBoost - 1f) + cboost) * coreM * nf)
                   + fine * (1f + ((p.detail - 1f) + cboost) * coreF * nf);

        // depth-aware light shaping
        float depthEV = 0f, far = 0f;
        if (p.depthDodge > 0f && maps != null && maps.hasDepth()) {
            float dn = Guided.sample(maps.depth, maps.w, maps.h, ud, vd);
            float sub = Guided.sample(maps.subject, maps.w, maps.h, ud, vd);
            float rel = dn - maps.focus;                        // <0 farther than the subject
            depthEV = p.depthDodge * (0.9f * Math.max(-0.6f, Math.min(0f, rel)) + 0.12f * sub);
            far = Math.max(0f, Math.min(1f, -rel * 1.6f));
        }
        float Bil = base2 + gainEV + depthEV;

        // ---- 2. filmic curve
        float Yf = toneY(ColorMath.exp2(pivot + (Bil - pivot) * p.contrast + det2));
        float ratio = Yf / Z;
        if (fm > 0f) {
            float sb = m.sample(m.smoothI, ud, vd);
            float sdb = sb - anchor;
            float ssig = 1f / (1f + (float) Math.exp(-sdb * 1.5f));
            float sB = anchor + sdb * (cS + (cH - cS) * ssig) + gainEV + depthEV;
            float yb = toneY(ColorMath.exp2(pivot + (sB - pivot) * p.contrast));
            float rb = yb / ColorMath.exp2(sb);
            if (rb < 1f) rb = 1f;
            ratio += (rb - ratio) * fm;
        }
        float R2 = Math.max(0f, (R + E) * ratio - E);
        float G2 = Math.max(0f, (G + E) * ratio - E);
        float B2 = Math.max(0f, (B + E) * ratio - E);
        float Yn = 0.2126f * R2 + 0.7152f * G2 + 0.0722f * B2;

        // ---- 3. colour grading (luminance preserving)
        float ev = (Bil + det2) - LOG2_MID;
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
        float sf = p.sat * (1f + p.vib * (1f - satN)) * (1f - 0.3f * far * p.depthDodge);  // aerial perspective
        sf += (1f - sf) * protect;
        R2 = Yn + (R2 - Yn) * sf; G2 = Yn + (G2 - Yn) * sf; B2 = Yn + (B2 - Yn) * sf;

        mx = Math.max(R2, Math.max(G2, B2));
        mn = Math.min(R2, Math.min(G2, B2));
        float Yl = Math.min(Yn, 1f);
        float kk = 1f;
        if (mx > 1f && mx - Yl > 1e-6f) kk = Math.min(kk, (1f - Yl) / (mx - Yl));
        if (mn < 0f && Yl - mn > 1e-6f) kk = Math.min(kk, Yl / (Yl - mn));
        if (kk < 1f) { R2 = Yl + (R2 - Yl) * kk; G2 = Yl + (G2 - Yl) * kk; B2 = Yl + (B2 - Yl) * kk; }

        // ---- 4. black level, vignette, strength
        if (p.blackLift > 0f) {
            R2 = p.blackLift + (1f - p.blackLift) * R2;
            G2 = p.blackLift + (1f - p.blackLift) * G2;
            B2 = p.blackLift + (1f - p.blackLift) * B2;
        }
        if (p.vig > 0f) {
            float dx = (ud - 0.5f) * dispAspect, dy = vd - 0.5f;
            float rr = (float) Math.sqrt(dx * dx + dy * dy) / (0.5f * (float) Math.sqrt(dispAspect * dispAspect + 1f));
            float v = 1f - p.vig * ColorMath.smooth(0.45f, 1.25f, rr) * (1f - fm);
            R2 *= v; G2 *= v; B2 *= v;
        }
        out[0] = R + (R2 - R) * s;
        out[1] = G + (G2 - G) * s;
        out[2] = B + (B2 - B) * s;
        out[3] = Y8 / 255f;
    }

    /**
     * Renders output rows [y0, y1).
     * @param in  ARGB pixels of rows [ys, ys + rows), full width; one row above y0 (if y0 > 0)
     *            and one below y1 (if y1 < height) are required for sharpening
     * @param out receives rows [y0, y1), full width, from index 0
     */
    void processStrip(int[] in, int ys, int rows, int y0, int y1, int[] out) {
        int cap = w * rows;
        if (fr.length < cap) {
            fr = new float[cap]; fg = new float[cap]; fb = new float[cap]; fl = new float[cap];
            fmask = new float[cap]; sharpW = new float[cap];
        }
        float[] px = new float[4];
        Bokeh bk = plan.bokeh;
        boolean lens = bk != null;
        for (int r = 0; r < rows; r++) {
            int y = ys + r;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                int c = in[idx];
                stage1(c, x, y, px, null);
                float R2 = px[0], G2 = px[1], B2 = px[2];
                float ud = lastUd, vd = lastVd, fm = lastFm, skin = lastSkin, protect = lastProtect;
                float sharpness = 1f;

                // ---- 5. lens composite
                if (lens) {
                    int pw = plan.ww, ph = plan.wh;
                    float sw = Guided.sample(plan.sA, pw, ph, ud, vd) * px[3] + Guided.sample(plan.sB, pw, ph, ud, vd);
                    sw = sw < 0f ? 0f : (sw > 1f ? 1f : sw);
                    float rR = Guided.sample(bk.rr, pw, ph, ud, vd), rG = Guided.sample(bk.rg, pw, ph, ud, vd), rB = Guided.sample(bk.rb, pw, ph, ud, vd);
                    float gR = Guided.sample(plan.gR, pw, ph, ud, vd), gG = Guided.sample(plan.gG, pw, ph, ud, vd), gB = Guided.sample(plan.gB, pw, ph, ud, vd);
                    R2 = Math.max(0f, rR + sw * (R2 - gR));
                    G2 = Math.max(0f, rG + sw * (G2 - gG));
                    B2 = Math.max(0f, rB + sw * (B2 - gB));
                    sharpness = sw;
                }

                // ---- 6. halation (warm glow bleeding from highlights)
                if (plan.halo != null) {
                    float hv = Guided.sample(plan.halo, plan.ww, plan.wh, ud, vd) * p.halation * s;
                    R2 += hv * 0.9f; G2 += hv * 0.32f; B2 += hv * 0.08f;
                }

                float er = ColorMath.encode(R2), eg = ColorMath.encode(G2), eb = ColorMath.encode(B2);

                // ---- 7. 3D LUT film look (display space), protected on skin and faces
                if (plan.lut != null) {
                    plan.lut.apply(er / 255f, eg / 255f, eb / 255f, lutOut);
                    float a = p.lutAmt * s * (1f - 0.7f * protect);
                    er += (lutOut[0] * 255f - er) * a;
                    eg += (lutOut[1] * 255f - eg) * a;
                    eb += (lutOut[2] * 255f - eb) * a;
                }

                // ---- 8. skin-tone refinement: unify skin hue toward the natural skin line
                if (p.skinRefine > 0f && skin > 0.05f) {
                    float yy = 0.299f * er + 0.587f * eg + 0.114f * eb;
                    float cbv = -0.168736f * er - 0.331264f * eg + 0.5f * eb;
                    float crv = 0.5f * er - 0.418688f * eg - 0.081312f * eb;
                    float ang = (float) Math.atan2(crv, cbv), chroma = (float) Math.hypot(cbv, crv);
                    float target = 2.18f;                            // about 125 degrees in CbCr
                    float d = target - ang;
                    if (d > Math.PI) d -= 2f * (float) Math.PI;
                    if (d < -Math.PI) d += 2f * (float) Math.PI;
                    float k = p.skinRefine * s * skin * 0.35f;
                    ang += d * k;
                    cbv = chroma * (float) Math.cos(ang);
                    crv = chroma * (float) Math.sin(ang);
                    er = yy + 1.402f * crv;
                    eg = yy - 0.344136f * cbv - 0.714136f * crv;
                    eb = yy + 1.772f * cbv;
                }

                fr[idx] = er; fg[idx] = eg; fb[idx] = eb;
                fl[idx] = 0.299f * er + 0.587f * eg + 0.114f * eb;
                fmask[idx] = fm;
                sharpW[idx] = sharpness;
            }
        }

        // ---- 9. sharpening (in-focus only, not on faces, not in deep shadows), grain, dither
        float amt = sharpenAmt * s;
        float grainAmt = p.grain * s * 255f;
        for (int y = y0; y < y1; y++) {
            int r = y - ys;
            int up = r > 0 ? -w : 0, dn = r < rows - 1 ? w : 0;
            for (int x = 0; x < w; x++) {
                int idx = r * w + x;
                float dl = 0f;
                float c0 = fl[idx];
                float a = amt * (1f - fmask[idx]) * sharpW[idx] * ColorMath.smooth(12f, 55f, c0);
                if (a > 1e-3f) {
                    int lf = x > 0 ? -1 : 0, rt = x < w - 1 ? 1 : 0;
                    float n0 = fl[idx + up + lf], n1 = fl[idx + up], n2 = fl[idx + up + rt];
                    float n3 = fl[idx + lf], n5 = fl[idx + rt];
                    float n6 = fl[idx + dn + lf], n7 = fl[idx + dn], n8 = fl[idx + dn + rt];
                    float blur = (4f * c0 + 2f * (n1 + n3 + n5 + n7) + (n0 + n2 + n6 + n8)) / 16f;
                    float d = c0 - blur;
                    float d2 = d * d;
                    d = d * d2 / (d2 + 2.25f);
                    float nl = c0 + a * 1.6f * d;
                    float lo = Math.min(c0, Math.min(Math.min(Math.min(n1, n3), Math.min(n5, n7)), Math.min(Math.min(n0, n2), Math.min(n6, n8))));
                    float hi = Math.max(c0, Math.max(Math.max(Math.max(n1, n3), Math.max(n5, n7)), Math.max(Math.max(n0, n2), Math.max(n6, n8))));
                    if (nl < lo) nl = lo;
                    if (nl > hi) nl = hi;
                    dl = nl - c0;
                }
                if (grainAmt > 0f) {
                    float L = Math.max(0f, Math.min(1f, c0 / 255f));
                    float lw = 0.35f + 2.6f * L * (1f - L);               // strongest in the mid-tones
                    dl += grainAmt * lw * (1f - 0.7f * fmask[idx]) * grain(x, y);
                }
                float dither = tri(x, y);
                int rr = q(fr[idx] + dl + dither), gg = q(fg[idx] + dl + dither), bb = q(fb[idx] + dl + dither);
                out[(y - y0) * w + x] = (in[idx] & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    /** Film grain: two octaves of smooth value noise, fixed size relative to the frame. */
    private float grain(int x, int y) {
        float gx = x / grainCell, gy = y / grainCell;
        return 0.7f * vnoise(gx, gy, 0x9E3779B1) + 0.3f * vnoise(gx * 2.1f, gy * 2.1f, 0x7F4A7C15);
    }

    private static float vnoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed), c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed);
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return (top + (bot - top) * fy) * 1.7f;
    }

    private static float hash(int x, int y, int seed) {
        int hsh = x * 0x27d4eb2d ^ y * 0x165667b1 ^ seed;
        hsh ^= hsh >>> 15; hsh *= 0x2c1b3c6d; hsh ^= hsh >>> 12; hsh *= 0x297a2d39; hsh ^= hsh >>> 15;
        return (hsh & 0xFFFF) / 32767.5f - 1f;
    }

    private static int q(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static float tri(int x, int y) {
        int hsh = x * 0x27d4eb2d ^ y * 0x165667b1;
        hsh ^= hsh >>> 15; hsh *= 0x2c1b3c6d; hsh ^= hsh >>> 12;
        float a = (hsh & 0xFFFF) / 65535f, b = ((hsh >>> 16) & 0xFFFF) / 65535f;
        return (a + b - 1f) * 0.5f;
    }
}
