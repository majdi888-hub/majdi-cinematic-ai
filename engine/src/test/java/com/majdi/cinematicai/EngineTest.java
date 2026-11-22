package com.majdi.cinematicai;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * End-to-end tests of the real engine with the real networks (ONNX Runtime, desktop build of the
 * same Java API the app uses). Writes before/after images and a metrics report to test.out.
 */
public class EngineTest {

    static File out;
    static String depthModel, matteModel;
    static final String[][] STYLES = {{"fusion", ""}, {"natural", ""}, {"cinematic", "hollywood"}, {"cinematic", "moody"}, {"cinematic", "night"},
            {"cinematic", "film"}, {"cinematic", "natural_cinematic"}, {"dramatic", ""}, {"dslr", ""}, {"auto", ""}};
    static final List<String> report = new ArrayList<>();

    @BeforeClass
    public static void setup() throws Exception {
        out = new File(System.getProperty("test.out", "build/test-output"));
        out.mkdirs();
        String md = System.getProperty("models.dir", "");
        if (!md.isEmpty()) {
            File d = new File(md, "depth_anything_v2_vits.onnx"), m = new File(md, "isnet-general-use.onnx");
            if (d.exists()) depthModel = d.getAbsolutePath();
            if (m.exists()) matteModel = m.getAbsolutePath();
        }
    }

    static int[] load(String name, int[] wh) throws Exception {
        try (InputStream in = EngineTest.class.getResourceAsStream("/images/" + name + ".png")) {
            BufferedImage im = ImageIO.read(in);
            wh[0] = im.getWidth(); wh[1] = im.getHeight();
            return im.getRGB(0, 0, wh[0], wh[1], null, 0, wh[0]);
        }
    }

    static float[][] faces(String name) throws Exception {
        try (InputStream in = EngineTest.class.getResourceAsStream("/images/" + name + ".faces")) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return new float[0][];
            String[] t = s.split("\\s+");
            return new float[][]{{Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])}};
        }
    }

    static Lut3D lut(String n) throws java.io.IOException {
        try (InputStream in = GradeCore.class.getResourceAsStream("/luts/" + n + ".cube")) {
            return Lut3D.parse(in);
        }
    }

    static int[] render(RenderPlan plan, int[] px, int w, int h, int strip) {
        GradeCore g = new GradeCore(plan, w, h, 1);
        int H = GradeCore.HALO;
        int[] o = new int[w * h], in = new int[w * (strip + 2 * H)], ob = new int[w * strip];
        for (int y0 = 0; y0 < h; y0 += strip) {
            int y1 = Math.min(h, y0 + strip), ys = Math.max(0, y0 - H), ye = Math.min(h, y1 + H);
            System.arraycopy(px, ys * w, in, 0, (ye - ys) * w);
            g.processStrip(in, ys, ye - ys, y0, y1, ob);
            System.arraycopy(ob, 0, o, y0 * w, (y1 - y0) * w);
        }
        return o;
    }

    static void save(int[] px, int w, int h, String name) throws Exception {
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        r.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(r, "png", new File(out, name + ".png"));
    }

    static double meanAbsDiff(int[] a, int[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i++)
            for (int k = 0; k < 24; k += 8) s += Math.abs(((a[i] >> k) & 255) - ((b[i] >> k) & 255));
        return s / (3.0 * a.length);
    }

    static double regionLuma(int[] px, int w, int h, float[] f) {
        double s = 0; int n = 0;
        float cx = (f[0] + f[2]) / 2 * w, cy = (f[1] + f[3]) / 2 * h, rx = (f[2] - f[0]) / 2 * w * 0.7f, ry = (f[3] - f[1]) / 2 * h * 0.7f;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float dx = (x - cx) / rx, dy = (y - cy) / ry;
            if (dx * dx + dy * dy < 1) { int c = px[y * w + x]; s += 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255); n++; }
        }
        return s / Math.max(1, n);
    }

    @Test
    public void lutParserAndTetrahedralIdentity() throws Exception {
        StringBuilder sb = new StringBuilder("TITLE \"id\"\nLUT_3D_SIZE 9\n");
        for (int b = 0; b < 9; b++) for (int g = 0; g < 9; g++) for (int r = 0; r < 9; r++)
            sb.append(r / 8f).append(' ').append(g / 8f).append(' ').append(b / 8f).append('\n');
        Lut3D id = Lut3D.parse(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)));
        float[] o = new float[3];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < 10000; i++) {
            float r = rnd.nextFloat(), g = rnd.nextFloat(), b = rnd.nextFloat();
            id.apply(r, g, b, o);
            assertEquals(r, o[0], 1e-5); assertEquals(g, o[1], 1e-5); assertEquals(b, o[2], 1e-5);
        }
        for (String n : new String[]{"hollywood", "moody_film", "night_cinema", "film_look", "natural_cinematic", "dramatic_print"}) {
            Lut3D l = lut(n);
            assertEquals(33, l.size);
        }
        report.add("LUT: .cube parser + tetrahedral interpolation exact on identity (10000 samples); 6 film LUTs load (33^3)");
    }

    @Test
    public void jpegEncoderRoundTrip() throws Exception {
        int[] wh = new int[2];
        int[] px = load("astronaut", wh);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        JpegStreamWriter jw = new JpegStreamWriter(bo, wh[0], wh[1], 95, 6);
        for (int y = 0; y < wh[1]; y += 37) jw.writeRows(px, y * wh[0], Math.min(37, wh[1] - y));
        jw.finish();
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(bo.toByteArray()));
        assertEquals(wh[0], back.getWidth());
        int[] bp = back.getRGB(0, 0, wh[0], wh[1], null, 0, wh[0]);
        double mad = meanAbsDiff(px, bp);
        assertTrue("JPEG error too high: " + mad, mad < 2.5);
        report.add(String.format(Locale.US, "JPEG 4:4:4 stream encoder: decodes with ImageIO, mean abs error %.2f/255", mad));
    }

    @Test
    public void depthModelMatchesPythonReference() throws Exception {
        Assume.assumeNotNull(depthModel);
        SceneAnalyzer an = new SceneAnalyzer(depthModel, null, 4);
        for (String n : new String[]{"astronaut", "night_launch"}) {
            int[] wh = new int[2];
            int[] px = load(n, wh);
            long t = System.nanoTime();
            float[] d = an.depth(px, wh[0], wh[1]);
            long ms = (System.nanoTime() - t) / 1000000;
            float[] small = Guided.resize(d, 518, 518, 32, 32);
            List<String> lines = Files.readAllLines(new File(EngineTest.class.getResource("/images/" + n + ".depth32.txt").toURI()).toPath());
            float[] ref = new float[1024];
            int k = 0;
            for (String line : lines) for (String v : line.trim().split("\\s+")) ref[k++] = Float.parseFloat(v);
            double c = corr(small, ref);
            assertTrue(n + " depth correlation with Python/ONNX reference too low: " + c, c > 0.99);
            report.add(String.format(Locale.US, "Depth Anything V2 (Java ORT) %s: %d ms, correlation with Python reference %.4f", n, ms, c));
        }
    }

    static double corr(float[] a, float[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) { double x = a[i] - ma, y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y; }
        return sab / Math.sqrt(saa * sbb);
    }

    @Test
    public void fullPipelineAllStyles() throws Exception {
        for (String name : new String[]{"astronaut", "night_launch"}) {
            int[] wh = new int[2];
            int[] px = load(name, wh);
            int w = wh[0], h = wh[1];
            float[][] faces = faces(name);
            ToneModel tone = ToneModel.build(px, w, h, faces);
            SceneStats stats = SceneStats.measure(px, w, h);
            SceneMaps maps = null;
            if (depthModel != null || matteModel != null) {
                long t = System.nanoTime();
                maps = new SceneAnalyzer(depthModel, matteModel, 4).analyze(px, w, h, faces);
                report.add(String.format(Locale.US, "%s analysis (depth + subject, Java ORT): %d ms, subject=%b reliable=%b area=%.2f separation=%.2f noise=%.2f",
                        name, (System.nanoTime() - t) / 1000000, maps.hasSubject, maps.subjectReliable(), maps.subjectArea, maps.separation, stats.noiseSigma));
            }
            save(px, w, h, name + "_original");
            int[][] results = new int[STYLES.length][];
            for (int si = 0; si < STYLES.length; si++) {
                String st = STYLES[si][0], pr = STYLES[si][1];
                String label = st + (pr.isEmpty() ? "" : "_" + pr);
                RenderOptions o = new RenderOptions();
                o.style = st; o.preset = pr; o.strength = 0.65f;
                long t0 = System.nanoTime();
                RenderPlan plan = RenderPlan.build(px, w, h, o, tone, stats, maps, faces.length, EngineTest::lut);
                int[] out = render(plan, px, w, h, 128);
                long ms = (System.nanoTime() - t0) / 1000000;
                save(out, w, h, name + "_" + label);
                int[] whole = render(plan, px, w, h, h);
                assertArrayEquals("strip output differs from whole-image output (" + label + ")", whole, out);
                String line = String.format(Locale.US, "%s %-26s 65%%: %5d ms, mean change %.1f/255, strips identical, isolation=%s",
                        name, label, ms, meanAbsDiff(px, out), plan.isolation);
                if (faces.length > 0) {
                    double before = regionLuma(px, w, h, faces[0]), after = regionLuma(out, w, h, faces[0]);
                    assertTrue("face darkened in " + label + ": " + before + " -> " + after, after > before - 1.5);
                    line += String.format(Locale.US, ", face luma %.1f -> %.1f", before, after);
                }
                report.add(line);
                results[si] = out;
            }
            // distinct looks: cinematic presets, dramatic and fusion must differ visibly
            int[] idx = {0, 2, 3, 5, 7};
            for (int a = 0; a < idx.length; a++) for (int b = a + 1; b < idx.length; b++) {
                double d = meanAbsDiff(results[idx[a]], results[idx[b]]);
                report.add(String.format(Locale.US, "%s difference %s/%s vs %s/%s: %.1f/255", name,
                        STYLES[idx[a]][0], STYLES[idx[a]][1], STYLES[idx[b]][0], STYLES[idx[b]][1], d));
            }
        }
    }

    @Test
    public void blurToolObjectFocusAndGate() throws Exception {
        Assume.assumeNotNull(depthModel);
        int[] wh = new int[2];
        int[] px = load("astronaut", wh);
        int w = wh[0], h = wh[1];
        float[][] faces = faces("astronaut");
        ToneModel tone = ToneModel.build(px, w, h, faces);
        SceneStats stats = SceneStats.measure(px, w, h);
        SceneMaps maps = new SceneAnalyzer(depthModel, matteModel, 4).analyze(px, w, h, faces);
        RenderOptions o = new RenderOptions();
        o.style = "dslr"; o.strength = 0.8f; o.focus = new float[]{0.43f, 0.26f}; o.focusMode = "object";
        RenderPlan plan = RenderPlan.build(px, w, h, o, tone, stats, maps, faces.length, EngineTest::lut);
        assertEquals("on", plan.isolation);
        assertNotNull(plan.focusMask);
        float inFace = Guided.sample(plan.focusMask, w, h, 0.43f, 0.26f);
        float corner = Guided.sample(plan.focusMask, w, h, 0.95f, 0.05f);
        assertTrue("tapped object must stay sharp: " + inFace, inFace > 0.8f);
        assertTrue("far background must be blurred: " + corner, corner < 0.3f);
        save(render(plan, px, w, h, 128), w, h, "astronaut_dslr_tap_face_object");
        o.focusRange = 1f;
        RenderPlan wide = RenderPlan.build(px, w, h, o, tone, stats, maps, faces.length, EngineTest::lut);
        o.focusRange = -1f;
        RenderPlan narrow = RenderPlan.build(px, w, h, o, tone, stats, maps, faces.length, EngineTest::lut);
        double sw = 0, sn = 0;
        for (int i = 0; i < wide.focusMask.length; i++) { sw += wide.focusMask[i]; sn += narrow.focusMask[i]; }
        assertTrue("wide focus range must keep more of the frame sharp", sw > sn);
        // no tap, no reliable subject -> isolation must stay off (never a generic blur)
        SceneMaps none = SceneMaps.build(new float[w * h], w, h, new float[w * h], w, h, null, 0, 0, new float[0][]);
        RenderOptions o2 = new RenderOptions();
        o2.style = "dslr";
        RenderPlan off = RenderPlan.build(px, w, h, o2, ToneModel.build(px, w, h, new float[0][]), stats, none, 0, EngineTest::lut);
        assertNull("DSLR must not blur without a trusted focus", off.bokeh);
        report.add(String.format(Locale.US, "Blur tool: tapped object sharp=%.2f, far corner=%.2f; sharp area wide/narrow = %.0f/%.0f px; no-subject isolation=%s",
                inFace, corner, sw, sn, off.isolation));
    }

    @org.junit.AfterClass
    public static void writeReport() throws Exception {
        try (PrintWriter pw = new PrintWriter(new File(out, "report.txt"), "UTF-8")) {
            pw.println("Majdi Cinematic AI engine tests  " + new java.util.Date());
            pw.println("depth model: " + depthModel);
            pw.println("subject model: " + matteModel);
            for (String r : report) pw.println(r);
        }
        System.out.println(String.join("\n", report));
    }
}
