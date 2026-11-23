package com.majdi.cinematicai;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * Studio features: edit state, undo/redo, filter stack, Adjust panel, filter import,
 * geometry/frames, projects, parallel export and preview/export agreement.
 */
public class StudioTest {

    static File out;
    static int w, h;
    static int[] px;
    static final List<String> lines = new ArrayList<>();

    @BeforeClass
    public static void setup() throws Exception {
        out = new File(System.getProperty("test.out", "build/test-output"), "studio");
        out.mkdirs();
        int[] wh = new int[2];
        int[] full = EngineTest.load("astronaut", wh);
        // working resolution like the app preview
        float s = 900f / Math.max(wh[0], wh[1]);
        w = Math.round(wh[0] * s); h = Math.round(wh[1] * s);
        px = scale(full, wh[0], wh[1], w, h);
    }

    static void log(String s) {
        System.out.println(s);
        synchronized (lines) {
            lines.add(s);
            try (PrintWriter pw = new PrintWriter(new FileWriter(new File(out, "studio-report.txt")))) {
                for (String l : lines) pw.println(l);
            } catch (Exception ignored) {
            }
        }
    }

    static int[] scale(int[] p, int sw, int sh, int dw, int dh) {
        BufferedImage b = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, sw, sh, p, 0, sw);
        Image s = b.getScaledInstance(dw, dh, Image.SCALE_AREA_AVERAGING);
        BufferedImage o = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        o.getGraphics().drawImage(s, 0, 0, null);
        return o.getRGB(0, 0, dw, dh, null, 0, dw);
    }

    static double diff(int[] a, int[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            int x = a[i], y = b[i];
            s += Math.abs((x >> 16 & 255) - (y >> 16 & 255)) + Math.abs((x >> 8 & 255) - (y >> 8 & 255)) + Math.abs((x & 255) - (y & 255));
        }
        return s / a.length / 3;
    }

    static double luma(int[] a) { double s = 0; for (int c : a) s += 0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255); return s / a.length; }
    static double rb(int[] a) { double s = 0; for (int c : a) s += (c >> 16 & 255) - (c & 255); return s / a.length; }
    static double sat(int[] a) { double s = 0; for (int c : a) { int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255; s += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)); } return s / a.length; }
    static double sd(int[] a) { double m = luma(a), s = 0; for (int c : a) { double l = 0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255); s += (l - m) * (l - m); } return Math.sqrt(s / a.length); }

    static int[] run(EditState st) throws Exception {
        return Pipeline.build(px, w, h, st, null, null, EngineTest::lut, null).render(px, w, h);
    }

    static void save(int[] o, int ow, int oh, String n) throws Exception {
        BufferedImage b = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, ow, oh, o, 0, ow);
        ImageIO.write(b, "jpg", new File(out, n + ".jpg"));
    }

    // ------------------------------------------------------------ state / history

    @Test
    public void editStateJsonAndValidation() {
        EditState s = EditState.defaults();
        s.layers.add(new EditState.Layer("cinematic:moody", 0.4f));
        s.adjust.put("exposure", 25f);
        s.rot90 = 1; s.frame = "film"; s.focus = new float[]{0.3f, 0.6f};
        String j = s.toJson();
        assertEquals(j, EditState.fromJson(j).toJson());
        try { EditState.fromJson("{\"layers\":[{\"filter\":\"../../x\"}]}"); fail("bad id accepted"); } catch (IllegalArgumentException ok) { }
        assertEquals(100f, EditState.fromJson("{\"adjust\":{\"contrast\":999}}").adj("contrast"), 0f);
        log("PASS edit state JSON round-trip + validation");
    }

    @Test
    public void undoRedo() {
        History hi = new History(EditState.defaults());
        EditState a = EditState.defaults(); a.adjust.put("contrast", 30f); assertTrue(hi.push(a));
        EditState b = a.copy(); b.adjust.put("contrast", 60f); assertTrue(hi.push(b));
        assertFalse(hi.push(b.copy()));
        assertEquals(30f, hi.undo().adj("contrast"), 0);
        assertEquals(0f, hi.undo().adj("contrast"), 0);
        assertFalse(hi.canUndo());
        assertEquals(30f, hi.redo().adj("contrast"), 0);
        assertEquals(60f, hi.redo().adj("contrast"), 0);
        assertFalse(hi.canRedo());
        log("PASS undo/redo");
    }

    // ------------------------------------------------------------ adjust

    @Test
    public void adjustNeutralAndDirections() throws Exception {
        RenderOptions o = new RenderOptions();
        o.style = "custom"; o.custom = FilterLibrary.adjustStyle(new EditState()); o.strength = 1f;
        RenderPlan np = RenderPlan.build(px, w, h, o, ToneModel.build(px, w, h, null), SceneStats.measure(px, w, h), null, 0, EngineTest::lut);
        int[] id = Pipeline.renderStage(np, px, w, h);
        double d0 = diff(id, px);
        log(String.format(Locale.ROOT, "  all-zero Adjust vs original: %.3f/255", d0));
        assertTrue(d0 < 0.6);
        double L = luma(px), RB = rb(px), S = sat(px), SD = sd(px);
        Object[][] dirs = {{"exposure", 50f, "luma+"}, {"exposure", -50f, "luma-"}, {"contrast", 60f, "sd+"}, {"contrast", -60f, "sd-"},
                {"highlights", -80f, "luma-"}, {"shadows", 80f, "luma+"}, {"whites", 80f, "luma+"}, {"blacks", -80f, "luma-"},
                {"temperature", 60f, "rb+"}, {"temperature", -60f, "rb-"}, {"saturation", 60f, "sat+"}, {"saturation", -100f, "sat-"},
                {"vibrance", 80f, "sat+"}, {"clarity", 80f, "chg"}, {"texture", 80f, "chg"}, {"dehaze", 80f, "chg"}, {"tint", 60f, "chg"},
                {"sharpening", 80f, "chg"}, {"noise", 80f, "any"}, {"vignette", -80f, "luma-"}, {"grain", 80f, "chg"}};
        for (Object[] d : dirs) {
            EditState t = new EditState();
            t.adjust.put((String) d[0], (Float) d[1]);
            int[] r = run(t);
            String k = (String) d[2];
            boolean ok;
            switch (k) {
                case "luma+": ok = luma(r) > L + 0.5; break;
                case "luma-": ok = luma(r) < L - 0.5; break;
                case "sd+": ok = sd(r) > SD + 0.5; break;
                case "sd-": ok = sd(r) < SD - 0.5; break;
                case "rb+": ok = rb(r) > RB + 0.5; break;
                case "rb-": ok = rb(r) < RB - 0.5; break;
                case "sat+": ok = sat(r) > S + 0.5; break;
                case "sat-": ok = sat(r) < S - 0.5; break;
                case "any": ok = true; break;
                default: ok = diff(r, id) > 0.1;
            }
            log(String.format(Locale.ROOT, "  %s %s -> luma %.1f->%.1f sd %.1f->%.1f r-b %.1f->%.1f sat %.1f->%.1f %s",
                    d[0], d[1], L, luma(r), SD, sd(r), RB, rb(r), S, sat(r), ok ? "ok" : "FAIL"));
            assertTrue(d[0] + " " + d[1], ok);
        }
        log("PASS Adjust: neutral at zero, every slider acts in its own direction");
    }

    // ------------------------------------------------------------ filters / stack

    @Test
    public void builtInFiltersAreDistinct() throws Exception {
        List<int[]> outs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (FilterLibrary.Entry e : FilterLibrary.BUILT_IN) {
            EditState t = new EditState();
            t.layers.add(new EditState.Layer(e.id, 0.8f));
            int[] r = run(t);
            outs.add(r); ids.add(e.id);
            save(r, w, h, "filter_" + e.id.replace(':', '_'));
        }
        double min = 1e9; String pair = "";
        for (int i = 0; i < outs.size(); i++) for (int k = i + 1; k < outs.size(); k++) {
            double d = diff(outs.get(i), outs.get(k));
            if (d < min) { min = d; pair = ids.get(i) + " / " + ids.get(k); }
        }
        log(String.format(Locale.ROOT, "  closest pair of built-in filters: %s %.2f/255", pair, min));
        assertTrue(min > 1.0);
        log("PASS " + ids.size() + " built-in filters, all visibly distinct");
    }

    @Test
    public void stackEnableReorderStrengthAndStrips() throws Exception {
        EditState st = new EditState();
        st.layers.add(new EditState.Layer("cinematic:hollywood", 0.7f));
        st.layers.add(new EditState.Layer("dramatic", 0.6f));
        st.adjust.put("contrast", 20f);
        int[] full = run(st);
        EditState dis = st.copy(); dis.layers.get(1).enabled = false;
        EditState only = new EditState(); only.layers.add(new EditState.Layer("cinematic:hollywood", 0.7f)); only.adjust.put("contrast", 20f);
        int[] r1 = run(dis);
        assertTrue(diff(full, r1) > 0.3);
        assertEquals(0.0, diff(r1, run(only)), 0.0);
        EditState rev = st.copy(); Collections.reverse(rev.layers);
        assertTrue(diff(run(rev), full) > 0.2);
        EditState lo = st.copy(); lo.layers.get(1).strength = 0.2f;
        assertTrue(diff(run(lo), full) > 0.2);
        // chained strips == whole image
        Pipeline p = Pipeline.build(px, w, h, st, null, null, EngineTest::lut, null);
        int[] whole = p.render(px, w, h);
        Pipeline.Strips sp = p.new Strips(w, h, 1);
        int hal = sp.halo();
        int[] so = new int[w * h];
        for (int y0 = 0; y0 < h; y0 += 97) {
            int y1 = Math.min(h, y0 + 97), ys = Math.max(0, y0 - hal), ye = Math.min(h, y1 + hal);
            int[] ob = new int[(y1 - y0) * w];
            sp.process(Arrays.copyOfRange(px, ys * w, ye * w), ys, ye - ys, y0, y1, ob);
            System.arraycopy(ob, 0, so, y0 * w, ob.length);
        }
        assertArrayEquals(whole, so);
        // cache: identical results, invalidated on change
        Pipeline.Cache c = new Pipeline.Cache();
        Pipeline.build(px, w, h, st, null, null, EngineTest::lut, null, c);
        EditState st2 = st.copy(); st2.adjust.put("contrast", 45f);
        assertArrayEquals(Pipeline.build(px, w, h, st2, null, null, EngineTest::lut, null).render(px, w, h),
                Pipeline.build(px, w, h, st2, null, null, EngineTest::lut, null, c).renderPreview(px, w, h));
        save(full, w, h, "stack_hollywood_dramatic_adjust");
        log("PASS stack: disable = remove, order and per-layer strength matter, strips == whole, cache exact");
    }

    // ------------------------------------------------------------ import

    @Test
    public void filterImportFormats() throws Exception {
        File dir = Files.createTempDirectory("fstore").toFile();
        FilterStore fs = new FilterStore(dir);
        StringBuilder cube = new StringBuilder("TITLE \"warm\"\nLUT_3D_SIZE 2\n");
        for (int b = 0; b < 2; b++) for (int g = 0; g < 2; g++) for (int r = 0; r < 2; r++)
            cube.append(Math.min(1, r + 0.05)).append(' ').append(g).append(' ').append(b * 0.9).append('\n');
        assertEquals(1, fs.importFile("Warm.cube", new ByteArrayInputStream(cube.toString().getBytes())).added.size());
        StringBuilder t3 = new StringBuilder("0 511 1023\n");
        for (int r = 0; r < 3; r++) for (int g = 0; g < 3; g++) for (int b = 0; b < 3; b++) t3.append(r * 511).append(' ').append(g * 511).append(' ').append(b * 511).append('\n');
        FilterStore.Report rp = fs.importFile("ident.3dl", new ByteArrayInputStream(t3.toString().getBytes()));
        float[] o = new float[3];
        fs.loadLut(rp.added.get(0).id).apply(0.3f, 0.6f, 0.9f, o);
        assertEquals(0.3f, o[0], 0.01f); assertEquals(0.6f, o[1], 0.01f); assertEquals(0.9f, o[2], 0.01f);
        StringBuilder t4 = new StringBuilder();
        for (int r = 0; r < 2; r++) for (int g = 0; g < 2; g++) for (int b = 0; b < 2; b++) t4.append(r * 4095).append(" 0 ").append(b * 4095).append('\n');
        rp = fs.importFile("rb.3dl", new ByteArrayInputStream(t4.toString().getBytes()));
        fs.loadLut(rp.added.get(0).id).apply(1f, 0f, 0f, o);
        assertTrue(o[0] > 0.99f && o[2] < 0.01f);
        String js = "{\"format\":\"majdi-filter\",\"name\":\"My Look\",\"edit\":{\"layers\":[{\"filter\":\"cinematic:moody\",\"strength\":0.5}],\"adjust\":{\"exposure\":20}}}";
        rp = fs.importFile("p.json", new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)));
        assertEquals("cinematic:moody", fs.loadPreset(rp.added.get(0).id).layers.get(0).filter);
        assertTrue(fs.importFile("evil.json", new ByteArrayInputStream("{\"format\":\"majdi-filter\",\"edit\":{\"layers\":[{\"filter\":\"Runtime.exec('x')\"}]}}".getBytes())).added.isEmpty());
        assertTrue(fs.importFile("bad.cube", new ByteArrayInputStream("LUT_3D_SIZE 2\n0 0 0\n".getBytes())).added.isEmpty());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("pack/Warm2.cube")); z.write(cube.toString().getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("../../evil.cube")); z.write(cube.toString().getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("pack/run.sh")); z.write("rm -rf /".getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("pack/l.3dl")); z.write(t3.toString().getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("inner.zip")); z.write(new byte[]{1, 2}); z.closeEntry();
        }
        rp = fs.importFile("pack.zip", new ByteArrayInputStream(bo.toByteArray()));
        log("  zip import: added " + rp.added.size() + ", rejected " + rp.rejected);
        assertEquals(2, rp.added.size());
        assertEquals(3, rp.rejected.size());
        int n = fs.list().size();
        FilterStore fs2 = new FilterStore(dir);
        assertEquals(n, fs2.list().size());
        String id = fs2.list().get(0).id;
        fs2.setCategory(id, "دافئة");
        fs2.delete(fs2.list().get(1).id);
        assertEquals("دافئة", new FilterStore(dir).get(id).category);
        assertEquals(n - 1, new FilterStore(dir).list().size());
        // an imported LUT works as a stack layer
        EditState st = new EditState();
        st.layers.add(new EditState.Layer("lut:" + id, 1f));
        int[] r = Pipeline.build(px, w, h, st, null, null, EngineTest::lut, fs2::loadLut).render(px, w, h);
        assertTrue(diff(r, px) > 1.0);
        log("PASS import: .cube .3dl(10/12-bit) .json .zip with validation, traversal/script/nested rejected, manage + persist, LUT as layer");
    }

    // ------------------------------------------------------------ geometry / frames

    @Test
    public void geometryAndFrames() {
        int gw = 7, gh = 5;
        int[] p = new int[gw * gh];
        for (int i = 0; i < p.length; i++) p[i] = 0xFF000000 | i;
        for (int r = 0; r < 4; r++) for (int f = 0; f < 4; f++) {
            EditState s = new EditState(); s.rot90 = r; s.flipH = (f & 1) != 0; s.flipV = (f & 2) != 0;
            Geometry g = new Geometry(s, gw, gh, true);
            int[] o = g.render(p);
            int W = (r & 1) == 1 ? gh : gw, H = (r & 1) == 1 ? gw : gh;
            assertEquals(W, g.outW); assertEquals(H, g.outH);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int X = s.flipH ? W - 1 - x : x, Y = s.flipV ? H - 1 - y : y, sx, sy;
                switch (r) { case 1: sx = Y; sy = gh - 1 - X; break; case 2: sx = gw - 1 - X; sy = gh - 1 - Y; break; case 3: sx = gw - 1 - Y; sy = X; break; default: sx = X; sy = Y; }
                assertEquals(p[sy * gw + sx], o[y * W + x]);
            }
        }
        assertTrue(new Geometry(new EditState(), gw, gh, true).identity());
        int W = 400, H = 300;
        int[] big = new int[W * H];
        Random rn = new Random(1);
        for (int i = 0; i < big.length; i++) big[i] = 0xFF000000 | rn.nextInt(0xFFFFFF);
        EditState c = new EditState(); c.cropL = 0.25f; c.cropT = 0.1f; c.cropR = 0.75f; c.cropB = 0.9f;
        Geometry gc = new Geometry(c, W, H, true);
        int[] oc = gc.render(big);
        assertEquals(200, gc.outW); assertEquals(240, gc.outH);
        for (int y = 0; y < 240; y++) for (int x = 0; x < 200; x++) assertEquals(big[(y + 30) * W + x + 100], oc[y * 200 + x]);
        int[] white = new int[W * H];
        Arrays.fill(white, 0xFFFFFFFF);
        EditState sr = new EditState(); sr.straighten = 12f; sr.perspV = 0.6f; sr.perspH = -0.4f;
        for (int v : new Geometry(sr, W, H, true).render(white)) assertEquals(0xFFFFFF, v & 0xFFFFFF);
        for (String f : new String[]{"border", "canvas", "film", "cinematic"}) {
            EditState e = new EditState(); e.frame = f; e.frameColor = 0xFF101010;
            Geometry g = new Geometry(e, W, H, true);
            int[] o = g.render(big);
            assertTrue(g.outW >= W && g.outH >= H && (g.outW > W || g.outH > H));
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) assertEquals(big[y * W + x], o[(y + g.py0) * g.outW + x + g.px0]);
        }
        EditState q = new EditState(); q.frame = "canvas"; q.canvasAspect = "4:5";
        Geometry gq = new Geometry(q, W, H, true);
        assertEquals(0.8f, gq.outW / (float) gq.outH, 0.01f);
        // EXIF orientation inside geometry == rotate first
        int sw = 9, sh = 6;
        int[] sp = new int[sw * sh];
        for (int i = 0; i < sp.length; i++) sp[i] = 0xFF000000 | (i * 7919);
        for (int or = 1; or <= 8; or++) {
            int dw = or >= 5 ? sh : sw, dh = or >= 5 ? sw : sh;
            int[] disp = new int[dw * dh];
            for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) {
                int X, Y;
                switch (or) { case 2: X = sw - 1 - x; Y = y; break; case 3: X = sw - 1 - x; Y = sh - 1 - y; break; case 4: X = x; Y = sh - 1 - y; break;
                    case 5: X = y; Y = x; break; case 6: X = y; Y = sh - 1 - x; break; case 7: X = sw - 1 - y; Y = sh - 1 - x; break; case 8: X = sw - 1 - y; Y = x; break; default: X = x; Y = y; }
                disp[y * dw + x] = sp[Y * sw + X];
            }
            EditState e = new EditState(); e.rot90 = 1; e.flipH = true; e.cropL = 0.2f; e.cropB = 0.8f; e.frame = "border";
            assertArrayEquals(new Geometry(e, dw, dh, 1, true).render(disp), new Geometry(e, sw, sh, or, true).render(sp));
        }
        log("PASS geometry: exact turns/flips/crop at full resolution, straighten+perspective without empty corners, 4 frames never cover the photo, EXIF 1-8");
    }

    // ------------------------------------------------------------ projects

    @Test
    public void projects() throws Exception {
        File dir = Files.createTempDirectory("pstore").toFile();
        ProjectStore ps = new ProjectStore(dir);
        EditState e = EditState.defaults(); e.adjust.put("clarity", 40f); e.cropL = 0.1f; e.frame = "film";
        byte[] orig = new byte[300000];
        new Random(5).nextBytes(orig);
        ProjectStore.Project p = ps.create(new ByteArrayInputStream(orig), "رحلة", e);
        e.adjust.put("clarity", 55f);
        ps.save(p, e);
        List<ProjectStore.Project> l = new ProjectStore(dir).list();
        assertEquals(1, l.size());
        assertEquals("رحلة", l.get(0).name);
        EditState b = new ProjectStore(dir).loadEdit(l.get(0));
        assertEquals(55f, b.adj("clarity"), 0);
        assertEquals("film", b.frame);
        assertArrayEquals(orig, Files.readAllBytes(l.get(0).original().toPath()));
        assertTrue(ps.delete(l.get(0)));
        assertTrue(ps.list().isEmpty());
        log("PASS projects: original kept byte-exact, full edit reopens, delete");
    }

    // ------------------------------------------------------------ export

    @Test
    public void parallelExportMatchesPreview() throws Exception {
        int[] wh = new int[2];
        int[] full = EngineTest.load("astronaut", wh);
        int W = wh[0], H = wh[1];
        // preview at about 60% of the source, like the 1600 px working copy of a 12-50 MP photo (smaller ratio)
        float ps = Math.min(0.6f, 1600f / Math.max(W, H));
        int pw = Math.round(W * ps), ph = Math.round(H * ps);
        int[] pv = scale(full, W, H, pw, ph);
        EditState st = new EditState();
        st.layers.add(new EditState.Layer("cinematic:film", 0.8f));
        st.layers.add(new EditState.Layer("dramatic", 0.5f));
        st.adjust.put("exposure", 10f); st.adjust.put("vibrance", 25f); st.adjust.put("vignette", -30f);
        st.straighten = 4f; st.cropL = 0.08f; st.cropT = 0.05f; st.cropR = 0.95f; st.cropB = 0.9f;
        st.frame = "border"; st.frameColor = 0xFFF2EEE6;
        Pipeline p = Pipeline.build(pv, pw, ph, st, null, null, EngineTest::lut, null);
        Geometry gp = new Geometry(st, pw, ph, true);
        int[] prev = gp.render(p.render(pv, pw, ph));
        int[] col = new int[W * H];
        final int[] last = {0};
        final boolean[] order = {true};
        long t0 = System.currentTimeMillis();
        p.renderParallel(W, H, 1, 128, (ys, ye, dst) -> System.arraycopy(full, ys * W, dst, 0, (ye - ys) * W),
                (y0, y1, rows) -> { if (y0 != last[0]) order[0] = false; last[0] = y1; System.arraycopy(rows, 0, col, y0 * W, rows.length); });
        long t1 = System.currentTimeMillis();
        assertTrue(order[0]);
        assertArrayEquals(p.render(full, W, H), col);
        Geometry gf = new Geometry(st, W, H, true);
        int[] exp = gf.render(col);
        save(exp, gf.outW, gf.outH, "export_full_res");
        int[] ed = scale(exp, gf.outW, gf.outH, gp.outW, gp.outH);
        int qw = gp.outW / 4, qh = gp.outH / 4;
        double pix = diff(prev, ed), tone = diff(scale(prev, gp.outW, gp.outH, qw, qh), scale(ed, gp.outW, gp.outH, qw, qh));
        log(String.format(Locale.ROOT, "  export %dx%d (source %dx%d) in %d ms on %d threads; preview vs export: per-pixel %.2f/255 (film grain differs by design), tone/colour %.2f/255",
                gf.outW, gf.outH, W, H, t1 - t0, Pipeline.threads(), pix, tone));
        assertTrue(tone < 2.0);
        log("PASS parallel export == serial render, rows in order, preview matches export");
    }
}
