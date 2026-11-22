package com.majdi.cinematicai;

/**
 * Image analysis computed once from the preview and shared by the preview and the
 * full-resolution export, so every strip of the export uses exactly the same parameters.
 *
 * Holds:
 *  - global luminance statistics (percentiles in EV) for adaptive exposure and contrast;
 *  - a low-resolution edge-aware illumination model: the coefficients (A, B) of a
 *    self-guided filter on log2 luminance. At full resolution the local illumination is
 *    base = A * log2(Y) + B, which follows the full-resolution edges (fast guided filter),
 *    so local tone mapping produces no halos and no seams between strips;
 *  - face ellipses in display-normalized coordinates.
 */
final class ToneModel {

    final int gw, gh;
    final float[] meanA, meanB;
    final float[] smoothI;   // fully smooth log2 illumination (used for face gain)
    /** Mid-scale edge-aware layer (about 1% of the frame): drives local contrast / clarity. */
    final int gw2, gh2;
    final float[] midA, midB;
    /** smoothed dark channel (min RGB, linear) and airlight estimate, for Dehaze */
    float[] hazeDark;
    float airlight;
    final float p01, p50, p99;
    final float[][] faces;   // {left, top, right, bottom} normalized, display orientation

    private ToneModel(int gw, int gh, float[] a, float[] b, float[] si, int gw2, int gh2, float[] a2, float[] b2,
                      float p01, float p50, float p99, float[][] faces) {
        this.gw = gw;
        this.gh = gh;
        this.meanA = a;
        this.meanB = b;
        this.smoothI = si;
        this.gw2 = gw2;
        this.gh2 = gh2;
        this.midA = a2;
        this.midB = b2;
        this.p01 = p01;
        this.p50 = p50;
        this.p99 = p99;
        this.faces = faces;
    }

    /**
     * Offset added to linear luminance before taking logarithms (about 6 sRGB code values).
     * Like a film log curve, it keeps near-black noise and quantization from turning into
     * large log-domain swings that would be amplified into speckle.
     */
    static final float EPS = 1f / 512f;

    /**
     * @param px    ARGB pixels of the preview, in display orientation
     * @param faces face rectangles normalized to the preview, may be empty
     */
    static ToneModel build(int[] px, int w, int h, float[][] faces) {
        float[] lin = ColorMath.SRGB_TO_LINEAR;

        // ---- global histogram of log2 luminance
        final float evMin = -17f, evMax = 1f;
        final int bins = 1800;
        int[] hist = new int[bins];
        // ---- low-resolution grid of mean linear luminance
        float s = Math.min(1f, 384f / Math.max(w, h));
        float s2 = Math.min(1f, 768f / Math.max(w, h));
        int gw2 = Math.max(4, Math.round(w * s2));
        int gh2 = Math.max(4, Math.round(h * s2));
        double[] sum2 = new double[gw2 * gh2];
        int[] cnt2 = new int[gw2 * gh2];
        int gw = Math.max(4, Math.round(w * s));
        int gh = Math.max(4, Math.round(h * s));
        double[] sum = new double[gw * gh];
        double[] dsum = new double[gw * gh];
        int[] cnt = new int[gw * gh];

        for (int y = 0; y < h; y++) {
            int gy = Math.min(gh - 1, (int) ((y + 0.5f) * gh / h));
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                float Y = 0.2126f * lin[(c >> 16) & 255] + 0.7152f * lin[(c >> 8) & 255] + 0.0722f * lin[c & 255];
                float ev = (float) (Math.log(Y + EPS) * ColorMath.INV_LN2);
                int bi = (int) ((ev - evMin) / (evMax - evMin) * bins);
                hist[Math.max(0, Math.min(bins - 1, bi))]++;
                int gx = Math.min(gw - 1, (int) ((x + 0.5f) * gw / w));
                sum[gy * gw + gx] += Y;
                dsum[gy * gw + gx] += Math.min(lin[(c >> 16) & 255], Math.min(lin[(c >> 8) & 255], lin[c & 255]));
                cnt[gy * gw + gx]++;
                int gx2 = Math.min(gw2 - 1, (int) ((x + 0.5f) * gw2 / w));
                int gy2 = Math.min(gh2 - 1, (int) ((y + 0.5f) * gh2 / h));
                sum2[gy2 * gw2 + gx2] += Y;
                cnt2[gy2 * gw2 + gx2]++;
            }
        }
        long total = (long) w * h;
        float p01 = percentile(hist, total, 0.01, evMin, evMax);
        float p50 = percentile(hist, total, 0.50, evMin, evMax);
        float p99 = percentile(hist, total, 0.99, evMin, evMax);

        int n = gw * gh;
        float[] I = new float[n];
        for (int i = 0; i < n; i++) {
            double m = cnt[i] > 0 ? sum[i] / cnt[i] : 0.18;
            I[i] = (float) (Math.log(m + EPS) * ColorMath.INV_LN2);
        }

        // ---- self-guided filter (He et al.) on the grid
        int r = Math.max(2, Math.round(Math.max(gw, gh) / 24f));
        float eps = 0.15f;  // EV^2: edges stronger than ~0.4 EV are preserved
        float[] mI = boxMean(I, gw, gh, r);
        float[] I2 = new float[n];
        for (int i = 0; i < n; i++) I2[i] = I[i] * I[i];
        float[] mI2 = boxMean(I2, gw, gh, r);
        float[] a = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            float var = Math.max(0f, mI2[i] - mI[i] * mI[i]);
            a[i] = var / (var + eps);
            b[i] = mI[i] - a[i] * mI[i];
        }
        float[] mA = boxMean(a, gw, gh, r);
        float[] mB = boxMean(b, gw, gh, r);

        float[] sI = boxMean(boxMean(I, gw, gh, r), gw, gh, r);

        // ---- mid-scale guided layer for local contrast
        int n2 = gw2 * gh2;
        float[] I2m = new float[n2];
        for (int i = 0; i < n2; i++) {
            double mm = cnt2[i] > 0 ? sum2[i] / cnt2[i] : 0.18;
            I2m[i] = (float) (Math.log(mm + EPS) * ColorMath.INV_LN2);
        }
        int r2 = Math.max(2, Math.round(Math.max(gw2, gh2) / 96f));
        float[][] ab2 = Guided.guidedCoeffs(I2m, I2m, gw2, gh2, r2, 0.06f);

        float[] dk = new float[n];
        for (int i = 0; i < n; i++) dk[i] = cnt[i] > 0 ? (float) (dsum[i] / cnt[i]) : 0f;
        dk = boxMean(dk, gw, gh, Math.max(1, r / 3));
        float[] srt = dk.clone();
        java.util.Arrays.sort(srt);
        float air = Math.max(0.3f, srt[(int) (srt.length * 0.995f)]);
        ToneModel tmOut = new ToneModel(gw, gh, mA, mB, sI, gw2, gh2, ab2[0], ab2[1], p01, p50, p99, faces == null ? new float[0][] : faces);
        tmOut.hazeDark = dk;
        tmOut.airlight = air;
        return tmOut;
    }

    private static float percentile(int[] hist, long total, double q, float lo, float hi) {
        long target = (long) Math.ceil(total * q);
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) return lo + (i + 0.5f) * (hi - lo) / hist.length;
        }
        return hi;
    }

    /** Box mean with clamped borders, using a summed-area table. */
    static float[] boxMean(float[] src, int w, int h, int r) {
        double[] sat = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += src[y * w + x];
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                double s = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0];
                out[y * w + x] = (float) (s / ((x1 - x0) * (y1 - y0)));
            }
        }
        return out;
    }

    /** Bilinear sample of a grid at display-normalized coordinates. */
    float sample(float[] g, float u, float v) {
        float gx = u * gw - 0.5f, gy = v * gh - 0.5f;
        if (gx < 0f) gx = 0f;
        if (gy < 0f) gy = 0f;
        if (gx > gw - 1) gx = gw - 1;
        if (gy > gh - 1) gy = gh - 1;
        int x0 = (int) gx, y0 = (int) gy;
        int x1 = Math.min(gw - 1, x0 + 1), y1 = Math.min(gh - 1, y0 + 1);
        float fx = gx - x0, fy = gy - y0;
        float t = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * fx;
        float btm = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * fx;
        return t + (btm - t) * fy;
    }
}
