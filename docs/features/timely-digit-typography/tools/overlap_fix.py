"""
Diagnose & fix spurious counters caused by OVERLAPPING contours in a glyph.

Cause of the Playfair "4" hole: the naive pipeline treats the largest contour as
the outer and EVERY other contour as a hole. Fonts often build a glyph from
several OVERLAPPING filled components (e.g. a "4" stem crossing its bar); forcing
such a filled component to hole-winding subtracts it, opening a false counter.

Fix: classify by winding SIGN relative to the largest contour -- same sign =
fill (boolean-union them), opposite sign = hole (subtract) -- so only true
counters survive. Renders Playfair 0-9 BEFORE vs AFTER and scans every font.

Usage:  python overlap_fix.py <fonts_dir> <out_dir>
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
from matplotlib.patches import PathPatch
from shapely.geometry import Polygon
from shapely.ops import unary_union

FONTS = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT = sys.argv[2] if len(sys.argv) > 2 else "previews"
os.makedirs(OUT, exist_ok=True)
SIZE = 1000.0
DIGITS = "0123456789"
INK = "#1c1c22"


def sarea(p):
    x, y = p[:, 0], p[:, 1]
    return 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)


def orient(p, ccw=True):
    a = sarea(p)
    if (a < 0 and ccw) or (a > 0 and not ccw):
        return p[::-1].copy()
    return p


def contours(ttf, ch):
    tp = TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf))
    return [np.asarray(p, float) for p in tp.to_polygons() if len(p) >= 3]


def naive(polys):
    """Current pipeline: largest = outer (CCW), every other = hole (CW)."""
    oi = int(np.argmax([abs(sarea(p)) for p in polys]))
    outer = orient(polys[oi], ccw=True)
    holes = [orient(polys[i], ccw=False) for i in range(len(polys)) if i != oi]
    return [(outer, holes)]


def unioned(polys):
    """Winding-correct: same-sign-as-largest = fill (union), opposite = hole."""
    areas = [sarea(p) for p in polys]
    oi = int(np.argmax([abs(a) for a in areas]))
    fs = np.sign(areas[oi])
    fills = [Polygon(polys[i]).buffer(0) for i in range(len(polys)) if np.sign(areas[i]) == fs]
    holes = [Polygon(polys[i]).buffer(0) for i in range(len(polys)) if np.sign(areas[i]) != fs]
    reg = unary_union(fills)
    if holes:
        reg = reg.difference(unary_union(holes))
    geoms = list(reg.geoms) if reg.geom_type == "MultiPolygon" else [reg]
    return [(np.array(g.exterior.coords), [np.array(r.coords) for r in g.interiors]) for g in geoms]


def cell(ax, parts):
    V, C, allpts = [], [], []
    for outer, holes in parts:
        for ring in [outer] + list(holes):
            allpts.append(ring)
            V.append(np.vstack([ring, ring[0]]))
            C += [Path.MOVETO] + [Path.LINETO] * (len(ring) - 1) + [Path.CLOSEPOLY]
    ax.add_patch(PathPatch(Path(np.vstack(V), C), facecolor=INK, edgecolor="none"))
    a = np.vstack(allpts)
    pad = (a[:, 1].max() - a[:, 1].min()) * 0.08 + 1
    ax.set_xlim(a[:, 0].min() - pad, a[:, 0].max() + pad)
    ax.set_ylim(a[:, 1].min() - pad, a[:, 1].max() + pad)
    ax.set_aspect("equal")
    ax.axis("off")


def hole_count(parts):
    return sum(len(h) for _, h in parts)


if __name__ == "__main__":
    pf = os.path.join(FONTS, "playfairdisplay.ttf")
    ps = contours(pf, "4")
    print("Playfair '4': %d raw contours" % len(ps))
    for i, p in enumerate(ps):
        bb = np.round([p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max()], 0)
        print("   contour %d: signed_area=%9.0f  bbox=%s" % (i, sarea(p), bb))
    print("   naive holes = %d   ->   union holes = %d"
          % (hole_count(naive(ps)), hole_count(unioned(ps))))

    fig, axes = plt.subplots(2, 10, figsize=(20, 4.8))
    fig.suptitle("Playfair Display   BEFORE (naive, top) vs AFTER (winding-correct union, bottom)", y=0.99)
    for ci, ch in enumerate(DIGITS):
        ps = contours(pf, ch)
        cell(axes[0, ci], naive(ps))
        cell(axes[1, ci], unioned(ps))
    fig.text(0.005, 0.72, "BEFORE", rotation=90, va="center", fontsize=12, color="#b00")
    fig.text(0.005, 0.28, "AFTER", rotation=90, va="center", fontsize=12, color="#0a0")
    out = os.path.join(OUT, "overlap_fix_playfair.png")
    fig.savefig(out, dpi=95, bbox_inches="tight")
    plt.close(fig)
    print("wrote", out)

    print("\nScan (glyphs whose naive hole-count differs from winding-correct, or split into parts):")
    fams = sorted(f[:-4] for f in os.listdir(FONTS) if f.endswith(".ttf"))
    for fam in fams:
        ttf = os.path.join(FONTS, fam + ".ttf")
        flags = []
        for ch in DIGITS:
            try:
                ps = contours(ttf, ch)
                nh = hole_count(naive(ps))
                u = unioned(ps)
                uh, parts = hole_count(u), len(u)
                if nh != uh or parts > 1:
                    flags.append("%s(n%d->u%d%s)" % (ch, nh, uh, ",%dparts" % parts if parts > 1 else ""))
            except Exception as e:
                flags.append("%s(ERR:%s)" % (ch, type(e).__name__))
        if flags:
            print("  %-20s %s" % (fam, " ".join(flags)))
