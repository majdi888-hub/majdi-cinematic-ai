package com.majdi.cinematicai;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Baseline JPEG encoder (YCbCr 4:2:0) that receives the image row by row, so a photo of any
 * resolution can be written while holding only 16 rows in memory. Pure Java.
 */
final class JpegStreamWriter {

    private static final int[] ZZ = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private static final int[] STD_Y = {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

    private static final int[] STD_C = {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    private static final int[] DC_Y_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] DC_C_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] DC_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

    private static final int[] AC_Y_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    private static final int[] AC_Y_VALS = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    private static final int[] AC_C_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    private static final int[] AC_C_VALS = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    private static final float[] AAN = {1.0f, 1.387039845f, 1.306562965f, 1.175875602f,
            1.0f, 0.785694958f, 0.541196100f, 0.275899379f};

    private final OutputStream out;
    private final int w, h, padW;
    private final int[] qY = new int[64], qC = new int[64];
    private final float[] fdY = new float[64], fdC = new float[64];
    private final int[] dcYc = new int[12], dcYs = new int[12], dcCc = new int[12], dcCs = new int[12];
    private final int[] acYc = new int[256], acYs = new int[256], acCc = new int[256], acCs = new int[256];

    private final float[] Yp, Cbp, Crp;   // 16 rows of Y, 8 rows of subsampled Cb/Cr
    private final int[] lastRow;
    private int bufRows = 0, rowsReceived = 0;
    private int bitBuf = 0, bitCnt = 0;
    private int predY = 0, predCb = 0, predCr = 0;
    private final float[] blk = new float[64];
    private final int[] coef = new int[64];
    private final byte[] obuf = new byte[1 << 16];
    private int opos = 0;
    private boolean finished = false;

    /**
     * @param orientation EXIF orientation (1..8) to store in the file; 1 means none.
     */
    JpegStreamWriter(OutputStream out, int width, int height, int quality, int orientation) throws IOException {
        this.out = out;
        this.w = width;
        this.h = height;
        this.padW = (width + 15) & ~15;
        Yp = new float[padW * 16];
        Cbp = new float[(padW / 2) * 8];
        Crp = new float[(padW / 2) * 8];
        lastRow = new int[width];

        quality = Math.max(1, Math.min(100, quality));
        int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        for (int i = 0; i < 64; i++) {
            qY[i] = Math.max(1, Math.min(255, (STD_Y[i] * scale + 50) / 100));
            qC[i] = Math.max(1, Math.min(255, (STD_C[i] * scale + 50) / 100));
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int i = r * 8 + c;
                fdY[i] = 1f / (qY[i] * AAN[r] * AAN[c] * 8f);
                fdC[i] = 1f / (qC[i] * AAN[r] * AAN[c] * 8f);
            }
        }
        buildHuff(DC_Y_BITS, DC_VALS, dcYc, dcYs);
        buildHuff(DC_C_BITS, DC_VALS, dcCc, dcCs);
        buildHuff(AC_Y_BITS, AC_Y_VALS, acYc, acYs);
        buildHuff(AC_C_BITS, AC_C_VALS, acCc, acCs);
        writeHeaders(orientation);
    }

    private static void buildHuff(int[] bits, int[] vals, int[] code, int[] size) {
        int c = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len - 1]; i++) {
                code[vals[k]] = c;
                size[vals[k]] = len;
                c++;
                k++;
            }
            c <<= 1;
        }
    }

    // ---------------------------------------------------------------- headers

    private void b(int v) throws IOException {
        if (opos == obuf.length) flushBuf();
        obuf[opos++] = (byte) v;
    }

    private void w16(int v) throws IOException {
        b(v >> 8);
        b(v);
    }

    private void flushBuf() throws IOException {
        out.write(obuf, 0, opos);
        opos = 0;
    }

    private void writeHeaders(int orientation) throws IOException {
        w16(0xFFD8);
        if (orientation >= 2 && orientation <= 8) {
            w16(0xFFE1);
            w16(34);
            b('E'); b('x'); b('i'); b('f'); b(0); b(0);
            b('M'); b('M'); w16(42); w16(0); w16(8);     // TIFF header, IFD at offset 8
            w16(1);                                       // one entry
            w16(0x0112); w16(3); w16(0); w16(1);          // Orientation, SHORT, count 1
            w16(orientation); w16(0);
            w16(0); w16(0);                               // no next IFD
        } else {
            w16(0xFFE0);
            w16(16);
            b('J'); b('F'); b('I'); b('F'); b(0);
            b(1); b(1); b(0); w16(1); w16(1); b(0); b(0);
        }
        w16(0xFFDB);
        w16(2 + 2 * 65);
        b(0);
        for (int i = 0; i < 64; i++) b(qY[ZZ[i]]);
        b(1);
        for (int i = 0; i < 64; i++) b(qC[ZZ[i]]);

        w16(0xFFC0);
        w16(17);
        b(8); w16(h); w16(w); b(3);
        b(1); b(0x22); b(0);
        b(2); b(0x11); b(1);
        b(3); b(0x11); b(1);

        w16(0xFFC4);
        w16(2 + (1 + 16 + 12) * 2 + (1 + 16 + 162) * 2);
        writeHuff(0x00, DC_Y_BITS, DC_VALS);
        writeHuff(0x10, AC_Y_BITS, AC_Y_VALS);
        writeHuff(0x01, DC_C_BITS, DC_VALS);
        writeHuff(0x11, AC_C_BITS, AC_C_VALS);

        w16(0xFFDA);
        w16(12);
        b(3);
        b(1); b(0x00);
        b(2); b(0x11);
        b(3); b(0x11);
        b(0); b(63); b(0);
    }

    private void writeHuff(int id, int[] bits, int[] vals) throws IOException {
        b(id);
        for (int v : bits) b(v);
        for (int v : vals) b(v);
    }

    // ---------------------------------------------------------------- pixel input

    /** Adds {@code count} full-width ARGB rows from {@code px} starting at {@code offset}. */
    void writeRows(int[] px, int offset, int count) throws IOException {
        for (int r = 0; r < count && rowsReceived < h; r++) {
            addRow(px, offset + r * w);
            rowsReceived++;
            if (rowsReceived == h) {
                while (bufRows != 0) addRow(lastRow, 0);
            }
        }
    }

    private void addRow(int[] px, int off) throws IOException {
        int yRow = bufRows * padW;
        int cRow = (bufRows >> 1) * (padW / 2);
        boolean firstOfPair = (bufRows & 1) == 0;
        for (int x = 0; x < padW; x++) {
            int c = px[off + Math.min(x, w - 1)];
            int R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            Yp[yRow + x] = 0.299f * R + 0.587f * G + 0.114f * B - 128f;
            float cb = -0.168736f * R - 0.331264f * G + 0.5f * B;
            float cr = 0.5f * R - 0.418688f * G - 0.081312f * B;
            int ci = cRow + (x >> 1);
            if (firstOfPair && (x & 1) == 0) {
                Cbp[ci] = cb * 0.25f;
                Crp[ci] = cr * 0.25f;
            } else {
                Cbp[ci] += cb * 0.25f;
                Crp[ci] += cr * 0.25f;
            }
        }
        if (px != lastRow) System.arraycopy(px, off, lastRow, 0, w);
        bufRows++;
        if (bufRows == 16) {
            encodeMcuRow();
            bufRows = 0;
        }
    }

    private void encodeMcuRow() throws IOException {
        int cw = padW / 2;
        for (int mx = 0; mx < padW; mx += 16) {
            for (int by = 0; by < 16; by += 8) {
                for (int bx = 0; bx < 16; bx += 8) {
                    for (int r = 0; r < 8; r++) {
                        System.arraycopy(Yp, (by + r) * padW + mx + bx, blk, r * 8, 8);
                    }
                    predY = encodeBlock(blk, fdY, predY, dcYc, dcYs, acYc, acYs);
                }
            }
            int c0 = mx >> 1;
            for (int r = 0; r < 8; r++) System.arraycopy(Cbp, r * cw + c0, blk, r * 8, 8);
            predCb = encodeBlock(blk, fdC, predCb, dcCc, dcCs, acCc, acCs);
            for (int r = 0; r < 8; r++) System.arraycopy(Crp, r * cw + c0, blk, r * 8, 8);
            predCr = encodeBlock(blk, fdC, predCr, dcCc, dcCs, acCc, acCs);
        }
    }

    // ---------------------------------------------------------------- entropy coding

    private int encodeBlock(float[] d, float[] fd, int pred, int[] dcC, int[] dcS, int[] acC, int[] acS)
            throws IOException {
        fdct(d);
        for (int i = 0; i < 64; i++) {
            int n = ZZ[i];
            coef[i] = Math.round(d[n] * fd[n]);
        }
        int dc = coef[0];
        int diff = dc - pred;
        int nb = bitLen(diff);
        putBits(dcC[nb], dcS[nb]);
        if (nb > 0) putBits(diff < 0 ? (diff - 1) & ((1 << nb) - 1) : diff, nb);

        int run = 0;
        for (int i = 1; i < 64; i++) {
            int v = coef[i];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                putBits(acC[0xF0], acS[0xF0]);
                run -= 16;
            }
            nb = bitLen(v);
            int sym = (run << 4) | nb;
            putBits(acC[sym], acS[sym]);
            putBits(v < 0 ? (v - 1) & ((1 << nb) - 1) : v, nb);
            run = 0;
        }
        if (run > 0) putBits(acC[0x00], acS[0x00]);
        return dc;
    }

    private static int bitLen(int v) {
        if (v < 0) v = -v;
        return v == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(v);
    }

    private void putBits(int code, int size) throws IOException {
        bitBuf = (bitBuf << size) | (code & ((1 << size) - 1));
        bitCnt += size;
        while (bitCnt >= 8) {
            int byt = (bitBuf >> (bitCnt - 8)) & 0xFF;
            b(byt);
            if (byt == 0xFF) b(0);
            bitCnt -= 8;
        }
        bitBuf &= (1 << bitCnt) - 1;
    }

    private static void fdct(float[] d) {
        for (int p = 0; p < 64; p += 8) pass(d, p, 1);
        for (int p = 0; p < 8; p++) pass(d, p, 8);
    }

    private static void pass(float[] d, int o, int s) {
        float d0 = d[o], d1 = d[o + s], d2 = d[o + 2 * s], d3 = d[o + 3 * s];
        float d4 = d[o + 4 * s], d5 = d[o + 5 * s], d6 = d[o + 6 * s], d7 = d[o + 7 * s];
        float t0 = d0 + d7, t7 = d0 - d7, t1 = d1 + d6, t6 = d1 - d6;
        float t2 = d2 + d5, t5 = d2 - d5, t3 = d3 + d4, t4 = d3 - d4;
        float t10 = t0 + t3, t13 = t0 - t3, t11 = t1 + t2, t12 = t1 - t2;
        d[o] = t10 + t11;
        d[o + 4 * s] = t10 - t11;
        float z1 = (t12 + t13) * 0.707106781f;
        d[o + 2 * s] = t13 + z1;
        d[o + 6 * s] = t13 - z1;
        t10 = t4 + t5;
        t11 = t5 + t6;
        t12 = t6 + t7;
        float z5 = (t10 - t12) * 0.382683433f;
        float z2 = 0.541196100f * t10 + z5;
        float z4 = 1.306562965f * t12 + z5;
        float z3 = t11 * 0.707106781f;
        float z11 = t7 + z3, z13 = t7 - z3;
        d[o + 5 * s] = z13 + z2;
        d[o + 3 * s] = z13 - z2;
        d[o + s] = z11 + z4;
        d[o + 7 * s] = z11 - z4;
    }

    /** Pads remaining rows if needed, flushes entropy data and writes the end marker. */
    void finish() throws IOException {
        if (finished) return;
        finished = true;
        if (rowsReceived < h) {
            while (rowsReceived < h) {
                addRow(lastRow, 0);
                rowsReceived++;
            }
            while (bufRows != 0) addRow(lastRow, 0);
        }
        if (bitCnt > 0) putBits((1 << (8 - bitCnt)) - 1, 8 - bitCnt);
        w16(0xFFD9);
        flushBuf();
        out.flush();
    }
}
