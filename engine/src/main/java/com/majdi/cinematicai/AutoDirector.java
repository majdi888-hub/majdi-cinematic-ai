package com.majdi.cinematicai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI Auto Director. Inputs are measurements: two neural networks (Depth Anything V2 depth map,
 * ISNet subject matte), Android's face detector, and computed statistics (exposure histogram,
 * dynamic range, measured noise, white-balance estimate, colourfulness, sky heuristic).
 * The DECISION itself is rule-based on those measurements; it is not a learned model,
 * and the description says exactly which operations were chosen.
 */
final class AutoDirector {

    static final class Decision {
        final Style style;
        final String description;   // Arabic, shown in the app
        final String scene;
        Decision(Style s, String d, String scene) { style = s; description = d; this.scene = scene; }
    }

    static Decision decide(ToneModel tone, SceneStats st, SceneMaps maps, int faceCount) {
        float night = ColorMath.smooth(-4.0f, -6.0f, tone.p50);
        float dr = tone.p99 - Math.max(tone.p01, -9f);
        float noise = st != null ? st.noiseSigma : 0f;
        float colorful = st != null ? st.colorfulness : 30f;
        float sky = st != null ? st.skyFraction : 0f;
        boolean reliableSubject = maps != null && maps.hasDepth() && maps.subjectReliable();
        boolean people = faceCount > 0;
        boolean isolate = maps != null && maps.hasDepth() && (reliableSubject || (people && maps.separation > 0.08f));

        List<String> facts = new ArrayList<>();
        facts.add(night > 0.5f ? "مشهد ليلي" : (tone.p50 > -1.8f ? "مشهد مضيء" : "إضاءة متوسطة"));
        facts.add(dr > 8f ? "نطاق ديناميكي واسع" : (dr < 5f ? "نطاق ضيق" : "نطاق متوسط"));
        facts.add(noise > 2.5f ? "ضوضاء مرتفعة" : (noise > 1.2f ? "ضوضاء متوسطة" : "ضوضاء منخفضة"));
        if (people) facts.add(faceCount == 1 ? "وجه واحد" : faceCount + " وجوه");
        if (reliableSubject) facts.add("موضوع واضح بالعمق");
        else facts.add("لا يوجد موضوع منفصل");
        if (sky > 0.35f) facts.add("سماء");

        Style s = new Style();
        String scene;
        String look;
        if (isolate && (people || reliableSubject)) {
            Style.fusion(s, 0.6f, 0.45f, 0.8f, 0.6f, night > 0.5f);
            scene = night > 0.5f ? "night portrait" : "portrait";
            look = "دمج احترافي مع عزل بالعمق";
        } else if (night > 0.5f) {
            Style.fusion(s, 0.85f, 0.7f, 0.6f, 0f, true);
            scene = "night";
            look = "معالجة ليلية سينمائية دون عزل";
        } else if (dr > 8f || sky > 0.35f) {
            Style.fusion(s, 0.6f, 0.85f, 0.6f, 0f, false);
            scene = "high dynamic range";
            look = "استعادة الإضاءات وتباين موضعي";
        } else if (colorful < 18f) {
            Style.fusion(s, 0.9f, 0.5f, 0.6f, 0f, false);
            scene = "flat";
            look = "تدرج سينمائي يعطي المشهد الباهت عمقًا";
        } else {
            Style.fusion(s, 0.75f, 0.6f, 0.7f, 0f, false);
            scene = "general";
            look = "دمج احترافي متوازن";
        }
        s.key = "auto";
        s.nr = noise > 1.2f ? Math.min(1.5f, 0.6f + noise / 3f) : 0.5f;
        String ops = String.format(Locale.US, "تباين موضعي %d%% • تدرج سينمائي %d%% • تقليل ضوضاء %s • حدة %d%%%s",
                Math.round((s.midBoost - 1f) / 1.1f * 100f), Math.round(s.lutAmt / 0.85f * 100f),
                noise > 1.2f ? "مفعّل" : "خفيف", Math.round(s.sharpen / 0.75f * 100f),
                s.bokeh ? " • عزل بالعمق" : "");
        String d = "المخرج الذكي: " + String.join("، ", facts) + " ← " + look + "\n" + ops;
        return new Decision(s, d, scene);
    }
}
