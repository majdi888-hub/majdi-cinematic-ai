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
                data[count * 3] = Float.parseFloat(t[0]);
                data[count * 3 + 1] = Float.parseFloat(t[1]);
                data[count * 3 + 2] = Float.parseFloat(t[2]);
                count++;
            }
        }
        if (data == null || count != size * size * size) throw new IOException("incomplete LUT: " + count + " entries");
        return new Lut3D(title, size, data, dmin, dmax);
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
