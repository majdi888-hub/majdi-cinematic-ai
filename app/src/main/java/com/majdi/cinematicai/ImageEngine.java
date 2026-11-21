package com.majdi.cinematicai;

import android.graphics.Bitmap;
import android.graphics.RectF;

import java.util.List;

/** Preview rendering on a Bitmap, using the same strip-based core as the full-resolution export. */
final class ImageEngine {

    private ImageEngine() {}

    static final int STRIP = 256;

    static float[][] toArray(List<RectF> faces) {
        float[][] f = new float[faces == null ? 0 : faces.size()][];
        for (int i = 0; i < f.length; i++) {
            RectF r = faces.get(i);
            f[i] = new float[]{r.left, r.top, r.right, r.bottom};
        }
        return f;
    }

    static Bitmap process(Bitmap src, String style, float strength, List<RectF> faces) {
        int w = src.getWidth();
        int h = src.getHeight();
        GradeCore core = new GradeCore(style, strength, toArray(faces), w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] in = new int[w * (STRIP + 2)];
        int[] o = new int[w * STRIP];
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            int y1 = Math.min(h, y0 + STRIP);
            int ys = Math.max(0, y0 - 1);
            int ye = Math.min(h, y1 + 1);
            src.getPixels(in, 0, w, 0, ys, w, ye - ys);
            core.processStrip(in, ys, ye - ys, y0, y1, o);
            out.setPixels(o, 0, w, 0, y0, w, y1 - y0);
        }
        return out;
    }
}
