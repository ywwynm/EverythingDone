"""Approximate the Android FILL_AND_STROKE weight ladder to sanity-check that
hour/minute/second differ clearly in THICKNESS at identical SIZE. Reads the
shipped assets JSON and renders "01:29:36" per style (fill + per-level edge
stroke), one row per style, white on dark.

Usage: python verify_weight.py <assets_timely_dir> <out_dir>
"""
import os
import sys
import json
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.path import Path as MPath
from matplotlib.patches import PathPatch, Circle

ASSETS = sys.argv[1]
OUT = sys.argv[2]
STYLES = ["poppins", "abrilfatface", "orbitron", "playfairdisplay", "pacifico", "comfortaa", "lora"]
SEQ = [("0", "h"), ("1", "h"), (":", "x"), ("2", "m"), ("9", "m"), (":", "x"), ("3", "s"), ("6", "s")]
WST = {"h": 0.08, "m": 0.035, "s": 0.0}
OPA = {"h": 0.92, "m": 0.80, "s": 0.66}
LW = 42.0            # points per unit-height so 0.08 -> ~8% of digit height
PITCH = 1.7

fig, ax = plt.subplots(figsize=(11, len(STYLES) * 1.0))
fig.patch.set_facecolor("#20242b")
ax.set_facecolor("#20242b")
maxx = 0.0
for r, st in enumerate(STYLES):
    d = json.load(open(os.path.join(ASSETS, st + ".json")))
    adv = d["advance"]
    y0 = (len(STYLES) - 1 - r) * PITCH
    x = 0.0
    for ch, lvl in SEQ:
        if ch == ":":
            x += 0.12
            for yy in (0.28, 0.56):
                ax.add_patch(Circle((x, yy + y0), 0.05, color="white", alpha=OPA["s"]))
            x += 0.24
            continue
        g = d[lvl][ch]
        o = np.array(g["outer"]).reshape(-1, 2)
        hs = [np.array(h).reshape(-1, 2) for h in g["holes"]]
        V, C = [], []
        for ring in [o] + hs:
            rr = ring.copy()
            rr[:, 0] += x + adv / 2
            rr[:, 1] += y0
            V.append(np.vstack([rr, rr[0]]))
            C += [MPath.MOVETO] + [MPath.LINETO] * (len(rr) - 1) + [MPath.CLOSEPOLY]
        ax.add_patch(PathPatch(MPath(np.vstack(V), C), facecolor="white", edgecolor="white",
                               lw=WST[lvl] * LW, alpha=OPA[lvl], joinstyle="round", capstyle="round"))
        x += adv
    maxx = max(maxx, x)
    ax.text(-0.25, y0 + 0.5, st, ha="right", va="center", color="white", fontsize=10)
ax.set_xlim(-3.2, maxx + 0.2)
ax.set_ylim(-0.3, len(STYLES) * PITCH)
ax.invert_yaxis()
ax.set_aspect("equal")
ax.axis("off")
fig.savefig(os.path.join(OUT, "verify_weight.png"), dpi=100, bbox_inches="tight", facecolor="#20242b")
print("wrote verify_weight.png")
