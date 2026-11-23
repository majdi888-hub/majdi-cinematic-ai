package com.majdi.cinematicai;

import java.util.Arrays;

/**
 * Depth and subject maps at working resolution, prepared from the raw network outputs:
 * resized to the image, normalized, and edge-refined with a guided filter that uses the
 * photo itself as guide (so depth and matte edges snap to real object boundaries, hair included).
 * Also chooses the focal plane and measures how well the subject separates from the background.
 */
final class SceneMaps {

    final int w, h;
    final float[] depth;      // 0 far .. 1 near (relative inverse depth)
    final float[] subject;    // 0..1
    final boolean hasSubject;
    final float subjectArea;  // fraction of the frame
    final float focus;        // inverse depth of focal plane
    final float separation;   // focus minus median background depth (0..1 scale)
    final float depthSpread;  // p90 - p10 of depth
    private final boolean depthValid;

    boolean hasDepth() { return depthValid; }

    /**
     * A subject is trusted for automatic lens isolation only if it is a sizeable region that
     * stands clearly in front of its background. A small bright object (a lamp, a fan) is not.
     */
    boolean subjectReliable() {
        return hasSubject && subjectArea >= 0.04f && subjectArea <= 0.65f && separation >= 0.10f;
    }

    /** Depth of the focal plane at a tapped point: median inverse depth in a small window. */
    float depthAt(float u, float v) {
        int cx = Math.round(u * (w - 1)), cy = Math.round(v * (h - 1));
        int r = Math.max(2, Math.max(w, h) / 120);
        float[] vals = new float[(2 * r + 1) * (2 * r + 1)];
        int c = 0;
        for (int y = Math.max(0, cy - r); y <= Math.min(h - 1, cy + r); y++)
            for (int x = Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) vals[c++] = depth[y * w + x];
        Arrays.sort(vals, 0, c);
        return vals[c / 2];
    }

    float subjectAt(float u, float v) {
        return Guided.sample(subject, w, h, u, v);
    }

    private SceneMaps(int w, int h, float[] d, float[] s, boolean has, float area, float focus, float sep, float spread, boolean dv) {
        this.w = w; this.h = h; depth = d; subject = s; hasSubject = has; subjectArea = area;
        this.focus = focus; separation = sep; depthSpread = spread; depthValid = dv;
    }

    /**
     * @param lum      display luma 0..1 of the working image (guide)
     * @param depthRaw network depth (larger = nearer), size dw x dh, any range; may be null
     * @param matteRaw network subject matte 0..1, size mw x mh; may be null
     * @param faces    face rectangles normalized to the image
     */
    static SceneMaps build(float[] lum, int w, int h, float[] depthRaw, int dw, int dh,
                           float[] matteRaw, int mw, int mh, float[][] faces) {
        int n = w * h;
        int lg = Math.max(w, h);

        float[] d;
        if (depthRaw != null) {
            d = Guided.resize(depthRaw, dw, dh, w, h);
            float[] sorted = d.clone();
            Arrays.sort(sorted);
            float lo = sorted[(int) (n * 0.01f)], hi = sorted[(int) (n * 0.995f)];
            float inv = 1f / Math.max(1e-6f, hi - lo);
            for (int i = 0; i < n; i++) d[i] = Math.max(0f, Math.min(1f, (d[i] - lo) * inv));
            // two-scale edge snapping with the photo as guide: a wide pass removes the network's
            // block structure, a narrow low-epsilon pass aligns depth steps with object edges
            d = Guided.guidedFilter(lum, d, w, h, Math.max(2, lg / 160), 1e-3f);
            d = Guided.guidedFilter(lum, d, w, h, Math.max(2, lg / 420), 2e-4f);
            for (int i = 0; i < n; i++) d[i] = Math.max(0f, Math.min(1f, d[i]));
        } else {
            d = new float[n];
            Arrays.fill(d, 0.5f);
        }

        float[] s = new float[n];
        float area = 0f;
        if (matteRaw != null) {
            float[] m = Guided.resize(matteRaw, mw, mh, w, h);
            m = Guided.guidedFilter(lum, m, w, h, Math.max(2, lg / 320), 1e-4f);
            for (int i = 0; i < n; i++) {
                float v = m[i];
                v = (v - 0.08f) / 0.84f;                   // remove faint background response
                v = v < 0f ? 0f : (v > 1f ? 1f : v);
                s[i] = v;
                area += v;
            }
            area /= n;
        }
        boolean has = matteRaw != null && area > 0.01f && area < 0.85f;
        if (!has) Arrays.fill(s, 0f);

        // ---- focal plane
        float focus;
        if (has) {
            float[] vals = new float[n];
            int c = 0;
            for (int i = 0; i < n; i++) if (s[i] > 0.6f) vals[c++] = d[i];
            if (c > 50) {
                Arrays.sort(vals, 0, c);
                focus = vals[c / 2];
            } else focus = percentile(d, 0.8f);
        } else if (faces != null && faces.length > 0) {
            float[] f = faces[0];
            focus = Guided.sample(d, w, h, (f[0] + f[2]) / 2f, (f[1] + f[3]) / 2f);
        } else {
            // no subject and no face: a depth statistic only (never the position in the frame);
            // automatic isolation stays off in this case anyway (see subjectReliable)
            focus = percentile(d, 0.8f);
        }

        // ---- separation and spread
        float[] bg = new float[n];
        int c = 0;
        for (int i = 0; i < n; i++) if (s[i] < 0.2f) bg[c++] = d[i];
        float sep = 0f;
        if (c > 0) {
            Arrays.sort(bg, 0, c);
            sep = focus - bg[c / 2];
        }
        float[] all = d.clone();
        Arrays.sort(all);
        float spread = all[(int) (n * 0.9f)] - all[(int) (n * 0.1f)];
        return new SceneMaps(w, h, d, s, has, area, focus, sep, spread, depthRaw != null);
    }

    private static float percentile(float[] d, float q) {
        float[] v = d.clone();
        Arrays.sort(v);
        return v.length > 0 ? v[(int) ((v.length - 1) * q)] : 0.5f;
    }

    /** The same maps resampled to another working size (used for small filter previews). */
    SceneMaps resized(int nw, int nh) {
        if (nw == w && nh == h) return this;
        return new SceneMaps(nw, nh, Guided.resize(depth, w, h, nw, nh), Guided.resize(subject, w, h, nw, nh),
                hasSubject, subjectArea, focus, separation, depthSpread, depthValid);
    }
}
