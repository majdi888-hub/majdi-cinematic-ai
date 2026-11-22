package com.majdi.cinematicai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The colour part of an edit: the enabled stack layers in order, then the Adjust panel.
 * Each stage is a RenderPlan prepared on the output of the previous stage (at working
 * resolution), so a stacked look reacts to what the layers before it produced, exactly
 * as the user previews it. The original pixels are never modified.
 *
 * Full-resolution rendering runs the stages strip by strip: stage k of an n-stage chain
 * needs HALO rows of context from stage k-1, so each output strip is computed from an input
 * window widened by n*HALO rows. This is bit-identical to rendering the whole image stage
 * after stage (verified by the tests).
 */
final class Pipeline {

    interface ImportedLuts {
        Lut3D get(String id) throws IOException;
    }

    final List<RenderPlan> stages = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    /** isolation state of the last lens layer ("on", "off-no-subject", ...), or null */
    String isolation;
    String reason;
    float[] focusMask;

    int halo() { return GradeCore.HALO * Math.max(1, stages.size()); }

    static Pipeline build(int[] px, int w, int h, EditState st, SceneMaps maps, float[][] faces,
                          RenderPlan.LutSource luts, ImportedLuts imported) throws IOException {
        Pipeline p = new Pipeline();
        int[] cur = px;
        int nf = faces == null ? 0 : faces.length;
        int enabled = 0;
        for (EditState.Layer l : st.layers) if (l.enabled && l.strength > 0.001f) enabled++;
        int done = 0;
        boolean adjust = st.hasAdjust();
        for (EditState.Layer l : st.layers) {
            if (!l.enabled || l.strength <= 0.001f) continue;
            Lut3D lut = null;
            if (l.filter.startsWith("lut:")) {
                lut = imported == null ? null : imported.get(l.filter.substring(4));
                if (lut == null) continue;          // missing import: skipped, never faked
            }
            RenderOptions o = FilterLibrary.optionsFor(l, st, lut);
            ToneModel tone = ToneModel.build(cur, w, h, faces);
            SceneStats stats = SceneStats.measure(cur, w, h);
            RenderPlan plan = RenderPlan.build(cur, w, h, o, tone, stats, maps, nf, luts);
            p.stages.add(plan);
            p.labels.add(l.filter);
            if (plan.style.bokeh) { p.isolation = plan.isolation; if (plan.focusMask != null) p.focusMask = plan.focusMask; }
            if (plan.reason != null) p.reason = plan.reason;
            done++;
            if (done < enabled || adjust) cur = renderStage(plan, cur, w, h);
        }
        if (adjust) {
            RenderOptions o = new RenderOptions();
            o.style = "custom";
            o.custom = FilterLibrary.adjustStyle(st);
            o.strength = 1f;
            ToneModel tone = ToneModel.build(cur, w, h, faces);
            SceneStats stats = SceneStats.measure(cur, w, h);
            p.stages.add(RenderPlan.build(cur, w, h, o, tone, stats, maps, nf, luts));
            p.labels.add("adjust");
        }
        return p;
    }

    /** One stage over a whole working-resolution image (same strip renderer as the export). */
    static int[] renderStage(RenderPlan plan, int[] px, int w, int h) {
        GradeCore core = new GradeCore(plan, w, h, 1);
        int[] out = new int[w * h];
        core.processStrip(px, 0, h, 0, h, out);
        return out;
    }

    /** Renders the whole chain at the image's own size (preview). With no stages, a copy. */
    int[] render(int[] px, int w, int h) {
        int[] cur = px.clone();
        for (RenderPlan s : stages) cur = renderStage(s, cur, w, h);
        return cur;
    }

    /** Strip renderer for any resolution; one instance per image, strips requested top to bottom. */
    final class Strips {
        private final GradeCore[] cores;
        private final int w, h;
        Strips(int w, int h, int orientation) {
            this.w = w; this.h = h;
            cores = new GradeCore[stages.size()];
            for (int i = 0; i < cores.length; i++) cores[i] = new GradeCore(stages.get(i), w, h, orientation);
        }

        /** rows needed above/below an output strip */
        int halo() { return GradeCore.HALO * cores.length; }

        /**
         * in: rows [ys, ys+rows) of the source, which must cover [max(0,y0-halo()), min(h,y1+halo()));
         * out: rows [y0, y1).
         */
        void process(int[] in, int ys, int rows, int y0, int y1, int[] out) {
            int n = cores.length;
            if (n == 0) { System.arraycopy(in, (y0 - ys) * w, out, 0, (y1 - y0) * w); return; }
            int[] cur = in;
            int cs = ys, cr = rows;
            for (int k = 0; k < n; k++) {
                int ext = GradeCore.HALO * (n - 1 - k);       // rows still needed by later stages
                int a = Math.max(0, y0 - ext), b = Math.min(h, y1 + ext);
                int[] o = k == n - 1 ? out : new int[(b - a) * w];
                cores[k].processStrip(cur, cs, cr, a, b, o);
                cur = o; cs = a; cr = b - a;
            }
        }
    }
}
