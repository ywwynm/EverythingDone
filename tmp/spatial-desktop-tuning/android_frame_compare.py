#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""接通真透视前后的对照表。每场景一行，取八方向的汇总值：

- **视差跨度**：左右满偏移对开（d000 vs d180）的块位移 p5–p95 跨度，单位屏幕像素。
  这是"效果有多强"的直接度量，也是判断真透视是否真的接进渲染的判据。
- **交叉跨度**：同一对帧的**竖直**块位移跨度。真透视的交叉项恒为零，理想值应远小于
  视差跨度；接通前它是 15 px，与水平的 38 px 同量级——那正是 V13 拟合场的指纹。
- **空洞**：新出现的成片近黑区占比（八方向最大值）。
- **条带**：沿位移方向一阶差分的行/列投影峰值与中位之比（八方向最大值）。
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from android_frame_audit import band_score, block_shifts, content_box, hole_score, load_cropped


def summarize(root: Path, scene: str):
    center_png = root / f"{scene}-center.png"
    if not center_png.is_file():
        return None
    b = content_box(center_png)
    center = load_cropped(center_png, b)
    holes = []
    bands = []
    for deg in (0, 45, 90, 135, 180, 225, 270, 315):
        p = root / f"{scene}-d{deg:03d}.png"
        if not p.is_file():
            continue
        f = load_cropped(p, b)
        holes.append(hole_score(center, f)[0])
        bands.append(band_score(f, 1 if deg in (0, 180) else 0)[0])
    a = root / f"{scene}-d000.png"
    c = root / f"{scene}-d180.png"
    if not (a.is_file() and c.is_file()):
        return None
    # 块边长决定可分辨位移上限 ±block/2；真透视档 00 场景实测 152.6 px，96 会混叠
    dy, dx, pk = block_shifts(load_cropped(a, b), load_cropped(c, b), block=192)
    g = pk > 0.10
    span_x = float(np.percentile(dx[g], 95) - np.percentile(dx[g], 5)) if g.any() else float("nan")
    span_y = float(np.percentile(dy[g], 95) - np.percentile(dy[g], 5)) if g.any() else float("nan")
    return {
        "视差跨度": span_x,
        "交叉跨度": span_y,
        "空洞": max(holes) if holes else float("nan"),
        "条带": max(bands) if bands else float("nan"),
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    a = ap.parse_args()
    scenes = sorted({p.name.replace("-center.png", "")
                     for p in list(a.before.glob("*-center.png")) + list(a.after.glob("*-center.png"))
                     if not p.name.startswith("probe")})
    print(f"{'场景':<20}"
          f"{'视差跨度 前→后':>22}{'交叉跨度 前→后':>22}"
          f"{'空洞% 前→后':>20}{'条带比 前→后':>20}")
    for s in scenes:
        x, y = summarize(a.before, s), summarize(a.after, s)
        def cell(key, fmt):
            bv = f"{x[key]:{fmt}}" if x else "缺"
            av = f"{y[key]:{fmt}}" if y else "缺"
            return f"{bv} -> {av}"
        print(f"{s:<20}{cell('视差跨度', '.0f'):>22}{cell('交叉跨度', '.0f'):>22}"
              f"{cell('空洞', '.3f'):>20}{cell('条带', '.2f'):>20}")
