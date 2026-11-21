package com.majdi.cinematicai;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 11;
    private static final int PREVIEW_MAX = 2048;

    private static final String[] STYLE_KEYS = {"cinematic", "dramatic", "dslr"};
    private static final String[] STYLE_NAMES = {"سينمائي\nCinematic", "درامي\nDramatic", "احترافي\nDSLR"};

    private static final int BG = Color.rgb(14, 15, 19);
    private static final int CARD = Color.rgb(30, 32, 40);
    private static final int ACCENT = Color.rgb(242, 165, 65);

    private ImageView imageView;
    private TextView status, strengthLabel;
    private final Button[] styleButtons = new Button[3];
    private Button openBtn, saveBtn, shareBtn;

    private Uri sourceUri;
    private Bitmap previewOriginal, previewResult;
    private List<RectF> faces = new ArrayList<>();
    private String style = "cinematic";
    private int strength = 80;
    private Uri lastSaved;
    private int renderToken = 0;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackground(rounded(CARD));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setMinHeight(dp(54));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(12), dp(28), dp(12), dp(16));

        TextView title = new TextView(this);
        title.setText("Majdi Cinematic AI");
        title.setTextColor(ACCENT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("افتح صورة للبدء — المعالجة تتم على الهاتف فقط");
        status.setTextColor(Color.rgb(180, 182, 190));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackground(rounded(Color.rgb(22, 23, 29)));
        imageView.setContentDescription("الصورة");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        imageView.setLayoutParams(ilp);
        imageView.setOnTouchListener((v, e) -> {
            if (previewOriginal == null || previewResult == null) return false;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                imageView.setImageBitmap(previewOriginal);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                imageView.setImageBitmap(previewResult);
                if (a == MotionEvent.ACTION_UP) v.performClick();
            }
            return true;
        });
        root.addView(imageView);

        TextView hint = new TextView(this);
        hint.setText("اضغط مطولًا على الصورة لمقارنتها بالأصل");
        hint.setTextColor(Color.rgb(120, 122, 130));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(4));
        root.addView(hint);

        LinearLayout styles = new LinearLayout(this);
        styles.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = makeButton(STYLE_NAMES[i]);
            b.setOnClickListener(v -> selectStyle(STYLE_KEYS[idx]));
            styleButtons[i] = b;
            styles.addView(b);
        }
        root.addView(styles);

        strengthLabel = new TextView(this);
        strengthLabel.setTextColor(Color.WHITE);
        strengthLabel.setTextSize(13);
        strengthLabel.setPadding(dp(6), dp(6), dp(6), 0);
        root.addView(strengthLabel);

        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(strength);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                strength = p;
                updateStrengthLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { renderPreview(); }
        });
        root.addView(seek);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        openBtn = makeButton("فتح صورة");
        saveBtn = makeButton("حفظ");
        shareBtn = makeButton("مشاركة");
        openBtn.setBackground(rounded(ACCENT));
        openBtn.setTextColor(BG);
        openBtn.setOnClickListener(v -> pickImage());
        saveBtn.setOnClickListener(v -> saveFull(false));
        shareBtn.setOnClickListener(v -> saveFull(true));
        actions.addView(openBtn);
        actions.addView(saveBtn);
        actions.addView(shareBtn);
        root.addView(actions);

        setContentView(root);
        updateStyleButtons();
        updateStrengthLabel();
    }

    private void updateStrengthLabel() {
        strengthLabel.setText("قوة التأثير: " + strength + "٪");
    }

    private void updateStyleButtons() {
        for (int i = 0; i < 3; i++) {
            boolean sel = STYLE_KEYS[i].equals(style);
            GradientDrawable g = rounded(sel ? Color.rgb(28, 110, 125) : CARD);
            if (sel) g.setStroke(dp(2), ACCENT);
            styleButtons[i].setBackground(g);
        }
    }

    private void selectStyle(String key) {
        style = key;
        updateStyleButtons();
        renderPreview();
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            uri = intent.getData();
        }
        if (uri != null) loadImage(uri);
    }

    private void pickImage() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            startActivityForResult(g, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadImage(data.getData());
        }
    }

    private void setBusy(boolean busy) {
        openBtn.setEnabled(!busy);
        saveBtn.setEnabled(!busy);
        shareBtn.setEnabled(!busy);
        for (Button b : styleButtons) b.setEnabled(!busy);
    }

    private String facesText(int n) {
        if (n == 0) return "لم يُكتشف وجه أمامي — البشرة محمية تلقائيًا";
        if (n == 1) return "تم اكتشاف وجه واحد — محمي من التعديل";
        if (n == 2) return "تم اكتشاف وجهين — محميان من التعديل";
        return "تم اكتشاف " + n + " وجوه — محمية من التعديل";
    }

    private void loadImage(Uri uri) {
        sourceUri = uri;
        lastSaved = null;
        status.setText("جارٍ فتح الصورة…");
        setBusy(true);
        final String st = style;
        final float s = strength / 100f;
        final int token = ++renderToken;
        worker.execute(() -> {
            try {
                Bitmap bmp = decode(uri, PREVIEW_MAX);
                List<RectF> f = detectFaces(bmp);
                Bitmap res = ImageEngine.process(bmp, st, s, f);
                ui.post(() -> {
                    previewOriginal = bmp;
                    faces = f;
                    if (token == renderToken) {
                        previewResult = res;
                        imageView.setImageBitmap(res);
                    }
                    status.setText(facesText(f.size()));
                    setBusy(false);
                    if (token != renderToken) renderPreview();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    status.setText("تعذّر فتح الصورة");
                    setBusy(false);
                });
            }
        });
    }

    private void renderPreview() {
        lastSaved = null;
        final Bitmap base = previewOriginal;
        if (base == null) return;
        final String st = style;
        final float s = strength / 100f;
        final List<RectF> f = new ArrayList<>(faces);
        final int token = ++renderToken;
        worker.execute(() -> {
            if (token != renderToken) return;
            try {
                Bitmap res = ImageEngine.process(base, st, s, f);
                ui.post(() -> {
                    if (token == renderToken) {
                        previewResult = res;
                        imageView.setImageBitmap(res);
                    }
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void saveFull(boolean thenShare) {
        if (sourceUri == null) {
            Toast.makeText(this, "افتح صورة أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        if (thenShare && lastSaved != null) {
            share(lastSaved);
            return;
        }
        final String st = style;
        final float s = strength / 100f;
        final List<RectF> f = new ArrayList<>(faces);
        final Uri src = sourceUri;
        setBusy(true);
        status.setText("جارٍ الحفظ بالدقة الأصلية…");
        worker.execute(() -> {
            try {
                ExportResult res = exportFullRes(src, st, s, f);
                ui.post(() -> {
                    lastSaved = res.uri;
                    setBusy(false);
                    status.setText("تم الحفظ بالدقة الأصلية " + res.width + "×" + res.height
                            + (res.reduced ? " (بنصف الأبعاد لضيق الذاكرة)" : "")
                            + " — Pictures/Majdi Cinematic AI");
                    if (thenShare) share(res.uri);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    status.setText("تعذّر حفظ الصورة");
                });
            }
        });
    }

    private static final class ExportResult {
        Uri uri;
        int width, height;
        boolean reduced;
    }

    /**
     * Processes the photo at its original resolution in horizontal strips: each strip is decoded,
     * graded and streamed into the JPEG encoder, so the full image never has to sit in memory.
     * Pixels stay in their stored orientation and the EXIF orientation tag is written to the output.
     */
    @SuppressWarnings("deprecation")
    private ExportResult exportFullRes(Uri src, String st, float s, List<RectF> facesDisplay) throws IOException {
        int orient = readOrientation(src);
        float[][] rawFaces = mapFacesToRaw(facesDisplay, orient);

        BitmapRegionDecoder dec = null;
        try (InputStream in = getContentResolver().openInputStream(src)) {
            if (in != null) dec = BitmapRegionDecoder.newInstance(in, false);
        } catch (Throwable ignored) {
            dec = null;
        }

        ExportResult result = new ExportResult();
        Bitmap whole = null;
        int W, H;
        if (dec != null) {
            W = dec.getWidth();
            H = dec.getHeight();
        } else {
            try {
                whole = decodeRaw(src, 1);
            } catch (OutOfMemoryError oom) {
                whole = decodeRaw(src, 2);
                result.reduced = true;
            }
            W = whole.getWidth();
            H = whole.getHeight();
        }
        result.width = W;
        result.height = H;

        String name = "Majdi_" + st + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Majdi Cinematic AI");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("insert failed");

        boolean ok = false;
        try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
            if (raw == null) throw new IOException("open failed");
            OutputStream os = new BufferedOutputStream(raw, 1 << 20);
            JpegStreamWriter jw = new JpegStreamWriter(os, W, H, 95, orient);
            GradeCore core = new GradeCore(st, s, rawFaces, W, H);
            int strip = ImageEngine.STRIP;
            int[] in = new int[W * (strip + 2)];
            int[] o = new int[W * strip];
            Bitmap reuse = null;
            int lastPct = -1;
            for (int y0 = 0; y0 < H; y0 += strip) {
                int y1 = Math.min(H, y0 + strip);
                int ys = Math.max(0, y0 - 1);
                int ye = Math.min(H, y1 + 1);
                int rows = ye - ys;
                if (dec != null) {
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    if (reuse != null && reuse.getHeight() == rows && reuse.getWidth() == W) {
                        op.inBitmap = reuse;
                        op.inMutable = true;
                    }
                    Bitmap region = dec.decodeRegion(new Rect(0, ys, W, ye), op);
                    if (region == null) throw new IOException("region decode failed");
                    region.getPixels(in, 0, W, 0, 0, W, rows);
                    if (reuse != null && reuse != region) reuse.recycle();
                    reuse = region;
                } else {
                    whole.getPixels(in, 0, W, 0, ys, W, rows);
                }
                core.processStrip(in, ys, rows, y0, y1, o);
                jw.writeRows(o, 0, y1 - y0);
                int pct = (int) (100L * y1 / H);
                if (pct / 5 != lastPct / 5) {
                    lastPct = pct;
                    final int p = pct;
                    ui.post(() -> status.setText("جارٍ الحفظ بالدقة الأصلية… " + p + "٪"));
                }
            }
            jw.finish();
            os.flush();
            if (reuse != null) reuse.recycle();
            ok = true;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
            if (!ok) {
                try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        result.uri = uri;
        return result;
    }

    private Bitmap decodeRaw(Uri uri, int sample) throws IOException {
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap b = BitmapFactory.decodeStream(in, null, d);
            if (b == null) throw new IOException("decode failed");
            return b;
        }
    }

    /** Maps face rectangles from the displayed (orientation-corrected) image back to stored pixels. */
    private static float[][] mapFacesToRaw(List<RectF> faces, int orient) {
        float[][] outArr = new float[faces.size()][];
        for (int i = 0; i < outArr.length; i++) {
            RectF r = faces.get(i);
            float[] a = toRaw(r.left, r.top, orient);
            float[] b = toRaw(r.right, r.bottom, orient);
            outArr[i] = new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1])};
        }
        return outArr;
    }

    private static float[] toRaw(float xd, float yd, int orient) {
        switch (orient) {
            case 2: return new float[]{1f - xd, yd};
            case 3: return new float[]{1f - xd, 1f - yd};
            case 4: return new float[]{xd, 1f - yd};
            case 5: return new float[]{yd, xd};
            case 6: return new float[]{yd, 1f - xd};
            case 7: return new float[]{1f - yd, 1f - xd};
            case 8: return new float[]{1f - yd, xd};
            default: return new float[]{xd, yd};
        }
    }

    private void share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "مشاركة الصورة"));
    }

    private Bitmap decode(Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("bad image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;

        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, d);
        }
        if (b == null) throw new IOException("decode failed");

        int max = Math.max(b.getWidth(), b.getHeight());
        if (max > maxSide) {
            float sc = maxSide / (float) max;
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * sc)),
                    Math.max(1, Math.round(b.getHeight() * sc)), true);
            if (s != b) b.recycle();
            b = s;
        }
        Matrix m = orientationMatrix(readOrientation(uri));
        if (m != null) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private int readOrientation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return 1;
            ExifInterface e = new ExifInterface(in);
            int o = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return (o >= 1 && o <= 8) ? o : 1;
        } catch (Throwable e) {
            return 1;
        }
    }

    private static Matrix orientationMatrix(int o) {
        Matrix m = new Matrix();
        switch (o) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setRotate(180); m.postScale(-1, 1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
            default: return null;
        }
        return m;
    }

    private List<RectF> detectFaces(Bitmap b) {
        List<RectF> list = new ArrayList<>();
        try {
            int w = b.getWidth(), h = b.getHeight();
            float sc = Math.min(1f, 1024f / Math.max(w, h));
            int dw = Math.max(2, Math.round(w * sc)) & ~1;
            int dh = Math.max(2, Math.round(h * sc));
            Bitmap small = Bitmap.createScaledBitmap(b, dw, dh, true);
            Bitmap rgb = small.copy(Bitmap.Config.RGB_565, false);
            if (small != b) small.recycle();
            FaceDetector fd = new FaceDetector(dw, dh, 16);
            FaceDetector.Face[] arr = new FaceDetector.Face[16];
            int n = fd.findFaces(rgb, arr);
            rgb.recycle();
            PointF p = new PointF();
            for (int i = 0; i < n; i++) {
                FaceDetector.Face f = arr[i];
                if (f == null || f.confidence() < 0.3f) continue;
                f.getMidPoint(p);
                float ed = f.eyesDistance();
                float cx = p.x, cy = p.y + ed * 0.35f;
                float rx = ed * 1.5f, ry = ed * 2.1f;
                list.add(new RectF((cx - rx) / dw, (cy - ry) / dh, (cx + rx) / dw, (cy + ry) / dh));
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
