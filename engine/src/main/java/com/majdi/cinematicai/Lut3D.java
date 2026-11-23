package com.majdi.cinematicai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 3D colour lookup table in the Adobe/Resolve .cube format, applied with tetrahedral
 * interpolation (the method used by professional grading tools; smoother than trilinear
 * on neutral axes and free of hue banding).
 */
final class Lut3D {

    final String title;
    final int size;
    private final float[] data;      // r fastest: index = (b*size + g)*size + r, 3 floats each
    private final float[] dmin = {0f, 0f, 0f}, dmax = {1f, 1f, 1f};

    private Lut3D(String title, int size, float[] data, float[] dmin, float[] dmax) {
        this.title = title;
        this.size = size;
        this.data = data;
        System.arraycopy(dmin, 0, this.dmin, 0, 3);
        System.arraycopy(dmax, 0, this.dmax, 0, 3);
    }

    static Lut3D parse(InputStream in) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String title = "LUT";
        int size = -1;
        float[] dmin = {0f, 0f, 0f}, dmax = {1f, 1f, 1f};
        float[] data = null;
        int count = 0;
        String line;
        while ((line = rd.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            String k = t[0].toUpperCase();
            if (k.equals("TITLE")) {
                int q = line.indexOf('"');
                title = q >= 0 ? line.substring(q + 1, Math.max(q + 1, line.lastIndexOf('"'))) : line.substring(5).trim();
            } else if (k.equals("LUT_3D_SIZE")) {
                size = Integer.parseInt(t[1]);
                if (size < 2 || size > 129) throw new IOException("unsupported LUT size " + size);
                data = new float[size * size * size * 3];
            } else if (k.equals("LUT_1D_SIZE")) {
                throw new IOException("1D LUTs are not supported");
            } else if (k.equals("DOMAIN_MIN")) {
                for (int i = 0; i < 3; i++) dmin[i] = Float.parseFloat(t[i + 1]);
            } else if (k.equals("DOMAIN_MAX")) {
                for (int i = 0; i < 3; i++) dmax[i] = Float.parseFloat(t[i + 1]);
            } else if (Character.isDigit(k.charAt(0)) || k.charAt(0) == '-' || k.charAt(0) == '.') {
                if (data == null) throw new IOException("LUT data before LUT_3D_SIZE");
                if (t.length < 3) throw new IOException("bad LUT line: " + line);
                if (count >= size * size * size) throw new IOException("too many LUT entries");
                for (int c = 0; c < 3; c++) {
                    float v = Float.parseFloat(t[c]);
                    if (Float.isNaN(v) || Float.isInfinite(v) || v < -4f || v > 16f) throw new IOException("invalid LUT value: " + line);
                    data[count * 3 + c] = v;
                }
                count++;
            }
        }
        if (data == null || count != size * size * size) throw new IOException("incomplete LUT: " + count + " entries");
        return new Lut3D(title, size, data, dmin, dmax);
    }

    /**
     * Autodesk Lustre / Flame .3dl: an optional input-shaper line of N integers (mesh points),
     * then N^3 lines of integer RGB triples, BLUE varying fastest, scaled to the file's bit depth
     * (detected from the maximum value: 10, 12 or 16 bit). Converted to the .cube layout.
     */
    static Lut3D parse3dl(InputStream in, String title) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        java.util.List<int[]> rows = new java.util.ArrayList<>();
        int[] shaper = null;
        String line;
        while ((line = rd.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("<") || line.startsWith("3DMESH") || line.startsWith("Mesh")) continue;
            String[] t = line.split("\\s+");
            if (!t[0].matches("-?\\d+")) throw new IOException("bad .3dl line: " + line);
            int[] v = new int[t.length];
            for (int i = 0; i < t.length; i++) v[i] = Integer.parseInt(t[i]);
            if (v.length > 3 && shaper == null && rows.isEmpty()) { shaper = v; continue; }
            if (v.length != 3) throw new IOException("bad .3dl line: " + line);
            rows.add(v);
            if (rows.size() > 129 * 129 * 129) throw new IOException("LUT too large");
        }
        if (shaper == null && rows.size() > 1) {
            // a 3-point shaper line looks like a data row: it is one when the rest forms the cube
            int m = (int) Math.round(Math.cbrt(rows.size() - 1));
            int[] f = rows.get(0);
            if (m == 3 && m * m * m == rows.size() - 1 && f[0] == 0 && f[1] > f[0] && f[2] > f[1]) shaper = rows.remove(0);
        }
        int n = (int) Math.round(Math.cbrt(rows.size()));
        if (n < 2 || n * n * n != rows.size()) throw new IOException(".3dl: " + rows.size() + " entries is not a cube");
        if (shaper != null && shaper.length != n) throw new IOException(".3dl: shaper/mesh size mismatch");
        int mx = 0;
        for (int[] v : rows) for (int c : v) { if (c < 0) throw new IOException(".3dl: negative value"); mx = Math.max(mx, c); }
        float scale = mx <= 1023 ? 1023f : (mx <= 4095 ? 4095f : 65535f);
        float[] data = new float[n * n * n * 3];
        int i = 0;
        for (int r = 0; r < n; r++) for (int g = 0; g < n; g++) for (int b = 0; b < n; b++, i++) {
            int[] v = rows.get(i);
            int o = ((b * n + g) * n + r) * 3;
            data[o] = v[0] / scale; data[o + 1] = v[1] / scale; data[o + 2] = v[2] / scale;
        }
        return new Lut3D(title, n, data, new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }

    /** Writes this LUT as a .cube file (how imported LUTs of every format are stored). */
    void writeCube(java.io.OutputStream os) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("TITLE \"").append(title.replace('"', '\'')).append("\"\n");
        sb.append("LUT_3D_SIZE ").append(size).append('\n');
        sb.append(String.format(java.util.Locale.ROOT, "DOMAIN_MIN %.6f %.6f %.6f%nDOMAIN_MAX %.6f %.6f %.6f%n", dmin[0], dmin[1], dmin[2], dmax[0], dmax[1], dmax[2]));
        for (int i = 0; i < size * size * size; i++)
            sb.append(String.format(java.util.Locale.ROOT, "%.6f %.6f %.6f%n", data[i * 3], data[i * 3 + 1], data[i * 3 + 2]));
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Applies the LUT to display-encoded values in [0,1]; writes the result into out[0..2]. */
    void apply(float r, float g, float b, float[] out) {
        int n = size - 1;
        float fr = clamp01((r - dmin[0]) / (dmax[0] - dmin[0])) * n;
        float fg = clamp01((g - dmin[1]) / (dmax[1] - dmin[1])) * n;
        float fb = clamp01((b - dmin[2]) / (dmax[2] - dmin[2])) * n;
        int r0 = Math.min(n - 1, (int) fr), g0 = Math.min(n - 1, (int) fg), b0 = Math.min(n - 1, (int) fb);
        float dr = fr - r0, dg = fg - g0, db = fb - b0;
        int c000 = idx(r0, g0, b0), c111 = idx(r0 + 1, g0 + 1, b0 + 1);
        int cA, cB;
        float w0, w1, w2, w3;
        if (dr >= dg) {
            if (dg >= db) {        // r > g > b
                cA = idx(r0 + 1, g0, b0); cB = idx(r0 + 1, g0 + 1, b0);
                w0 = 1f - dr; w1 = dr - dg; w2 = dg - db; w3 = db;
            } else if (dr >= db) { // r > b > g
                cA = idx(r0 + 1, g0, b0); cB = idx(r0 + 1, g0, b0 + 1);
                w0 = 1f - dr; w1 = dr - db; w2 = db - dg; w3 = dg;
            } else {               // b > r > g
                cA = idx(r0, g0, b0 + 1); cB = idx(r0 + 1, g0, b0 + 1);
                w0 = 1f - db; w1 = db - dr; w2 = dr - dg; w3 = dg;
            }
        } else {
            if (db >= dg) {        // b > g > r
                cA = idx(r0, g0, b0 + 1); cB = idx(r0, g0 + 1, b0 + 1);
                w0 = 1f - db; w1 = db - dg; w2 = dg - dr; w3 = dr;
            } else if (db >= dr) { // g > b > r
                cA = idx(r0, g0 + 1, b0); cB = idx(r0, g0 + 1, b0 + 1);
                w0 = 1f - dg; w1 = dg - db; w2 = db - dr; w3 = dr;
            } else {               // g > r > b
                cA = idx(r0, g0 + 1, b0); cB = idx(r0 + 1, g0 + 1, b0);
                w0 = 1f - dg; w1 = dg - dr; w2 = dr - db; w3 = db;
            }
        }
        for (int c = 0; c < 3; c++) {
            out[c] = w0 * data[c000 + c] + w1 * data[cA + c] + w2 * data[cB + c] + w3 * data[c111 + c];
        }
    }

    private int idx(int r, int g, int b) {
        return ((b * size + g) * size + r) * 3;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
