#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""整圈放大巡检：九场景 × 全部 24 个角度，逐角度按 |mix−str| 选区放大并排。

用户的验收口径是"各种视角、区域都测一遍，放大看细节，条带/伪影/扭曲一个不留"。
所以这里**不取正交四角**（本日已因此吃过亏），逐角度都出图；选区按两档差异选，
镜头对准改动本身而不是别处。

每个场景一张长图（24 角 × 每角 2 块），另出一张"最坏角"汇总：按差异域内台阶能量
挑最差的三个角单独放大 4×。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from zoom_matrix import crop, label

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def pick_diff(a, b, tile, count, min_sep):
    d = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(2)
    s = cv2.blur(d, (tile, tile))
    h2 = tile // 2
    s[:h2, :] = s[-h2:, :] = 0
    s[:, :h2] = s[:, -h2:] = 0
    picks, work = [], s.copy()
    for _ in range(count):
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        if work[y, x] <= 0:
            break
        picks.append((int(x), int(y), float(work[y, x])))
        cv2.circle(work, (x, y), min_sep, 0.0, -1)
    return picks, d


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/qa"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/zoom-str"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tile", type=int, default=100)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--per-angle", type=int, default=2)
    ap.add_argument("--min-sep", type=int, default=200)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    degs = list(range(0, 360, 15))
    for scene in args.scenes:
        sc = scene[:2]
        rows, worst = [], []
        for deg in degs:
            pa, pb = args.dir / f"{sc}mix-{deg}.png", args.dir / f"{sc}str-{deg}.png"
            if not (pa.is_file() and pb.is_file()):
                continue
            a, b = cv2.imread(str(pa)), cv2.imread(str(pb))
            picks, d = pick_diff(a, b, args.tile, args.per_angle, args.min_sep)
            worst.append((float(d.mean()), deg))
            heat = cv2.applyColorMap(np.clip(d * 8, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
            for i, (x, y, s) in enumerate(picks):
                rows.append(np.concatenate([
                    label(crop(a, x, y, args.tile, args.zoom), f"{deg}deg p{i} mix"),
                    label(crop(b, x, y, args.tile, args.zoom), "str"),
                    label(crop(heat, x, y, args.tile, args.zoom), f"diff x8 {s:.1f}"),
                ], axis=1))
        if not rows:
            continue
        per = 8
        for k in range(0, len(rows), per):
            p = args.out / f"{sc}-sweep-p{k // per + 1}.jpg"
            cv2.imwrite(str(p), np.concatenate(rows[k:k + per], axis=0),
                        [cv2.IMWRITE_JPEG_QUALITY, 90])
        worst.sort(reverse=True)
        print(f"{scene}: {len(rows)} 块 -> {args.out.name}/{sc}-sweep-p*.jpg  "
              f"差异最大角 {[d for _, d in worst[:3]]}")


if __name__ == "__main__":
    main()
