package com.majdi.cinematicai;

/**
 * Depth-aware lens blur rendered at working resolution.
 *
 * Thin-lens defocus is proportional to |1/z - 1/z_focus|; the depth network predicts relative
 * inverse depth, so the circle of confusion is linear in (d - d_focus). The scene is split into
 * depth layers (background, in-focus, foreground) and each layer is blurred with a true disk
 * kernel using normalized convolution of premultiplied colour, then composited back to front:
 *  - background layers only ever average background pixels, so the subject's colour cannot
 *    bleed outward (no halo), and the region behind the subject is filled from background;
 *  - the in-focus layer (subject) is kept sharp with its refined matte;
 *  - foreground layers are blurred and composited over everything, as a real lens does.
 * Highlights are expanded in linear light before blurring so bright points become bokeh discs.
 *
 * Outputs: the rendered image R (linear RGB, working resolution) and the sharp weight S, which
 * the full-resolution renderer uses to keep original full-resolution detail where in focus.
 */
final class Bokeh {

    final int w, h;
    final float[] rr, rg, rb;   // rendered linear RGB
    final float[] sharp;        // weight of the sharp layer in the final pixel (0..1)

    private Bokeh(int w, int h, float[] r, float[] g, float[] b, float[] s) {
        this.w = w; this.h = h; this.rr = r; this.rg = g; this.rb = b; this.sharp = s;
    }

    /**
     * @param lr,lg,lb  linear RGB at working resolution (already graded)
     * @param depth     relative inverse depth, normalized 0 (far) .. 1 (near), edge-refined
     * @param subject   subject matte 0..1, edge-refined
     * @param focus     inverse depth of the focal plane
     * @param maxRadius maximum blur radius in pixels at this resolution
     */
    static Bokeh render(float[] lr, float[] lg, float[] lb, float[] depth, float[] subject,
                        int w, int h, float focus, float maxRadius, float depthOfField) {
        int n = w * h;
        final int L = 6;                      // blur layers on each side of the focal plane
        float[] coc = new float[n];           // signed, in layer units: +background, -foreground
        for (int i = 0; i < n; i++) {
            float dd = focus - depth[i];      // >0: farther than focus
            float a = Math.abs(dd);
            a = Math.max(0f, a - depthOfField) / Math.max(1e-3f, 0.55f - depthOfField);  // dead zone = depth of field
            if (a > 1f) a = 1f;
            float c = a * L * Math.signum(dd);
            c *= (1f - subject[i]);           // the subject is always in focus
            coc[i] = c;
        }

        // highlight expansion (clipped JPEG highlights carry more energy than their code value)
        float[] er = new float[n], eg = new float[n], eb = new float[n];
        for (int i = 0; i < n; i++) {
            float yl = 0.2126f * lr[i] + 0.7152f * lg[i] + 0.0722f * lb[i];
            float boost = 1f + 2.5f * ColorMath.smooth(0.80f, 1.0f, yl);  // only bright, near-clipped lights
            er[i] = lr[i] * boost; eg[i] = lg[i] * boost; eb[i] = lb[i] * boost;
        }

        float[] accR = new float[n], accG = new float[n], accB = new float[n], accA = new float[n];

        // ---- background, far to near
        for (int k = L; k >= 1; k--) {
            float[] wgt = new float[n];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                float c = coc[i];
                if (c <= 0f) continue;
                float m = 1f - Math.abs(c - k);
                if (k == L && c > L) m = 1f;
                if (m > 0f) { wgt[i] = m; any = true; }
            }
            if (!any) continue;
            float radius = maxRadius * k / L;
            float[][] bl = blurPremult(er, eg, eb, wgt, w, h, radius);
            overComposite(accR, accG, accB, accA, bl);
        }

        // fill the region behind the subject from background only (normalized convolution)
        {
            float[] wbg = new float[n];
            for (int i = 0; i < n; i++) wbg[i] = coc[i] > 0.5f ? 1f : 0f;
            float[][] fill = blurPremult(er, eg, eb, wbg, w, h, Math.max(maxRadius, 8f) * 1.5f);
            float[][] fill2 = blurPremult(er, eg, eb, wbg, w, h, Math.max(maxRadius, 8f) * 4f);
            for (int i = 0; i < n; i++) {
                float a = accA[i];
                if (a >= 0.999f) continue;
                float fa = fill[3][i], fr, fg, fb;
                if (fa > 0.02f) { fr = fill[0][i] / fa; fg = fill[1][i] / fa; fb = fill[2][i] / fa; }
                else if (fill2[3][i] > 1e-4f) { float f2 = fill2[3][i]; fr = fill2[0][i] / f2; fg = fill2[1][i] / f2; fb = fill2[2][i] / f2; }
                else { fr = er[i]; fg = eg[i]; fb = eb[i]; }
                float miss = 1f - a;
                accR[i] += fr * miss; accG[i] += fg * miss; accB[i] += fb * miss; accA[i] = 1f;
            }
        }

        // ---- in-focus layer (sharp)
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float c = Math.abs(coc[i]);
            float m = c < 1f ? 1f - c : 0f;
            s[i] = m;
            accR[i] = lr[i] * m + accR[i] * (1f - m);
            accG[i] = lg[i] * m + accG[i] * (1f - m);
            accB[i] = lb[i] * m + accB[i] * (1f - m);
        }

        // ---- foreground, far (near the focus) to nearest
        for (int k = 1; k <= L; k++) {
            float[] wgt = new float[n];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                float c = -coc[i];
                if (c <= 0f) continue;
                float m = 1f - Math.abs(c - k);
                if (k == L && c > L) m = 1f;
                if (m > 0f) { wgt[i] = m; any = true; }
            }
            if (!any) continue;
            float radius = maxRadius * k / L;
            float[][] bl = blurPremult(er, eg, eb, wgt, w, h, radius);
            for (int i = 0; i < n; i++) {
                float a = Math.min(1f, bl[3][i]);
                accR[i] = bl[0][i] + accR[i] * (1f - a);
                accG[i] = bl[1][i] + accG[i] * (1f - a);
                accB[i] = bl[2][i] + accB[i] * (1f - a);
                s[i] *= (1f - a);
            }
        }

        // undo highlight expansion with a soft roll-off back into range
        for (int i = 0; i < n; i++) {
            accR[i] = rolloff(accR[i]); accG[i] = rolloff(accG[i]); accB[i] = rolloff(accB[i]);
        }
        return new Bokeh(w, h, accR, accG, accB, s);
    }

    private static float rolloff(float v) {
        if (v <= 0.9f) return v;
        return 0.9f + 0.1f * (1f - (float) Math.exp(-(v - 0.9f) / 0.1f));
    }

    private static float[][] blurPremult(float[] r, float[] g, float[] b, float[] wgt, int w, int h, float radius) {
        int n = w * h;
        float[] pr = new float[n], pg = new float[n], pb = new float[n];
        for (int i = 0; i < n; i++) { pr[i] = r[i] * wgt[i]; pg[i] = g[i] * wgt[i]; pb[i] = b[i] * wgt[i]; }
        return Guided.diskBlur(new float[][]{pr, pg, pb, wgt}, w, h, radius);
    }

    private static void overComposite(float[] ar, float[] ag, float[] ab, float[] aa, float[][] bl) {
        for (int i = 0; i < ar.length; i++) {
            float a = Math.min(1f, bl[3][i]);
            ar[i] = bl[0][i] + ar[i] * (1f - a);
            ag[i] = bl[1][i] + ag[i] * (1f - a);
            ab[i] = bl[2][i] + ab[i] * (1f - a);
            aa[i] = a + aa[i] * (1f - a);
        }
    }
}
