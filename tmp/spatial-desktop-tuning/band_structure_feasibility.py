#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""结构搬运这条路能覆盖多少：量两个决定成败的几何量。

**① 结构要被搬多远。** 显露带是贴着剪影**向内**的一条（`hidden_mask` 圈的是遮挡侧
像素），所以它只有**一侧**紧邻真实背景，另一侧继续深入遮挡物。工单里写的"两端都命中
带边界的结构做端点配对"只在细颈处成立。这里量的是：带内每个像素到最近的**真实背景**
像素有多远——这就是结构必须被单侧外推的距离。中位≈带宽 ⇒ 单侧；中位≈带宽/2 ⇒ 两侧有锚。

**② 带边界上到底有多少结构可搬。** 真实背景侧若是一片平墙，延续就无从谈起，
这一段带只能保持原样（工单自己也写了"无结构段不动"）。量：带的外边界像素中，
外侧 3–10px 的真实背景里存在强边缘的比例。强边缘阈值取该图 Sobel 幅值的 p75，
是图内相对量，不跨图硬编码。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tag", default="_mix")
    ap.add_argument("--edge-pct", type=float, default=75.0)
    args = ap.parse_args()

    print(f"{'场景':<22} {'带px':>7} | {'到真背景 中位':>12} {'p90':>6} {'max':>5} "
          f"| {'带宽 中位':>9} {'p90':>6} | {'边界有结构%':>10}")
    out = {}
    for scene in args.scenes:
        g = args.geometry / scene
        mp = g / f"hidden_mask{args.tag}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        band = cv2.imread(str(mp), 0) > 127
        if band.sum() < 256:
            continue
        img = cv2.imread(str(args.assets / scene / "center.jpg"))
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{args.tag}.f32", dtype=np.float32).reshape(h, w)
        inv, inv_layer = 1.0 / np.maximum(z, 1e-6), 1.0 / np.maximum(hz, 1e-6)

        # 可用来源 = 带外、且逆深度落在该带背景层级内（前景与遮挡物自动被排除）
        lo, hi = np.percentile(inv_layer[band], 2), np.percentile(inv_layer[band], 98)
        src = (~band) & (inv >= lo) & (inv <= hi)
        if src.sum() < 512:
            print(f"{scene:<22} 可用来源仅 {int(src.sum())} px")
            continue
        dist = cv2.distanceTransform((~src).astype(np.uint8), cv2.DIST_L2, 5)[band]

        # 带宽：逐行连续段长度（与 D185 同口径）
        widths = []
        for y in range(h):
            xs = np.where(band[y])[0]
            if xs.size:
                widths.extend(np.diff(np.r_[0, np.where(np.diff(xs) > 1)[0] + 1, xs.size]))
        widths = np.array(widths) if widths else np.array([0])

        # 带的外边界（与来源相邻的那一圈带像素）
        edge_mag = (np.abs(cv2.Sobel(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32),
                                     cv2.CV_32F, 1, 0, 3))
                    + np.abs(cv2.Sobel(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32),
                                       cv2.CV_32F, 0, 1, 3)))
        thr = float(np.percentile(edge_mag, args.edge_pct))
        srcu = src.astype(np.uint8)
        outer = band & (cv2.dilate(srcu, np.ones((3, 3), np.uint8)) > 0)
        # 外侧 3–10px 的来源环里有没有强结构：把"强边缘且属于来源"的图做最大值膨胀
        strong = ((edge_mag > thr) & src).astype(np.uint8)
        near_strong = cv2.dilate(strong, np.ones((21, 21), np.uint8)) > 0   # 半径 10
        frac = float((outer & near_strong).sum()) / max(int(outer.sum()), 1)

        print(f"{scene:<22} {int(band.sum()):>7d} | {np.median(dist):>12.1f} "
              f"{np.percentile(dist,90):>6.1f} {dist.max():>5.0f} "
              f"| {np.median(widths):>9.0f} {np.percentile(widths,90):>6.0f} "
              f"| {100*frac:>10.1f}")
        out[scene] = {"bandPx": int(band.sum()), "distMedian": float(np.median(dist)),
                      "distP90": float(np.percentile(dist, 90)), "distMax": float(dist.max()),
                      "widthMedian": float(np.median(widths)),
                      "widthP90": float(np.percentile(widths, 90)),
                      "outerWithStructurePct": 100 * frac}
    (args.geometry.parent / "matte-soft-probe" / "band_structure_feasibility.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
