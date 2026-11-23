package com.majdi.cinematicai;

import java.util.Arrays;

/**
 * Selects a whole object from a tap, for the controllable lens-blur tool.
 * Region growing on the depth map: a pixel joins if its depth is close to the tapped depth and
 * continuous with its neighbour (a depth step stops the region, so the object ends at its real
 * boundary). If the tap falls on the subject found by the segmentation network, that subject is
 * merged in. The binary region is then turned into a soft mask that follows image edges
 * (guided filter on the photo), and can be grown or shrunk by the user.
 */
final class ObjectSelect {

    private ObjectSelect() {}

    /**
     * @param lum  display luma 0..1 of the working image (edge guide)
     * @param u,v  tap in display-normalized coordinates
     * @param grow -1 (shrink) .. 0 .. +1 (grow) the selected region
     * @return soft mask 0..1 at working resolution
     */
    static float[] select(SceneMaps maps, float[] lum, float u, float v, float grow) {
        int w = maps.w, h = maps.h, n = w * h;
        float[] d = maps.depth;
        int sx = Math.max(0, Math.min(w - 1, Math.round(u * (w - 1))));
        int sy = Math.max(0, Math.min(h - 1, Math.round(v * (h - 1))));
        float d0 = maps.depthAt(u, v);
        float tolAbs = 0.20f, step = 0.030f;

        boolean[] in = new boolean[n];
        int[] queue = new int[n];
        int qh = 0, qt = 0;
        int seed = sy * w + sx;
        in[seed] = true;
        queue[qt++] = seed;
        while (qh < qt) {
            int i = queue[qh++];
            int x = i % w, y = i / w;
            float di = d[i];
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int j = ny * w + nx;
                if (in[j]) continue;
                float dj = d[j];
                float dstep = Math.abs(dj - di), lstep = Math.abs(lum[j] - lum[i]);
                // grow along continuous depth; across a visible image edge only if depth is flat there
                boolean cont = dstep < step && (lstep < 0.15f || dstep < step * 0.5f);
                if (Math.abs(dj - d0) < tolAbs && cont) {
                    in[j] = true;
                    queue[qt++] = j;
                }
            }
        }
        float[] m = new float[n];
        for (int i = 0; i < n; i++) m[i] = in[i] ? 1f : 0f;
        if (maps.hasSubject && maps.subjectAt(u, v) > 0.5f) {
            // the segmentation matte may hold several objects: take only what lies at the
            // tapped object's distance (a lamp or fan elsewhere in the room is not merged)
            for (int i = 0; i < n; i++) {
                float near = 1f - ColorMath.smooth(tolAbs * 0.5f, tolAbs, Math.abs(d[i] - d0));
                m[i] = Math.max(m[i], maps.subject[i] * near);
            }
        }
        // soft, edge-following mask; grow/shrink by moving the threshold of a smoothed mask
        int lg = Math.max(w, h);
        int r = Math.max(2, lg / 200);
        float[] sm = Guided.boxMean(m, w, h, r);
        float thr = 0.5f - 0.38f * Math.max(-1f, Math.min(1f, grow));
        for (int i = 0; i < n; i++) sm[i] = ColorMath.smooth(thr - 0.12f, thr + 0.12f, sm[i]);
        float[] g = Guided.guidedFilter(lum, sm, w, h, Math.max(2, lg / 320), 1e-4f);
        for (int i = 0; i < n; i++) g[i] = Math.max(0f, Math.min(1f, g[i]));
        return g;
    }

    /** Median depth inside a mask (focal plane of a selected object). */
    static float medianDepth(float[] depth, float[] mask) {
        float[] v = new float[depth.length];
        int c = 0;
        for (int i = 0; i < depth.length; i++) if (mask[i] > 0.6f) v[c++] = depth[i];
        if (c == 0) return 0.5f;
        Arrays.sort(v, 0, c);
        return v[c / 2];
    }
}
