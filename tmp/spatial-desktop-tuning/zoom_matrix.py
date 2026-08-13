#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""铺开的放大巡检矩阵：**每个场景十几个区域 × 多个视角 × 多个变体**。

写这个是因为之前只挑了两处放大（00 手部、05 玫瑰）就下结论，覆盖面根本不够——
用户点名"00 还得看女人轮廓和玻璃杯，05 还得看上半部分"。选区不能靠人挑。

选区规则（不靠肉眼）：
1. **自动**：按 `遮挡带密度 × 原图边缘能量` 打分，非极大抑制取前 N 块。
   带越宽、周围结构越强的地方，补全失败越显眼。
2. **手工**：`--spots` 追加用户点名的位置，与自动块并列，不替换。

排版：每个角度一张表，行=区域，列=各变体，最后一列是首两个变体的差异热图。
坐标在**源图**上选，再按 `--scale` 映射到渲染帧——视差最大 43px@720，
远小于 tile，块内内容不会跑掉。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def label(img: np.ndarray, text: str, height: int = 20) -> np.ndarray:
    bar = np.zeros((height, img.shape[1], 3), np.uint8)
    cv2.putText(bar, text, (4, height - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.42,
                (240, 240, 240), 1, cv2.LINE_AA)
    return np.concatenate([bar, img], axis=0)


def pick_regions(mask: np.ndarray, image: np.ndarray, tile: int, count: int,
                 min_sep: int) -> list[tuple[int, int, float]]:
    grey = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.float32)
    edge = (np.abs(cv2.Sobel(grey, cv2.CV_32F, 1, 0, 3))
            + np.abs(cv2.Sobel(grey, cv2.CV_32F, 0, 1, 3)))
    band = cv2.blur(mask.astype(np.float32), (tile, tile))
    energy = cv2.blur(edge, (tile, tile))
    score = band * energy
    h2 = tile // 2
    score[:h2, :] = score[-h2:, :] = 0
    score[:, :h2] = score[:, -h2:] = 0
    picks, work = [], score.copy()
    for _ in range(count):
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        if work[y, x] <= 0:
            break
        picks.append((int(x), int(y), float(work[y, x])))
        cv2.circle(work, (x, y), min_sep, 0.0, -1)
    return picks


def crop(img: np.ndarray, cx: int, cy: int, tile: int, zoom: int) -> np.ndarray:
    h, w = img.shape[:2]
    x0 = int(np.clip(cx - tile // 2, 0, max(w - tile, 0)))
    y0 = int(np.clip(cy - tile // 2, 0, max(h - tile, 0)))
    return cv2.resize(img[y0:y0 + tile, x0:x0 + tile], (tile * zoom, tile * zoom),
                      interpolation=cv2.INTER_NEAREST)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mt"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/zoom"))
    ap.add_argument("--scene", required=True)
    ap.add_argument("--tags", nargs="+", required=True, help="变体后缀，不带下划线前缀也行")
    ap.add_argument("--angles", nargs="*", type=int, default=[0, 90, 180, 270])
    ap.add_argument("--tile", type=int, default=120)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--count", type=int, default=9, help="自动选几块")
    ap.add_argument("--min-sep", type=int, default=70, help="源图坐标下的最小间距")
    ap.add_argument("--scale", type=float, default=2.0, help="渲染帧 / 源图")
    ap.add_argument("--spots", default="", help="名字:x,y;… 源图坐标，追加在自动块之后")
    ap.add_argument("--per-sheet", type=int, default=4,
                    help="一张表最多几行。太高的图在阅读时会被整体缩掉，等于白放大")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    sc2 = args.scene[:2]
    geo = args.geometry / args.scene
    meta = json.loads((geo / "moge-meta.json").read_text(encoding="utf-8"))
    image = cv2.imread(str(args.assets / meta["scene"] / "center.jpg"))
    mask = cv2.imread(str(geo / "hidden_mask.png"), 0) > 127

    regions = [(f"auto{i}", x, y) for i, (x, y, _) in
               enumerate(pick_regions(mask, image, args.tile, args.count, args.min_sep))]
    for item in filter(None, args.spots.split(";")):
        name, xy = item.split(":")
        x, y = (int(float(v)) for v in xy.split(","))
        regions.append((name, x, y))
    print(f"{args.scene}: 巡检 {len(regions)} 个区域 × {len(args.angles)} 视角 × "
          f"{len(args.tags)} 变体，放大 {args.zoom}×")
    for nm, x, y in regions:
        print(f"    {nm:<8} ({x},{y})")

    tags = [t if t.startswith("_") else "_" + t for t in args.tags]
    for deg in args.angles:
        imgs = {}
        for t in tags:
            p = args.frames / f"{sc2}-{t[1:]}-{deg:03d}.png"
            imgs[t] = cv2.imread(str(p))
            if imgs[t] is None:
                print(f"  缺帧 {p.name}")
                return
        a, b = imgs[tags[0]], imgs[tags[1]] if len(tags) > 1 else None
        heat = None
        if b is not None:
            d = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(axis=2)
            heat = cv2.applyColorMap(np.clip(d * 4, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
        rows = []
        for nm, x, y in regions:
            cx, cy = int(x * args.scale), int(y * args.scale)
            cells = [label(crop(image, x, y, args.tile, args.zoom), f"{nm} 原图 ({x},{y})")]
            for t in tags:
                cells.append(label(crop(imgs[t], cx, cy, args.tile, args.zoom), f"{t[1:]} {deg}deg"))
            if heat is not None:
                cells.append(label(crop(heat, cx, cy, args.tile, args.zoom), "diff x4"))
            rows.append(np.concatenate(cells, axis=1))
        stem = f"{sc2}-{deg:03d}-{'-vs-'.join(t[1:] for t in tags)}"
        for k in range(0, len(rows), args.per_sheet):
            sheet = np.concatenate(rows[k:k + args.per_sheet], axis=0)
            p = args.out / f"{stem}-p{k // args.per_sheet + 1}.jpg"
            cv2.imwrite(str(p), sheet, [cv2.IMWRITE_JPEG_QUALITY, 92])
            print(f"  -> {p.name}  {sheet.shape[1]}x{sheet.shape[0]}")


if __name__ == "__main__":
    main()
