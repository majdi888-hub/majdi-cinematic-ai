package com.majdi.cinematicai;

/** sRGB transfer functions as high-precision lookup tables. */
final class ColorMath {

    private ColorMath() {}

    static final double INV_LN2 = 1.0 / Math.log(2.0);
    static final float LN2 = (float) Math.log(2.0);

    /** 8-bit sRGB code value -> linear light [0,1]. */
    static final float[] SRGB_TO_LINEAR = new float[256];

    private static final int ENC_N = 8192;
    /** linear [0,1] -> sRGB in [0,255] (float), sampled with linear interpolation. */
    private static final float[] ENC = new float[ENC_N + 2];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= ENC_N + 1; i++) {
            double l = Math.min(1.0, i / (double) ENC_N);
            double e = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
            ENC[i] = (float) (e * 255.0);
        }
    }

    /** Linear light -> sRGB code value as float in [0,255] (no rounding). */
    static float encode(float l) {
        if (l <= 0f) return 0f;
        if (l >= 1f) return 255f;
        if (l < 0.0031308f) return l * 12.92f * 255f;
        float f = l * ENC_N;
        int i = (int) f;
        float t = f - i;
        return ENC[i] + (ENC[i + 1] - ENC[i]) * t;
    }

    static float exp2(float x) {
        return (float) Math.exp(x * LN2);
    }

    static float log2(float x) {
        return (float) (Math.log(x) * INV_LN2);
    }

    static float smooth(float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }
}
