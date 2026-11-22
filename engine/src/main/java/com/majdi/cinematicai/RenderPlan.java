package com.majdi.cinematicai;

import java.io.IOException;

/**
 * Everything needed to render one look at any resolution, prepared once from the preview
 * (working resolution, display orientation): the resolved style, tone model, depth/subject
 * maps, LUT, and the working-resolution layers (lens blur render, sharp weight, halation)
 * that the full-resolution renderer composites strip by strip.
 */
final class RenderPlan {

    interface LutSource {
        Lut3D load(String name) throws IOException;
    }

    final Style style;
    final float strength;
    final ToneModel tone;
    final SceneMaps maps;       // may be null (models unavailable)
    final Lut3D lut;            // may be null
    final String reason;        // Auto Director explanation, or null

    // working-resolution layers (display orientation)
    int ww, wh;
    float[] gR, gG, gB;         // graded linear image used as the bokeh base
    Bokeh bokeh;                // may be null
    float[] sA, sB;             // guided coefficients of the sharp weight vs. working luma
    float[] halo;               // halation energy, may be null

    private RenderPlan(Style st, float strength, ToneModel tone, SceneMaps maps, Lut3D lut, String reason) {
        this.style = st; this.strength = strength; this.tone = tone; this.maps = maps; this.lut = lut; this.reason = reason;
    }

    /**
     * @param px  preview ARGB pixels (display orientation), the same image ToneModel/SceneMaps were built from
     * @param userLut a user-imported LUT that replaces the Cinematic look's LUT, or null
     */
    static RenderPlan build(int[] px, int w, int h, String key, float strength, ToneModel tone, SceneMaps maps,
                            int faceCount, LutSource luts, Lut3D userLut) throws IOException {
        Style st;
        String reason = null;
        if (key.equals("auto")) {
            AutoDirector.Decision d = AutoDirector.decide(tone, maps, faceCount);
            st = d.style;
            reason = d.reason;
        } else {
            st = Style.of(key);
            // adaptive film stock for Cinematic: the night palette under artificial light
            if (key.equals("cinematic") && tone.p50 < -4.5f) st.lut = "night_neon";
            // Dramatic at night: keep the scene readable — lift shadows, keep lights vivid,
            // only gentle depth shaping (everything is "far" in a night scene)
            if (key.equals("dramatic") && tone.p50 < -4.5f) {
                st.depthDodge = 0.3f;
                st.shadowLift = 0.42f;
                st.toe = 0.002f;
                st.lut = "night_neon";
                st.lutAmt = 0.5f;
                st.sat = 0.95f;
                st.halation = 0.35f;
            }
        }
        if (st.bokeh && (maps == null || !maps.hasDepth())) st.bokeh = false;  // never fake a depth blur
        Lut3D lut = null;
        if (key.equals("cinematic") && userLut != null) lut = userLut;
        else if (st.lut != null && st.lutAmt > 0f && luts != null) lut = luts.load(st.lut);

        RenderPlan plan = new RenderPlan(st, strength, tone, maps, lut, reason);
        plan.ww = w;
        plan.wh = h;
        boolean needBase = st.bokeh || st.halation > 0f;
        if (needBase) {
            GradeCore core = new GradeCore(plan, w, h, 1);
            float[][] g = core.gradeLinear(px);
            plan.gR = g[0]; plan.gG = g[1]; plan.gB = g[2];
        }
        int n = w * h;
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            lum[i] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
        }
        if (st.bokeh && strength > 0.01f) {
            float radius = st.bokehRadius * Math.max(w, h) * strength;
            plan.bokeh = Bokeh.render(plan.gR, plan.gG, plan.gB, maps.depth, maps.subject, w, h, maps.focus, radius, st.depthOfField);
            float[][] ab = Guided.guidedCoeffs(lum, plan.bokeh.sharp, w, h, 2, 1e-4f);
            plan.sA = ab[0];
            plan.sB = ab[1];
        }
        if (st.halation > 0f) {
            float[] r = plan.bokeh != null ? plan.bokeh.rr : plan.gR;
            float[] g = plan.bokeh != null ? plan.bokeh.rg : plan.gG;
            float[] b = plan.bokeh != null ? plan.bokeh.rb : plan.gB;
            float[] e = new float[n];
            for (int i = 0; i < n; i++) {
                float yl = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i];
                float t = ColorMath.smooth(0.55f, 1.0f, yl);
                e[i] = t * t * yl;
            }
            int rad = Math.max(2, Math.round(Math.max(w, h) * 0.006f));
            plan.halo = Guided.boxMean(Guided.boxMean(Guided.boxMean(e, w, h, rad), w, h, rad), w, h, rad);
        }
        return plan;
    }
}
