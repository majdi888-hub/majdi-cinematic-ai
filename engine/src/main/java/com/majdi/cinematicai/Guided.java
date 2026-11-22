package com.majdi.cinematicai;

/** Planar float image utilities: box filters, guided filter, resampling, disk blur. */
final class Guided {

    private Guided() {}

    /** Box mean with clamped borders (summed-area table). */
    static float[] boxMean(float[] src, int w, int h, int r) {
        double[] sat = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            int o = (y + 1) * (w + 1), p = y * (w + 1);
            for (int x = 0; x < w; x++) {
                row += src[y * w + x];
                sat[o + x + 1] = sat[p + x + 1] + row;
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

    /**
     * Guided filter coefficients (He et al.): q = meanA * I + meanB.
     * Returning the coefficients lets them be upsampled and applied with a higher-resolution
     * guide (fast guided filter), so edges follow the full-resolution image.
     */
    static float[][] guidedCoeffs(float[] I, float[] p, int w, int h, int r, float eps) {
        int n = w * h;
        float[] mI = boxMean(I, w, h, r), mP = boxMean(p, w, h, r);
        float[] ip = new float[n], ii = new float[n];
        for (int i = 0; i < n; i++) { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i]; }
        float[] mIP = boxMean(ip, w, h, r), mII = boxMean(ii, w, h, r);
        float[] a = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            float cov = mIP[i] - mI[i] * mP[i];
            float var = Math.max(0f, mII[i] - mI[i] * mI[i]);
            a[i] = cov / (var + eps);
            b[i] = mP[i] - a[i] * mI[i];
        }
        return new float[][]{boxMean(a, w, h, r), boxMean(b, w, h, r)};
    }

    static float[] guidedFilter(float[] I, float[] p, int w, int h, int r, float eps) {
        float[][] ab = guidedCoeffs(I, p, w, h, r, eps);
        float[] q = new float[w * h];
        for (int i = 0; i < q.length; i++) q[i] = ab[0][i] * I[i] + ab[1][i];
        return q;
    }

    /** Resize: area averaging when shrinking, bilinear when enlarging. */
    static float[] resize(float[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src.clone();
        float[] out = new float[dw * dh];
        if (dw <= sw && dh <= sh) {
            float fx = (float) sw / dw, fy = (float) sh / dh;
            for (int y = 0; y < dh; y++) {
                float sy0 = y * fy, sy1 = (y + 1) * fy;
                for (int x = 0; x < dw; x++) {
                    float sx0 = x * fx, sx1 = (x + 1) * fx;
                    double acc = 0, wsum = 0;
                    for (int yy = (int) sy0; yy < Math.min(sh, (int) Math.ceil(sy1)); yy++) {
                        float wy = Math.min(sy1, yy + 1) - Math.max(sy0, yy);
                        for (int xx = (int) sx0; xx < Math.min(sw, (int) Math.ceil(sx1)); xx++) {
                            float wx = Math.min(sx1, xx + 1) - Math.max(sx0, xx);
                            acc += src[yy * sw + xx] * wx * wy;
                            wsum += wx * wy;
                        }
                    }
                    out[y * dw + x] = (float) (acc / Math.max(wsum, 1e-9));
                }
            }
        } else {
            for (int y = 0; y < dh; y++) {
                float v = (y + 0.5f) / dh;
                for (int x = 0; x < dw; x++) out[y * dw + x] = sample(src, sw, sh, (x + 0.5f) / dw, v);
            }
        }
        return out;
    }

    /** Bilinear sample at normalized coordinates (pixel centres at (i+0.5)/size). */
    static float sample(float[] g, int gw, int gh, float u, float v) {
        float gx = u * gw - 0.5f, gy = v * gh - 0.5f;
        if (gx < 0f) gx = 0f;
        if (gy < 0f) gy = 0f;
        if (gx > gw - 1) gx = gw - 1;
        if (gy > gh - 1) gy = gh - 1;
        int x0 = (int) gx, y0 = (int) gy;
        int x1 = Math.min(gw - 1, x0 + 1), y1 = Math.min(gh - 1, y0 + 1);
        float fx = gx - x0, fy = gy - y0;
        float t = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * fx;
        float b = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * fx;
        return t + (b - t) * fy;
    }

    /**
     * Lens-like disk blur of several planes at once (same kernel). Large radii are computed on a
     * box-downsampled copy and upsampled, which keeps the cost bounded; the kernel is a true
     * disk with anti-aliased edges (row prefix sums with fractional half-widths).
     */
    static float[][] diskBlur(float[][] planes, int w, int h, float r) {
        if (r < 0.5f) {
            float[][] c = new float[planes.length][];
            for (int k = 0; k < planes.length; k++) c[k] = planes[k].clone();
            return c;
        }
        int f = Math.max(1, (int) (r / 7f));
        if (f > 1) {
            int sw = Math.max(1, w / f), sh = Math.max(1, h / f);
            float[][] small = new float[planes.length][];
            for (int k = 0; k < planes.length; k++) small[k] = resize(planes[k], w, h, sw, sh);
            float[][] bs = diskBlurDirect(small, sw, sh, r * sw / (float) w);
            float[][] out = new float[planes.length][];
            for (int k = 0; k < planes.length; k++) out[k] = resize(bs[k], sw, sh, w, h);
            return out;
        }
        return diskBlurDirect(planes, w, h, r);
    }

    private static float[][] diskBlurDirect(float[][] planes, int w, int h, float r) {
        int R = (int) Math.ceil(r);
        int np = planes.length;
        // row prefix sums with padding by edge replication
        int pw = w + 2 * R + 2;
        float[][] out = new float[np][w * h];
        float[] hw = new float[2 * R + 1];
        float area = 0f;
        for (int dy = -R; dy <= R; dy++) {
            float v = r * r - dy * dy;
            hw[dy + R] = v > 0f ? (float) Math.sqrt(v) : -1f;
            if (hw[dy + R] >= 0f) area += 2f * hw[dy + R] + 1f;
        }
        float inv = 1f / area;
        final int fR = R, fpw = pw;
        final float finv = inv;
        java.util.stream.IntStream.range(0, np).parallel().forEach(k -> blurPlane(planes[k], out[k], w, h, fR, fpw, hw, finv));
        return out;
    }

    private static void blurPlane(float[] src, float[] dst, int w, int h, int R, int pw, float[] hw, float inv) {
        {
            double[][] rows = new double[h][];
            for (int y = 0; y < h; y++) {
                double[] p = new double[pw + 1];
                for (int i = 0; i < pw; i++) {
                    int sx = Math.min(w - 1, Math.max(0, i - R - 1));
                    p[i + 1] = p[i] + src[y * w + sx];
                }
                rows[y] = p;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double acc = 0;
                    for (int dy = -R; dy <= R; dy++) {
                        float hwv = hw[dy + R];
                        if (hwv < 0f) continue;
                        int yy = Math.min(h - 1, Math.max(0, y + dy));
                        double[] p = rows[yy];
                        float cx = x + R + 1;            // index in padded row
                        acc += prefixAt(p, cx + hwv + 0.5f) - prefixAt(p, cx - hwv - 0.5f);
                    }
                    dst[y * w + x] = (float) (acc * inv);
                }
            }
        }
    }

    /** Linearly interpolated prefix sum at fractional position t (sum of samples [0, t)). */
    private static double prefixAt(double[] p, float t) {
        if (t <= 0f) return 0;
        int n = p.length - 1;
        if (t >= n) return p[n];
        int i = (int) t;
        float f = t - i;
        return p[i] + (p[i + 1] - p[i]) * f;
    }
}
