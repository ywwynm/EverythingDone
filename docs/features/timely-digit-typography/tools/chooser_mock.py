"""
Final chooser preview: all styles as "01:29:36", ONE ROW PER STYLE, white on dark.

Combines every locked decision so the whole thing can be judged together:
  - Option A filled outlines (real font shapes, no skeleton)
  - overlapping-contour resolution (shapely winding-correct union)
  - hour/minute/second WEIGHT ladder (variable fonts instanced heavy/med/light;
    Poppins uses its Bold/Regular/Light files) + opacity ladder (0.92/0.80/0.66)
  - consistent optical size & baseline across styles (each normalized by its own
    figure height, all drawn at unit height in one equal-aspect axes)

Usage:  python chooser_mock.py <fonts_dir> <out_dir>
"""
import os
import sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.textpath import TextPath
from matplotlib.font_manager import FontProperties
from matplotlib.path import Path
from matplotlib.patches import PathPatch, Circle
from shapely.geometry import Polygon
from shapely.ops import unary_union
from fontTools import ttLib
from fontTools.varLib.instancer import instantiateVariableFont

FONTS = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT = sys.argv[2] if len(sys.argv) > 2 else "previews"
INST = os.path.join(OUT, "_inst")
os.makedirs(OUT, exist_ok=True)
os.makedirs(INST, exist_ok=True)

SIZE = 1000.0
BG = "#20242b"
TIME_GROUPS = [("01", "h"), ("29", "m"), ("36", "s")]
W = {"h": 700, "m": 500, "s": 300}
OP = {"h": 0.92, "m": 0.80, "s": 0.66}
STYLES = [("poppins", "Geometric · Poppins"), ("comfortaa", "Rounded · Comfortaa"),
          ("orbitron", "Technical · Orbitron"), ("playfairdisplay", "Didone · Playfair Display"),
          ("abrilfatface", "Display · Abril Fatface"), ("cormorantgaramond", "Elegant · Cormorant"),
          ("zillaslab", "Slab · Zilla Slab"), ("lora", "Serif · Lora"),
          ("dmserifdisplay", "High-contrast · DM Serif"), ("jetbrainsmono", "Mono · JetBrains Mono"),
          ("pacifico", "Script · Pacifico"), ("dancingscript", "Script · Dancing Script")]
VARIABLE = {"comfortaa", "orbitron", "playfairdisplay", "cormorantgaramond", "lora",
            "jetbrainsmono", "dancingscript"}


def wrange(path):
    f = ttLib.TTFont(path)
    if "fvar" not in f:
        return None
    for a in f["fvar"].axes:
        if a.axisTag == "wght":
            return (a.minValue, a.maxValue)
    return None


def font_for(fam, level):
    base = os.path.join(FONTS, fam + ".ttf")
    if fam in VARIABLE:
        out = os.path.join(INST, f"{fam}_{level}.ttf")
        if not os.path.exists(out):
            f = ttLib.TTFont(base)
            r = wrange(base)
            wght = max(r[0], min(r[1], W[level])) if r else W[level]
            instantiateVariableFont(f, {"wght": wght}, inplace=True)
            f.save(out)
        return out
    if fam == "poppins":
        return {"h": os.path.join(FONTS, "poppins_bold.ttf"), "m": base,
                "s": os.path.join(FONTS, "poppins_light.ttf")}[level]
    return base


def sarea(p):
    x, y = p[:, 0], p[:, 1]
    return 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)


def clean_contours(ttf, ch):
    tp = TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf))
    polys = [np.asarray(p, float) for p in tp.to_polygons() if len(p) >= 3]
    if not polys:
        return []
    areas = [sarea(p) for p in polys]
    oi = int(np.argmax([abs(a) for a in areas]))
    fs = np.sign(areas[oi])
    fills = [Polygon(polys[i]).buffer(0) for i in range(len(polys)) if np.sign(areas[i]) == fs]
    holes = [Polygon(polys[i]).buffer(0) for i in range(len(polys)) if np.sign(areas[i]) != fs]
    reg = unary_union(fills)
    if holes:
        reg = reg.difference(unary_union(holes))
    geoms = list(reg.geoms) if reg.geom_type == "MultiPolygon" else [reg]
    out = []
    for g in geoms:
        if not g.is_empty and g.geom_type == "Polygon":
            out.append((np.array(g.exterior.coords), [np.array(r.coords) for r in g.interiors]))
    return out


def metrics(ttf):
    mn = np.array([1e18, 1e18])
    mx = -mn
    for ch in "0123456789":
        for p in TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf)).to_polygons():
            p = np.asarray(p, float)
            mn = np.minimum(mn, p.min(0))
            mx = np.maximum(mx, p.max(0))
    return mn[1], (mx[1] - mn[1])


PITCH = 1.75
fig, ax = plt.subplots(figsize=(11.5, len(STYLES) * 0.92))
fig.patch.set_facecolor(BG)
ax.set_facecolor(BG)
maxx = 0.0
for i, (fam, label) in enumerate(STYLES):
    y0 = (len(STYLES) - 1 - i) * PITCH
    med = font_for(fam, "m")
    miny, H = metrics(med)

    def gw(ttf, ch):
        try:
            parts = clean_contours(ttf, ch)
            a = np.vstack([r for o, hs in parts for r in [o] + list(hs)])
            return (a[:, 0].max() - a[:, 0].min()) / H
        except Exception:
            return 0.45
    adv = max(gw(med, c) for c in "0123456789") + 0.26
    x = 0.0
    for gi, (grp, lvl) in enumerate(TIME_GROUPS):
        ttf, op = font_for(fam, lvl), OP[lvl]
        for ch in grp:
            try:
                parts = clean_contours(ttf, ch)
            except Exception:
                parts = []
            if parts:
                allp = np.vstack([r for o, hs in parts for r in [o] + list(hs)])
                cx = ((allp[:, 0].min() + allp[:, 0].max()) / 2) / H
                V, C = [], []
                for o, hs in parts:
                    for ring in [o] + list(hs):
                        rr = np.column_stack([ring[:, 0] / H - cx + x + adv / 2, (ring[:, 1] - miny) / H + y0])
                        V.append(np.vstack([rr, rr[0]]))
                        C += [Path.MOVETO] + [Path.LINETO] * (len(ring) - 1) + [Path.CLOSEPOLY]
                ax.add_patch(PathPatch(Path(np.vstack(V), C), facecolor="white", alpha=op, edgecolor="none"))
            x += adv
        if gi < 2:
            x += 0.05
            for yy in (0.30, 0.60):
                ax.add_patch(Circle((x, yy + y0), 0.052, color="white", alpha=OP["s"]))
            x += 0.20
    maxx = max(maxx, x)
    ax.text(-0.3, y0 + 0.5, label, ha="right", va="center", color="white", fontsize=11)

ax.set_xlim(-3.6, maxx + 0.2)
ax.set_ylim(-0.35, len(STYLES) * PITCH)
ax.set_aspect("equal")
ax.axis("off")
out = os.path.join(OUT, "chooser_mock.png")
fig.savefig(out, dpi=115, bbox_inches="tight", facecolor=BG)
print("wrote", out)
