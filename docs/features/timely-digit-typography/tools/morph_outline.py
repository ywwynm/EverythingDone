"""
Prototype v2: fixed-N filled-OUTLINE morph with flubber-style correspondence.

Each digit -> outer contour + hole contours (top-first). Every contour is
arc-length resampled to a fixed point count. For a given transition A->B we:
  1. rotate B's outer to the cyclic offset that minimizes summed squared travel
     vs A's outer (flubber's "best rotation" step);
  2. pair holes top-first; when one side lacks a counter, seed it as a zero-area
     point at the OTHER side's counter centroid, so the hole grows/shrinks in
     place;
  3. best-rotate each real hole pair likewise;
  4. linear-interpolate matched points; render filled with even-odd.

No centreline / skeleton: shapes ARE the font outlines (zero extraction loss).

Usage:  python morph_outline.py <fonts_dir> <out_dir>
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

FONTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "previews"
os.makedirs(OUT_DIR, exist_ok=True)

OUTER_N = 220
HOLE_M = 80
SIZE = 1000.0
DIGITS = "0123456789"


def signed_area(p):
    x, y = p[:, 0], p[:, 1]
    return 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)


def orient(poly, ccw=True):
    a = signed_area(poly)
    if (a < 0 and ccw) or (a > 0 and not ccw):
        return poly[::-1].copy()
    return poly


def resample_closed(poly, n):
    P = np.asarray(poly, float)
    Q = np.vstack([P, P[0]])
    seg = np.linalg.norm(np.diff(Q, axis=0), axis=1)
    L = np.concatenate([[0], np.cumsum(seg)])
    total = L[-1]
    if total <= 0:
        return np.repeat(P[:1], n, axis=0)
    t = np.linspace(0, total, n, endpoint=False)
    return np.stack([np.interp(t, L, Q[:, 0]), np.interp(t, L, Q[:, 1])], 1)


def best_offset(a, b):
    """Rotate closed ring b to the cyclic start that best matches a (min SS dist)."""
    n = len(a)
    best_s, best_d = 0, np.inf
    for s in range(n):
        d = np.sum((a - np.roll(b, -s, axis=0)) ** 2)
        if d < best_d:
            best_d, best_s = d, s
    return np.roll(b, -best_s, axis=0)


def get_contours(ttf, ch):
    tp = TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf))
    return [np.asarray(p, float) for p in tp.to_polygons() if len(p) >= 3]


def union_metrics(ttf):
    mins = np.array([1e18, 1e18])
    maxs = np.array([-1e18, -1e18])
    for ch in DIGITS:
        for p in get_contours(ttf, ch):
            mins = np.minimum(mins, p.min(0))
            maxs = np.maximum(maxs, p.max(0))
    return mins[1], (maxs[1] - mins[1])


def raw_contours(ttf, ch, miny, H):
    """Return outer(OUTER_N) + holes(list of HOLE_M), resampled & oriented,
    holes sorted top-first. Not yet start-aligned across digits."""
    polys = get_contours(ttf, ch)
    polys = [np.column_stack([p[:, 0] / H, (p[:, 1] - miny) / H]) for p in polys]
    allp = np.vstack(polys)
    xc = (allp[:, 0].min() + allp[:, 0].max()) / 2.0
    polys = [p - np.array([xc - 0.5, 0.0]) for p in polys]

    areas = [abs(signed_area(p)) for p in polys]
    oi = int(np.argmax(areas))
    outer = resample_closed(orient(polys[oi], ccw=True), OUTER_N)
    holes = [orient(polys[i], ccw=False) for i in range(len(polys)) if i != oi]
    holes.sort(key=lambda p: -p[:, 1].mean())          # top-first by centroid y
    holes = [resample_closed(h, HOLE_M) for h in holes[:2]]
    return outer, holes


def build_pair(ttf, a, b, miny, H):
    oa, ha = raw_contours(ttf, a, miny, H)
    ob, hb = raw_contours(ttf, b, miny, H)
    ob = best_offset(oa, ob)
    holesA, holesB = [], []
    for k in range(max(len(ha), len(hb))):
        Ak = ha[k] if k < len(ha) else None
        Bk = hb[k] if k < len(hb) else None
        if Ak is not None and Bk is not None:
            holesA.append(Ak)
            holesB.append(best_offset(Ak, Bk))
        elif Bk is None:                                # B lacks this counter
            holesA.append(Ak)
            holesB.append(np.repeat(Ak.mean(0)[None], HOLE_M, 0))
        else:                                           # A lacks this counter
            holesA.append(np.repeat(Bk.mean(0)[None], HOLE_M, 0))
            holesB.append(Bk)
    return (oa, holesA), (ob, holesB)


def lerp(a, b, t):
    return a * (1.0 - t) + b * t


def morph(dA, dB, t):
    oa, ha = dA
    ob, hb = dB
    return lerp(oa, ob, t), [lerp(ha[k], hb[k], t) for k in range(len(ha))]


def to_path(outer, holes):
    V, C = [], []
    for ring in [outer] + holes:
        V.append(np.vstack([ring, ring[0]]))
        C += [Path.MOVETO] + [Path.LINETO] * (len(ring) - 1) + [Path.CLOSEPOLY]
    return Path(np.vstack(V), C)


def draw(ax, digit, color="#1c1c22"):
    outer, holes = digit
    ax.add_patch(PathPatch(to_path(outer, holes), facecolor=color, edgecolor="none"))
    ax.set_xlim(-0.15, 1.15)
    ax.set_ylim(-0.08, 1.16)
    ax.set_aspect("equal")
    ax.axis("off")


def digit_sheet(ttf, label, out):
    miny, H = union_metrics(ttf)
    fig, axes = plt.subplots(1, 10, figsize=(20, 2.6))
    fig.suptitle(f"{label}  -  filled outlines rebuilt from normalized contours", y=0.98)
    for i, ch in enumerate(DIGITS):
        o, hs = raw_contours(ttf, ch, miny, H)
        draw(axes[i], (o, hs))
    fig.savefig(out, dpi=90, bbox_inches="tight")
    plt.close(fig)
    return out


def morph_strip(ttf, label, a, b, out, frames=7):
    miny, H = union_metrics(ttf)
    dA, dB = build_pair(ttf, a, b, miny, H)
    ts = np.linspace(0, 1, frames)
    fig, axes = plt.subplots(1, frames, figsize=(2.2 * frames, 2.6))
    fig.suptitle(f"{label}   morph  {a} -> {b}   (flubber-style correspondence)", y=0.99)
    for j, t in enumerate(ts):
        draw(axes[j], morph(dA, dB, t))
        axes[j].set_title(f"t={t:.2f}", fontsize=9)
    fig.savefig(out, dpi=90, bbox_inches="tight")
    plt.close(fig)
    return out


STYLES = [("playfairdisplay", "Serif Didone | Playfair Display"),
          ("dmserifdisplay", "Serif High-contrast | DM Serif Display"),
          ("cormorantgaramond", "Serif Elegant | Cormorant Garamond"),
          ("abrilfatface", "Display Didone | Abril Fatface"),
          ("zillaslab", "Slab | Zilla Slab"),
          ("lora", "Serif Readable | Lora")]
PAIRS = [("2", "3"), ("8", "9"), ("4", "5")]

if __name__ == "__main__":
    for fam, label in STYLES:
        ttf = os.path.join(FONTS_DIR, fam + ".ttf")
        if not os.path.exists(ttf):
            print("missing", ttf)
            continue
        print("wrote", digit_sheet(ttf, label, os.path.join(OUT_DIR, f"outline_digits_{fam}.png")))
        for a, b in PAIRS:
            print("wrote", morph_strip(ttf, label, a, b, os.path.join(OUT_DIR, f"morph_{fam}_{a}{b}_v2.png")))
