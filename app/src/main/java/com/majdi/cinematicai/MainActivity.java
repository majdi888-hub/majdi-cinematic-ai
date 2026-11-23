package com.majdi.cinematicai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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

/**
 * Majdi Cinematic AI Studio: full-screen photo, bottom tool bar, one panel per tool.
 * Every edit is non-destructive (EditState over the untouched original) and undoable.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 11, REQ_IMPORT = 12, REQ_TREE = 13;
    private static final int PREVIEW_MAX = 1600;
    private static final int THUMB_MAX = 300;

    static final int BG = Color.rgb(11, 12, 15);
    static final int PANEL = Color.argb(240, 20, 21, 27);
    static final int CARD = Color.rgb(34, 36, 45);
    static final int ACCENT = Color.rgb(242, 165, 65);
    static final int SEL = Color.rgb(28, 110, 125);
    static final int TEXT2 = Color.rgb(170, 173, 182);

    private static final String[][] TOOLS = {
            {"filters", "◈", "الفلاتر"}, {"stack", "☰", "الطبقات"}, {"adjust", "◐", "الضبط"},
            {"crop", "⌗", "القص والتدوير"}, {"frame", "▣", "الإطار"}, {"lens", "◎", "العدسة"},
            {"import", "⇩", "استيراد"}, {"projects", "▤", "المشاريع"}, {"export", "⇪", "التصدير"}};

    private static final String[][] ADJUST_LABELS = {
            {"exposure", "التعريض"}, {"contrast", "التباين"}, {"highlights", "الإضاءات العالية"}, {"shadows", "الظلال"},
            {"whites", "الأبيض"}, {"blacks", "الأسود"}, {"temperature", "الحرارة اللونية"}, {"tint", "الصبغة"},
            {"saturation", "التشبع"}, {"vibrance", "الحيوية"}, {"clarity", "الوضوح"}, {"texture", "الملمس"},
            {"dehaze", "إزالة الضباب"}, {"sharpening", "الحدة"}, {"noise", "تقليل الضوضاء"},
            {"vignette", "التظليل الجانبي"}, {"grain", "حبيبات الفيلم"}};

    // ---- views
    private FrameLayout root;
    private PhotoView photo;
    private LinearLayout topBar, bottomArea, emptyState;
    private ScrollView panelScroll;
    private LinearLayout panel;
    private TextView status, panelTitle;
    private ProgressBar busy, exportBar;
    private Button undoBtn, redoBtn;
    private final Map<String, Button> toolButtons = new HashMap<>();
    private String tool;                 // open panel, or null
    private boolean uiHidden;
    private View handle;
    private int panelState = 1;          // 0 closed, 1 medium, 2 expanded
    private int panelH;                  // current panel height in px (follows the drag)

    // ---- image + edit
    private Uri sourceUri;
    private int fullW, fullH, orient = 1;
    private int pw, ph;
    private int[] previewPx;
    private Bitmap previewOriginal;
    private float[][] faces = new float[0][];
    private SceneMaps maps;
    private EditState state = EditState.defaults();
    private History history = new History(state);
    private int selLayer = 0;
    private boolean addMode;
    private String filterCategory = "الكل";

    // ---- render
    private final Pipeline.Cache cache = new Pipeline.Cache();
    private int[] colored;               // working-resolution colour result
    private String coloredKey;
    private Pipeline pipe;
    private int renderToken;
    private boolean flashFocus;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private int thumbToken;

    // ---- stores
    private ModelStore models;
    private FilterStore filters;
    private ProjectStore projects;
    private ProjectStore.Project project;
    private final Map<String, Lut3D> lutCache = new HashMap<>();
    private final Map<String, Lut3D> importedCache = new HashMap<>();
    private SharedPreferences prefs;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService viewWorker = Executors.newSingleThreadExecutor();
    private int viewToken;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingRender;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("studio", MODE_PRIVATE);
        models = new ModelStore(this);
        try {
            filters = new FilterStore(new File(getFilesDir(), "filters"));
            projects = new ProjectStore(new File(getFilesDir(), "projects"));
        } catch (IOException e) {
            Toast.makeText(this, "تعذّر تجهيز مجلد التطبيق", Toast.LENGTH_LONG).show();
        }
        buildUi();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    // =================================================================== small UI helpers

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    /** Keeps Latin fragments (lens names, formats, numbers) in order inside Arabic text. */
    static String ltr(String s) { return "⁦" + s + "⁩"; }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(primary ? BG : Color.WHITE);
        b.setBackground(rounded(primary ? ACCENT : CARD, 12));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private Button chip(String s, boolean selected, Runnable r) {
        Button b = button(s, false);
        b.setTextSize(12);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable g = rounded(selected ? SEL : CARD, 18);
        if (selected) g.setStroke(dp(1.5f), ACCENT);
        b.setBackground(g);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private HorizontalScrollView hscroll(View content) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(content);
        return h;
    }

    private TextView section(String s) {
        TextView t = text(s, 13, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(10), dp(4), dp(2));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11.5f, TEXT2);
        t.setPadding(dp(4), dp(2), dp(4), dp(4));
        return t;
    }

    interface SliderChange { void on(int value, boolean finished); }

    /**
     * A labelled slider; numeric sliders run left (minimum) to right (maximum) as in photo editors.
     * Tapping the label resets it to its default.
     */
    private SeekBar slider(LinearLayout parent, String label, int min, int max, int value, int def, String unit, SliderChange ch) {
        LinearLayout head = hrow();
        TextView name = text(label, 12.5f, Color.WHITE);
        TextView val = text(ltr((value > 0 && min < 0 ? "+" : "") + value + unit), 12.5f, value == def ? TEXT2 : ACCENT);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        head.setPadding(dp(6), dp(6), dp(6), 0);
        parent.addView(head);
        SeekBar s = new SeekBar(this);
        s.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        s.setMax(max - min);
        s.setProgress(value - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int v = p + min;
                val.setText(ltr((v > 0 && min < 0 ? "+" : "") + v + unit));
                val.setTextColor(v == def ? TEXT2 : ACCENT);
                if (user) ch.on(v, false);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { ch.on(sb.getProgress() + min, true); }
        });
        name.setOnClickListener(v -> { s.setProgress(def - min); ch.on(def, true); });
        parent.addView(s);
        return s;
    }

    // =================================================================== layout

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        photo = new PhotoView(this);
        photo.setListener(new PhotoView.Listener() {
            @Override public void onTap(float u, float v) { onPhotoTap(u, v); }
            @Override public void onCropChanged(RectF c, boolean finished) {
                state.cropL = c.left; state.cropT = c.top; state.cropR = c.right; state.cropB = c.bottom;
                if (finished) commit(false);
            }
            @Override public void onCompare(boolean on) { setStatus(on ? "الصورة الأصلية (أفلت للعودة)" : statusLine()); }
        });
        root.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Color.argb(215, 11, 12, 15));
        topBar = hrow();
        topBar.setPadding(dp(4), statusBarHeight(), dp(4), 0);
        status = text("Majdi Studio", 11.5f, TEXT2);
        status.setPadding(dp(8), 0, dp(4), 0);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        busy = new ProgressBar(this);
        busy.setIndeterminate(true);
        busy.setVisibility(View.INVISIBLE);
        topBar.addView(busy, new LinearLayout.LayoutParams(dp(26), dp(26)));
        undoBtn = iconButton("↶", "تراجع", v -> undo());
        redoBtn = iconButton("↷", "إعادة", v -> redo());
        Button compare = iconButton("◑", "مقارنة (اضغط مطولًا)", null);
        compare.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) photo.setComparing(true);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { photo.setComparing(false); v.performClick(); }
            return true;
        });
        Button full = iconButton("⛶", "إخفاء الأدوات / ملء الشاشة", v -> setUiHidden(true));
        Button save = iconButton("⇪", "حفظ بالدقة الأصلية (اضغط مطولًا لإعدادات التصدير)", v -> { if (previewPx != null) startExport(false); });
        save.setOnLongClickListener(v -> { openTool("export"); return true; });
        topBar.addView(undoBtn);
        topBar.addView(redoBtn);
        topBar.addView(compare);
        topBar.addView(save);
        topBar.addView(full);
        top.addView(topBar);
        exportBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportBar.setMax(100);
        exportBar.setVisibility(View.GONE);
        top.addView(exportBar);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ---- bottom: panel (above) + tool bar
        bottomArea = new LinearLayout(this);
        bottomArea.setOrientation(LinearLayout.VERTICAL);
        panelScroll = new ScrollView(this);
        panelScroll.setBackground(rounded(PANEL, 18));
        LinearLayout pwrap = new LinearLayout(this);
        pwrap.setOrientation(LinearLayout.VERTICAL);
        pwrap.setPadding(dp(10), 0, dp(10), dp(10));
        LinearLayout ph0 = hrow();
        panelTitle = text("", 14, Color.WHITE);
        panelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ph0.addView(panelTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = iconButton("✕", "إغلاق اللوحة", v -> openTool(null));
        ph0.addView(close);
        pwrap.addView(ph0);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        pwrap.addView(panel);
        panelScroll.addView(pwrap);
        panelScroll.setVisibility(View.GONE);
        // drag handle: drag to resize; release snaps to closed / medium / expanded; tap toggles
        FrameLayout hwrap = new FrameLayout(this);
        handle = hwrap;
        View bar = new View(this);
        bar.setBackground(rounded(Color.rgb(120, 122, 132), 3));
        hwrap.addView(bar, new FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER));
        hwrap.setBackground(rounded(PANEL, 18));
        hwrap.setContentDescription("اسحب لتغيير حجم اللوحة");
        hwrap.setVisibility(View.GONE);
        hwrap.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startH; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); startH = panelH; moved = false; return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = downY - e.getRawY();
                        if (Math.abs(dy) > dp(6)) moved = true;
                        if (moved) setPanelHeight((int) Math.max(0, Math.min(root.getHeight() * 0.68f, startH + dy)));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved) { snapPanel(panelState == 2 ? 1 : 2); v.performClick(); return true; }
                        float f = panelH / (float) root.getHeight();
                        snapPanel(f < 0.14f ? 0 : (f < 0.47f ? 1 : 2));
                        return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        hlp.setMargins(dp(6), 0, dp(6), 0);
        bottomArea.addView(hwrap, hlp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        plp.setMargins(dp(6), 0, dp(6), dp(2));
        bottomArea.addView(panelScroll, plp);

        LinearLayout tb = hrow();
        tb.setPadding(dp(2), dp(2), dp(2), dp(4));
        for (String[] t : TOOLS) {
            final String key = t[0];
            Button b = new Button(this);
            b.setText(t[1] + "\n" + t[2]);
            b.setAllCaps(false);
            b.setTextSize(10.5f);
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(Color.TRANSPARENT, 12));
            b.setStateListAnimator(null);
            b.setMinWidth(dp(64));
            b.setMinimumWidth(dp(64));
            b.setMinHeight(dp(50));
            b.setMinimumHeight(dp(50));
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setOnClickListener(v -> openTool(key.equals(tool) ? null : key));
            toolButtons.put(key, b);
            tb.addView(b);
        }
        HorizontalScrollView tbs = hscroll(tb);
        tbs.setBackgroundColor(Color.argb(235, 11, 12, 15));
        bottomArea.addView(tbs);
        root.addView(bottomArea, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // ---- empty state
        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        TextView big = text("Majdi Cinematic AI Studio", 22, ACCENT);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        emptyState.addView(big);
        TextView sub = text("تحرير احترافي غير إتلافي — كل المعالجة على الهاتف دون إنترنت", 13, TEXT2);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(20), dp(8), dp(20), dp(16));
        emptyState.addView(sub);
        Button o = button("فتح صورة", true);
        o.setOnClickListener(v -> pickImage());
        emptyState.addView(o);
        Button pr = button("المشاريع المحفوظة", false);
        pr.setOnClickListener(v -> openTool("projects"));
        emptyState.addView(pr);
        root.addView(emptyState, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the photo sits between the bars (resized when the panel opens, so it is never hidden)
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> ui.post(this::layoutPhoto));
        setContentView(root);
        refreshChrome();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private void setPanelHeight(int h) {
        panelH = h;
        panelScroll.getLayoutParams().height = h;
        panelScroll.setVisibility(h > 0 ? View.VISIBLE : View.INVISIBLE);
        panelScroll.requestLayout();
        ui.post(this::layoutPhoto);
    }

    /** 0 closed (tool bar only), 1 medium (photo stays large), 2 expanded. */
    private void snapPanel(int st) {
        if (st == 0) { openTool(null); return; }
        panelState = st;
        setPanelHeight((int) (root.getHeight() * (st == 1 ? 0.30f : 0.60f)));
    }

    private Button iconButton(String icon, String desc, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(icon);
        b.setTextSize(19);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(rounded(Color.TRANSPARENT, 20));
        b.setStateListAnimator(null);
        b.setContentDescription(desc);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(0, 0, 0, 0);
        if (l != null) b.setOnClickListener(l);
        b.setOnLongClickListener(v -> { Toast.makeText(this, desc, Toast.LENGTH_SHORT).show(); return true; });
        return b;
    }

    private void layoutPhoto() {
        int topH = uiHidden ? 0 : ((View) topBar.getParent()).getHeight();
        int botH = uiHidden ? 0 : bottomArea.getHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) photo.getLayoutParams();
        if (lp.topMargin != topH || lp.bottomMargin != botH) {
            lp.topMargin = topH;
            lp.bottomMargin = botH;
            photo.setLayoutParams(lp);
        }
    }

    private void setUiHidden(boolean hidden) {
        uiHidden = hidden;
        ((View) topBar.getParent()).setVisibility(hidden ? View.GONE : View.VISIBLE);
        bottomArea.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (hidden) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        }
        if (hidden) Toast.makeText(this, "ملء الشاشة — اضغط رجوع لإظهار الأدوات", Toast.LENGTH_SHORT).show();
        ui.post(this::layoutPhoto);
    }

    @Override
    public void onBackPressed() {
        if (uiHidden) { setUiHidden(false); return; }
        if (tool != null) { openTool(null); return; }
        super.onBackPressed();
    }

    private void setStatus(String s) { status.setText(s); }

    private void refreshChrome() {
        boolean has = previewPx != null;
        emptyState.setVisibility(has || "projects".equals(tool) ? View.GONE : View.VISIBLE);
        undoBtn.setEnabled(history.canUndo());
        redoBtn.setEnabled(history.canRedo());
        undoBtn.setAlpha(history.canUndo() ? 1f : 0.35f);
        redoBtn.setAlpha(history.canRedo() ? 1f : 0.35f);
        for (Map.Entry<String, Button> e : toolButtons.entrySet()) {
            boolean sel = e.getKey().equals(tool);
            e.getValue().setBackground(rounded(sel ? SEL : Color.TRANSPARENT, 12));
            e.getValue().setTextColor(sel ? ACCENT : Color.WHITE);
        }
    }

    // =================================================================== tools / panels

    private void openTool(String t) {
        String old = tool;
        tool = t;
        handle.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t == null) {
            panelScroll.setVisibility(View.GONE);
            panelH = 0;
        } else if (old == null) {
            snapPanel(panelState == 2 ? 2 : 1);
        }
        buildPanel();
        refreshChrome();
        boolean viewModeChanged = "crop".equals(old) != "crop".equals(t) || "lens".equals(old) != "lens".equals(t);
        if (viewModeChanged) showResult();
        if ("filters".equals(t)) makeThumbs();
        ui.post(this::layoutPhoto);
    }

    private void buildPanel() {
        panel.removeAllViews();
        if (tool == null) return;
        for (String[] t : TOOLS) if (t[0].equals(tool)) panelTitle.setText(t[2]);
        boolean needImage = !tool.equals("projects") && !tool.equals("import") && !tool.equals("export");
        if (needImage && previewPx == null) {
            panel.addView(note("افتح صورة أولًا."));
            return;
        }
        switch (tool) {
            case "filters": buildFilters(); break;
            case "stack": buildStack(); break;
            case "adjust": buildAdjust(); break;
            case "crop": buildCrop(); break;
            case "frame": buildFrame(); break;
            case "lens": buildLens(); break;
            case "import": buildImport(); break;
            case "projects": buildProjects(); break;
            case "export": buildExport(); break;
        }
    }

    // ---------------------------------------------------------------- filters

    private static final class FilterCard {
        final String id, name;
        final boolean preset;
        FilterCard(String id, String name, boolean preset) { this.id = id; this.name = name; this.preset = preset; }
    }

    private List<FilterCard> cardsFor(String cat) {
        List<FilterCard> out = new ArrayList<>();
        for (FilterLibrary.Entry e : FilterLibrary.BUILT_IN)
            if (cat.equals("الكل") || cat.equals(e.category)) out.add(new FilterCard(e.id, e.nameAr + "\n" + e.name, false));
        if (filters != null) for (FilterStore.Item it : filters.list()) {
            boolean personal = it.category.equals("شخصية");
            boolean match = cat.equals("الكل") || cat.equals(it.category) || (cat.equals("مستوردة") && !personal && !isBuiltInCategory(it.category));
            if (!match) continue;
            if (it.kind.equals("lut")) out.add(new FilterCard("lut:" + it.id, it.name + "\nLUT", false));
            else out.add(new FilterCard("preset:" + it.id, "★ " + it.name, true));
        }
        return out;
    }

    private static boolean isBuiltInCategory(String c) {
        for (String k : FilterLibrary.CATEGORIES) if (k.equals(c)) return true;
        return false;
    }

    private void buildFilters() {
        LinearLayout cats = hrow();
        List<String> all = new ArrayList<>();
        all.add("الكل");
        for (String c : FilterLibrary.CATEGORIES) all.add(c);
        if (filters != null) for (FilterStore.Item it : filters.list()) if (!all.contains(it.category)) all.add(it.category);
        for (String c : all) cats.addView(chip(c, c.equals(filterCategory), () -> { filterCategory = c; buildPanel(); makeThumbs(); }));
        panel.addView(hscroll(cats));

        LinearLayout mode = hrow();
        mode.addView(chip("استبدال الطبقة المحددة", !addMode, () -> { addMode = false; buildPanel(); invalidateThumbs(); }));
        mode.addView(chip("+ إضافة كطبقة جديدة", addMode, () -> { addMode = true; buildPanel(); invalidateThumbs(); }));
        panel.addView(hscroll(mode));

        LinearLayout grid = hrow();
        String cur = selectedLayer() != null ? selectedLayer().filter : null;
        for (FilterCard c : cardsFor(filterCategory)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            boolean sel = c.id.equals(cur);
            GradientDrawable bg = rounded(sel ? SEL : CARD, 12);
            if (sel) bg.setStroke(dp(2), ACCENT);
            card.setBackground(bg);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap tb = thumbs.get(c.id);
            if (tb != null) iv.setImageBitmap(tb);
            else iv.setBackgroundColor(Color.rgb(45, 47, 56));
            iv.setTag("thumb:" + c.id);
            card.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(84)));
            TextView n = text(c.name, 10.5f, Color.WHITE);
            n.setGravity(Gravity.CENTER);
            n.setMaxLines(2);
            n.setWidth(dp(92));
            card.addView(n);
            card.setOnClickListener(v -> applyFilter(c));
            if (c.preset) card.setOnLongClickListener(v -> { Toast.makeText(this, "فلتر مركّب: يطبّق طبقاته وقيم الضبط", Toast.LENGTH_SHORT).show(); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            grid.addView(card, lp);
        }
        panel.addView(hscroll(grid));

        EditState.Layer l = selectedLayer();
        if (l != null) {
            panel.addView(note("الطبقة المحددة: " + FilterLibrary.displayName(l.filter, filters)));
            slider(panel, "قوة الفلتر", 0, 100, Math.round(l.strength * 100), Math.round(FilterLibrary.defaultStrength(l.filter) * 100), "٪",
                    (v, done) -> { l.strength = v / 100f; if (done) commit(true); else renderSoon(); });
            layerExtras(panel, l);
        } else {
            panel.addView(note("لا توجد طبقات — اختر فلترًا لإضافته."));
        }
    }

    /** Controls that belong to one specific look, shown under its strength. */
    private void layerExtras(LinearLayout parent, EditState.Layer l) {
        if (l.filter.equals("cinematic:film")) {
            parent.addView(section("مظهر الفيلم"));
            slider(parent, "حبيبات الفيلم", 0, 100, Math.round(state.filmGrain), 30, "",
                    (v, d) -> { state.filmGrain = v; if (d) commit(true); else renderSoon(); });
            slider(parent, "تبهيت الأسود", 0, 100, Math.round(state.filmFade), 30, "",
                    (v, d) -> { state.filmFade = v; if (d) commit(true); else renderSoon(); });
            slider(parent, "تبهيت الألوان", 0, 100, Math.round(state.filmColorFade), 25, "",
                    (v, d) -> { state.filmColorFade = v; if (d) commit(true); else renderSoon(); });
            slider(parent, "تباين الفيلم", -100, 100, Math.round(state.filmContrast), 0, "",
                    (v, d) -> { state.filmContrast = v; if (d) commit(true); else renderSoon(); });
        } else if (l.filter.equals("portrait") && faces.length == 0 && state.focus == null) {
            parent.addView(note("Portrait Pro مخصص لصور الأشخاص. لم يُكتشف وجه في هذه الصورة، لذلك تُطبَّق معالجة محافظة دون عزل للخلفية."));
        } else if (l.filter.equals("dslr") || l.filter.equals("portrait") || l.filter.equals("fusion")) {
            parent.addView(note("مقدار عزل الخلفية مستقل عن قوة الفلتر: اضبطه من أداة «العدسة»."));
        }
    }

    private EditState.Layer selectedLayer() {
        if (state.layers.isEmpty()) return null;
        selLayer = Math.max(0, Math.min(state.layers.size() - 1, selLayer));
        return state.layers.get(selLayer);
    }

    private void applyFilter(FilterCard c) {
        if (c.preset) {
            try {
                EditState p = filters.loadPreset(c.id.substring("preset:".length()));
                if (p == null) return;
                state.layers.clear();
                for (EditState.Layer l : p.layers) state.layers.add(l.copy());
                state.adjust.putAll(p.adjust);
                selLayer = Math.max(0, state.layers.size() - 1);
                commit(true);
                buildPanel();
                toast("طُبّق الفلتر المركّب: " + c.name.replace("★ ", ""));
            } catch (Exception e) {
                toast("تعذّر قراءة الفلتر");
            }
            return;
        }
        EditState.Layer l = selectedLayer();
        if (l == null || addMode) {
            if (state.layers.size() >= 16) { toast("الحد الأقصى 16 طبقة"); return; }
            state.layers.add(new EditState.Layer(c.id, FilterLibrary.defaultStrength(c.id)));
            selLayer = state.layers.size() - 1;
            addMode = false;
        } else {
            if (l.filter.equals(c.id)) return;
            l.filter = c.id;
            l.strength = FilterLibrary.defaultStrength(c.id);
            l.enabled = true;
        }
        commit(true);
        buildPanel();
    }

    /**
     * Previews of every visible filter on the current photo, rendered by the real engine in the
     * real context: the current stack with the selected layer replaced by that filter (at the
     * strength it would get), the Adjust panel, the lens settings and the depth maps.
     */
    private void makeThumbs() {
        if (previewPx == null || !"filters".equals(tool)) return;
        final int token = ++thumbToken;
        final List<FilterCard> cards = cardsFor(filterCategory);
        final int[] src = previewPx;
        final int sw = pw, sh = ph;
        final EditState base = state.copy();
        final int sel = state.layers.isEmpty() ? -1 : Math.max(0, Math.min(state.layers.size() - 1, selLayer));
        final boolean add = addMode;
        final SceneMaps sm = maps;
        final float[][] fa = faces;
        thumbWorker.execute(() -> {
            float sc = THUMB_MAX / (float) Math.max(sw, sh);
            int tw = Math.max(8, Math.round(sw * sc)), th = Math.max(8, Math.round(sh * sc));
            int[] spx;
            SceneMaps tm;
            try {
                Bitmap b = Bitmap.createBitmap(src, sw, sh, Bitmap.Config.ARGB_8888);
                Bitmap small = Bitmap.createScaledBitmap(b, tw, th, true);
                b.recycle();
                spx = ImageEngine.pixels(small);
                tm = sm != null ? sm.resized(tw, th) : null;
            } catch (Throwable e) { return; }
            for (FilterCard c : cards) {
                if (token != thumbToken) return;
                if (c.preset || thumbs.containsKey(c.id)) continue;
                try {
                    EditState s = base.copy();
                    if (sel < 0 || add) {
                        s.layers.add(new EditState.Layer(c.id, FilterLibrary.defaultStrength(c.id)));
                    } else {
                        EditState.Layer l = s.layers.get(sel);
                        float strength = l.filter.equals(c.id) ? l.strength : FilterLibrary.defaultStrength(c.id);
                        l.filter = c.id;
                        l.strength = strength;
                        l.enabled = true;
                    }
                    Pipeline p = Pipeline.build(spx, tw, th, s, tm, fa, this::lut, this::importedLut);
                    int[] out = p.render(spx, tw, th);
                    Bitmap tb = Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888);
                    ui.post(() -> {
                        if (token != thumbToken) return;
                        thumbs.put(c.id, tb);
                        View v = panel.findViewWithTag("thumb:" + c.id);
                        if (v instanceof ImageView) ((ImageView) v).setImageBitmap(tb);
                    });
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** Thumbnails depend on the whole edit: drop them after any colour change. */
    private void invalidateThumbs() {
        thumbs.clear();
        thumbToken++;
        if ("filters".equals(tool)) ui.postDelayed(this::makeThumbs, 250);
    }

    // ---------------------------------------------------------------- stack

    private void buildStack() {
        panel.addView(note("الطبقات تُطبَّق من الأعلى إلى الأسفل، ثم لوحة الضبط. الأصل لا يتغير أبدًا."));
        if (state.layers.isEmpty()) panel.addView(note("لا توجد طبقات."));
        for (int i = 0; i < state.layers.size(); i++) {
            final int idx = i;
            EditState.Layer l = state.layers.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = rounded(idx == selLayer ? Color.rgb(28, 50, 58) : CARD, 12);
            if (idx == selLayer) bg.setStroke(dp(1.5f), ACCENT);
            box.setBackground(bg);
            box.setPadding(dp(6), dp(2), dp(6), dp(4));
            LinearLayout r = hrow();
            Button en = iconButton(l.enabled ? "☑" : "☐", l.enabled ? "تعطيل الطبقة" : "تفعيل الطبقة", v -> { l.enabled = !l.enabled; commit(true); buildPanel(); });
            r.addView(en);
            TextView name = text((idx + 1) + ". " + FilterLibrary.displayName(l.filter, filters), 12.5f, l.enabled ? Color.WHITE : TEXT2);
            name.setOnClickListener(v -> { selLayer = idx; buildPanel(); });
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(iconButton("▲", "تحريك لأعلى", v -> moveLayer(idx, -1)));
            r.addView(iconButton("▼", "تحريك لأسفل", v -> moveLayer(idx, 1)));
            r.addView(iconButton("✕", "حذف الطبقة", v -> { state.layers.remove(idx); selLayer = Math.max(0, selLayer - (selLayer >= idx ? 1 : 0)); commit(true); buildPanel(); }));
            box.addView(r);
            slider(box, "القوة", 0, 100, Math.round(l.strength * 100), Math.round(FilterLibrary.defaultStrength(l.filter) * 100), "٪",
                    (v, done) -> { l.strength = v / 100f; selLayer = idx; if (done) commit(true); else renderSoon(); });
            if (l.filter.equals("cinematic:film")) layerExtras(box, l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            panel.addView(box, lp);
        }
        LinearLayout acts = hrow();
        Button add = button("+ إضافة طبقة", true);
        add.setOnClickListener(v -> { addMode = true; openTool("filters"); });
        acts.addView(add);
        Button save = button("حفظ كفلتر شخصي", false);
        save.setOnClickListener(v -> savePersonalPreset());
        acts.addView(save);
        Button reset = button("الافتراضي", false);
        reset.setOnClickListener(v -> confirm("إعادة الطبقات والضبط إلى الوضع الافتراضي؟", () -> {
            EditState d = EditState.defaults();
            state.layers.clear();
            state.layers.addAll(d.layers);
            state.adjust.putAll(d.adjust);
            selLayer = 0;
            commit(true);
            buildPanel();
        }));
        acts.addView(reset);
        panel.addView(hscroll(acts));
    }

    private void moveLayer(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= state.layers.size()) return;
        EditState.Layer l = state.layers.remove(i);
        state.layers.add(j, l);
        selLayer = j;
        commit(true);
        buildPanel();
    }

    private void savePersonalPreset() {
        if (filters == null) return;
        prompt("اسم الفلتر الشخصي", "فلتري " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()), name -> {
            try {
                filters.savePersonal(state, name);
                toast("حُفظ في «شخصية»: " + name);
            } catch (IOException e) {
                toast("تعذّر الحفظ");
            }
        });
    }

    // ---------------------------------------------------------------- adjust

    private void buildAdjust() {
        panel.addView(note("كل خاصية مستقلة وتُطبَّق بعد الطبقات. اضغط على اسم الخاصية لإعادتها إلى الصفر."));
        for (String[] a : ADJUST_LABELS) {
            final String k = a[0];
            int min = EditState.positiveOnly(k) ? 0 : -100;
            slider(panel, a[1], min, 100, Math.round(state.adj(k)), 0, "", (v, done) -> {
                state.adjust.put(k, (float) v);
                if (done) commit(true); else renderSoon();
            });
        }
        Button reset = button("تصفير كل الضبط", false);
        reset.setOnClickListener(v -> { for (String k : EditState.ADJUST_KEYS) state.adjust.put(k, 0f); commit(true); buildPanel(); });
        panel.addView(reset);
    }

    // ---------------------------------------------------------------- crop / transform

    private static final String[][] ASPECTS = {{"free", "حر"}, {"original", "الأصلي"}, {"1:1", "1:1"}, {"4:5", "4:5"}, {"5:4", "5:4"},
            {"3:2", "3:2"}, {"2:3", "2:3"}, {"16:9", "16:9"}, {"9:16", "9:16"}};

    private float aspectValue(String a) {
        if (a.equals("free")) return 0f;
        if (a.equals("original")) {
            boolean swap = (state.rot90 & 1) == 1;
            return swap ? ph / (float) pw : pw / (float) ph;
        }
        return Geometry.aspect(a);
    }

    private void buildCrop() {
        panel.addView(note("اسحب حواف الإطار أو زواياه أو داخله. الدقة الأصلية تبقى كما هي داخل منطقة القص."));
        LinearLayout ar = hrow();
        for (String[] a : ASPECTS) ar.addView(chip(ltr(a[1]), state.aspect.equals(a[0]), () -> {
            state.aspect = a[0];
            photo.setCropAspect(aspectValue(a[0]));
            buildPanel();
        }));
        panel.addView(hscroll(ar));
        LinearLayout tr = hrow();
        tr.addView(chip("⟲ تدوير يسار", false, () -> { state.rot90 = (state.rot90 + 3) & 3; resetCrop(); commit(false); showResult(); }));
        tr.addView(chip("⟳ تدوير يمين", false, () -> { state.rot90 = (state.rot90 + 1) & 3; resetCrop(); commit(false); showResult(); }));
        tr.addView(chip("⇋ قلب أفقي", state.flipH, () -> { state.flipH = !state.flipH; commit(false); showResult(); buildPanel(); }));
        tr.addView(chip("⇵ قلب رأسي", state.flipV, () -> { state.flipV = !state.flipV; commit(false); showResult(); buildPanel(); }));
        panel.addView(hscroll(tr));
        slider(panel, "تقويم الميلان (درجة × 10)", -450, 450, Math.round(state.straighten * 10), 0, "",
                (v, done) -> { state.straighten = v / 10f; if (done) { commit(false); } showResultSoon(); });
        slider(panel, "منظور رأسي", -100, 100, Math.round(state.perspV * 100), 0, "",
                (v, done) -> { state.perspV = v / 100f; if (done) commit(false); showResultSoon(); });
        slider(panel, "منظور أفقي", -100, 100, Math.round(state.perspH * 100), 0, "",
                (v, done) -> { state.perspH = v / 100f; if (done) commit(false); showResultSoon(); });
        Button reset = button("إعادة الهندسة الأصلية", false);
        reset.setOnClickListener(v -> {
            state.rot90 = 0; state.flipH = false; state.flipV = false; state.straighten = 0; state.perspV = 0; state.perspH = 0;
            state.aspect = "free";
            resetCrop();
            commit(false);
            showResult();
            buildPanel();
        });
        panel.addView(reset);
    }

    private void resetCrop() { state.cropL = 0; state.cropT = 0; state.cropR = 1; state.cropB = 1; }

    // ---------------------------------------------------------------- frame

    private static final String[][] FRAMES = {{"none", "بدون"}, {"border", "حدود"}, {"canvas", "كانفس"}, {"film", "شريط فيلم"}, {"cinematic", "إطار سينمائي"}};
    private static final int[] COLORS = {0xFFFFFFFF, 0xFF000000, 0xFFF1E9DA, 0xFF808080, 0xFFC9A44C, 0xFF1B2440, 0xFF7A1F1F};
    private static final String[] COLOR_NAMES = {"أبيض", "أسود", "كريمي", "رمادي", "ذهبي", "كحلي", "عنابي"};

    private void buildFrame() {
        panel.addView(note("الإطار يضيف مساحة حول الصورة ولا يقص منها شيئًا."));
        LinearLayout fr = hrow();
        for (String[] f : FRAMES) fr.addView(chip(f[1], state.frame.equals(f[0]), () -> {
            state.frame = f[0];
            if ((f[0].equals("film") || f[0].equals("cinematic")) && state.frameColor == 0xFFFFFFFF) state.frameColor = 0xFF000000;
            commit(false); showResult(); buildPanel();
        }));
        panel.addView(hscroll(fr));
        if (state.frame.equals("none")) return;
        LinearLayout cr = hrow();
        for (int i = 0; i < COLORS.length; i++) {
            final int c = COLORS[i];
            Button b = chip(COLOR_NAMES[i], state.frameColor == c, () -> { state.frameColor = c; commit(false); showResult(); buildPanel(); });
            GradientDrawable g = rounded(c, 18);
            g.setStroke(dp(state.frameColor == c ? 3 : 1), state.frameColor == c ? ACCENT : Color.GRAY);
            b.setBackground(g);
            int lum = ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10;
            b.setTextColor(lum > 128 ? Color.BLACK : Color.WHITE);
            cr.addView(b);
        }
        panel.addView(hscroll(cr));
        slider(panel, "السماكة", 0, 30, Math.round(state.frameThickness * 100), 4, "٪",
                (v, done) -> { state.frameThickness = v / 100f; if (done) commit(false); showResultSoon(); });
        if (state.frame.equals("canvas")) {
            LinearLayout ca = hrow();
            for (String a : new String[]{"1:1", "4:5", "5:4", "3:2", "2:3", "16:9", "9:16"})
                ca.addView(chip(ltr(a), state.canvasAspect.equals(a), () -> { state.canvasAspect = a; commit(false); showResult(); buildPanel(); }));
            panel.addView(section("نسبة الكانفس"));
            panel.addView(hscroll(ca));
            slider(panel, "الهامش", 0, 40, Math.round(state.frameMargin * 100), 6, "٪",
                    (v, done) -> { state.frameMargin = v / 100f; if (done) commit(false); showResultSoon(); });
            slider(panel, "موضع الصورة أفقيًا", 0, 100, Math.round(state.framePosX * 100), 50, "",
                    (v, done) -> { state.framePosX = v / 100f; if (done) commit(false); showResultSoon(); });
            slider(panel, "موضع الصورة رأسيًا", 0, 100, Math.round(state.framePosY * 100), 50, "",
                    (v, done) -> { state.framePosY = v / 100f; if (done) commit(false); showResultSoon(); });
        }
    }

    // ---------------------------------------------------------------- lens

    private boolean stackUsesLens() {
        for (EditState.Layer l : state.layers) {
            if (!l.enabled) continue;
            String f = l.filter;
            if (f.equals("dslr") || f.equals("portrait") || f.equals("fusion") || f.equals("auto")) return true;
        }
        return false;
    }

    private void buildLens() {
        if (maps == null || !maps.hasDepth()) panel.addView(note("نموذج العمق غير متاح لهذه الصورة — العزل بالعمق معطّل (لن يُضاف تمويه شكلي)."));
        else if (!stackUsesLens()) panel.addView(note("أضف طبقة DSLR أو بورتريه أو الدمج الاحترافي لتفعيل العدسة."));
        else panel.addView(note("المس الصورة لاختيار ما يبقى حادًّا. " + isolationText()));
        LinearLayout m = hrow();
        m.addView(chip("تركيز على جسم كامل", state.focusMode.equals("object"), () -> { state.focusMode = "object"; flashFocus = true; commit(true); buildPanel(); }));
        m.addView(chip("تركيز على مستوى", state.focusMode.equals("point"), () -> { state.focusMode = "point"; flashFocus = true; commit(true); buildPanel(); }));
        m.addView(chip("إلغاء نقطة التركيز", false, () -> { state.focus = null; commit(true); buildPanel(); }));
        panel.addView(hscroll(m));
        slider(panel, "مدى التركيز: ضيّق ← → واسع", -100, 100, Math.round(state.focusRange * 100), 0, "",
                (v, done) -> { state.focusRange = v / 100f; if (done) { flashFocus = true; commit(true); } });
        slider(panel, "حدود الجسم: تقليص ← → توسيع", -100, 100, Math.round(state.objectGrow * 100), 0, "",
                (v, done) -> { state.objectGrow = v / 100f; if (done) { flashFocus = true; commit(true); } });
        slider(panel, "مقدار عزل الخلفية (مستقل عن قوة الفلتر)", 0, 100, Math.round(state.depthBlur * 100), 50, "٪",
                (v, done) -> { state.depthBlur = v / 100f; if (done) commit(true); });
        panel.addView(section("محاكاة العدسة (تقريب بصري، لا يغيّر عدسة الهاتف)"));
        LinearLayout fl = hrow();
        for (int f : new int[]{35, 50, 85}) fl.addView(chip(ltr(f + "mm"), state.focal == f, () -> { state.focal = f; commit(true); buildPanel(); }));
        for (float a : new float[]{1.8f, 2.8f, 4f, 5.6f}) {
            String t = a == 4f ? "f/4" : "f/" + a;
            fl.addView(chip(ltr(t), state.aperture == a, () -> { state.aperture = a; commit(true); buildPanel(); }));
        }
        panel.addView(hscroll(fl));
        panel.addView(section("مكوّنات الدمج الاحترافي"));
        slider(panel, "السينمائي", 0, 100, Math.round(state.fusionCinematic * 100), 80, "٪", (v, d) -> { state.fusionCinematic = v / 100f; if (d) commit(true); });
        slider(panel, "الدراماتيكي", 0, 100, Math.round(state.fusionDramatic * 100), 60, "٪", (v, d) -> { state.fusionDramatic = v / 100f; if (d) commit(true); });
        slider(panel, "تحسين DSLR", 0, 100, Math.round(state.fusionDslr * 100), 70, "٪", (v, d) -> { state.fusionDslr = v / 100f; if (d) commit(true); });
    }

    private String isolationText() {
        if (pipe == null || pipe.isolation == null) return "";
        switch (pipe.isolation) {
            case "on": return "العزل بالعمق مفعّل (" + ltr(state.focal + "mm f/" + (state.aperture == 4f ? "4" : String.valueOf(state.aperture))) + ").";
            case "off-no-subject": return "لا يوجد موضوع واضح — المس جسمًا لتفعيل العزل.";
            case "off-no-depth": return "نموذج العمق غير متاح — العزل معطّل.";
            default: return "";
        }
    }

    private void onPhotoTap(float u, float v) {
        if (!"lens".equals(tool)) return;
        if (maps == null || !maps.hasDepth()) { toast("نموذج العمق غير متاح — لا يمكن العزل"); return; }
        if (!stackUsesLens()) { toast("أضف طبقة DSLR أو بورتريه أو الدمج أولًا"); return; }
        state.focus = new float[]{u, v};
        if (state.depthBlur < 0.05f) state.depthBlur = 0.5f;
        flashFocus = true;
        commit(true);
    }

    // ---------------------------------------------------------------- import

    private void buildImport() {
        panel.addView(note("الصيغ المدعومة: " + ltr(".cube  .3dl  .json  .zip") + " — تُقرأ كبيانات فقط ولا يُنفَّذ أي محتوى بداخلها."));
        Button imp = button("استيراد ملفات فلاتر", true);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_IMPORT);
        });
        panel.addView(imp);
        if (filters == null) return;
        List<FilterStore.Item> items = filters.list();
        if (items.isEmpty()) panel.addView(note("لا توجد فلاتر مستوردة أو شخصية بعد."));
        for (FilterStore.Item it : items) {
            LinearLayout r = hrow();
            r.setBackground(rounded(CARD, 10));
            r.setPadding(dp(8), dp(2), dp(4), dp(2));
            TextView t = text(it.name + "\n" + (it.kind.equals("lut") ? "LUT" : "فلتر مركّب") + " • " + it.category, 12, Color.WHITE);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(iconButton("✓", "تطبيق", v -> {
                if (previewPx == null) { toast("افتح صورة أولًا"); return; }
                FilterCard c = it.kind.equals("lut") ? new FilterCard("lut:" + it.id, it.name, false) : new FilterCard("preset:" + it.id, it.name, true);
                if (!c.preset) addMode = true;
                applyFilter(c);
            }));
            r.addView(iconButton("✎", "إعادة تسمية", v -> prompt("الاسم", it.name, n -> { try { filters.rename(it.id, n); } catch (IOException ignored) {} thumbs.clear(); buildPanel(); })));
            r.addView(iconButton("⌂", "تغيير التصنيف", v -> prompt("التصنيف", it.category, n -> { try { filters.setCategory(it.id, n); } catch (IOException ignored) {} buildPanel(); })));
            r.addView(iconButton("✕", "حذف", v -> confirm("حذف «" + it.name + "»؟", () -> {
                try { filters.delete(it.id); } catch (IOException ignored) {}
                importedCache.remove(it.id);
                thumbs.remove("lut:" + it.id);
                buildPanel();
                if (usesImported(it.id)) toast("الطبقة التي تستخدمه ستُتجاهل حتى تحذفها أو تستبدلها");
                commit(true);
            })));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            panel.addView(r, lp);
        }
    }

    private boolean usesImported(String id) {
        for (EditState.Layer l : state.layers) if (l.filter.equals("lut:" + id)) return true;
        return false;
    }

    private void importFiles(List<Uri> uris) {
        setBusy(true);
        io.execute(() -> {
            StringBuilder ok = new StringBuilder(), bad = new StringBuilder();
            int added = 0;
            for (Uri u : uris) {
                String name = displayName(u);
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) { bad.append(name).append(": تعذّر الفتح\n"); continue; }
                    FilterStore.Report r = filters.importFile(name, in);
                    for (FilterStore.Item it : r.added) { ok.append("✓ ").append(it.name).append('\n'); added++; }
                    for (String s : r.rejected) bad.append("✗ ").append(s).append('\n');
                } catch (Exception e) {
                    bad.append("✗ ").append(name).append(": ").append(e.getMessage()).append('\n');
                }
            }
            final int n = added;
            ui.post(() -> {
                setBusy(false);
                new AlertDialog.Builder(this)
                        .setTitle(n > 0 ? "تم استيراد " + n + " فلتر" : "لم يُستورد شيء")
                        .setMessage((ok.length() > 0 ? ok.toString() : "") + (bad.length() > 0 ? "\nمرفوض:\n" + bad : ""))
                        .setPositiveButton("حسنًا", null).show();
                thumbs.clear();
                buildPanel();
            });
        });
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String s = c.getString(0); if (s != null) return s; }
        } catch (Exception ignored) {
        }
        String p = u.getLastPathSegment();
        return p == null ? "file" : p;
    }

    // ---------------------------------------------------------------- projects

    private void buildProjects() {
        LinearLayout acts = hrow();
        if (previewPx != null) {
            Button save = button(project == null ? "حفظ كمشروع" : "المشروع محفوظ تلقائيًا ✓", project == null);
            save.setOnClickListener(v -> { if (project == null) saveProject(); });
            acts.addView(save);
            Button copy = button("نسخ الإعدادات", false);
            copy.setOnClickListener(v -> {
                prefs.edit().putString("copied", FilterStore.lookOnly(state).toJson()).apply();
                toast("نُسخت الطبقات وقيم الضبط — افتح صورة أخرى ثم «لصق الإعدادات»");
                buildPanel();
            });
            acts.addView(copy);
            if (prefs.contains("copied")) {
                Button paste = button("لصق الإعدادات", false);
                paste.setOnClickListener(v -> {
                    try {
                        EditState c = EditState.fromJson(prefs.getString("copied", "{}"));
                        state.layers.clear();
                        for (EditState.Layer l : c.layers) state.layers.add(l.copy());
                        state.adjust.putAll(c.adjust);
                        selLayer = 0;
                        commit(true);
                        toast("طُبّقت الإعدادات المنسوخة");
                    } catch (Exception e) { toast("تعذّر اللصق"); }
                });
                acts.addView(paste);
            }
            Button def = button("الوضع الافتراضي", false);
            def.setOnClickListener(v -> confirm("إعادة كل التعديلات (الطبقات، الضبط، القص، الإطار، العدسة) إلى الافتراضي؟", () -> {
                state = EditState.defaults();
                selLayer = 0;
                commit(true);
                showResult();
                buildPanel();
            }));
            acts.addView(def);
        }
        Button open = button("فتح صورة جديدة", previewPx == null);
        open.setOnClickListener(v -> pickImage());
        acts.addView(open);
        panel.addView(hscroll(acts));
        if (projects == null) return;
        List<ProjectStore.Project> list = projects.list();
        panel.addView(section("المشاريع (" + list.size() + ")"));
        if (list.isEmpty()) panel.addView(note("لا توجد مشاريع محفوظة. المشروع يحفظ الأصل وكل التعديلات لتعيد فتحها لاحقًا."));
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (ProjectStore.Project p : list) {
            LinearLayout r = hrow();
            r.setBackground(rounded(project != null && project.id.equals(p.id) ? Color.rgb(28, 50, 58) : CARD, 10));
            r.setPadding(dp(4), dp(4), dp(4), dp(4));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (p.thumb().isFile()) iv.setImageBitmap(BitmapFactory.decodeFile(p.thumb().getPath()));
            r.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView t = text(p.name + "\n" + ltr(df.format(new Date(p.modified))), 12, Color.WHITE);
            t.setPadding(dp(8), 0, dp(4), 0);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(iconButton("↗", "فتح", v -> openProject(p)));
            r.addView(iconButton("✕", "حذف", v -> confirm("حذف المشروع «" + p.name + "»؟ (لا يمس الصورة الأصلية في المعرض)", () -> {
                if (project != null && project.id.equals(p.id)) project = null;
                projects.delete(p);
                buildPanel();
            })));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            panel.addView(r, lp);
        }
    }

    private void saveProject() {
        if (sourceUri == null || projects == null) return;
        prompt("اسم المشروع", "مشروع " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()), name -> {
            final EditState st = state.copy();
            final Uri src = sourceUri;
            final Bitmap thumb = photo.shown();
            setBusy(true);
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(src)) {
                    if (in == null) throw new IOException("open");
                    ProjectStore.Project p = projects.create(in, name, st);
                    writeThumb(p, thumb);
                    ui.post(() -> {
                        project = p;
                        sourceUri = Uri.fromFile(p.original());
                        setBusy(false);
                        toast("حُفظ المشروع — التعديلات اللاحقة تُحفظ تلقائيًا");
                        buildPanel();
                    });
                } catch (Exception e) {
                    ui.post(() -> { setBusy(false); toast("تعذّر حفظ المشروع"); });
                }
            });
        });
    }

    private void writeThumb(ProjectStore.Project p, Bitmap b) {
        if (b == null) return;
        try (FileOutputStream os = new FileOutputStream(p.thumb())) {
            float s = 320f / Math.max(b.getWidth(), b.getHeight());
            Bitmap t = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
            t.compress(Bitmap.CompressFormat.JPEG, 85, os);
        } catch (Exception ignored) {
        }
    }

    private void autosave() {
        if (project == null || projects == null) return;
        final ProjectStore.Project p = project;
        final EditState st = state.copy();
        io.execute(() -> { try { projects.save(p, st); } catch (IOException ignored) {} });
    }

    private void openProject(ProjectStore.Project p) {
        try {
            EditState st = projects.loadEdit(p);
            project = p;
            openTool(null);
            loadImage(Uri.fromFile(p.original()), st);
        } catch (Exception e) {
            toast("تعذّر فتح المشروع");
        }
    }

    // ---------------------------------------------------------------- export

    private void buildExport() {
        if (previewPx == null) { panel.addView(note("افتح صورة أولًا.")); return; }
        String fmt = prefs.getString("fmt", "jpeg");
        int q = prefs.getInt("quality", 95);
        LinearLayout fr = hrow();
        for (String f : new String[]{"jpeg", "png", "webp"})
            fr.addView(chip(ltr(f.toUpperCase(Locale.US)), fmt.equals(f), () -> { prefs.edit().putString("fmt", f).apply(); buildPanel(); }));
        panel.addView(hscroll(fr));
        if (!fmt.equals("png")) {
            slider(panel, fmt.equals("webp") ? "الجودة (100 = بلا فقد)" : "الجودة", 60, 100, q, 95, "",
                    (v, done) -> { if (done) prefs.edit().putInt("quality", v).apply(); });
        } else {
            panel.addView(note("PNG بلا فقد."));
        }
        Geometry g = new Geometry(state, fullW, fullH, orient, true);
        panel.addView(note("المقاس الناتج: " + ltr(g.outW + " × " + g.outH) + " بكسل — بالدقة الأصلية دون تصغير. يُحفظ دائمًا كنسخة جديدة."));
        String tree = prefs.getString("tree", null);
        panel.addView(note("المجلد: " + (tree == null ? "الصور/Majdi Cinematic AI" : "مجلد مختار")));
        LinearLayout fo = hrow();
        fo.addView(chip("اختيار مجلد", false, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_TREE);
        }));
        if (tree != null) fo.addView(chip("المجلد الافتراضي", false, () -> { prefs.edit().remove("tree").apply(); buildPanel(); }));
        panel.addView(hscroll(fo));
        LinearLayout acts = hrow();
        Button ex = button("تصدير", true);
        ex.setOnClickListener(v -> startExport(false));
        acts.addView(ex);
        Button sh = button("تصدير ومشاركة", false);
        sh.setOnClickListener(v -> startExport(true));
        acts.addView(sh);
        panel.addView(acts);
    }

    // =================================================================== edit state

    /** Records the current state in the undo history (if changed) and re-renders. */
    private void commit(boolean colorChanged) {
        if (history.push(state)) autosave();
        refreshChrome();
        if (colorChanged) { requestRender(); invalidateThumbs(); } else showResult();
    }

    private void undo() {
        if (!history.canUndo()) return;
        state = history.undo();
        afterHistory();
    }

    private void redo() {
        if (!history.canRedo()) return;
        state = history.redo();
        afterHistory();
    }

    private void afterHistory() {
        invalidateThumbs();
        autosave();
        refreshChrome();
        buildPanel();
        requestRender();
    }

    // =================================================================== rendering

    private String colorKey(EditState s) {
        Map<String, Object> m = s.toMap();
        return Json.write(m.get("layers")) + Json.write(m.get("adjust")) + Json.write(m.get("lens")) + Json.write(m.get("film"));
    }

    private void renderSoon() {
        if (pendingRender != null) ui.removeCallbacks(pendingRender);
        pendingRender = this::requestRender;
        ui.postDelayed(pendingRender, 140);
    }

    private void showResultSoon() {
        if (pendingRender != null) ui.removeCallbacks(pendingRender);
        pendingRender = this::showResult;
        ui.postDelayed(pendingRender, 60);
    }

    private void requestRender() {
        if (previewPx == null) return;
        final EditState st = state.copy();
        final String key = colorKey(st);
        if (key.equals(coloredKey) && colored != null && !flashFocus) { showResult(); return; }
        final int token = ++renderToken;
        final int[] src = previewPx;
        final int w = pw, h = ph;
        final SceneMaps sm = maps;
        final float[][] fa = faces;
        final boolean flash = flashFocus;
        flashFocus = false;
        setBusy(true);
        worker.execute(() -> {
            if (token != renderToken) return;
            try {
                Pipeline p = Pipeline.build(src, w, h, st, sm, fa, this::lut, this::importedLut, cache);
                if (token != renderToken) return;
                int[] out = p.renderPreview(src, w, h);
                ui.post(() -> {
                    if (token != renderToken) return;
                    pipe = p;
                    colored = out;
                    coloredKey = key;
                    setBusy(false);
                    showResult();
                    if (flash && p.focusMask != null && "lens".equals(tool)) {
                        Bitmap base = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
                        Bitmap ov = ImageEngine.focusOverlay(base, p.focusMask);
                        if (ov != null) {
                            ui.post(() -> { viewToken++; photo.setImages(ov, previewOriginal); });
                            ui.postDelayed(this::showResult, 1100);
                        }
                    }
                    if ("lens".equals(tool)) buildPanel();
                });
            } catch (Throwable e) {
                ui.post(() -> { if (token == renderToken) { setBusy(false); setStatus("تعذّرت المعالجة: " + e.getClass().getSimpleName()); } });
            }
        });
    }

    /**
     * Shows the colour result with the geometry/frame view that fits the open tool. The geometry
     * pass and bitmap creation run off the UI thread so zooming and panning stay smooth.
     */
    private void showResult() {
        if (colored == null || previewPx == null) return;
        final int token = ++viewToken;
        final int[] col = colored, src = previewPx;
        final int w = pw, h = ph;
        final String t = tool;
        final EditState st = state.copy();
        final Bitmap origBase = previewOriginal;
        viewWorker.execute(() -> {
            if (token != viewToken) return;
            try {
                Bitmap shown, orig;
                if ("lens".equals(t)) {
                    shown = Bitmap.createBitmap(col, w, h, Bitmap.Config.ARGB_8888);
                    orig = origBase;
                } else {
                    Geometry g = new Geometry(st, w, h, !"crop".equals(t));
                    if (g.identity()) {
                        shown = Bitmap.createBitmap(col, w, h, Bitmap.Config.ARGB_8888);
                        orig = origBase;
                    } else {
                        shown = Bitmap.createBitmap(g.render(col), g.outW, g.outH, Bitmap.Config.ARGB_8888);
                        orig = Bitmap.createBitmap(g.render(src), g.outW, g.outH, Bitmap.Config.ARGB_8888);
                    }
                }
                ui.post(() -> {
                    if (token != viewToken) return;
                    photo.setImages(shown, orig);
                    if ("crop".equals(tool)) photo.setCropMode(true, new RectF(state.cropL, state.cropT, state.cropR, state.cropB), aspectValue(state.aspect));
                    else photo.setCropMode(false, null, 0);
                    setStatus(statusLine());
                });
            } catch (OutOfMemoryError e) {
                ui.post(() -> setStatus("الذاكرة لا تكفي للعرض"));
            }
        });
    }

    private String statusLine() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (EditState.Layer l : state.layers) if (l.enabled) n++;
        if (n == 0) b.append("بدون فلاتر");
        else {
            int k = 0;
            for (EditState.Layer l : state.layers) {
                if (!l.enabled) continue;
                if (k++ > 0) b.append(" + ");
                FilterLibrary.Entry e = FilterLibrary.find(l.filter);
                b.append(e != null ? e.nameAr : FilterLibrary.displayName(l.filter, filters)).append(' ').append(ltr(Math.round(l.strength * 100) + "%"));
            }
        }
        if (state.hasAdjust()) b.append(" • ضبط يدوي");
        if (state.hasGeometry()) b.append(" • قص/تدوير");
        if (!state.frame.equals("none")) b.append(" • إطار");
        if (pipe != null && pipe.reason != null) b.append("\n").append(pipe.reason);
        else if (pipe != null && pipe.isolation != null && !pipe.isolation.equals("off")) b.append("\n").append(isolationText());
        return b.toString();
    }

    private void setBusy(boolean b) { busy.setVisibility(b ? View.VISIBLE : View.INVISIBLE); }

    private Lut3D lut(String name) throws IOException {
        synchronized (lutCache) {
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
    }

    private Lut3D importedLut(String id) throws IOException {
        synchronized (importedCache) {
            Lut3D l = importedCache.get(id);
            if (l == null && filters != null) {
                l = filters.loadLut(id);
                if (l != null) importedCache.put(id, l);
            }
            return l;
        }
    }

    // =================================================================== image loading

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) uri = intent.getData();
        if (uri != null) { project = null; loadImage(uri, null); }
    }

    private void pickImage() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        else { i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); }
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
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK && data.getData() != null) {
            project = null;
            loadImage(data.getData(), null);
        } else if (requestCode == REQ_IMPORT) {
            List<Uri> list = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) list.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) list.add(data.getData());
            if (!list.isEmpty()) importFiles(list);
        } else if (requestCode == REQ_TREE && data.getData() != null) {
            Uri t = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("tree", t.toString()).apply();
            buildPanel();
        }
    }

    /** Opens a photo (new edit when initial == null, or a project's saved edit). */
    private void loadImage(Uri uri, EditState initial) {
        setStatus("جارٍ فتح الصورة…");
        setBusy(true);
        final int token = ++renderToken;
        thumbToken++;
        worker.execute(() -> {
            try {
                int o = readOrientation(uri);
                int[] dims = bounds(uri);
                Bitmap bmp = decode(uri, PREVIEW_MAX, o);
                List<RectF> f = detectFaces(bmp);
                int[] px = ImageEngine.pixels(bmp);
                int w = bmp.getWidth(), h = bmp.getHeight();
                float[][] fa = ImageEngine.faces(f);
                ui.post(() -> setStatus("تحليل العمق وعزل الموضوع بنموذجين محليين…"));
                SceneMaps sm = null;
                String noteTxt = null;
                try {
                    SceneAnalyzer an = models.analyzer();
                    if (an != null) sm = an.analyze(px, w, h, fa);
                    else noteTxt = "نماذج العمق غير متاحة — العزل معطّل";
                } catch (Throwable e) {
                    noteTxt = "تعذّر تشغيل نموذج العمق — العزل معطّل";
                }
                final SceneMaps fsm = sm;
                final String fn = noteTxt;
                ui.post(() -> {
                    if (token != renderToken) return;
                    sourceUri = uri;
                    orient = o;
                    fullW = dims[0];
                    fullH = dims[1];
                    pw = w; ph = h;
                    previewPx = px;
                    previewOriginal = bmp;
                    faces = fa;
                    maps = fsm;
                    colored = null;
                    coloredKey = null;
                    pipe = null;
                    thumbs.clear();
                    state = initial != null ? initial : EditState.defaults();
                    history = new History(state);
                    selLayer = Math.max(0, state.layers.size() - 1);
                    photo.resetZoom();
                    refreshChrome();
                    buildPanel();
                    setBusy(false);
                    if (fn != null) toast(fn);
                    requestRender();
                    if ("filters".equals(tool)) makeThumbs();
                });
            } catch (Throwable t) {
                ui.post(() -> { setBusy(false); setStatus("تعذّر فتح الصورة"); });
            }
        });
    }

    private int[] bounds(Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("bad image");
        return new int[]{o.outWidth, o.outHeight};
    }

    private Bitmap decode(Uri uri, int maxSide, int orientation) throws IOException {
        int[] d0 = bounds(uri);
        int sample = 1;
        while (Math.max(d0[0], d0[1]) / (sample * 2) >= maxSide) sample *= 2;
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
        Matrix m = orientationMatrix(orientation);
        if (m != null) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        if (!b.isMutable() || b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
            if (c != null) b = c;
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

    // =================================================================== export

    private static final class TooLargeException extends Exception {
        final boolean geometry;
        TooLargeException(boolean geometry) { this.geometry = geometry; }
    }

    private void startExport(boolean thenShare) {
        if (sourceUri == null) return;
        final String fmt = prefs.getString("fmt", "jpeg");
        final int quality = prefs.getInt("quality", 95);
        final EditState st = state.copy();
        final Uri src = sourceUri;
        final String tree = prefs.getString("tree", null);
        exportBar.setProgress(0);
        exportBar.setVisibility(View.VISIBLE);
        setStatus("جارٍ التصدير بالدقة الأصلية…");
        setBusy(true);
        worker.execute(() -> {
            try {
                Pipeline p = Pipeline.build(previewPx, pw, ph, st, maps, faces, this::lut, this::importedLut, cache);
                Uri out = exportFull(src, st, p, fmt, quality, tree);
                ui.post(() -> {
                    exportBar.setVisibility(View.GONE);
                    setBusy(false);
                    setStatus("حُفظت نسخة جديدة بالدقة الأصلية ✓");
                    toast("تم التصدير");
                    if (thenShare) share(out, mimeOf(fmt));
                });
            } catch (TooLargeException e) {
                ui.post(() -> {
                    exportBar.setVisibility(View.GONE);
                    setBusy(false);
                    setStatus(statusLine());
                    String msg = e.geometry
                            ? "ذاكرة الهاتف لا تكفي لتطبيق القص/التدوير/الإطار على الصورة بدقتها الأصلية. لن تُصغَّر الصورة دون موافقتك."
                            : "صيغة " + fmt.toUpperCase(Locale.US) + " تحتاج الصورة كاملة في الذاكرة ولا تكفي الذاكرة لهذه الدقة. JPEG يُكتب على أجزاء ويحفظ الدقة الأصلية.";
                    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("الصورة كبيرة").setMessage(msg).setNegativeButton("إلغاء", null);
                    if (!e.geometry && !fmt.equals("jpeg"))
                        b.setPositiveButton("حفظ JPEG بالدقة الأصلية", (d, w) -> { prefs.edit().putString("fmt", "jpeg").apply(); startExport(thenShare); });
                    b.show();
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    exportBar.setVisibility(View.GONE);
                    setBusy(false);
                    setStatus("تعذّر التصدير: " + e.getClass().getSimpleName());
                });
            }
        });
    }

    private static String mimeOf(String fmt) {
        return fmt.equals("jpeg") ? "image/jpeg" : (fmt.equals("png") ? "image/png" : "image/webp");
    }

    private void progress(int pct) {
        ui.post(() -> { exportBar.setProgress(pct); setStatus("جارٍ التصدير بالدقة الأصلية… " + pct + "٪"); });
    }

    /**
     * Full-resolution export, using exactly the plan shown in the preview:
     *  - colour: all cores grade strips of the original (with overlap) in parallel, written in order;
     *  - no geometry/frame + JPEG: streamed straight to the file (any resolution, EXIF orientation kept);
     *  - otherwise the graded image is held once in memory and geometry/frame are applied row by row.
     */
    @SuppressWarnings("deprecation")
    private Uri exportFull(Uri src, EditState st, Pipeline p, String fmt, int quality, String tree) throws Exception {
        int o = readOrientation(src);
        BitmapRegionDecoder dec = null;
        try (InputStream in = getContentResolver().openInputStream(src)) {
            if (in != null) dec = BitmapRegionDecoder.newInstance(in, false);
        } catch (Throwable ignored) {
        }
        Bitmap whole = null;
        int W, H;
        if (dec != null) {
            W = dec.getWidth();
            H = dec.getHeight();
            try {   // noise measured at full resolution so noise reduction matches the saved pixels
                int cw = Math.min(W, 1024), ch = Math.min(H, 1024);
                Bitmap crop = dec.decodeRegion(new Rect((W - cw) / 2, (H - ch) / 2, (W - cw) / 2 + cw, (H - ch) / 2 + ch), null);
                if (crop != null && !p.stages.isEmpty()) {
                    int[] cp = new int[cw * ch];
                    crop.getPixels(cp, 0, cw, 0, 0, cw, ch);
                    p.stages.get(0).fullNoiseSigma = SceneStats.measure(cp, cw, ch).noiseSigma;
                    crop.recycle();
                }
            } catch (Throwable ignored) {
            }
        } else {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(src)) {
                whole = BitmapFactory.decodeStream(in, null, d);
            } catch (OutOfMemoryError e) {
                throw new TooLargeException(false);
            }
            if (whole == null) throw new IOException("decode failed");
            W = whole.getWidth();
            H = whole.getHeight();
        }
        final BitmapRegionDecoder fdec = dec;
        final Bitmap fwhole = whole;
        final int fW = W;
        Pipeline.RowSource source = (ys, ye, dst) -> {
            if (fdec != null) {
                Bitmap region;
                synchronized (fdec) {
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    region = fdec.decodeRegion(new Rect(0, ys, fW, ye), op);
                }
                if (region == null) throw new IOException("region decode failed");
                region.getPixels(dst, 0, fW, 0, 0, fW, ye - ys);
                region.recycle();
            } else {
                synchronized (fwhole) { fwhole.getPixels(dst, 0, fW, 0, ys, fW, ye - ys); }
            }
        };

        Geometry g = new Geometry(st, W, H, o, true);
        boolean jpeg = fmt.equals("jpeg");
        String ext = jpeg ? "jpg" : fmt;
        String name = "Majdi_Studio_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "." + ext;
        String mime = mimeOf(fmt);

        int[] col = null;
        Bitmap outBmp = null;
        boolean streamed = jpeg && g.identity();
        if (!streamed) {
            try {
                col = new int[W * H];
                if (!jpeg) outBmp = Bitmap.createBitmap(g.outW, g.outH, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                col = null;
                if (dec != null) dec.recycle();
                if (whole != null) whole.recycle();
                throw new TooLargeException(jpeg);
            }
        }

        Uri uri;
        ContentValues pending = null;
        if (tree != null) {
            Uri treeUri = Uri.parse(tree);
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            uri = DocumentsContract.createDocument(getContentResolver(), dir, mime, name);
            if (uri == null) throw new IOException("cannot create file in folder");
        } else {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Majdi Cinematic AI");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            pending = v;
        }

        boolean ok = false;
        try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
            if (raw == null) throw new IOException("open failed");
            OutputStream os = new BufferedOutputStream(raw, 1 << 20);
            final int fH = H;
            if (streamed) {
                JpegStreamWriter jw = new JpegStreamWriter(os, W, H, quality, o);
                p.renderParallel(W, H, o, 128, source, (y0, y1, rows) -> { jw.writeRows(rows, 0, y1 - y0); progress((int) (100L * y1 / fH)); });
                jw.finish();
            } else {
                final int[] fcol = col;
                p.renderParallel(W, H, o, 128, source, (y0, y1, rows) -> {
                    System.arraycopy(rows, 0, fcol, y0 * fW, rows.length);
                    progress((int) (85L * y1 / fH));
                });
                if (dec != null) { dec.recycle(); dec = null; }
                if (whole != null) { whole.recycle(); whole = null; }
                int strip = 64;
                int[] buf = new int[g.outW * strip];
                JpegStreamWriter jw = jpeg ? new JpegStreamWriter(os, g.outW, g.outH, quality, 1) : null;
                for (int y0 = 0; y0 < g.outH; y0 += strip) {
                    int y1 = Math.min(g.outH, y0 + strip);
                    g.renderRows(col, y0, y1, buf);
                    if (jpeg) jw.writeRows(buf, 0, y1 - y0);
                    else outBmp.setPixels(buf, 0, g.outW, 0, y0, g.outW, y1 - y0);
                    progress(85 + (int) (10L * y1 / g.outH));
                }
                col = null;
                if (jpeg) jw.finish();
                else {
                    Bitmap.CompressFormat cf;
                    int q = quality;
                    if (fmt.equals("png")) { cf = Bitmap.CompressFormat.PNG; q = 100; }
                    else if (Build.VERSION.SDK_INT >= 30) cf = quality >= 100 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP_LOSSY;
                    else cf = Bitmap.CompressFormat.WEBP;
                    if (!outBmp.compress(cf, q, os)) throw new IOException("encode failed");
                    outBmp.recycle();
                }
            }
            os.flush();
            ok = true;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
            if (!ok) {
                try {
                    if (tree != null) DocumentsContract.deleteDocument(getContentResolver(), uri);
                    else getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
        }
        if (pending != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        return uri;
    }

    private void share(Uri uri, String mime) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "مشاركة الصورة"));
    }

    // =================================================================== dialogs

    interface TextResult { void on(String s); }

    private void prompt(String title, String def, TextResult r) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle(title).setView(e)
                .setPositiveButton("حفظ", (d, w) -> { String s = e.getText().toString().trim(); if (!s.isEmpty()) r.on(s); })
                .setNegativeButton("إلغاء", null).show();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("نعم", (d, w) -> yes.run()).setNegativeButton("إلغاء", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        thumbWorker.shutdownNow();
        viewWorker.shutdownNow();
        io.shutdown();
    }
}
