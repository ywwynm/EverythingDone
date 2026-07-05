"""
Offline glyph-data generator for ALL styles.

One unified knob controls the hour/minute/second weight ladder: WT below. Variable
fonts are instanced at those weights; a few static families map to concrete weight
files; single-weight families reuse one file (hierarchy then comes from opacity).

Per style writes <out>/<style>.json:
  { "style":..., "N":128, "M":48, "advance":<float>,
    "h": {"0":{"outer":[...N], "holes":[[...M],...0..2]}, ...}, "m":{...}, "s":{...} }
Coords: x centred at 0, y DOWN (capTop=0, baseline=1), scaled by the medium-weight
figure height so all three weights share one box.

Usage:  python generate_glyph_data.py <fonts_dir> <assets_out_dir> <verify_out_dir> [style...]
"""
import os
import sys
import json
import numpy as np
from fontTools.ttLib import TTFont
from fontTools.ttLib.removeOverlaps import removeOverlaps
from fontTools.varLib.instancer import instantiateVariableFont
from matplotlib.textpath import TextPath
from matplotlib.font_manager import FontProperties

FONTS = sys.argv[1]
OUT = sys.argv[2]
VERIFY = sys.argv[3] if len(sys.argv) > 3 else OUT
TMP = os.path.join(VERIFY, "_clean")
os.makedirs(OUT, exist_ok=True)
os.makedirs(VERIFY, exist_ok=True)
os.makedirs(TMP, exist_ok=True)
SIZE = 1000.0
N, M = 128, 48
DIGITS = "0123456789"

# --- the single adjustable weight ladder (hour heaviest -> second lightest) ---
WT = {"h": 900, "m": 450, "s": 200}

# per style: 'VAR' = instance the variable base; dict = explicit static files;
# 'SINGLE' = one file for all three levels (hierarchy via opacity only).
STYLES = {
    "poppins": {"h": "poppins_black.ttf", "m": "poppins.ttf", "s": "poppins_extralight.ttf"},
    "comfortaa": "VAR", "orbitron": "VAR", "playfairdisplay": "VAR",
    "cormorantgaramond": "VAR", "lora": "VAR", "jetbrainsmono": "VAR", "dancingscript": "VAR",
    "zillaslab": {"h": "zillaslab_bold.ttf", "m": "zillaslab.ttf", "s": "zillaslab_light.ttf"},
    "abrilfatface": "SINGLE", "dmserifdisplay": "SINGLE", "pacifico": "SINGLE",
    "spacegrotesk": "VAR", "limelight": "SINGLE", "righteous": "SINGLE", "poiretone": "SINGLE",
    "majormonodisplay": "SINGLE", "genos": "VAR", "italiana": "SINGLE", "nixieone": "SINGLE",
    "outfit": "VAR",
}
ORDER = ["poppins", "comfortaa", "orbitron", "playfairdisplay", "abrilfatface",
         "cormorantgaramond", "zillaslab", "lora", "dmserifdisplay", "jetbrainsmono",
         "pacifico", "dancingscript", "spacegrotesk", "limelight", "righteous", "poiretone",
         "majormonodisplay", "genos", "italiana", "nixieone", "outfit"]
REQUESTED = sys.argv[4:]
if REQUESTED:
    unknown = sorted(set(REQUESTED) - set(STYLES.keys()))
    if unknown:
        raise SystemExit("unknown styles: " + ", ".join(unknown))
    ORDER = [style for style in ORDER if style in REQUESTED]

_clean_cache = {}


def wrange(path):
    f = TTFont(path)
    if "fvar" not in f:
        return None
    for a in f["fvar"].axes:
        if a.axisTag == "wght":
            return (a.minValue, a.maxValue)
    return None


def source_ttf(style, level):
    cfg = STYLES[style]
    if cfg == "VAR":
        key = "%s_%s" % (style, level)
        dst = os.path.join(TMP, key + ".ttf")
        if key not in _clean_cache:
            base = os.path.join(FONTS, style + ".ttf")
            f = TTFont(base)
            r = wrange(base)
            w = max(r[0], min(r[1], WT[level])) if r else WT[level]
            instantiateVariableFont(f, {"wght": w}, inplace=True)
            try:
                removeOverlaps(f)
            except Exception as e:
                print("  removeOverlaps skip", key, e)
            f.save(dst)
            _clean_cache[key] = dst
        return dst
    src_name = (style + ".ttf") if cfg == "SINGLE" else cfg[level]
    key = os.path.splitext(src_name)[0]
    dst = os.path.join(TMP, key + "_clean.ttf")
    if key not in _clean_cache:
        f = TTFont(os.path.join(FONTS, src_name))
        try:
            removeOverlaps(f)
        except Exception as e:
            print("  removeOverlaps skip", key, e)
        f.save(dst)
        _clean_cache[key] = dst
    return dst


def sarea(p):
    x, y = p[:, 0], p[:, 1]
    return 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)


def orient(p, ccw=True):
    a = sarea(p)
    return p[::-1].copy() if ((a < 0 and ccw) or (a > 0 and not ccw)) else p


def resample(poly, n):
    P = np.asarray(poly, float)
    Q = np.vstack([P, P[0]])
    seg = np.linalg.norm(np.diff(Q, axis=0), axis=1)
    L = np.concatenate([[0], np.cumsum(seg)])
    T = L[-1]
    if T <= 0:
        return np.repeat(P[:1], n, axis=0)
    t = np.linspace(0, T, n, endpoint=False)
    return np.stack([np.interp(t, L, Q[:, 0]), np.interp(t, L, Q[:, 1])], 1)


def align_top(poly):
    y = poly[:, 1]
    idx = np.where(y >= y.max() - 1e-9)[0]
    return np.roll(poly, -idx[np.argmin(poly[idx, 0])], axis=0)


def contours(ttf, ch):
    tp = TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf))
    return [np.asarray(p, float) for p in tp.to_polygons() if len(p) >= 3]


def metrics(ttf):
    mn = np.array([1e18, 1e18])
    mx = -mn
    for ch in DIGITS:
        for p in contours(ttf, ch):
            mn = np.minimum(mn, p.min(0))
            mx = np.maximum(mx, p.max(0))
    return mn[1], (mx[1] - mn[1])


def build_style(style):
    med = source_ttf(style, "m")
    miny, H = metrics(med)
    captop = miny + H

    def digit(ttf, ch):
        ps = contours(ttf, ch)
        if not ps:
            return None
        oi = int(np.argmax([abs(sarea(p)) for p in ps]))
        outer = align_top(resample(orient(ps[oi], ccw=True), N))
        holes = [orient(ps[i], ccw=False) for i in range(len(ps)) if i != oi]
        holes.sort(key=lambda p: -p[:, 1].mean())
        holes = [align_top(resample(h, M)) for h in holes[:2]]
        cx = (outer[:, 0].min() + outer[:, 0].max()) / 2.0

        def nrm(P):
            return np.stack([(P[:, 0] - cx) / H, (captop - P[:, 1]) / H], 1)
        o = nrm(outer)
        hs = [nrm(h) for h in holes]
        return {"outer": [round(float(v), 5) for v in o.reshape(-1)],
                "holes": [[round(float(v), 5) for v in h.reshape(-1)] for h in hs]}

    ws = [np.ptp(np.vstack(contours(med, c))[:, 0]) / H for c in DIGITS]
    data = {"style": style, "N": N, "M": M, "advance": round(max(ws) + 0.22, 5)}
    for lvl in ("h", "m", "s"):
        ttf = source_ttf(style, lvl)
        data[lvl] = {ch: digit(ttf, ch) for ch in DIGITS}
    with open(os.path.join(OUT, style + ".json"), "w") as fp:
        json.dump(data, fp, separators=(",", ":"))
    return data


all_data = {}
for st in ORDER:
    print("building", st)
    all_data[st] = build_style(st)
print("wrote %d style json files to %s" % (len(all_data), OUT))

# ---- verify: draw "01:29:36" per style straight from the JSON ----
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.path import Path as MPath
from matplotlib.patches import PathPatch, Circle

OP = {"h": 0.92, "m": 0.80, "s": 0.66}
GROUPS = [("01", "h"), ("29", "m"), ("36", "s")]
fig, ax = plt.subplots(figsize=(11.5, len(ORDER) * 0.92))
fig.patch.set_facecolor("#20242b")
ax.set_facecolor("#20242b")
PITCH = 1.75
maxx = 0.0
for i, st in enumerate(ORDER):
    d = all_data[st]
    y0 = (len(ORDER) - 1 - i) * PITCH
    adv = d["advance"]
    x = 0.0
    for gi, (grp, lvl) in enumerate(GROUPS):
        for ch in grp:
            g = d[lvl][ch]
            o = np.array(g["outer"]).reshape(-1, 2)
            hs = [np.array(h).reshape(-1, 2) for h in g["holes"]]
            V, C = [], []
            for ring in [o] + hs:
                r = ring.copy()
                r[:, 0] += x + adv / 2
                r[:, 1] += y0
                V.append(np.vstack([r, r[0]]))
                C += [MPath.MOVETO] + [MPath.LINETO] * (len(r) - 1) + [MPath.CLOSEPOLY]
            ax.add_patch(PathPatch(MPath(np.vstack(V), C), facecolor="white", alpha=OP[lvl], edgecolor="none"))
            x += adv
        if gi < 2:
            x += 0.05
            for yy in (0.30, 0.60):
                ax.add_patch(Circle((x, yy + y0), 0.05, color="white", alpha=OP["s"]))
            x += 0.20
    maxx = max(maxx, x)
    ax.text(-0.25, y0 + 0.5, st, ha="right", va="center", color="white", fontsize=10)
ax.set_xlim(-3.4, maxx + 0.2)
ax.set_ylim(-0.35, len(ORDER) * PITCH)
ax.invert_yaxis()
ax.set_aspect("equal")
ax.axis("off")
fig.savefig(os.path.join(VERIFY, "chooser_from_json.png"), dpi=110, bbox_inches="tight", facecolor="#20242b")
print("wrote verify chooser_from_json.png")
