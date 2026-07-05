"""
Doing-screen look mock: white "12:34:56" on a dark background, rendered two ways
per style -- SOLID FILL vs CONTOUR OUTLINE (stroke the filled outline's contours)
-- to choose how Option A digits should read on the countdown screen.

Usage:  python mock_doing.py <fonts_dir> <out_dir>
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

FONTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "previews"
os.makedirs(OUT_DIR, exist_ok=True)

SIZE = 1000.0
BG = "#20242b"
FG = "white"
TIME = "12:34:56"
STYLES = [("poppins", "Geometric | Poppins"),
          ("comfortaa", "Rounded | Comfortaa"),
          ("orbitron", "Technical | Orbitron")]


def raw(ttf, ch):
    tp = TextPath((0, 0), ch, size=SIZE, prop=FontProperties(fname=ttf))
    return [np.asarray(p, float) for p in tp.to_polygons() if len(p) >= 3]


def metrics(ttf):
    mn = np.array([1e18, 1e18])
    mx = -mn
    for ch in "0123456789":
        for p in raw(ttf, ch):
            mn = np.minimum(mn, p.min(0))
            mx = np.maximum(mx, p.max(0))
    return mn[1], (mx[1] - mn[1])


def contours(ttf, ch, miny, H):
    return [np.column_stack([p[:, 0] / H, (p[:, 1] - miny) / H]) for p in raw(ttf, ch)]


def width(ps):
    a = np.vstack(ps)
    return a[:, 0].max() - a[:, 0].min()


def place(ps, cx_target):
    a = np.vstack(ps)
    cx = (a[:, 0].min() + a[:, 0].max()) / 2
    return [p + np.array([cx_target - cx, 0.0]) for p in ps]


def gpath(ps):
    V, C = [], []
    for r in ps:
        V.append(np.vstack([r, r[0]]))
        C += [Path.MOVETO] + [Path.LINETO] * (len(r) - 1) + [Path.CLOSEPOLY]
    return Path(np.vstack(V), C)


def draw_time(ax, ttf, mode):
    miny, H = metrics(ttf)
    adv = max(width(contours(ttf, c, miny, H)) for c in "0123456789") + 0.20
    x = 0.0
    for ch in TIME:
        if ch == ":":
            x += 0.18
            for yy in (0.30, 0.60):
                ax.add_patch(Circle((x, yy), 0.05, color=FG))
            x += 0.18
        else:
            pp = gpath(place(contours(ttf, ch, miny, H), x + adv / 2))
            if mode == "fill":
                ax.add_patch(PathPatch(pp, facecolor=FG, edgecolor="none"))
            else:
                ax.add_patch(PathPatch(pp, facecolor="none", edgecolor=FG, linewidth=3.0))
            x += adv
    ax.set_xlim(-0.1, x + 0.1)
    ax.set_ylim(-0.12, 1.12)
    ax.set_aspect("equal")
    ax.axis("off")


if __name__ == "__main__":
    for fam, label in STYLES:
        ttf = os.path.join(FONTS_DIR, fam + ".ttf")
        if not os.path.exists(ttf):
            print("missing", ttf)
            continue
        fig, axes = plt.subplots(2, 1, figsize=(11, 4.6))
        fig.patch.set_facecolor(BG)
        for ax, mode, ttl in zip(axes, ["fill", "outline"],
                                 ["SOLID FILL", "CONTOUR OUTLINE"]):
            ax.set_facecolor(BG)
            draw_time(ax, ttf, mode)
            ax.set_title(f"{label}   -   {ttl}", color="white", fontsize=12, loc="left")
        out = os.path.join(OUT_DIR, f"mock_doing_{fam}.png")
        fig.savefig(out, dpi=100, bbox_inches="tight", facecolor=BG)
        plt.close(fig)
        print("wrote", out)
