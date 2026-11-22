package com.majdi.cinematicai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 11;
    private static final int REQ_LUT = 12;
    /** working resolution: preview, scene analysis and the lens-blur render */
    private static final int PREVIEW_MAX = 1600;
    private static final int DEFAULT_STRENGTH = 65;

    private static final String[] STYLE_KEYS = {"auto", "natural", "cinematic", "dramatic", "dslr"};
    private static final String[] STYLE_NAMES = {"تلقائي\nAI Auto", "طبيعي\nNatural Pro", "سينمائي\nCinematic", "دراماتيكي\nDramatic", "احترافي\nDSLR"};
    private static final String[] PRESET_KEYS = {"hollywood", "moody", "night", "film", "natural_cinematic"};
    private static final String[] PRESET_NAMES = {"Hollywood", "Moody Film", "Night Cinema", "Film Look", "Natural Cinematic"};

    private static final int BG = Color.rgb(14, 15, 19);
    private static final int CARD = Color.rgb(30, 32, 40);
    private static final int ACCENT = Color.rgb(242, 165, 65);
    private static final int TEAL = Color.rgb(28, 110, 125);

    // views
    private ImageView imageView;
    private TextView status, strengthLabel;
    private Button fusionBtn, openBtn, saveBtn, shareBtn, advBtn, resetBtn;
    private final Map<String, Button> styleButtons = new HashMap<>();
    private final Map<String, Button> presetButtons = new HashMap<>();
    private final Map<String, Button> chipButtons = new HashMap<>();
    private HorizontalScrollView presetRow;
    private ScrollView advancedPanel;
    private ProgressBar progress;
    private SeekBar strengthSeek, cinSeek, dramSeek, dslrSeek, blurSeek, rangeSeek, growSeek;

    // image state
    private Uri sourceUri;
    private Bitmap previewOriginal, previewResult;
    private int[] previewPx;
    private List<RectF> faces = new ArrayList<>();
    private ToneModel tone;
    private SceneStats stats;
    private SceneMaps maps;
    private RenderPlan currentPlan;
    private Uri lastSaved;
    private String lastMime = "image/jpeg";
    private int renderToken = 0;

    // options
    private final RenderOptions opt = new RenderOptions();
    private String format = "jpeg";
    private Lut3D userLut;
    private ModelStore models;
    private final Map<String, Lut3D> lutCache = new HashMap<>();

    // zoom / pan
    private float zoom = 1f, panX = 0f, panY = 0f;
    private boolean showingOriginal = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        models = new ModelStore(this);
        loadUserLut();
        opt.style = "fusion";
        opt.strength = DEFAULT_STRENGTH / 100f;
        buildUi();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private Button makeButton(String text, float weight) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackground(rounded(CARD));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = weight > 0
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private Button chip(String group, String key, String text, LinearLayout parent, Runnable onClick) {
        Button b = makeButton(text, 0);
        b.setTextSize(12);
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(v -> onClick.run());
        chipButtons.put(group + ":" + key, b);
        parent.addView(b);
        return b;
    }

    private TextView label(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(dp(6), dp(6), dp(6), dp(2));
        return t;
    }

    private SeekBar slider(LinearLayout parent, String title, int value, int max) {
        TextView t = label(title, 12, Color.rgb(200, 202, 210));
        parent.addView(t);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { readSliders(); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { renderPreview(false); }
        });
        parent.addView(s);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(10), dp(26), dp(10), dp(12));

        TextView title = label("Majdi Cinematic AI", 22, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = label("افتح صورة للبدء — كل المعالجة تتم على الهاتف دون إنترنت", 12, Color.rgb(180, 182, 190));
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        root.addView(status);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageView.setBackground(rounded(Color.rgb(22, 23, 29)));
        imageView.setContentDescription("الصورة");
        imageView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        imageView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateMatrix());
        setupGestures();
        root.addView(imageView);

        TextView hint = label("اضغط مطولًا للمقارنة • المس جسمًا لتحديد التركيز • قرّب بإصبعين • انقر مرتين لإعادة الحجم", 11, Color.rgb(120, 122, 130));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        // ---- the professional fusion mode, visually distinct
        fusionBtn = makeButton("دمج احترافي — Cinematic Fusion Pro", 0);
        fusionBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fusionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        fusionBtn.setOnClickListener(v -> selectStyle("fusion"));
        root.addView(fusionBtn);

        LinearLayout styles = row();
        for (int i = 0; i < STYLE_KEYS.length; i++) {
            final String key = STYLE_KEYS[i];
            Button b = makeButton(STYLE_NAMES[i], 1f);
            b.setTextSize(11);
            b.setPadding(dp(1), dp(4), dp(1), dp(4));
            b.setOnClickListener(v -> selectStyle(key));
            if (key.equals("cinematic")) b.setOnLongClickListener(v -> { pickLut(); return true; });
            styleButtons.put(key, b);
            styles.addView(b);
        }
        root.addView(styles);

        presetRow = new HorizontalScrollView(this);
        LinearLayout presets = row();
        for (int i = 0; i < PRESET_KEYS.length; i++) {
            final String k = PRESET_KEYS[i];
            Button b = chip("preset", k, PRESET_NAMES[i], presets, () -> { opt.preset = k; updateSelection(); renderPreview(false); });
            presetButtons.put(k, b);
        }
        presetRow.addView(presets);
        presetRow.setVisibility(View.GONE);
        root.addView(presetRow);

        strengthLabel = label("", 13, Color.WHITE);
        root.addView(strengthLabel);
        strengthSeek = new SeekBar(this);
        strengthSeek.setMax(100);
        strengthSeek.setProgress(DEFAULT_STRENGTH);
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { opt.strength = p / 100f; updateStrengthLabel(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { renderPreview(false); }
        });
        root.addView(strengthSeek);

        LinearLayout tools = row();
        advBtn = makeButton("إعدادات متقدمة", 1f);
        resetBtn = makeButton("إعادة الضبط", 1f);
        advBtn.setOnClickListener(v -> advancedPanel.setVisibility(advancedPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        resetBtn.setOnClickListener(v -> resetAll());
        tools.addView(advBtn);
        tools.addView(resetBtn);
        root.addView(tools);

        advancedPanel = new ScrollView(this);
        advancedPanel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
        advancedPanel.setBackground(rounded(Color.rgb(20, 21, 27)));
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setPadding(dp(8), dp(4), dp(8), dp(8));
        adv.addView(label("الدمج الاحترافي", 14, ACCENT));
        cinSeek = slider(adv, "قوة السينمائي — Cinematic Strength", 80, 100);
        dramSeek = slider(adv, "قوة الدراماتيكي — Dramatic Strength", 60, 100);
        dslrSeek = slider(adv, "تحسين DSLR — DSLR Enhancement", 70, 100);
        blurSeek = slider(adv, "قوة التمويه بالعمق — Depth Blur Strength", 50, 100);
        adv.addView(label("أداة التمويه (DSLR والدمج)", 14, ACCENT));
        LinearLayout modeRow = row();
        chip("mode", "object", "تركيز على جسم كامل", modeRow, () -> { opt.focusMode = "object"; updateSelection(); renderPreview(true); });
        chip("mode", "point", "تركيز على مستوى", modeRow, () -> { opt.focusMode = "point"; updateSelection(); renderPreview(true); });
        chip("mode", "clear", "إلغاء نقطة التركيز", modeRow, () -> { opt.focus = null; renderPreview(false); });
        adv.addView(modeRow);
        rangeSeek = slider(adv, "مدى التركيز: ضيّق ← → واسع", 100, 200);
        growSeek = slider(adv, "حدود الجسم: تقليص ← → توسيع", 100, 200);
        adv.addView(label("محاكاة العدسة (تقريب بصري، لا يغيّر عدسة الهاتف)", 12, Color.rgb(200, 202, 210)));
        LinearLayout lensRow = row();
        for (int f : new int[]{35, 50, 85}) {
            final int ff = f;
            chip("focal", "" + f, f + "mm", lensRow, () -> { opt.focal = ff; updateSelection(); renderPreview(false); });
        }
        adv.addView(lensRow);
        LinearLayout apRow = row();
        for (float a : new float[]{1.8f, 2.8f, 4f, 5.6f}) {
            final float aa = a;
            String t = (a == 4f ? "f/4" : "f/" + a);
            chip("ap", t, t, apRow, () -> { opt.aperture = aa; updateSelection(); renderPreview(false); });
        }
        adv.addView(apRow);
        adv.addView(label("صيغة الحفظ", 14, ACCENT));
        LinearLayout fmtRow = row();
        chip("fmt", "jpeg", "JPEG", fmtRow, () -> { format = "jpeg"; updateSelection(); });
        chip("fmt", "png", "PNG", fmtRow, () -> { format = "png"; updateSelection(); });
        chip("fmt", "webp", "WEBP", fmtRow, () -> { format = "webp"; updateSelection(); });
        adv.addView(fmtRow);
        advancedPanel.addView(adv);
        advancedPanel.setVisibility(View.GONE);
        root.addView(advancedPanel);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        LinearLayout actions = row();
        openBtn = makeButton("فتح صورة", 1f);
        saveBtn = makeButton("حفظ", 1f);
        shareBtn = makeButton("مشاركة", 1f);
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
        readSliders();
        updateSelection();
        updateStrengthLabel();
    }

    private void readSliders() {
        if (cinSeek == null || growSeek == null) return;
        opt.cinematic = cinSeek.getProgress() / 100f;
        opt.dramatic = dramSeek.getProgress() / 100f;
        opt.dslr = dslrSeek.getProgress() / 100f;
        opt.depthBlur = blurSeek.getProgress() / 100f;
        opt.focusRange = (rangeSeek.getProgress() - 100) / 100f;
        opt.objectGrow = (growSeek.getProgress() - 100) / 100f;
    }

    private void updateStrengthLabel() {
        strengthLabel.setText("قوة التأثير: " + Math.round(opt.strength * 100) + "٪");
    }

    private void styleChip(String group, String key, boolean sel) {
        Button b = chipButtons.get(group + ":" + key);
        if (b == null) return;
        GradientDrawable g = rounded(sel ? TEAL : CARD);
        if (sel) g.setStroke(dp(2), ACCENT);
        b.setBackground(g);
    }

    private void updateSelection() {
        boolean fusion = opt.style.equals("fusion");
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                fusion ? new int[]{Color.rgb(242, 165, 65), Color.rgb(200, 95, 40)} : new int[]{Color.rgb(70, 55, 35), Color.rgb(55, 40, 30)});
        fg.setCornerRadius(dp(14));
        if (fusion) fg.setStroke(dp(2), Color.WHITE);
        fusionBtn.setBackground(fg);
        fusionBtn.setTextColor(fusion ? BG : ACCENT);
        for (String k : STYLE_KEYS) {
            boolean sel = k.equals(opt.style);
            GradientDrawable g = rounded(sel ? TEAL : CARD);
            if (sel) g.setStroke(dp(2), ACCENT);
            styleButtons.get(k).setBackground(g);
        }
        presetRow.setVisibility(opt.style.equals("cinematic") ? View.VISIBLE : View.GONE);
        String cur = opt.preset.isEmpty() ? (tone != null && tone.p50 < -4f ? "night" : "hollywood") : opt.preset;
        for (String k : PRESET_KEYS) styleChip("preset", k, k.equals(cur));
        styleChip("mode", "object", opt.focusMode.equals("object"));
        styleChip("mode", "point", opt.focusMode.equals("point"));
        for (int f : new int[]{35, 50, 85}) styleChip("focal", "" + f, opt.focal == f);
        for (String a : new String[]{"f/1.8", "f/2.8", "f/4", "f/5.6"}) {
            float v = Float.parseFloat(a.substring(2));
            styleChip("ap", a, Math.abs(opt.aperture - v) < 0.01f);
        }
        for (String f : new String[]{"jpeg", "png", "webp"}) styleChip("fmt", f, f.equals(format));
    }

    private void selectStyle(String key) {
        opt.style = key;
        updateSelection();
        renderPreview(false);
    }

    private void resetAll() {
        opt.strength = DEFAULT_STRENGTH / 100f;
        strengthSeek.setProgress(DEFAULT_STRENGTH);
        cinSeek.setProgress(80);
        dramSeek.setProgress(60);
        dslrSeek.setProgress(70);
        blurSeek.setProgress(50);
        rangeSeek.setProgress(100);
        growSeek.setProgress(100);
        readSliders();
        opt.focus = null;
        opt.focusMode = "object";
        opt.focal = 50;
        opt.aperture = 2.8f;
        opt.preset = "";
        opt.style = "fusion";
        zoom = 1f; panX = 0f; panY = 0f;
        updateMatrix();
        updateSelection();
        updateStrengthLabel();
        renderPreview(false);
    }

    // ------------------------------------------------------------------ gestures: compare, focus, zoom

    private void setupGestures() {
        ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor()));
                updateMatrix();
                return true;
            }
        });
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { tapFocus(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { zoom = 1f; panX = 0f; panY = 0f; updateMatrix(); return true; }
            @Override public void onLongPress(MotionEvent e) {
                if (previewOriginal != null) { showingOriginal = true; imageView.setImageBitmap(previewOriginal); updateMatrix(); }
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom > 1f) { panX -= dx; panY -= dy; updateMatrix(); }
                return true;
            }
        });
        imageView.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && showingOriginal) {
                showingOriginal = false;
                if (previewResult != null) imageView.setImageBitmap(previewResult);
                updateMatrix();
                if (a == MotionEvent.ACTION_UP) v.performClick();
            }
            return true;
        });
    }

    private void updateMatrix() {
        Bitmap b = previewResult != null ? previewResult : previewOriginal;
        if (b == null || imageView.getWidth() == 0) return;
        float vw = imageView.getWidth(), vh = imageView.getHeight();
        float s0 = Math.min(vw / b.getWidth(), vh / b.getHeight());
        float s = s0 * zoom;
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        float maxX = Math.max(0f, (dw - vw) / 2f), maxY = Math.max(0f, (dh - vh) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((vw - dw) / 2f + panX, (vh - dh) / 2f + panY);
        imageView.setImageMatrix(m);
    }

    private void tapFocus(float x, float y) {
        Bitmap b = previewResult;
        if (b == null) return;
        Matrix inv = new Matrix();
        if (!imageView.getImageMatrix().invert(inv)) return;
        float[] p = {x, y};
        inv.mapPoints(p);
        float u = p[0] / b.getWidth(), v = p[1] / b.getHeight();
        if (u < 0f || v < 0f || u > 1f || v > 1f) return;
        if (!(opt.style.equals("dslr") || opt.style.equals("fusion") || opt.style.equals("auto"))) {
            Toast.makeText(this, "تحديد نقطة التركيز متاح في DSLR والدمج الاحترافي والتلقائي", Toast.LENGTH_SHORT).show();
            return;
        }
        if (maps == null || !maps.hasDepth()) {
            Toast.makeText(this, "نموذج العمق غير متاح لهذه الصورة — لا يمكن العزل", Toast.LENGTH_LONG).show();
            return;
        }
        opt.focus = new float[]{u, v};
        if (opt.style.equals("fusion") && blurSeek.getProgress() == 0) { blurSeek.setProgress(50); readSliders(); }
        renderPreview(true);
    }

    // ------------------------------------------------------------------ image loading and rendering

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) uri = intent.getData();
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK) loadImage(data.getData());
        else if (requestCode == REQ_LUT) importLut(data.getData());
    }

    private void setBusy(boolean busy) {
        openBtn.setEnabled(!busy);
        saveBtn.setEnabled(!busy);
        shareBtn.setEnabled(!busy);
        fusionBtn.setEnabled(!busy);
        for (Button b : styleButtons.values()) b.setEnabled(!busy);
    }

    private void loadImage(Uri uri) {
        sourceUri = uri;
        lastSaved = null;
        opt.focus = null;
        opt.style = "fusion";      // Cinematic Fusion Pro is the default for every new photo
        updateSelection();
        status.setText("جارٍ فتح الصورة…");
        setBusy(true);
        final int token = ++renderToken;
        final RenderOptions o = opt.copy();
        worker.execute(() -> {
            try {
                Bitmap bmp = decode(uri, PREVIEW_MAX);
                List<RectF> f = detectFaces(bmp);
                int[] px = ImageEngine.pixels(bmp);
                int w = bmp.getWidth(), h = bmp.getHeight();
                float[][] fa = ImageEngine.faces(f);
                ToneModel tm = ToneModel.build(px, w, h, fa);
                SceneStats ss = SceneStats.measure(px, w, h);
                ui.post(() -> status.setText("تحليل العمق وعزل الموضوع بنموذجين محليين (Depth Anything V2 + ISNet)…"));
                SceneMaps sm = null;
                String note = "";
                try {
                    SceneAnalyzer an = models.analyzer();
                    if (an != null) sm = an.analyze(px, w, h, fa);
                    else note = "\nنماذج الذكاء الاصطناعي غير متاحة — العزل معطّل";
                } catch (Throwable e) {
                    note = "\nتعذّر تشغيل نموذج العمق — العزل معطّل";
                }
                final SceneMaps fsm = sm;
                final String fnote = note;
                RenderPlan plan = RenderPlan.build(px, w, h, o, tm, ss, sm, f.size(), this::lut);
                Bitmap res = ImageEngine.render(plan, px, w, h);
                ui.post(() -> {
                    previewOriginal = bmp;
                    previewPx = px;
                    faces = f;
                    tone = tm;
                    stats = ss;
                    maps = fsm;
                    zoom = 1f; panX = 0f; panY = 0f;
                    if (token == renderToken) {
                        currentPlan = plan;
                        previewResult = res;
                        imageView.setImageBitmap(res);
                        updateMatrix();
                    }
                    updateSelection();
                    status.setText(statusFor(plan) + fnote);
                    setBusy(false);
                    if (token != renderToken) renderPreview(false);
                });
            } catch (Throwable t) {
                ui.post(() -> { status.setText("تعذّر فتح الصورة"); setBusy(false); });
            }
        });
    }

    private void renderPreview(boolean flashFocus) {
        lastSaved = null;
        final Bitmap base = previewOriginal;
        if (base == null || tone == null) return;
        final int[] px = previewPx;
        final ToneModel tm = tone;
        final SceneStats ss = stats;
        final SceneMaps sm = maps;
        final int nf = faces.size();
        readSliders();
        final RenderOptions o = opt.copy();
        o.userLut = userLut;
        final int token = ++renderToken;
        currentPlan = null;
        boolean lens = o.focus != null || o.style.equals("dslr") || o.style.equals("fusion") || o.style.equals("auto");
        status.setText(lens ? "جارٍ المعالجة ومحاكاة العدسة حسب العمق…" : "جارٍ المعالجة…");
        worker.execute(() -> {
            if (token != renderToken) return;
            try {
                RenderPlan plan = RenderPlan.build(px, base.getWidth(), base.getHeight(), o, tm, ss, sm, nf, this::lut);
                Bitmap res = ImageEngine.render(plan, px, base.getWidth(), base.getHeight());
                Bitmap overlay = flashFocus && plan.focusMask != null ? ImageEngine.focusOverlay(res, plan.focusMask) : null;
                ui.post(() -> {
                    if (token != renderToken) return;
                    currentPlan = plan;
                    previewResult = res;
                    if (overlay != null) {
                        imageView.setImageBitmap(overlay);
                        ui.postDelayed(() -> { if (previewResult == res && !showingOriginal) imageView.setImageBitmap(res); }, 1100);
                    } else {
                        imageView.setImageBitmap(res);
                    }
                    updateMatrix();
                    status.setText(statusFor(plan));
                });
            } catch (Throwable e) {
                ui.post(() -> status.setText("تعذّرت المعالجة"));
            }
        });
    }

    private String lensText() {
        return opt.focal + "mm " + (opt.aperture == 4f ? "f/4" : "f/" + opt.aperture);
    }

    private String statusFor(RenderPlan plan) {
        String iso = "";
        if (plan.isolation.equals("on")) iso = " • عزل بالعمق (" + lensText() + ")" + (opt.focus != null ? (opt.focusMode.equals("object") ? " على الجسم المحدد" : " على مستوى اللمس") : " على الموضوع");
        else if (plan.isolation.equals("off-no-subject")) iso = "\nلا يوجد موضوع واضح — المس جسمًا لتفعيل العزل";
        else if (plan.isolation.equals("off-no-depth")) iso = "\nنموذج العمق غير متاح — العزل معطّل";
        if (plan.reason != null) return plan.reason + (plan.isolation.equals("on") && opt.focus != null ? iso : "");
        switch (plan.style.key) {
            case "fusion": return "دمج احترافي: سينمائي " + Math.round(opt.cinematic * 100) + "٪ • دراماتيكي " + Math.round(opt.dramatic * 100)
                    + "٪ • DSLR " + Math.round(opt.dslr * 100) + "٪" + iso;
            case "cinematic": return "سينمائي — " + (plan.lut != null ? plan.lut.title : "") + (userLut != null ? " (LUT مستورد)" : "")
                    + "\nاضغط مطولًا على زر سينمائي لاستيراد ملف .cube";
            case "dramatic": return "دراماتيكي — Tone Mapping تكيفي، استعادة الإضاءات، تباين موضعي";
            case "dslr": return "احترافي — تقليل الضوضاء وحدة بصرية طبيعية" + iso;
            default: return "طبيعي — تعريض وتوازن أبيض ونطاق ديناميكي وحدة طبيعية" + (faces.isEmpty() ? "" : " • الوجوه محمية");
        }
    }

    // ------------------------------------------------------------------ LUTs

    private Lut3D lut(String name) throws IOException {
        Lut3D l = lutCache.get(name);
        if (l == null) {
            try (InputStream in = GradeCore.class.getResourceAsStream("/luts/" + name + ".cube")) {
                if (in == null) throw new IOException("missing LUT " + name);
                l = Lut3D.parse(in);
            }
            lutCache.put(name, l);
        }
        return l;
    }

    private void pickLut() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_LUT);
        Toast.makeText(this, "اختر ملف LUT بصيغة .cube", Toast.LENGTH_SHORT).show();
    }

    private void importLut(Uri uri) {
        worker.execute(() -> {
            try {
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
                Lut3D l = Lut3D.parse(new java.io.ByteArrayInputStream(data));
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "user.cube"))) {
                    fo.write(data);
                }
                ui.post(() -> {
                    userLut = l;
                    Toast.makeText(this, "تم تحميل LUT: " + l.title + " (" + l.size + "³)", Toast.LENGTH_LONG).show();
                    selectStyle("cinematic");
                });
            } catch (Throwable e) {
                ui.post(() -> Toast.makeText(this, "ملف LUT غير صالح", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadUserLut() {
        java.io.File f = new java.io.File(getFilesDir(), "user.cube");
        if (!f.exists()) return;
        try (InputStream in = new java.io.FileInputStream(f)) {
            userLut = Lut3D.parse(in);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ saving

    private void saveFull(boolean thenShare) {
        if (sourceUri == null) {
            Toast.makeText(this, "افتح صورة أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        if (thenShare && lastSaved != null) { share(lastSaved, lastMime); return; }
        final RenderPlan plan = currentPlan;
        if (plan == null) {
            Toast.makeText(this, "انتظر اكتمال المعاينة", Toast.LENGTH_SHORT).show();
            return;
        }
        startExport(plan, format, thenShare);
    }

    private void startExport(RenderPlan plan, String fmt, boolean thenShare) {
        final Uri src = sourceUri;
        final String st = plan.style.key;
        setBusy(true);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        status.setText("جارٍ الحفظ بالدقة الأصلية…");
        worker.execute(() -> {
            try {
                ExportResult res = exportFullRes(src, st, plan, fmt);
                ui.post(() -> {
                    lastSaved = res.uri;
                    lastMime = res.mime;
                    setBusy(false);
                    progress.setVisibility(View.GONE);
                    status.setText("تم الحفظ بالدقة الأصلية " + res.width + "×" + res.height + " (" + fmt.toUpperCase(Locale.US) + ") — Pictures/Majdi Cinematic AI");
                    if (thenShare) share(res.uri, res.mime);
                });
            } catch (OutOfMemoryError | TooLargeException big) {
                ui.post(() -> {
                    setBusy(false);
                    progress.setVisibility(View.GONE);
                    status.setText("الذاكرة لا تكفي لحفظ " + fmt.toUpperCase(Locale.US) + " بالدقة الكاملة");
                    new AlertDialog.Builder(this)
                            .setTitle("الصورة كبيرة جدًا")
                            .setMessage("لا تكفي الذاكرة لحفظ هذه الصورة بصيغة " + fmt.toUpperCase(Locale.US)
                                    + " بدقتها الكاملة. صيغة JPEG تُحفظ مجزأة وتحافظ على الدقة الكاملة. لن تُخفَّض الدقة دون موافقتك.")
                            .setPositiveButton("احفظ JPEG بالدقة الكاملة", (d, w) -> startExport(plan, "jpeg", thenShare))
                            .setNegativeButton("إلغاء", null)
                            .show();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    progress.setVisibility(View.GONE);
                    status.setText("تعذّر حفظ الصورة");
                });
            }
        });
    }

    private static final class TooLargeException extends Exception {}

    private static final class ExportResult {
        Uri uri;
        String mime;
        int width, height;
    }

    /**
     * Renders the photo at its original resolution in horizontal strips with overlap (GradeCore.HALO),
     * using exactly the plan shown in the preview. JPEG is streamed (any resolution); PNG/WEBP need
     * a full-size bitmap and are refused (with an offer of full-resolution JPEG) if memory is short.
     */
    @SuppressWarnings("deprecation")
    private ExportResult exportFullRes(Uri src, String st, RenderPlan plan, String fmt) throws Exception {
        int orient = readOrientation(src);
        BitmapRegionDecoder dec = null;
        try (InputStream in = getContentResolver().openInputStream(src)) {
            if (in != null) dec = BitmapRegionDecoder.newInstance(in, false);
        } catch (Throwable ignored) {
            dec = null;
        }
        Bitmap whole = null;
        int W, H;
        if (dec != null) {
            W = dec.getWidth();
            H = dec.getHeight();
            // noise measured at full resolution on a centre crop, so NR matches the saved pixels
            try {
                int cw = Math.min(W, 1024), ch = Math.min(H, 1024);
                Rect r = new Rect((W - cw) / 2, (H - ch) / 2, (W - cw) / 2 + cw, (H - ch) / 2 + ch);
                Bitmap crop = dec.decodeRegion(r, null);
                if (crop != null) {
                    int[] cp = new int[cw * ch];
                    crop.getPixels(cp, 0, cw, 0, 0, cw, ch);
                    plan.fullNoiseSigma = SceneStats.measure(cp, cw, ch).noiseSigma;
                    crop.recycle();
                }
            } catch (Throwable ignored) {
            }
        } else {
            whole = decodeRaw(src);    // never downsampled silently: OOM is reported to the user
            W = whole.getWidth();
            H = whole.getHeight();
        }

        Bitmap outBmp = null;
        boolean jpeg = fmt.equals("jpeg");
        if (!jpeg) {
            try {
                outBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                if (dec != null) dec.recycle();
                if (whole != null) whole.recycle();
                throw new TooLargeException();
            }
        }

        String ext = jpeg ? "jpg" : fmt;
        String mime = jpeg ? "image/jpeg" : (fmt.equals("png") ? "image/png" : "image/webp");
        String name = "Majdi_" + st + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "." + ext;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Majdi Cinematic AI");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("insert failed");

        ExportResult result = new ExportResult();
        boolean ok = false;
        try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
            if (raw == null) throw new IOException("open failed");
            OutputStream os = new BufferedOutputStream(raw, 1 << 20);
            JpegStreamWriter jw = jpeg ? new JpegStreamWriter(os, W, H, 95, orient) : null;
            GradeCore core = new GradeCore(plan, W, H, orient);
            int strip = ImageEngine.STRIP, halo = GradeCore.HALO;
            int[] in = new int[W * (strip + 2 * halo)];
            int[] o = new int[W * strip];
            Bitmap reuse = null;
            int lastPct = -1;
            for (int y0 = 0; y0 < H; y0 += strip) {
                int y1 = Math.min(H, y0 + strip);
                int ys = Math.max(0, y0 - halo);
                int ye = Math.min(H, y1 + halo);
                int rows = ye - ys;
                if (dec != null) {
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    op.inMutable = true;
                    if (reuse != null && reuse.isMutable() && reuse.getHeight() == rows && reuse.getWidth() == W) op.inBitmap = reuse;
                    Bitmap region = dec.decodeRegion(new Rect(0, ys, W, ye), op);
                    if (region == null) throw new IOException("region decode failed");
                    region.getPixels(in, 0, W, 0, 0, W, rows);
                    if (reuse != null && reuse != region) reuse.recycle();
                    reuse = region;
                } else {
                    whole.getPixels(in, 0, W, 0, ys, W, rows);
                }
                core.processStrip(in, ys, rows, y0, y1, o);
                if (jpeg) jw.writeRows(o, 0, y1 - y0);
                else outBmp.setPixels(o, 0, W, 0, y0, W, y1 - y0);
                int pct = (int) (100L * y1 / H);
                if (pct != lastPct) {
                    lastPct = pct;
                    final int p = pct;
                    ui.post(() -> { progress.setProgress(p); status.setText("جارٍ الحفظ بالدقة الأصلية… " + p + "٪"); });
                }
            }
            if (reuse != null) reuse.recycle();
            if (jpeg) {
                jw.finish();
            } else {
                Matrix m = orientationMatrix(orient);
                Bitmap toWrite = outBmp;
                if (m != null) {
                    try {
                        toWrite = Bitmap.createBitmap(outBmp, 0, 0, W, H, m, false);
                    } catch (OutOfMemoryError e) {
                        throw new TooLargeException();
                    }
                    outBmp.recycle();
                }
                Bitmap.CompressFormat cf = fmt.equals("png") ? Bitmap.CompressFormat.PNG
                        : (Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP);
                if (!toWrite.compress(cf, 100, os)) throw new IOException("encode failed");
                result.width = toWrite.getWidth();
                result.height = toWrite.getHeight();
                toWrite.recycle();
            }
            os.flush();
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
        result.mime = mime;
        if (jpeg) {
            boolean swap = orient >= 5;
            result.width = swap ? H : W;
            result.height = swap ? W : H;
        }
        return result;
    }

    private Bitmap decodeRaw(Uri uri) throws IOException {
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap b = BitmapFactory.decodeStream(in, null, d);
            if (b == null) throw new IOException("decode failed");
            return b;
        }
    }

    private void share(Uri uri, String mime) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "مشاركة الصورة"));
    }

    // ------------------------------------------------------------------ decoding helpers

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
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * sc)), Math.max(1, Math.round(b.getHeight() * sc)), true);
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
