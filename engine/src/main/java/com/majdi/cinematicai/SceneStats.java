package com.majdi.cinematicai;

/**
 * Measured image statistics used by the adaptive pipeline and the Auto Director:
 * noise level (Immerkaer estimator), neutral reference for white balance, colourfulness
 * (Hasler-Suesstrunk) and a sky heuristic. All are plain measurements, not learned models.
 */
final class SceneStats {

    final float noiseSigma;       // luma noise std in 8-bit code values, at the measured resolution
    final float[] wbGains;        // linear R,G,B gains that neutralize the estimated illuminant (G = 1)
    final float wbConfidence;     // 0..1, share of reliable neutral pixels
    final float colorfulness;     // Hasler-Suesstrunk M (0 = grey, >60 = very colourful)
    final float skyFraction;      // share of the top third that looks like sky (heuristic)

    private SceneStats(float n, float[] g, float c, float col, float sky) {
        noiseSigma = n; wbGains = g; wbConfidence = c; colorfulness = col; skyFraction = sky;
    }

    static SceneStats measure(int[] px, int w, int h) {
        // ---- noise: Immerkaer (1996) Laplacian-difference estimator on flat areas only
        float[] Y = new float[w * h];
        for (int i = 0; i < Y.length; i++) {
            int c = px[i];
            Y[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        double acc = 0; long cnt = 0;
        int step = Math.max(1, (w * h) / 2_000_000);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x += step) {
                int i = y * w + x;
                float gx = Y[i + 1] - Y[i - 1], gy = Y[i + w] - Y[i - w];
                if (gx * gx + gy * gy > 400f) continue;            // skip edges and texture peaks
                float l = Y[i - w - 1] - 2 * Y[i - w] + Y[i - w + 1]
                        - 2 * Y[i - 1] + 4 * Y[i] - 2 * Y[i + 1]
                        + Y[i + w - 1] - 2 * Y[i + w] + Y[i + w + 1];
                acc += Math.abs(l);
                cnt++;
            }
        }
        float sigma = cnt > 0 ? (float) (Math.sqrt(Math.PI / 2.0) * acc / (6.0 * cnt)) : 0f;

        // ---- white balance: average of bright, low-chroma pixels (grey-edge / white-patch blend)
        float[] lin = ColorMath.SRGB_TO_LINEAR;
        double sr = 0, sg = 0, sb = 0, wsum = 0;
        double rg = 0, yb = 0, rg2 = 0, yb2 = 0;
        long n = 0;
        int skyHit = 0, skyN = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c = px[y * w + x];
                int r8 = (c >> 16) & 255, g8 = (c >> 8) & 255, b8 = c & 255;
                float r = lin[r8], g = lin[g8], b = lin[b8];
                float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                float yl = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                if (mx < 0.97f && yl > 0.05f && (mx - mn) / (mx + 1e-4f) < 0.25f) {
                    float wgt = yl;
                    sr += r * wgt; sg += g * wgt; sb += b * wgt; wsum += wgt;
                }
                float a = r8 - g8, bb = 0.5f * (r8 + g8) - b8;
                rg += a; yb += bb; rg2 += a * a; yb2 += bb * bb; n++;
                if (y < h / 3) {
                    skyN++;
                    boolean blueSky = b8 > r8 + 8 && b8 > 90 && (mx - mn) / (mx + 1e-4f) < 0.7f;
                    boolean brightOvercast = yl > 0.55f && (mx - mn) / (mx + 1e-4f) < 0.12f;
                    if (blueSky || brightOvercast) skyHit++;
                }
            }
        }
        float[] gains = {1f, 1f, 1f};
        float conf = 0f;
        if (wsum > 0) {
            float R = (float) (sr / wsum), G = (float) (sg / wsum), B = (float) (sb / wsum);
            gains[0] = clamp(G / Math.max(R, 1e-5f), 0.80f, 1.25f);
            gains[2] = clamp(G / Math.max(B, 1e-5f), 0.80f, 1.25f);
            conf = clamp((float) (wsum / (n * 0.05 + 1e-6)), 0f, 1f);
        }
        double mrg = rg / n, myb = yb / n;
        double srg = Math.sqrt(Math.max(0, rg2 / n - mrg * mrg)), syb = Math.sqrt(Math.max(0, yb2 / n - myb * myb));
        float col = (float) (Math.sqrt(srg * srg + syb * syb) + 0.3 * Math.sqrt(mrg * mrg + myb * myb));
        float sky = skyN > 0 ? (float) skyHit / skyN : 0f;
        return new SceneStats(sigma, gains, conf, col, sky);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
