package com.majdi.cinematicai;

/**
 * AI Auto Director: reads the scene analysis (subject matte and depth from the networks,
 * faces, exposure statistics) and composes the treatment that suits the photo.
 */
final class AutoDirector {

    static final class Decision {
        final Style style;
        final String reason;     // shown to the user (Arabic)
        final String scene;      // short scene label for reports
        Decision(Style s, String reason, String scene) { this.style = s; this.reason = reason; this.scene = scene; }
    }

    static Decision decide(ToneModel tone, SceneMaps maps, int faceCount) {
        float night = ColorMath.smooth(-4.0f, -6.0f, tone.p50);
        float dr = tone.p99 - Math.max(tone.p01, -9f);
        boolean subject = maps != null && maps.hasSubject;
        float sep = maps != null ? maps.separation : 0f;
        float area = maps != null ? maps.subjectArea : 0f;
        boolean portrait = (subject && sep > 0.14f && area > 0.03f && area < 0.70f) || (faceCount > 0 && sep > 0.10f);

        if (portrait) {
            // lens isolation + gentle film colour; night portraits get the night palette
            Style s = Style.of("dslr");
            s.key = "auto";
            s.bokehRadius = 0.022f;
            s.lut = night > 0.5f ? "night_neon" : "teal_orange_film";
            s.lutAmt = 0.55f;
            s.halation = night > 0.5f ? 0.45f : 0.25f;
            s.grain = 0.008f;
            s.vig = 0.10f;
            s.skinRefine = 0.3f;
            String r = night > 0.5f ? "المخرج الذكي: بورتريه ليلي — عزل بالعمق ولون ليلي سينمائي"
                                    : "المخرج الذكي: بورتريه — عزل بالعمق ولون سينمائي ناعم";
            return new Decision(s, r, night > 0.5f ? "night portrait" : "portrait");
        }
        if (night > 0.5f) {
            Style s = Style.of("cinematic");
            s.key = "auto";
            s.lut = "night_neon";
            s.midBoost = 1.35f;
            s.depthDodge = 0.4f;
            s.halation = 0.7f;
            return new Decision(s, "المخرج الذكي: مشهد ليلي — تباين محلي ولون ليلي مع توهج الأضواء", "night");
        }
        if (dr > 8.5f || (maps != null && maps.depthSpread > 0.55f && !subject)) {
            Style s = Style.of("dramatic");
            s.key = "auto";
            s.midBoost = 1.55f;
            s.lutAmt = 0.35f;
            return new Decision(s, "المخرج الذكي: مشهد بعمق ومدى إضاءة واسع — معالجة درامية متوازنة", "deep scene");
        }
        Style s = Style.of("cinematic");
        s.key = "auto";
        s.lut = dr < 5.5f ? "warm_print" : "teal_orange_film";
        return new Decision(s, "المخرج الذكي: مشهد عام — محاكاة فيلم سينمائي", "general");
    }
}
