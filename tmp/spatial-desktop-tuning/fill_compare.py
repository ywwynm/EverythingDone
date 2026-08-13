#!/usr/bin/env python
"""直接比对补全图本身（不经过渲染），把几何与光栅化这两层变量摘掉。

渲染图里看到的可疑块，可能来自补全、也可能来自网格断边或分块融合。先在补全图上看，
再叠上 512 分块的边界线——如果可疑块贴着分块边界，那就是融合问题不是模型问题。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def label(img: np.ndarray, text: str, height: int = 24) -> np.ndarray:
    bar = np.zeros((height, img.shape[1], 3), np.uint8)
    cv2.putText(bar, text, (6, height - 7), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (235, 235, 235), 1, cv2.LINE_AA)
    return np.concatenate([bar, img], axis=0)


def tile_lines(shape: tuple[int, int], tile: int = 512, overlap: int = 128) -> np.ndarray:
    """返回与补全时同一套分块的边界图（1=边界），用于判断可疑块是否贴着块界。"""
    h, w = shape
    stride = tile - overlap
    pad_r = max(0, -(-max(w - tile, 0) // stride) * stride + tile - w) if w > tile else tile - w
    pad_b = max(0, -(-max(h - tile, 0) // stride) * stride + tile - h) if h > tile else tile - h
    ph, pw = h + pad_b, w + pad_r
    lines = np.zeros((ph, pw), np.uint8)
    for y in range(0, max(ph - tile, 0) + 1, stride):
        for x in range(0, max(pw - tile, 0) + 1, stride):
            cv2.rectangle(lines, (x, y), (x + tile - 1, y + tile - 1), 1, 1)
    return lines[:h, :w]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geo", type=Path, required=True)
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--variants", nargs="+", required=True)
    ap.add_argument("--centers", nargs="+", required=True, help="y,x 形式的裁剪中心")
    ap.add_argument("--tile", type=int, default=140)
    ap.add_argument("--zoom", type=float, default=3.0)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    image = np.asarray(Image.open(args.image).convert("RGB"))
    h, w = image.shape[:2]
    lines = tile_lines((h, w))

    fills = {}
    for v in args.variants:
        fills[v] = np.asarray(Image.open(args.geo / f"hidden_color_{v}.png").convert("RGB"))
    mask = np.asarray(Image.open(args.geo / f"hidden_mask_{args.variants[0]}.png").convert("L")) > 127

    overlay = image.copy()
    overlay[mask] = (overlay[mask] * 0.35 + np.array([255, 0, 255]) * 0.65).astype(np.uint8)
    grid = image.copy()
    grid[lines > 0] = (0, 255, 255)

    args.out.mkdir(parents=True, exist_ok=True)
    half = args.tile // 2
    for i, c in enumerate(args.centers):
        cy, cx = (int(t) for t in c.split(","))
        y0, y1 = max(0, cy - half), min(h, cy + half)
        x0, x1 = max(0, cx - half), min(w, cx + half)
        cells = []
        for name, src in [("原图", image), ("掩膜", overlay), ("512分块界", grid)] + \
                         [(v, fills[v]) for v in args.variants]:
            crop = src[y0:y1, x0:x1]
            crop = cv2.resize(crop, (int(crop.shape[1] * args.zoom), int(crop.shape[0] * args.zoom)),
                              interpolation=cv2.INTER_NEAREST)
            cells.append(label(crop, name))
        sheet = np.concatenate(cells, axis=1)
        p = args.out / f"fill{i}_y{cy}_x{cx}.png"
        Image.fromarray(sheet).save(p)
        print(f"{p}  {sheet.shape[1]}x{sheet.shape[0]}")


if __name__ == "__main__":
    main()
