package com.majdi.cinematicai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete, non-destructive description of an edit. The original photo is never modified:
 * every render (preview or export) is computed from the original plus this state.
 * Serialized as JSON for projects, undo history, copy/paste and personal presets.
 */
final class EditState {

    /** One layer of the filter stack: a built-in look or an imported LUT, with its own strength. */
    static final class Layer {
        String filter;      // FilterLibrary id, e.g. "cinematic:hollywood", "fusion", "lut:<uuid>"
        float strength = 1f;
        boolean enabled = true;
        Layer(String f, float s) { filter = f; strength = s; }
        Layer copy() { Layer l = new Layer(filter, strength); l.enabled = enabled; return l; }
    }

    static final String[] ADJUST_KEYS = {"exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "temperature", "tint", "saturation", "vibrance", "clarity", "texture", "dehaze",
            "sharpening", "noise", "vignette", "grain"};
    /** sliders whose range is 0..100 (the others are -100..100) */
    static boolean positiveOnly(String k) {
        return k.equals("sharpening") || k.equals("noise") || k.equals("grain");
    }

    final List<Layer> layers = new ArrayList<>();
    final Map<String, Float> adjust = new LinkedHashMap<>();

    // geometry (applied after colour, in display orientation)
    int rot90;                  // 0..3 quarter turns clockwise
    boolean flipH, flipV;
    float straighten;           // degrees, -45..45
    float perspV, perspH;       // keystone, -1..1
    float cropL, cropT, cropR = 1f, cropB = 1f;   // normalized in the transformed image
    String aspect = "free";

    // frame
    String frame = "none";      // none, border, canvas, film, cinematic
    int frameColor = 0xFFFFFFFF;
    float frameThickness = 0.04f;   // fraction of the short side
    String canvasAspect = "1:1";
    float framePosX = 0.5f, framePosY = 0.5f;
    float frameMargin = 0.06f;

    // lens tool (used by DSLR / Portrait / Fusion layers)
    float[] focus;
    String focusMode = "object";
    float focusRange, objectGrow;
    int focal = 50;
    float aperture = 2.8f;
    float depthBlur = 0.5f;
    // Fusion Pro sub-strengths
    float fusionCinematic = 0.8f, fusionDramatic = 0.6f, fusionDslr = 0.7f;
    // Film Look controls (used by Film Look layers): 0..100, contrast -100..100
    float filmGrain = 30f, filmFade = 30f, filmColorFade = 25f, filmContrast = 0f;

    boolean filmDefaults() {
        return filmGrain == 30f && filmFade == 30f && filmColorFade == 25f && filmContrast == 0f;
    }

    EditState() {
        for (String k : ADJUST_KEYS) adjust.put(k, 0f);
    }

    static EditState defaults() {
        EditState s = new EditState();
        s.layers.add(new Layer("fusion", 0.65f));
        return s;
    }

    float adj(String k) {
        Float v = adjust.get(k);
        return v == null ? 0f : v;
    }

    boolean hasAdjust() {
        for (Float v : adjust.values()) if (Math.abs(v) > 0.001f) return true;
        return false;
    }

    boolean hasGeometry() {
        return rot90 != 0 || flipH || flipV || Math.abs(straighten) > 0.01f || Math.abs(perspV) > 0.001f
                || Math.abs(perspH) > 0.001f || cropL > 0.0005f || cropT > 0.0005f || cropR < 0.9995f || cropB < 0.9995f;
    }

    boolean hasFrame() {
        return !frame.equals("none");
    }

    EditState copy() {
        return fromJson(toJson());
    }

    // ------------------------------------------------------------------ JSON

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "majdi-edit");
        m.put("version", 1);
        List<Object> ls = new ArrayList<>();
        for (Layer l : layers) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("filter", l.filter);
            o.put("strength", l.strength);
            o.put("enabled", l.enabled);
            ls.add(o);
        }
        m.put("layers", ls);
        Map<String, Object> a = new LinkedHashMap<>();
        for (Map.Entry<String, Float> e : adjust.entrySet()) a.put(e.getKey(), e.getValue());
        m.put("adjust", a);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("rot90", rot90); g.put("flipH", flipH); g.put("flipV", flipV); g.put("straighten", straighten);
        g.put("perspV", perspV); g.put("perspH", perspH);
        g.put("crop", listOf(cropL, cropT, cropR, cropB)); g.put("aspect", aspect);
        m.put("geometry", g);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", frame); f.put("color", String.format("#%08X", frameColor)); f.put("thickness", frameThickness);
        f.put("canvasAspect", canvasAspect); f.put("posX", framePosX); f.put("posY", framePosY); f.put("margin", frameMargin);
        m.put("frame", f);
        Map<String, Object> lens = new LinkedHashMap<>();
        if (focus != null) lens.put("focus", listOf(focus[0], focus[1]));
        lens.put("mode", focusMode); lens.put("range", focusRange); lens.put("grow", objectGrow);
        lens.put("focal", focal); lens.put("aperture", aperture); lens.put("depthBlur", depthBlur);
        lens.put("fusionCinematic", fusionCinematic); lens.put("fusionDramatic", fusionDramatic); lens.put("fusionDslr", fusionDslr);
        m.put("lens", lens);
        Map<String, Object> film = new LinkedHashMap<>();
        film.put("grain", filmGrain); film.put("fadeBlacks", filmFade); film.put("colorFade", filmColorFade); film.put("contrast", filmContrast);
        m.put("film", film);
        return m;
    }

    String toJson() {
        return Json.write(toMap());
    }

    private static List<Object> listOf(float... v) {
        List<Object> l = new ArrayList<>();
        for (float f : v) l.add(f);
        return l;
    }

    static EditState fromJson(String json) {
        return fromMap(Json.obj(Json.parse(json)));
    }

    /** Strict import: unknown keys are ignored, every value is range-checked. */
    static EditState fromMap(Map<String, Object> m) {
        EditState s = new EditState();
        if (m.get("layers") != null) {
            List<Object> ls = Json.arr(m.get("layers"));
            if (ls.size() > 16) throw new IllegalArgumentException("too many layers");
            for (Object o : ls) {
                Map<String, Object> lm = Json.obj(o);
                String f = Json.str(lm, "filter", null, 80);
                if (f == null || !f.matches("[a-z_]+(:[A-Za-z0-9_\\-]+)?")) throw new IllegalArgumentException("bad filter id");
                Layer l = new Layer(f, Json.num(lm, "strength", 1f, 0f, 1f));
                l.enabled = Json.bool(lm, "enabled", true);
                s.layers.add(l);
            }
        }
        if (m.get("adjust") != null) {
            Map<String, Object> a = Json.obj(m.get("adjust"));
            for (String k : ADJUST_KEYS) s.adjust.put(k, Json.num(a, k, 0f, positiveOnly(k) ? 0f : -100f, 100f));
        }
        if (m.get("geometry") != null) {
            Map<String, Object> g = Json.obj(m.get("geometry"));
            s.rot90 = ((int) Json.num(g, "rot90", 0, 0, 3)) & 3;
            s.flipH = Json.bool(g, "flipH", false);
            s.flipV = Json.bool(g, "flipV", false);
            s.straighten = Json.num(g, "straighten", 0f, -45f, 45f);
            s.perspV = Json.num(g, "perspV", 0f, -1f, 1f);
            s.perspH = Json.num(g, "perspH", 0f, -1f, 1f);
            s.aspect = Json.str(g, "aspect", "free", 16);
            if (g.get("crop") != null) {
                List<Object> c = Json.arr(g.get("crop"));
                if (c.size() == 4) {
                    float l = clamp01(((Number) c.get(0)).floatValue()), t = clamp01(((Number) c.get(1)).floatValue());
                    float r = clamp01(((Number) c.get(2)).floatValue()), b = clamp01(((Number) c.get(3)).floatValue());
                    if (r - l > 0.02f && b - t > 0.02f) { s.cropL = l; s.cropT = t; s.cropR = r; s.cropB = b; }
                }
            }
        }
        if (m.get("frame") != null) {
            Map<String, Object> f = Json.obj(m.get("frame"));
            s.frame = Json.str(f, "type", "none", 16);
            if (!s.frame.matches("none|border|canvas|film|cinematic")) s.frame = "none";
            String col = Json.str(f, "color", "#FFFFFFFF", 9);
            if (col.matches("#[0-9A-Fa-f]{8}")) s.frameColor = (int) Long.parseLong(col.substring(1), 16);
            s.frameThickness = Json.num(f, "thickness", 0.04f, 0f, 0.3f);
            s.canvasAspect = Json.str(f, "canvasAspect", "1:1", 8);
            s.framePosX = Json.num(f, "posX", 0.5f, 0f, 1f);
            s.framePosY = Json.num(f, "posY", 0.5f, 0f, 1f);
            s.frameMargin = Json.num(f, "margin", 0.06f, 0f, 0.4f);
        }
        if (m.get("lens") != null) {
            Map<String, Object> l = Json.obj(m.get("lens"));
            if (l.get("focus") != null) {
                List<Object> fp = Json.arr(l.get("focus"));
                if (fp.size() == 2) s.focus = new float[]{clamp01(((Number) fp.get(0)).floatValue()), clamp01(((Number) fp.get(1)).floatValue())};
            }
            s.focusMode = Json.str(l, "mode", "object", 8).equals("point") ? "point" : "object";
            s.focusRange = Json.num(l, "range", 0f, -1f, 1f);
            s.objectGrow = Json.num(l, "grow", 0f, -1f, 1f);
            int fo = (int) Json.num(l, "focal", 50, 35, 85);
            s.focal = fo <= 42 ? 35 : (fo >= 68 ? 85 : 50);
            s.aperture = Json.num(l, "aperture", 2.8f, 1.8f, 5.6f);
            s.depthBlur = Json.num(l, "depthBlur", 0.5f, 0f, 1f);
            s.fusionCinematic = Json.num(l, "fusionCinematic", 0.8f, 0f, 1f);
            s.fusionDramatic = Json.num(l, "fusionDramatic", 0.6f, 0f, 1f);
            s.fusionDslr = Json.num(l, "fusionDslr", 0.7f, 0f, 1f);
        }
        if (m.get("film") != null) {
            Map<String, Object> f = Json.obj(m.get("film"));
            s.filmGrain = Json.num(f, "grain", 30f, 0f, 100f);
            s.filmFade = Json.num(f, "fadeBlacks", 30f, 0f, 100f);
            s.filmColorFade = Json.num(f, "colorFade", 25f, 0f, 100f);
            s.filmContrast = Json.num(f, "contrast", 0f, -100f, 100f);
        }
        return s;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
