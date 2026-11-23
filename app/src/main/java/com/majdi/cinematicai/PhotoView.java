package com.majdi.cinematicai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * The image canvas: fit-to-screen display, pinch zoom, pan, double-tap zoom, press-and-hold
 * compare with the original, tap callback (focus point) and an interactive crop rectangle
 * (drag edges/corners/inside, optional fixed aspect ratio).
 */
final class PhotoView extends View {

    interface Listener {
        void onTap(float u, float v);           // normalized coordinates in the shown bitmap
        void onCropChanged(RectF crop, boolean finished);
        void onCompare(boolean showingOriginal);
    }

    private Bitmap bitmap, original;
    private boolean showOriginal;
    private float zoom = 1f, panX, panY;
    private final Matrix matrix = new Matrix();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
    private Listener listener;

    // crop mode
    private boolean cropMode;
    private final RectF crop = new RectF(0, 0, 1, 1);   // normalized in the shown bitmap
    private float cropAspect;                           // w/h in pixels; 0 = free
    private int dragging = 0;                           // bit flags: 1 L, 2 T, 4 R, 8 B, 16 move
    private float lastX, lastY;

    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;

    PhotoView(Context c) {
        super(c);
        shade.setColor(0x99000000);
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        handle.setColor(Color.rgb(242, 165, 65));
        grid.setColor(0x66FFFFFF);
        grid.setStrokeWidth(dp(0.8f));
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float old = zoom;
                zoom = Math.max(1f, Math.min(10f, zoom * d.getScaleFactor()));
                // zoom around the fingers
                float fx = d.getFocusX() - getWidth() / 2f, fy = d.getFocusY() - getHeight() / 2f;
                panX = fx - (fx - panX) * zoom / old;
                panY = fy - (fy - panY) * zoom / old;
                updateMatrix();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (cropMode || listener == null || bitmap == null) return true;
                float[] p = toBitmap(e.getX(), e.getY());
                if (p[0] >= 0 && p[1] >= 0 && p[0] <= 1 && p[1] <= 1) listener.onTap(p[0], p[1]);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1.01f) { zoom = 1f; panX = 0; panY = 0; }
                else {
                    zoom = 2.5f;
                    float fx = e.getX() - getWidth() / 2f, fy = e.getY() - getHeight() / 2f;
                    panX = -fx * (zoom - 1f);
                    panY = -fy * (zoom - 1f);
                }
                updateMatrix();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (!cropMode && original != null && dragging == 0) setComparing(true);
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (dragging == 0 && zoom > 1f) { panX -= dx; panY -= dy; updateMatrix(); }
                return true;
            }
        });
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    void setListener(Listener l) { listener = l; }

    /** Shows a new result; keeps zoom if the size is unchanged. */
    void setImages(Bitmap shown, Bitmap originalForCompare) {
        boolean sameSize = bitmap != null && shown != null && bitmap.getWidth() == shown.getWidth() && bitmap.getHeight() == shown.getHeight();
        bitmap = shown;
        original = originalForCompare;
        if (!sameSize) { zoom = 1f; panX = 0; panY = 0; }
        updateMatrix();
    }

    Bitmap shown() { return bitmap; }

    void setComparing(boolean on) {
        if (showOriginal == on) return;
        showOriginal = on && original != null;
        if (listener != null) listener.onCompare(showOriginal);
        invalidate();
    }

    void resetZoom() { zoom = 1f; panX = 0; panY = 0; updateMatrix(); }

    void setCropMode(boolean on, RectF c, float aspect) {
        cropMode = on;
        if (c != null) crop.set(c);
        cropAspect = aspect;
        if (on) { zoom = 1f; panX = 0; panY = 0; }
        updateMatrix();
    }

    /** Applies a fixed aspect (w/h in pixels) to the current crop, centred, as large as possible. */
    void setCropAspect(float aspect) {
        cropAspect = aspect;
        if (bitmap == null) return;
        if (aspect > 0) {
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float cx = crop.centerX(), cy = crop.centerY();
            float w = crop.width() * bw, h = crop.height() * bh;
            float area = w * h;
            float nw = (float) Math.sqrt(area * aspect), nh = nw / aspect;
            float s = Math.min(1f, Math.min(bw / nw, bh / nh));
            nw *= s; nh *= s;
            float hw = nw / bw / 2f, hh = nh / bh / 2f;
            cx = Math.max(hw, Math.min(1 - hw, cx));
            cy = Math.max(hh, Math.min(1 - hh, cy));
            crop.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }
        invalidate();
        if (listener != null) listener.onCropChanged(new RectF(crop), true);
    }

    private void updateMatrix() {
        if (bitmap == null || getWidth() == 0) { invalidate(); return; }
        float pad = cropMode ? dp(24) : 0;
        float vw = getWidth() - 2 * pad, vh = getHeight() - 2 * pad;
        float s0 = Math.min(vw / bitmap.getWidth(), vh / bitmap.getHeight());
        float s = s0 * zoom;
        float dw = bitmap.getWidth() * s, dh = bitmap.getHeight() * s;
        float maxX = Math.max(0f, (dw - vw) / 2f), maxY = Math.max(0f, (dh - vh) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        matrix.setScale(s, s);
        matrix.postTranslate((getWidth() - dw) / 2f + panX, (getHeight() - dh) / 2f + panY);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { updateMatrix(); }

    private float[] toBitmap(float x, float y) {
        Matrix inv = new Matrix();
        matrix.invert(inv);
        float[] p = {x, y};
        inv.mapPoints(p);
        p[0] /= bitmap.getWidth();
        p[1] /= bitmap.getHeight();
        return p;
    }

    private RectF screenRect(RectF n) {
        RectF r = new RectF(n.left * bitmap.getWidth(), n.top * bitmap.getHeight(), n.right * bitmap.getWidth(), n.bottom * bitmap.getHeight());
        matrix.mapRect(r);
        return r;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bitmap == null) return;
        Bitmap b = showOriginal && original != null ? original : bitmap;
        if (b != bitmap) {
            // the original may have a different size (e.g. before a frame): fit it in the same place
            Matrix m = new Matrix(matrix);
            m.preScale(bitmap.getWidth() / (float) b.getWidth(), bitmap.getHeight() / (float) b.getHeight());
            c.drawBitmap(b, m, bmpPaint);
        } else {
            c.drawBitmap(b, matrix, bmpPaint);
        }
        if (cropMode) {
            RectF img = screenRect(new RectF(0, 0, 1, 1));
            RectF r = screenRect(crop);
            c.drawRect(img.left, img.top, img.right, r.top, shade);
            c.drawRect(img.left, r.bottom, img.right, img.bottom, shade);
            c.drawRect(img.left, r.top, r.left, r.bottom, shade);
            c.drawRect(r.right, r.top, img.right, r.bottom, shade);
            for (int i = 1; i < 3; i++) {
                float x = r.left + r.width() * i / 3f, y = r.top + r.height() * i / 3f;
                c.drawLine(x, r.top, x, r.bottom, grid);
                c.drawLine(r.left, y, r.right, y, grid);
            }
            c.drawRect(r, line);
            float hs = dp(5);
            for (float x : new float[]{r.left, r.centerX(), r.right}) for (float y : new float[]{r.top, r.centerY(), r.bottom}) {
                if (x == r.centerX() && y == r.centerY()) continue;
                c.drawCircle(x, y, hs, handle);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return true;
        int a = e.getActionMasked();
        if (cropMode && handleCrop(e)) return true;
        scale.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && showOriginal) {
            setComparing(false);
            if (a == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    private boolean handleCrop(MotionEvent e) {
        int a = e.getActionMasked();
        RectF r = screenRect(crop);
        float tol = dp(28);
        switch (a) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                int f = 0;
                boolean inY = y > r.top - tol && y < r.bottom + tol, inX = x > r.left - tol && x < r.right + tol;
                if (inY && Math.abs(x - r.left) < tol) f |= 1;
                if (inY && Math.abs(x - r.right) < tol) f |= 4;
                if (inX && Math.abs(y - r.top) < tol) f |= 2;
                if (inX && Math.abs(y - r.bottom) < tol) f |= 8;
                if (f == 0 && r.contains(x, y)) f = 16;
                dragging = f;
                lastX = x; lastY = y;
                return f != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == 0 || e.getPointerCount() > 1) return false;
                RectF img = screenRect(new RectF(0, 0, 1, 1));
                float du = (e.getX() - lastX) / img.width(), dv = (e.getY() - lastY) / img.height();
                lastX = e.getX(); lastY = e.getY();
                moveCrop(du, dv);
                invalidate();
                if (listener != null) listener.onCropChanged(new RectF(crop), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != 0) {
                    dragging = 0;
                    if (listener != null) listener.onCropChanged(new RectF(crop), true);
                    return true;
                }
                return false;
        }
        return dragging != 0;
    }

    private void moveCrop(float du, float dv) {
        float min = 0.05f;
        RectF c = new RectF(crop);
        if (dragging == 16) {
            du = Math.max(-c.left, Math.min(1 - c.right, du));
            dv = Math.max(-c.top, Math.min(1 - c.bottom, dv));
            c.offset(du, dv);
        } else {
            if ((dragging & 1) != 0) c.left = Math.max(0, Math.min(c.right - min, c.left + du));
            if ((dragging & 4) != 0) c.right = Math.min(1, Math.max(c.left + min, c.right + du));
            if ((dragging & 2) != 0) c.top = Math.max(0, Math.min(c.bottom - min, c.top + dv));
            if ((dragging & 8) != 0) c.bottom = Math.min(1, Math.max(c.top + min, c.bottom + dv));
            if (cropAspect > 0) {
                // keep the ratio: width drives height (or height drives width for top/bottom drags)
                float bw = bitmap.getWidth(), bh = bitmap.getHeight();
                boolean horiz = (dragging & 5) != 0;
                if (horiz) {
                    float h = c.width() * bw / cropAspect / bh;
                    if ((dragging & 2) != 0) c.top = c.bottom - h; else c.bottom = c.top + h;
                } else {
                    float w = c.height() * bh * cropAspect / bw;
                    if ((dragging & 1) != 0) c.left = c.right - w; else c.right = c.left + w;
                }
                if (c.left < 0 || c.top < 0 || c.right > 1 || c.bottom > 1) return;
            }
        }
        crop.set(c);
    }
}
