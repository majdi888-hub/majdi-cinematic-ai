package com.majdi.cinematicai;

import android.graphics.Bitmap;
import android.graphics.RectF;

import java.util.List;

/** Bitmap glue between Android and the pure-Java engine. */
final class ImageEngine {

    private ImageEngine() {}

    static final int STRIP = 128;

    static int[] pixels(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    static float[][] faces(List<RectF> faces) {
        float[][] f = new float[faces == null ? 0 : faces.size()][];
        for (int i = 0; i < f.length; i++) {
            RectF r = faces.get(i);
            f[i] = new float[]{r.left, r.top, r.right, r.bottom};
        }
        return f;
    }

    /** Renders the preview with the plan (same strip renderer as the full-resolution export). */
    static Bitmap render(RenderPlan plan, int[] px, int w, int h) {
        GradeCore core = new GradeCore(plan, w, h, 1);
        int[] out = new int[w * h];
        int[] in = new int[w * (STRIP + 2)];
        int[] o = new int[w * STRIP];
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            int y1 = Math.min(h, y0 + STRIP);
            int ys = Math.max(0, y0 - 1);
            int ye = Math.min(h, y1 + 1);
            System.arraycopy(px, ys * w, in, 0, (ye - ys) * w);
            core.processStrip(in, ys, ye - ys, y0, y1, o);
            System.arraycopy(o, 0, out, y0 * w, (y1 - y0) * w);
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }
}
