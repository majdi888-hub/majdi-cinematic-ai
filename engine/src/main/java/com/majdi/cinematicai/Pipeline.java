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
    private int[] inputOfLast;   // working-resolution input of the final stage (for a fast preview)

    int halo() { return GradeCore.HALO * Math.max(1, stages.size()); }

    /**
     * Reuses the stages (plan + rendered output) of the previous build whose inputs did not change,
     * so moving an Adjust slider does not re-run the looks before it. Keyed on the exact source
     * pixels array and the serialized prefix of the edit.
     */
    static final class Cache {
        int[] source;
        final java.util.Map<String, RenderPlan> plans = new java.util.HashMap<>();
        final java.util.Map<String, int[]> outs = new java.util.HashMap<>();
        void reset(int[] src) { source = src; plans.clear(); outs.clear(); }
    }

    static Pipeline build(int[] px, int w, int h, EditState st, SceneMaps maps, float[][] faces,
                          RenderPlan.LutSource luts, ImportedLuts imported) throws IOException {
        return build(px, w, h, st, maps, faces, luts, imported, null);
    }

    static Pipeline build(int[] px, int w, int h, EditState st, SceneMaps maps, float[][] faces,
                          RenderPlan.LutSource luts, ImportedLuts imported, Cache cache) throws IOException {
        Pipeline p = new Pipeline();
        if (cache != null && cache.source != px) cache.reset(px);
        StringBuilder key = new StringBuilder();
        java.util.Map<String, Object> lens = st.toMap();
        key.append(Json.write(lens.get("lens")));
        java.util.Map<String, RenderPlan> usedPlans = new java.util.HashMap<>();
        java.util.Map<String, int[]> usedOuts = new java.util.HashMap<>();
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
            key.append('|').append(l.filter).append('@').append(l.strength);
            String k = key.toString();
            RenderPlan plan = cache != null ? cache.plans.get(k) : null;
            if (plan == null) {
                RenderOptions o = FilterLibrary.optionsFor(l, st, lut);
                ToneModel tone = ToneModel.build(cur, w, h, faces);
                SceneStats stats = SceneStats.measure(cur, w, h);
                plan = RenderPlan.build(cur, w, h, o, tone, stats, maps, nf, luts);
            }
            usedPlans.put(k, plan);
            p.stages.add(plan);
            p.labels.add(l.filter);
            p.inputOfLast = cur;
            if (plan.style.bokeh) { p.isolation = plan.isolation; if (plan.focusMask != null) p.focusMask = plan.focusMask; }
            if (plan.reason != null) p.reason = plan.reason;
            done++;
            if (done < enabled || adjust) {
                int[] c = cache != null ? cache.outs.get(k) : null;
                cur = c != null ? c : renderStage(plan, cur, w, h);
                usedOuts.put(k, cur);
            }
        }
        if (cache != null) {   // keep only what this edit uses
            cache.plans.clear(); cache.plans.putAll(usedPlans);
            cache.outs.clear(); cache.outs.putAll(usedOuts);
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
            p.inputOfLast = cur;
        }
        return p;
    }

    static int threads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * One stage over a whole working-resolution image, split into row bands rendered in parallel
     * (one GradeCore per band; results are identical to a single pass, verified by the tests).
     */
    static int[] renderStage(RenderPlan plan, int[] px, int w, int h) {
        int[] out = new int[w * h];
        int t = Math.min(threads(), Math.max(1, h / 64));
        if (t <= 1) {
            new GradeCore(plan, w, h, 1).processStrip(px, 0, h, 0, h, out);
            return out;
        }
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(t);
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            int band = (h + t - 1) / t;
            for (int i = 0; i < t; i++) {
                final int y0 = i * band, y1 = Math.min(h, y0 + band);
                if (y0 >= y1) break;
                fs.add(ex.submit(() -> {
                    int ys = Math.max(0, y0 - GradeCore.HALO), ye = Math.min(h, y1 + GradeCore.HALO);
                    int[] o = new int[(y1 - y0) * w];
                    new GradeCore(plan, w, h, 1).processStrip(java.util.Arrays.copyOfRange(px, ys * w, ye * w), ys, ye - ys, y0, y1, o);
                    System.arraycopy(o, 0, out, y0 * w, o.length);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : fs) f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ex.shutdown();
        }
        return out;
    }

    /** Supplies source rows [ys, ye) (full width). Called from several threads. */
    interface RowSource { void read(int ys, int ye, int[] dst) throws Exception; }
    /** Receives finished output rows [y0, y1) strictly in top-to-bottom order. */
    interface RowSink { void write(int y0, int y1, int[] rows) throws Exception; }

    /**
     * Full-resolution render on all cores: strips are graded in parallel (each worker owns its
     * renderer) and delivered to the sink in order, with a bounded number in flight to cap memory.
     */
    void renderParallel(int w, int h, int orientation, int strip, RowSource src, RowSink sink) throws Exception {
        int t = threads();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(t);
        final ThreadLocal<Strips> local = ThreadLocal.withInitial(() -> new Strips(w, h, orientation));
        try {
            java.util.ArrayDeque<java.util.concurrent.Future<int[]>> q = new java.util.ArrayDeque<>();
            java.util.ArrayDeque<int[]> ranges = new java.util.ArrayDeque<>();
            int y = 0;
            while (y < h || !q.isEmpty()) {
                while (y < h && q.size() < 2 * t) {
                    final int y0 = y, y1 = Math.min(h, y + strip);
                    q.add(ex.submit(() -> {
                        Strips sp = local.get();
                        int hal = sp.halo();
                        int ys = Math.max(0, y0 - hal), ye = Math.min(h, y1 + hal);
                        int[] in = new int[(ye - ys) * w];
                        src.read(ys, ye, in);
                        int[] o = new int[(y1 - y0) * w];
                        sp.process(in, ys, ye - ys, y0, y1, o);
                        return o;
                    }));
                    ranges.add(new int[]{y0, y1});
                    y = y1;
                }
                int[] r = ranges.poll();
                sink.write(r[0], r[1], q.poll().get());
            }
        } finally {
            ex.shutdownNow();
        }
    }

    /** The working-resolution result of the build (only the final stage is rendered here). */
    int[] renderPreview(int[] src, int w, int h) {
        if (stages.isEmpty()) return src.clone();
        return renderStage(stages.get(stages.size() - 1), inputOfLast, w, h);
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
