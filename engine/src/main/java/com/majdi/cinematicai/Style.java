package com.majdi.cinematicai;

/** All parameters of a look, at 100% strength. */
final class Style {
    String key = "natural";
    String preset = "";
    // base processing
    float wbAmt;           // share of the measured white-balance correction applied (0..1)
    float temp;            // colour temperature shift, -1 cool .. +1 warm
    float expoComp;        // manual exposure compensation in EV (added to the adaptive gain)
    float expo;            // strength of adaptive exposure normalization
    float nr = 1f;         // noise reduction relative to the measured noise (0 = off)
    float nightKeep = 0.7f;// how strongly night scenes keep their darkness (limits brightening)
    // tone
    float shadowLift, highComp, detail = 1f, midBoost = 1f, contrast = 1f, shoulder = 0.8f, blackLift, toe;
    float[] shadowGain = {1f, 1f, 1f}, highGain = {1f, 1f, 1f};
    float sat = 1f, vib, vig, sharpen;
    float depthDodge;      // depth-shaped light: far darker, subject brighter
    // film
    String lut;            // resource name without extension, or null
    float lutAmt;
    float halation, grain, skinRefine;
    // final balancing (soft limit on chroma, display space)
    float chromaLimit = 1f;
    // lens simulation (DSLR / Fusion); applied only with reliable depth + focus
    boolean bokeh;
    int focal = 50;        // 35 / 50 / 85 (visual simulation only)
    float aperture = 2.8f; // f/1.8 .. f/5.6 (visual simulation only)
    float blurAmt = 1f;
    // manual (Adjust) controls; neutral defaults leave the image untouched
    boolean manualTone;    // tone knees not scaled by scene dynamic range
    float whites, blacks;  // -1..1 end-point adjustments
    float tint;            // -1 green .. +1 magenta
    float dehaze;          // -1 add haze .. +1 remove haze
    float nrUser;          // 0..1 explicit noise reduction, independent of measured noise
    float tempScale = 0.08f;

    static Style of(String key) {
        return of(key, "");
    }

    static Style of(String key, String preset) {
        Style p = new Style();
        p.key = key;
        switch (key) {
            case "cinematic":
                cinematic(p, preset.isEmpty() ? "hollywood" : preset);
                break;
            case "dramatic":
                // adaptive tone mapping with highlight recovery and shadow enhancement, strong
                // edge-aware local contrast, texture emphasis; restrained print colour
                p.wbAmt = 0.3f; p.expo = 0.10f;
                p.shadowLift = 0.40f; p.highComp = 0.62f; p.detail = 1.75f; p.midBoost = 3.0f;
                p.contrast = 1.30f; p.shoulder = 0.62f; p.toe = 0.003f;
                p.shadowGain = new float[]{0.96f, 0.99f, 1.06f};
                p.highGain = new float[]{1.03f, 1.00f, 0.96f};
                p.sat = 0.88f; p.vig = 0.22f; p.sharpen = 0.45f; p.nr = 1.0f;
                p.depthDodge = 0.25f;
                p.lut = "dramatic_print"; p.lutAmt = 0.6f;
                p.grain = 0.008f; p.skinRefine = 0.2f; p.chromaLimit = 0.85f;
                break;
            case "dslr":
                // clean, optically sharp rendering; lens isolation only with reliable depth + focus
                p.wbAmt = 0.4f; p.expo = 0.20f;
                p.shadowLift = 0.25f; p.highComp = 0.32f; p.detail = 1.28f; p.midBoost = 1.15f;
                p.contrast = 1.06f; p.shoulder = 0.80f; p.toe = 0.002f;
                p.sat = 1.0f; p.vib = 0.12f; p.sharpen = 0.75f; p.nr = 1.2f;
                p.skinRefine = 0.2f;
                p.bokeh = true; p.focal = 50; p.aperture = 2.8f;
                break;
            case "fusion":
                fusion(p, 0.8f, 0.6f, 0.7f, 0.5f, false);
                break;
            case "portrait":
                // Portrait Pro: flattering natural colour, refined skin tones, gentle subject light,
                // background separation by depth only when the subject is reliable
                p.wbAmt = 0.4f; p.temp = 0.06f; p.expo = 0.18f;
                p.shadowLift = 0.30f; p.highComp = 0.36f; p.detail = 1.10f; p.midBoost = 1.12f;
                p.contrast = 1.06f; p.shoulder = 0.74f; p.blackLift = 0.004f;
                p.shadowGain = new float[]{0.98f, 1.00f, 1.02f};
                p.highGain = new float[]{1.03f, 1.00f, 0.97f};
                p.sat = 0.97f; p.vib = 0.10f; p.vig = 0.12f; p.sharpen = 0.35f; p.nr = 1.0f;
                p.depthDodge = 0.18f;
                p.lut = "natural_cinematic"; p.lutAmt = 0.6f;
                p.skinRefine = 0.5f; p.grain = 0.004f;
                p.bokeh = true; p.focal = 85; p.aperture = 2.8f; p.blurAmt = 0.6f;
                break;
            case "noir":
                // Dramatic Noir: monochrome print, strong local contrast, deep but detailed blacks
                p.wbAmt = 0f; p.expo = 0.10f;
                p.shadowLift = 0.32f; p.highComp = 0.55f; p.detail = 1.50f; p.midBoost = 2.4f;
                p.contrast = 1.32f; p.shoulder = 0.64f; p.toe = 0.004f;
                p.sat = 1f; p.vig = 0.26f; p.sharpen = 0.45f; p.nr = 1.0f;
                p.lut = "noir"; p.lutAmt = 1.0f;
                p.grain = 0.02f;
                break;
            case "natural":
            default:
                // Natural Pro: exposure, white balance, dynamic range, noise, natural sharpness;
                // colours stay faithful (no LUT, no grain, no vignette)
                p.key = "natural";
                p.wbAmt = 0.6f; p.expo = 0.25f;
                p.shadowLift = 0.28f; p.highComp = 0.32f; p.detail = 1.12f; p.midBoost = 1.10f;
                p.contrast = 1.04f; p.shoulder = 0.82f;
                p.sat = 1.0f; p.vib = 0.08f; p.sharpen = 0.50f; p.nr = 1.0f;
                p.skinRefine = 0.15f;
                break;
        }
        return p;
    }

    /** The five cinematic presets differ in tone curves, colour separation, film stock and texture. */
    static void cinematic(Style p, String preset) {
        p.preset = preset;
        p.nr = 0.8f;
        switch (preset) {
            case "moody":
                p.wbAmt = 0.2f; p.expo = 0.0f; p.expoComp = -0.35f; p.nightKeep = 1f;
                p.shadowLift = 0.18f; p.highComp = 0.48f; p.detail = 1.18f; p.midBoost = 1.45f;
                p.contrast = 1.28f; p.shoulder = 0.60f; p.toe = 0.012f; p.blackLift = 0.006f;
                p.shadowGain = new float[]{0.95f, 1.01f, 1.03f};
                p.highGain = new float[]{1.03f, 1.00f, 0.95f};
                p.sat = 0.75f; p.vig = 0.32f; p.sharpen = 0.25f;
                p.lut = "moody_film"; p.lutAmt = 1.0f;
                p.halation = 0.2f; p.grain = 0.022f; p.skinRefine = 0.3f;
                break;
            case "night":
                p.wbAmt = 0.15f; p.temp = -0.25f; p.expo = 0.10f; p.nightKeep = 0.85f;
                p.shadowLift = 0.30f; p.highComp = 0.58f; p.detail = 1.15f; p.midBoost = 1.30f;
                p.contrast = 1.20f; p.shoulder = 0.60f; p.toe = 0.006f;
                p.shadowGain = new float[]{0.92f, 0.99f, 1.12f};
                p.highGain = new float[]{1.08f, 1.00f, 0.88f};
                p.sat = 0.95f; p.vig = 0.22f; p.sharpen = 0.30f; p.nr = 1.2f;
                p.lut = "night_cinema"; p.lutAmt = 1.0f;
                p.halation = 0.8f; p.grain = 0.014f; p.skinRefine = 0.35f;
                break;
            case "film":
                p.wbAmt = 0.2f; p.temp = 0.15f; p.expo = 0.15f;
                p.shadowLift = 0.30f; p.highComp = 0.38f; p.detail = 1.05f; p.midBoost = 1.12f;
                p.contrast = 1.10f; p.shoulder = 0.70f; p.blackLift = 0.018f;
                p.sat = 0.92f; p.vig = 0.12f; p.sharpen = 0.15f;
                p.lut = "film_look"; p.lutAmt = 1.0f;
                p.halation = 0.35f; p.grain = 0.035f; p.skinRefine = 0.35f;
                break;
            case "natural_cinematic":
                p.wbAmt = 0.4f; p.expo = 0.20f;
                p.shadowLift = 0.26f; p.highComp = 0.30f; p.detail = 1.08f; p.midBoost = 1.10f;
                p.contrast = 1.08f; p.shoulder = 0.76f; p.blackLift = 0.003f;
                p.shadowGain = new float[]{0.97f, 1.00f, 1.04f};
                p.highGain = new float[]{1.03f, 1.00f, 0.97f};
                p.sat = 0.98f; p.vib = 0.05f; p.vig = 0.08f; p.sharpen = 0.35f;
                p.lut = "natural_cinematic"; p.lutAmt = 0.8f;
                p.grain = 0.006f; p.skinRefine = 0.25f;
                break;
            case "hollywood":
            default:
                p.preset = "hollywood";
                p.wbAmt = 0.3f; p.temp = 0.08f; p.expo = 0.15f;
                p.shadowLift = 0.26f; p.highComp = 0.42f; p.detail = 1.12f; p.midBoost = 1.28f;
                p.contrast = 1.22f; p.shoulder = 0.62f; p.toe = 0.006f; p.blackLift = 0.004f;
                p.shadowGain = new float[]{0.90f, 1.00f, 1.10f};
                p.highGain = new float[]{1.08f, 1.00f, 0.90f};
                p.sat = 0.95f; p.vig = 0.20f; p.sharpen = 0.30f;
                p.lut = "hollywood"; p.lutAmt = 1.0f;
                p.halation = 0.5f; p.grain = 0.018f; p.skinRefine = 0.35f;
                break;
        }
    }

    /**
     * Cinematic Fusion Pro: one unified pipeline. Each control drives the stage it belongs to
     * (cinematic -> filmic curve, grading, film stock, halation, grain; dramatic -> adaptive tone
     * mapping, highlight recovery, local contrast, depth-shaped light; DSLR -> noise reduction,
     * optical sharpness, micro-detail; depth blur -> lens isolation when reliable). A final
     * balancing stage then caps combined contrast and chroma so the stages never stack up.
     */
    static void fusion(Style p, float cin, float dram, float dslr, float blur, boolean night) {
        p.key = "fusion";
        p.preset = night ? "night" : "hollywood";
        p.wbAmt = 0.4f; p.expo = 0.18f; p.nr = 0.6f + 0.6f * dslr; p.nightKeep = 0.75f;
        // cinematic stage
        p.contrast = 1f + 0.20f * cin;
        p.shoulder = 0.80f - 0.18f * cin;
        p.toe = 0.006f * cin;
        p.blackLift = 0.003f * cin;
        float t = cin * (night ? 1.0f : 0.9f);
        p.shadowGain = night ? new float[]{1 - 0.08f * t, 1 - 0.01f * t, 1 + 0.12f * t} : new float[]{1 - 0.10f * t, 1f, 1 + 0.10f * t};
        p.highGain = night ? new float[]{1 + 0.08f * t, 1f, 1 - 0.12f * t} : new float[]{1 + 0.08f * t, 1f, 1 - 0.10f * t};
        p.temp = night ? -0.15f * cin : 0.06f * cin;
        p.lut = night ? "night_cinema" : "hollywood";
        p.lutAmt = 0.85f * cin;
        p.halation = (night ? 0.6f : 0.4f) * cin;
        p.grain = 0.014f * cin;
        p.vig = 0.06f + 0.10f * cin + 0.06f * dram;
        p.skinRefine = 0.3f;
        // dramatic stage
        p.shadowLift = 0.24f + 0.14f * dram;
        p.highComp = 0.30f + 0.32f * dram;
        p.midBoost = 1f + 1.10f * dram;
        p.depthDodge = 0.32f * dram;
        // DSLR stage
        p.detail = 1.05f + 0.25f * dslr + 0.15f * dram;
        p.sharpen = 0.25f + 0.50f * dslr;
        p.sat = 0.95f;
        p.vib = 0.06f * dslr;
        p.bokeh = blur > 0.01f;
        p.blurAmt = blur;
        p.focal = 50; p.aperture = 2.8f;
        // final balancing: cap the combined global + local contrast and the chroma
        float total = (p.contrast - 1f) + 0.5f * (p.midBoost - 1f);
        if (total > 0.75f) {
            float k = 0.75f / total;
            p.contrast = 1f + (p.contrast - 1f) * k;
            p.midBoost = 1f + (p.midBoost - 1f) * k;
        }
        p.chromaLimit = 0.85f;
    }
}
