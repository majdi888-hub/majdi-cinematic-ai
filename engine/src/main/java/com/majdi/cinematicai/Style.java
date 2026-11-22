package com.majdi.cinematicai;

/** All parameters of a look, at 100% strength. */
final class Style {
    String key = "natural";
    // tone
    float expo, shadowLift, highComp, detail, midBoost = 1f, contrast, shoulder, blackLift, toe;
    float[] shadowGain = {1f, 1f, 1f}, highGain = {1f, 1f, 1f};
    float sat = 1f, vib, vig, sharpen;
    // depth-aware light shaping (Dramatic): darkens what is far, lifts the subject
    float depthDodge;
    // film
    String lut;            // resource name without extension, or null
    float lutAmt;
    float halation, grain, skinRefine;
    // lens
    boolean bokeh;
    float bokehRadius;     // max blur radius as a fraction of the long side
    float depthOfField = 0.04f;

    static Style of(String key) {
        Style p = new Style();
        p.key = key;
        switch (key) {
            case "cinematic":
                // film emulation: 3D LUT, filmic curve, halation, grain, refined skin tones
                p.expo = 0.18f; p.shadowLift = 0.30f; p.highComp = 0.34f; p.detail = 1.10f; p.midBoost = 1.15f;
                p.contrast = 1.14f; p.shoulder = 0.66f; p.blackLift = 0.006f; p.toe = 0f;
                p.shadowGain = new float[]{0.93f, 1.00f, 1.08f};
                p.highGain = new float[]{1.06f, 1.00f, 0.92f};
                p.sat = 0.92f; p.vib = 0f; p.vig = 0.16f; p.sharpen = 0.25f;
                p.lut = "teal_orange_film"; p.lutAmt = 1.0f;
                p.halation = 0.55f; p.grain = 0.022f; p.skinRefine = 0.35f;
                break;
            case "dramatic":
                // local contrast and adaptive tone mapping, depth-shaped light, silver-retention print
                p.expo = 0.08f; p.shadowLift = 0.30f; p.highComp = 0.50f; p.detail = 1.22f; p.midBoost = 1.80f;
                p.contrast = 1.22f; p.shoulder = 0.66f; p.blackLift = 0f; p.toe = 0.008f;
                p.shadowGain = new float[]{0.95f, 0.99f, 1.08f};
                p.highGain = new float[]{1.03f, 1.00f, 0.96f};
                p.sat = 0.85f; p.vib = 0f; p.vig = 0.22f; p.sharpen = 0.35f;
                p.depthDodge = 0.9f;
                p.lut = "silver_dramatic"; p.lutAmt = 0.55f;
                p.grain = 0.012f; p.skinRefine = 0.2f;
                break;
            case "dslr":
                // depth-aware lens isolation with clean, natural rendering of the subject
                p.expo = 0.22f; p.shadowLift = 0.22f; p.highComp = 0.26f; p.detail = 1.15f; p.midBoost = 1.10f;
                p.contrast = 1.06f; p.shoulder = 0.80f; p.blackLift = 0f; p.toe = 0.002f;
                p.highGain = new float[]{1.012f, 1.00f, 0.988f};
                p.sat = 1.0f; p.vib = 0.16f; p.vig = 0.04f; p.sharpen = 0.55f;
                p.lut = "warm_print"; p.lutAmt = 0.2f;
                p.skinRefine = 0.25f;
                p.bokeh = true; p.bokehRadius = 0.026f; p.depthOfField = 0.035f;
                break;
            case "natural":
            default:
                p.key = "natural";
                p.expo = 0.20f; p.shadowLift = 0.24f; p.highComp = 0.22f; p.detail = 1.08f; p.midBoost = 1.06f;
                p.contrast = 1.06f; p.shoulder = 0.78f; p.blackLift = 0.003f;
                p.shadowGain = new float[]{0.97f, 1.00f, 1.04f};
                p.highGain = new float[]{1.03f, 1.00f, 0.97f};
                p.sat = 0.98f; p.vib = 0.06f; p.vig = 0.06f; p.sharpen = 0.35f;
                p.lut = "warm_print"; p.lutAmt = 0.3f;
                p.grain = 0.005f; p.skinRefine = 0.25f;
                break;
        }
        return p;
    }
}
