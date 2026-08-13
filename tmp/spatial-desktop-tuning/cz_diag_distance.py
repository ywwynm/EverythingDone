#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""废止实验：crop-zoom 的收益到底来自"带变宽"还是"洞离真实像素更近"？

档位矩阵（scene 00）给出的增量是反直觉的：Moebius 的收益 97% 来自**裁窗**，放大几乎
不贡献；LaMa 反而是放大更管用。如果病因真是「带只占 0.5 个 latent 像素、模型看不见」，
那放大到 4 个 latent 像素应当是主要疗效——实际不是。所以要把两个候选解释分开量：

- **解释 A（亚 latent 像素）**：带在模型输入里有多宽（latent 像素）。
- **解释 B（D195 定律的窗级版本）**：带内像素到**最近真实像素**有多远——真实像素指
  该窗送进模型的输入里洞以外的部分。裁窗把洞切小，这个距离会跟着掉。

两个数都在**模型输入坐标系**里量（512²），因为那才是模型实际看到的东西。距离同时按
latent 像素给一份（÷8），与解释 A 同量纲，可以直接比哪个变化更大。

统计一律按带像素加权（碎片窗不参与拉平均）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

import cz_inpaint as cz

LADDERS = ("square", "tiled", "crop", "cropzoom")


def stats(image, hole, band, ladder: str) -> dict:
    jobs, meta = cz.prepare_jobs(image, hole, band, ladder, verbose=False)
    w_all, d_all, wt = [], [], []
    for j in jobs:
        m, b = j["mask"], j.get("bandIn")
        if b is None or not b.any():
            continue
        b = b & m                        # 只统计"确实是洞"的带像素
        if not b.any():
            continue
        # 到最近真实像素的距离：真实 = 该输入里洞以外的部分
        dist = cv2.distanceTransform(m.astype(np.uint8), cv2.DIST_L2, 5)
        # 局部带宽 = 带内距离变换 ×2（D188 口径），在模型输入坐标系里量
        wid = 2.0 * cv2.distanceTransform(b.astype(np.uint8), cv2.DIST_L2, 5)
        w_all.append(wid[b])
        d_all.append(dist[b])
        wt.append(int(b.sum()))
    if not w_all:
        return {}
    w, d = np.concatenate(w_all), np.concatenate(d_all)
    q = lambda a, p: float(np.percentile(a, p))
    return {"ladder": ladder, "jobs": len(jobs), "px": int(w.size),
            "bandW50": q(w, 50), "bandW90": q(w, 90),
            "bandLat50": q(w, 50) / 8.0, "bandLat90": q(w, 90) / 8.0,
            "dist50": q(d, 50), "dist90": q(d, 90),
            "distLat50": q(d, 50) / 8.0, "distLat90": q(d, 90) / 8.0,
            "holeFrac": float(np.mean([j["mask"].mean() for j in jobs]))}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenes", nargs="*",
                    default=["00_original_single", "05_near_object"])
    ap.add_argument("--source", choices=("gt", "band"), default="gt",
                    help="gt=真值台的搬运掩膜（与档位矩阵同一份），band=真带")
    ap.add_argument("--out", type=Path, default=Path("qa/cz-bench/diag_distance.json"))
    args = ap.parse_args()

    rows = []
    for sc in args.scenes:
        g, a = Path("qa/moge-geometry") / sc, Path("assets") / sc
        image = np.asarray(Image.open(a / "center.jpg").convert("RGB"))
        if args.source == "gt":
            import cz_bench as B
            case = B.gt_case(sc, {})
            hole = band = case["mask"]
        else:
            band = np.asarray(Image.open(g / "hidden_mask_base.png").convert("L")) > 127
            hole = np.asarray(Image.open(g / "hidden_paint_base.png").convert("L")) > 127
        print(f"\n=== {sc}（{args.source}）===")
        print(f"{'档':>9} {'作业':>5} {'洞占比':>7} | "
              f"{'带宽 p50':>8} {'latent':>7} | {'到真实像素 p50':>13} {'latent':>7} {'p90 latent':>10}")
        for lad in LADDERS:
            s = stats(image, hole, band, lad)
            if not s:
                continue
            s["scene"] = sc
            s["source"] = args.source
            rows.append(s)
            print(f"{lad:>9} {s['jobs']:>5} {100*s['holeFrac']:>6.1f}% | "
                  f"{s['bandW50']:>8.1f} {s['bandLat50']:>7.2f} | "
                  f"{s['dist50']:>13.1f} {s['distLat50']:>7.2f} {s['distLat90']:>10.2f}")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
