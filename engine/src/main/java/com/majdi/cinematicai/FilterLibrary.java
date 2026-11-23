package com.majdi.cinematicai;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalogue of built-in looks, grouped by style. Each entry is a genuinely different processing
 * recipe (tone curve, local contrast, colour separation, film stock, texture, lens), not a
 * renamed parameter set. Imported LUTs ("lut:<id>") and personal presets join at runtime.
 */
final class FilterLibrary {

    static final class Entry {
        final String id, name, nameAr, category;
        Entry(String id, String name, String nameAr, String category) {
            this.id = id; this.name = name; this.nameAr = nameAr; this.category = category;
        }
    }

    static final String[] CATEGORIES = {"سينمائي", "دراماتيكي", "احترافي", "طبيعي", "دمج", "مستوردة", "شخصية"};

    /** Default strength when a look is first applied. */
    static float defaultStrength(String id) {
        if (id.equals("fusion")) return 0.65f;
        if (id.equals("noir") || id.startsWith("lut:")) return 1f;
        return 0.8f;
    }

    /** Display name for any layer id (built-in, imported, or missing). */
    static String displayName(String id, FilterStore store) {
        Entry e = find(id);
        if (e != null) return e.nameAr + " — " + e.name;
        if (id.startsWith("lut:") && store != null) {
            FilterStore.Item it = store.get(id.substring(4));
            return it != null ? it.name + " (LUT)" : "LUT محذوف";
        }
        return id;
    }

    static final List<Entry> BUILT_IN = new ArrayList<>();
    static {
        BUILT_IN.add(new Entry("fusion", "Cinematic Fusion Pro", "دمج احترافي", "دمج"));
        BUILT_IN.add(new Entry("cinematic:hollywood", "Hollywood Cinematic", "هوليوود", "سينمائي"));
        BUILT_IN.add(new Entry("cinematic:moody", "Moody Film", "فيلم داكن", "سينمائي"));
        BUILT_IN.add(new Entry("cinematic:night", "Night Cinema", "سينما ليلية", "سينمائي"));
        BUILT_IN.add(new Entry("cinematic:film", "Film Look", "مظهر الفيلم", "سينمائي"));
        BUILT_IN.add(new Entry("cinematic:natural_cinematic", "Natural Cinematic", "سينمائي طبيعي", "سينمائي"));
        BUILT_IN.add(new Entry("dramatic", "Dramatic", "دراماتيكي", "دراماتيكي"));
        BUILT_IN.add(new Entry("noir", "Dramatic Noir", "أبيض وأسود درامي", "دراماتيكي"));
        BUILT_IN.add(new Entry("dslr", "DSLR", "احترافي DSLR", "احترافي"));
        BUILT_IN.add(new Entry("portrait", "Portrait Pro", "بورتريه احترافي", "احترافي"));
        BUILT_IN.add(new Entry("natural", "Natural Pro", "طبيعي احترافي", "طبيعي"));
        BUILT_IN.add(new Entry("auto", "AI Auto", "تلقائي ذكي", "طبيعي"));
    }

    static Entry find(String id) {
        for (Entry e : BUILT_IN) if (e.id.equals(id)) return e;
        return null;
    }

    /** Render options for one stack layer. Imported LUTs use a neutral pipeline + the LUT. */
    static RenderOptions optionsFor(EditState.Layer layer, EditState st, Lut3D importedLut) {
        RenderOptions o = new RenderOptions();
        o.strength = layer.strength;
        o.focus = st.focus;
        o.focusMode = st.focusMode;
        o.focusRange = st.focusRange;
        o.objectGrow = st.objectGrow;
        o.focal = st.focal;
        o.aperture = st.aperture;
        o.cinematic = st.fusionCinematic;
        o.dramatic = st.fusionDramatic;
        o.dslr = st.fusionDslr;
        o.depthBlur = st.depthBlur;
        String id = layer.filter;
        if (id.startsWith("lut:")) {
            Style s = neutral();
            s.lutAmt = 1f;
            o.style = "custom";
            o.custom = s;
            o.lutOverride = importedLut;
        } else if (id.startsWith("cinematic:")) {
            o.style = "cinematic";
            o.preset = id.substring("cinematic:".length());
        } else if (id.equals("portrait") || id.equals("noir")) {
            o.style = "custom";
            Style s = Style.of(id);
            if (s.bokeh) { s.blurAmt = 0.6f * Math.max(0.2f, st.depthBlur * 2f); }
            o.custom = s;
        } else {
            o.style = id;   // fusion, dramatic, dslr, natural, auto
        }
        return o;
    }

    /** A style that leaves the image unchanged (base for imported LUTs and the Adjust panel). */
    static Style neutral() {
        Style s = new Style();
        s.key = "neutral";
        s.manualTone = true;
        s.nr = 0f;
        s.shoulder = 1f;
        s.expo = 0f;
        s.nightKeep = 0f;
        return s;
    }

    /** Maps the 17 Adjust sliders (-100..100 / 0..100) onto engine parameters. */
    static Style adjustStyle(EditState st) {
        Style s = neutral();
        s.key = "adjust";
        s.expoComp = st.adj("exposure") / 100f * 2.0f;
        s.contrast = 1f + st.adj("contrast") / 100f * 0.5f;
        s.highComp = -st.adj("highlights") / 100f * 0.7f;
        s.shadowLift = st.adj("shadows") / 100f * 0.6f;
        s.whites = st.adj("whites") / 100f;
        s.blacks = st.adj("blacks") / 100f;
        s.temp = st.adj("temperature") / 100f;
        s.tempScale = 0.15f;
        s.tint = st.adj("tint") / 100f;
        s.sat = 1f + st.adj("saturation") / 100f;
        s.vib = st.adj("vibrance") / 100f * 0.6f;
        s.midBoost = Math.max(0.3f, 1f + st.adj("clarity") / 100f * 1.2f);
        s.detail = Math.max(0.3f, 1f + st.adj("texture") / 100f * 0.8f);
        s.dehaze = st.adj("dehaze") / 100f;
        s.sharpen = st.adj("sharpening") / 100f;
        s.nrUser = st.adj("noise") / 100f;
        s.vig = -st.adj("vignette") / 100f * 0.6f;   // Lightroom convention: negative = dark corners
        s.grain = st.adj("grain") / 100f * 0.05f;
        return s;
    }
}
