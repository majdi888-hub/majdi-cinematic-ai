package com.majdi.cinematicai;

import java.io.IOException;

/**
 * Everything needed to render one look at any resolution, prepared once from the preview
 * (working resolution, display orientation): the resolved style, tone model, measured statistics,
 * depth/subject maps, LUT, and the working-resolution layers (lens blur render, sharp weight,
 * halation) that the full-resolution renderer composites strip by strip.
 */
final class RenderPlan {

    interface LutSource {
        Lut3D load(String name) throws IOException;
    }

    final Style style;
    final float strength;
    final ToneModel tone;
    final SceneStats stats;     // may be null
    final SceneMaps maps;       // may be null (models unavailable)
    final Lut3D lut;            // may be null
    final String reason;        // description of what was done (Auto)
    final String isolation;     // "on", "off-no-subject", "off-no-depth", "off" (style without lens)

    float fullNoiseSigma = -1f; // noise measured at full resolution (export), if known

    int ww, wh;
    float[] gR, gG, gB;
    Bokeh bokeh;
    float[] sA, sB;
    float[] halo;
    float[] focusMask;          // working-res map of what stays sharp (for the on-screen overlay)

    private RenderPlan(Style st, float strength, ToneModel tone, SceneStats stats, SceneMaps maps, Lut3D lut, String reason, String iso) {
        this.style = st; this.strength = strength; this.tone = tone; this.stats = stats; this.maps = maps;
        this.lut = lut; this.reason = reason; this.isolation = iso;
    }

    /** Noise level for a render at the given size (the preview uses its own measurement). */
    float noiseSigmaFor(int w, int h) {
        float pv = stats != null ? stats.noiseSigma : 0f;
        if (w == ww && h == wh) return pv;
        if (fullNoiseSigma >= 0f) return fullNoiseSigma;
        return pv;
    }

    static RenderPlan build(int[] px, int w, int h, RenderOptions opt, ToneModel tone, SceneStats stats,
                            SceneMaps maps, int faceCount, LutSource luts) throws IOException {
        Style st;
        String reason = null;
        boolean night = tone.p50 < -4.0f;
        switch (opt.style) {
            case "auto": {
                AutoDirector.Decision d = AutoDirector.decide(tone, stats, maps, faceCount);
                st = d.style;
                reason = d.description;
                break;
            }
            case "fusion":
                st = new Style();
                Style.fusion(st, opt.cinematic, opt.dramatic, opt.dslr, opt.depthBlur, night);
                break;
            case "cinematic":
                st = Style.of("cinematic", opt.preset.isEmpty() ? (night ? "night" : "hollywood") : opt.preset);
                break;
            case "dramatic":
                st = Style.of("dramatic");
                if (night) { st.depthDodge = 0.15f; st.nightKeep = 0.8f; st.shadowLift = 0.46f; }
                break;
            default:
                st = Style.of(opt.style);
        }
        if (st.bokeh) { st.focal = opt.focal; st.aperture = opt.aperture; }

        // ---- lens isolation gate: real depth AND a trusted focus (tap, reliable subject or face)
        String iso = "off";
        boolean useSubject = false;
        float focus = 0f;
        float[] objectMask = null;
        float[] lumW = new float[w * h];
        for (int i = 0; i < lumW.length; i++) {
            int c = px[i];
            lumW[i] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
        }
        if (st.bokeh) {
            if (maps == null || !maps.hasDepth()) {
                iso = "off-no-depth";
            } else if (opt.focus != null && opt.focusMode.equals("object")) {
                // whole tapped object stays sharp; focal plane = its median depth
                objectMask = ObjectSelect.select(maps, lumW, opt.focus[0], opt.focus[1], opt.objectGrow);
                focus = ObjectSelect.medianDepth(maps.depth, objectMask);
                iso = "on";
            } else if (opt.focus != null) {
                focus = maps.depthAt(opt.focus[0], opt.focus[1]);
                useSubject = maps.hasSubject && maps.subjectAt(opt.focus[0], opt.focus[1]) > 0.5f;
                iso = "on";
            } else if (maps.subjectReliable()) {
                focus = maps.focus;
                useSubject = true;
                iso = "on";
            } else if (faceCount > 0 && tone.faces.length > 0) {
                float[] f = tone.faces[0];
                focus = maps.depthAt((f[0] + f[2]) / 2f, (f[1] + f[3]) / 2f);
                iso = "on";
            } else {
                iso = "off-no-subject";
            }
        }

        Lut3D lut = null;
        if (opt.style.equals("cinematic") && opt.userLut != null) lut = opt.userLut;
        else if (st.lut != null && st.lutAmt > 0f && luts != null) lut = luts.load(st.lut);

        RenderPlan plan = new RenderPlan(st, opt.strength, tone, stats, maps, lut, reason, iso);
        plan.ww = w;
        plan.wh = h;
        boolean lens = iso.equals("on") && opt.strength > 0.01f && st.blurAmt > 0.01f;
        if (lens || st.halation > 0f) {
            GradeCore core = new GradeCore(plan, w, h, 1);
            float[][] g = core.gradeLinear(px);
            plan.gR = g[0]; plan.gG = g[1]; plan.gB = g[2];
        }
        int n = w * h;
        if (lens) {
            float[] lum = lumW;
            // lens simulation (visual approximation): blur grows with focal length and opening,
            // depth of field widens with smaller apertures and shorter lenses
            float lensK = st.focal <= 35 ? 0.55f : (st.focal >= 85 ? 1.7f : 1.0f);
            float apK = 1.8f / Math.max(1.8f, st.aperture);
            float radius = Math.max(w, h) * 0.020f * lensK * apK * st.blurAmt * opt.strength;
            float dof = 0.025f * (st.aperture / 1.8f) * (float) Math.sqrt(50.0 / st.focal);
            dof *= (float) Math.pow(8.0, Math.max(-1f, Math.min(1f, opt.focusRange)));   // user: narrow .. wide
            float[] subj = objectMask != null ? objectMask : (useSubject ? maps.subject : new float[n]);
            plan.bokeh = Bokeh.render(plan.gR, plan.gG, plan.gB, maps.depth, subj, w, h, focus, radius, dof);
            float[][] ab = Guided.guidedCoeffs(lum, plan.bokeh.sharp, w, h, 2, 1e-4f);
            plan.sA = ab[0];
            plan.sB = ab[1];
            plan.focusMask = plan.bokeh.sharp;
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
