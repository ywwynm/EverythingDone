#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 变体的放大目检表。选区规则、裁块、贴标签全部复用 `zoom_matrix.py`，
只换两处：帧的命名（本轮出帧是 `{sc2}{tag}-{deg}.png`）和带掩膜的来源（`hidden_mask_base.png`）。

**指标改善 ≠ 画面改善**，这条在本项目栽过三次（D186 局部对比度、D189 结构延续、
D193 硬门槛多视角），所以任何指标结论都必须配这张表。选区不靠肉眼挑：按
`带密度 × 原图边缘能量` 打分取前 N 块，再追加用户点名的位置。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from zoom_matrix import crop, label, pick_regions


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=Path, default=Path("qa/orbit/diag/probe"))
    ap.add_argument("--assets", type=Path, default=Path("assets"))
    ap.add_argument("--geometry", type=Path, default=Path("qa/moge-geometry"))
    ap.add_argument("--out", type=Path, default=Path("qa/cz-bench/zoom"))
    ap.add_argument("--scene", required=True)
    ap.add_argument("--tags", nargs="+", required=True)
    ap.add_argument("--angles", nargs="*", type=int, default=[0, 90, 180, 270])
    ap.add_argument("--tile", type=int, default=120)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--count", type=int, default=9)
    ap.add_argument("--min-sep", type=int, default=70)
    ap.add_argument("--scale", type=float, default=2.0, help="渲染帧 / 源图")
    ap.add_argument("--spots", default="", help="名字:x,y;… 源图坐标，追加在自动块之后")
    ap.add_argument("--per-sheet", type=int, default=3)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    sc2 = args.scene[:2]
    geo = args.geometry / args.scene
    image = cv2.imread(str(args.assets / args.scene / "center.jpg"))
    mask = cv2.imread(str(geo / "hidden_mask_base.png"), 0) > 127

    regions = [(f"auto{i}", x, y) for i, (x, y, _) in
               enumerate(pick_regions(mask, image, args.tile, args.count, args.min_sep))]
    for item in filter(None, args.spots.split(";")):
        name, xy = item.split(":")
        x, y = (int(float(v)) for v in xy.split(","))
        regions.append((name, x, y))
    tags = [t if t.startswith("_") else "_" + t for t in args.tags]
    print(f"{args.scene}: {len(regions)} 区域 × {len(args.angles)} 视角 × "
          f"{len(tags)} 变体，放大 {args.zoom}×")

    made = []
    for deg in args.angles:
        imgs, missing = {}, []
        for t in tags:
            p = args.frames / f"{sc2}{t}-{deg}.png"
            imgs[t] = cv2.imread(str(p))
            if imgs[t] is None:
                missing.append(p.name)
        if missing:
            print(f"  {deg}deg 缺帧 {missing}，跳过（**不是没差异，是没有数据**）")
            continue
        a = imgs[tags[0]]
        b = imgs[tags[1]] if len(tags) > 1 else None
        heat = None
        if b is not None:
            d = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(axis=2)
            heat = cv2.applyColorMap(np.clip(d * 4, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
        rows = []
        for nm, x, y in regions:
            cx, cy = int(x * args.scale), int(y * args.scale)
            cells = [label(crop(image, x, y, args.tile, args.zoom), f"{nm} 原图 ({x},{y})")]
            for t in tags:
                cells.append(label(crop(imgs[t], cx, cy, args.tile, args.zoom),
                                   f"{t[1:]} {deg}deg"))
            if heat is not None:
                cells.append(label(crop(heat, cx, cy, args.tile, args.zoom),
                                   f"diff x4 ({tags[0][1:]} vs {tags[1][1:]})"))
            rows.append(np.concatenate(cells, axis=1))
        stem = f"{sc2}-{deg:03d}-" + "-vs-".join(t[1:] for t in tags)
        for k in range(0, len(rows), args.per_sheet):
            sheet = np.concatenate(rows[k:k + args.per_sheet], axis=0)
            p = args.out / f"{stem}-p{k // args.per_sheet + 1}.jpg"
            cv2.imwrite(str(p), sheet, [cv2.IMWRITE_JPEG_QUALITY, 92])
            made.append(p)
    print(f"-> {len(made)} 张：{args.out}")


if __name__ == "__main__":
    main()
