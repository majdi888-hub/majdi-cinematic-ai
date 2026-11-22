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

    /** Renders the preview with the plan: the same strip renderer (and overlap) as the export. */
    static Bitmap render(RenderPlan plan, int[] px, int w, int h) {
        GradeCore core = new GradeCore(plan, w, h, 1);
        int halo = GradeCore.HALO;
        int[] out = new int[w * h];
        int[] in = new int[w * (STRIP + 2 * halo)];
        int[] o = new int[w * STRIP];
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            int y1 = Math.min(h, y0 + STRIP);
            int ys = Math.max(0, y0 - halo);
            int ye = Math.min(h, y1 + halo);
            System.arraycopy(px, ys * w, in, 0, (ye - ys) * w);
            core.processStrip(in, ys, ye - ys, y0, y1, o);
            System.arraycopy(o, 0, out, y0 * w, (y1 - y0) * w);
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    /** Tints what stays sharp in orange, so the user sees the effect of the focus controls. */
    static Bitmap focusOverlay(Bitmap res, float[] mask) {
        int w = res.getWidth(), h = res.getHeight();
        if (mask.length != w * h) return null;
        int[] px = pixels(res);
        for (int i = 0; i < px.length; i++) {
            float a = 0.45f * mask[i];
            int c = px[i];
            int r = (int) (((c >> 16) & 255) * (1 - a) + 255 * a);
            int g = (int) (((c >> 8) & 255) * (1 - a) + 165 * a);
            int b = (int) ((c & 255) * (1 - a) + 40 * a);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}
