package com.majdi.cinematicai;

/** User choices for one render. Defaults match the app's defaults (Fusion Pro at 65%). */
final class RenderOptions {
    String style = "fusion";
    String preset = "";            // cinematic sub-preset: hollywood, moody, night, film, natural_cinematic
    float strength = 0.65f;
    // Fusion Pro advanced controls
    float cinematic = 0.8f, dramatic = 0.6f, dslr = 0.7f, depthBlur = 0.5f;
    // lens simulation (visual approximation only)
    int focal = 50;
    float aperture = 2.8f;
    float[] focus;                 // tapped focus point {u, v} in display coordinates, or null
    Lut3D userLut;                 // replaces the Cinematic preset's LUT when set

    RenderOptions copy() {
        RenderOptions o = new RenderOptions();
        o.style = style; o.preset = preset; o.strength = strength;
        o.cinematic = cinematic; o.dramatic = dramatic; o.dslr = dslr; o.depthBlur = depthBlur;
        o.focal = focal; o.aperture = aperture; o.focus = focus == null ? null : focus.clone(); o.userLut = userLut;
        return o;
    }
}
