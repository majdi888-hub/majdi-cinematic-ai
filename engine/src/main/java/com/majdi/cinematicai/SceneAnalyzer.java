package com.majdi.cinematicai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Runs the on-device networks with ONNX Runtime (the same Java API on Android and on the JVM):
 *  - Depth Anything V2 Small: relative inverse depth, 518x518 input;
 *  - ISNet (DIS, general use): salient-subject matte, 1024x1024 input.
 * Sessions are opened per call and closed immediately so the two models never occupy memory
 * at the same time. No generative model is involved; outputs are only used as masks/maps.
 */
final class SceneAnalyzer {

    static final int DEPTH_SIZE = 518;
    static final int MATTE_SIZE = 1024;

    private final String depthModel, matteModel;
    private final int threads;

    /** @param depthModel / matteModel absolute paths to the .onnx files, or null if unavailable */
    SceneAnalyzer(String depthModel, String matteModel, int threads) {
        this.depthModel = depthModel;
        this.matteModel = matteModel;
        this.threads = threads;
    }

    /** Relative inverse depth (larger = nearer), DEPTH_SIZE x DEPTH_SIZE, or null. */
    float[] depth(int[] px, int w, int h) throws OrtException {
        if (depthModel == null) return null;
        int S = DEPTH_SIZE;
        float[] in = planarInput(px, w, h, S, S,
                new float[]{0.485f, 0.456f, 0.406f}, new float[]{0.229f, 0.224f, 0.225f});
        return run(depthModel, in, S, S);
    }

    /** Subject matte normalized to 0..1, MATTE_SIZE x MATTE_SIZE, or null. */
    float[] matte(int[] px, int w, int h) throws OrtException {
        if (matteModel == null) return null;
        int S = MATTE_SIZE;
        float[] in = planarInput(px, w, h, S, S, new float[]{0.5f, 0.5f, 0.5f}, new float[]{1f, 1f, 1f});
        float[] m = run(matteModel, in, S, S);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : m) { if (v < lo) lo = v; if (v > hi) hi = v; }
        float inv = 1f / Math.max(1e-8f, hi - lo);
        for (int i = 0; i < m.length; i++) m[i] = (m[i] - lo) * inv;
        return m;
    }

    /** Full analysis of a display-oriented preview. Missing models degrade gracefully. */
    SceneMaps analyze(int[] px, int w, int h, float[][] faces) throws OrtException {
        float[] d = depth(px, w, h);
        float[] m = matte(px, w, h);
        float[] lum = new float[w * h];
        for (int i = 0; i < lum.length; i++) {
            int c = px[i];
            lum[i] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
        }
        return SceneMaps.build(lum, w, h, d, DEPTH_SIZE, DEPTH_SIZE, m, MATTE_SIZE, MATTE_SIZE, faces);
    }

    private float[] run(String model, float[] input, int iw, int ih) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions so = new OrtSession.SessionOptions()) {
            so.setIntraOpNumThreads(threads);
            so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            try (OrtSession session = env.createSession(model, so)) {
                String name = session.getInputNames().iterator().next();
                try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, ih, iw});
                     OrtSession.Result r = session.run(Collections.singletonMap(name, t))) {
                    OnnxTensor o = (OnnxTensor) r.get(0);
                    FloatBuffer fb = o.getFloatBuffer();
                    float[] out = new float[fb.remaining()];
                    fb.get(out);
                    if (out.length != iw * ih) throw new IllegalStateException("unexpected output size " + out.length);
                    return out;
                }
            }
        }
    }

    /** ARGB -> normalized planar CHW float tensor at the network resolution (area/bilinear resize). */
    private static float[] planarInput(int[] px, int w, int h, int ow, int oh, float[] mean, float[] std) {
        int n = w * h;
        float[] r = new float[n], g = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            r[i] = ((c >> 16) & 255) / 255f;
            g[i] = ((c >> 8) & 255) / 255f;
            b[i] = (c & 255) / 255f;
        }
        float[][] pl = {Guided.resize(r, w, h, ow, oh), Guided.resize(g, w, h, ow, oh), Guided.resize(b, w, h, ow, oh)};
        int m = ow * oh;
        float[] out = new float[3 * m];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < m; i++) out[c * m + i] = (pl[c][i] - mean[c]) / std[c];
        }
        return out;
    }
}
