"""Generates the film-look 3D LUTs (.cube, 33^3) shipped with Majdi Cinematic AI.

Each look is a colour transform defined in Oklab (perceptual lightness/chroma/hue):
hue-band shifts (e.g. greens toward teal), per-hue chroma control, split toning that treats
shadows and highlights separately, a gentle film-print density curve and highlight roll-off.
Input/output are display-referred sRGB in [0,1]. Run: python3 tools/make_luts.py OUT_DIR
"""
import numpy as np, sys, os

def srgb_to_lin(c): return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def lin_to_srgb(c):
    c = np.clip(c, 0, 1); return np.where(c <= 0.0031308, 12.92 * c, 1.055 * c ** (1 / 2.4) - 0.055)

M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566], [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099], [0.0259040371, 0.7827717662, -0.8086757660]])
def lin_to_oklab(rgb): return np.cbrt(rgb @ M1.T) @ M2.T
def oklab_to_lin(lab):
    lms = lab @ np.linalg.inv(M2).T; return (lms ** 3) @ np.linalg.inv(M1).T

def hue_pull(h, anchor, width, amount):
    d = (anchor - h + 180) % 360 - 180            # signed distance toward anchor
    w = np.clip(1 - np.abs(d) / width, 0, 1); w = w * w * (3 - 2 * w)
    return h + d * w * amount
def band(h, center, width):
    d = np.abs((h - center + 180) % 360 - 180); w = np.clip(1 - d / width, 0, 1); return w * w * (3 - 2 * w)
def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)

def look(rgb, p):
    lin0 = srgb_to_lin(rgb)
    if p.get('mono'):
        # black & white with a yellow-orange filter response (skies deepen, skin stays luminous)
        y = lin0 @ np.array(p['mono'])
        lin0 = np.repeat(y[..., None], 3, axis=-1)
    lab = lin_to_oklab(lin0)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b); h = np.degrees(np.arctan2(b, a)) % 360
    neutral = smooth(0.02, 0.06, C)                # leave greys neutral before split toning
    for anchor, width, amt in p['pulls']: h = hue_pull(h, anchor, width, amt * neutral)
    g = np.ones_like(C)
    for center, width, gain in p['chroma']: g *= 1 + (gain - 1) * band(h, center, width)
    C = C * g * p['sat']
    C *= 1 - p['hl_desat'] * smooth(0.82, 1.0, L)
    a = C * np.cos(np.radians(h)); b = C * np.sin(np.radians(h))
    sw = (1 - smooth(0.15, 0.6, L)); hw = smooth(0.5, 0.95, L)
    sa, sb = p['shadow_tint']; ha, hb = p['high_tint']
    a = a + sw * sa + hw * ha; b = b + sw * sb + hw * hb
    pivot, k = 0.6, p['contrast']                  # film print density curve on Oklab L
    Lc = pivot + (L - pivot) * k
    Lc = p['black'] + (1 - p['black'] - (1 - p['white'])) * np.clip(Lc, 0, 1)
    L = L + (Lc - L) * smooth(0.0, 0.08, L)        # keep pure black stable
    lab2 = np.stack([L, a, b], -1)
    lin = oklab_to_lin(lab2)
    # gamut: pull chroma toward neutral until in range
    for _ in range(6):
        bad = (lin.min(-1) < -1e-4) | (lin.max(-1) > 1 + 1e-4)
        if not bad.any(): break
        lab2[bad, 1:] *= 0.85; lin = oklab_to_lin(lab2)
    return lin_to_srgb(lin)

LOOKS = {
  # Hollywood Cinematic: complementary teal/orange separation, warm skin and practicals, deep blacks
  'hollywood': dict(title='Majdi Hollywood Cinematic',
      pulls=[(215, 85, 0.50), (58, 50, 0.30), (140, 65, 0.60), (100, 40, 0.30), (270, 40, 0.30)],
      chroma=[(58, 45, 1.14), (215, 70, 1.22), (140, 55, 0.55), (330, 50, 0.65), (280, 40, 0.75)],
      sat=0.95, hl_desat=0.45, shadow_tint=(-0.032, -0.036), high_tint=(0.018, 0.032),
      contrast=1.10, black=0.022, white=0.985),
  # Moody Film: low-key, desaturated, green-teal shadows, muted highlights
  'moody_film': dict(title='Majdi Moody Film',
      pulls=[(170, 70, 0.40), (58, 40, 0.15), (240, 60, 0.30)],
      chroma=[(58, 45, 0.90), (30, 40, 0.85), (240, 60, 0.75), (140, 60, 0.70)],
      sat=0.72, hl_desat=0.55, shadow_tint=(-0.022, -0.004), high_tint=(0.006, 0.012),
      contrast=1.12, black=0.030, white=0.950),
  # Night Cinema: cool ambient night, warm practical lights (strong luminance colour separation)
  'night_cinema': dict(title='Majdi Night Cinema',
      pulls=[(235, 90, 0.45), (60, 45, 0.30), (150, 70, 0.60), (320, 45, 0.20)],
      chroma=[(60, 45, 1.18), (235, 80, 1.20), (150, 60, 0.55), (320, 45, 1.05)],
      sat=1.0, hl_desat=0.40, shadow_tint=(-0.024, -0.046), high_tint=(0.022, 0.034),
      contrast=1.10, black=0.012, white=0.99),
  # Film Look: colour-negative print feel: warm highlights, cyan-green shadow crossover, faded blacks
  'film_look': dict(title='Majdi Film Look',
      pulls=[(110, 55, 0.45), (40, 35, 0.20), (230, 60, 0.30)],
      chroma=[(40, 40, 1.08), (110, 45, 0.80), (230, 60, 0.82), (300, 50, 0.80)],
      sat=0.96, hl_desat=0.50, shadow_tint=(-0.014, 0.002), high_tint=(0.012, 0.030),
      contrast=1.04, black=0.040, white=0.960),
  # Natural Cinematic: gentle warmth and separation, colours stay true
  'natural_cinematic': dict(title='Majdi Natural Cinematic',
      pulls=[(110, 50, 0.20), (215, 60, 0.15)],
      chroma=[(30, 40, 1.04), (140, 50, 0.90)],
      sat=1.0, hl_desat=0.30, shadow_tint=(-0.006, -0.008), high_tint=(0.006, 0.012),
      contrast=1.03, black=0.012, white=0.99),
  # Dramatic print: restrained palette, cool shadows, silver-like highlights
  # Dramatic Noir: monochrome silver print with a cool-shadow / warm-highlight split tone
  'noir': dict(title='Majdi Dramatic Noir', mono=[0.42, 0.48, 0.10],
      pulls=[], chroma=[], sat=1.0, hl_desat=0.0, shadow_tint=(-0.004, -0.008), high_tint=(0.004, 0.008),
      contrast=1.14, black=0.008, white=0.99),
  'dramatic_print': dict(title='Majdi Dramatic Print',
      pulls=[(215, 70, 0.20), (58, 40, 0.10)],
      chroma=[(58, 45, 0.95), (140, 50, 0.80)],
      sat=0.82, hl_desat=0.50, shadow_tint=(-0.012, -0.020), high_tint=(0.008, 0.012),
      contrast=1.10, black=0.010, white=0.99),
}

def write_cube(path, title, n=33):
    v = np.linspace(0, 1, n); b, g, r = np.meshgrid(v, v, v, indexing='ij')
    rgb = np.stack([r, g, b], -1).reshape(-1, 3)
    out = look(rgb, LOOKS[title])
    with open(path, 'w') as f:
        f.write(f'TITLE "{LOOKS[title]["title"]}"\n# Generated by tools/make_luts.py (Majdi Cinematic AI)\nLUT_3D_SIZE {n}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
        for row in out: f.write(f'{row[0]:.6f} {row[1]:.6f} {row[2]:.6f}\n')

if __name__ == '__main__':
    od = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(od, exist_ok=True)
    for k in LOOKS: write_cube(os.path.join(od, k + '.cube'), k); print('wrote', k)
