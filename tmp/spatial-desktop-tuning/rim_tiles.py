#!/usr/bin/env python
"""沿显露带自动切放大瓦片。

强制纪律 #2 要求"关键区裁剪放大 ≥2× 逐块查"，靠肉眼在整图上挑位置既慢又会漏。
本工具用来源标记图（uAblate==6，R=OVIE / G=前景 / B=底板）定位显露带，沿带
等距取样，输出带坐标标注的放大瓦片联表；可同时并排多个候选帧做同位置对照。

用法：
  python rim_tiles.py --prov diag/pv-250-prov.png \
      --frame normal=diag/pv-250-norm.png --frame center=diag/pv-center.png \
      --out diag/tiles-250.jpg --tiles 8 --zoom 4
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def reveal_band(tag: np.ndarray, mode: str) -> np.ndarray:
    """显露带掩膜。

    `prov`（uAblate==6）R=OVIE、G=前景、B=底板；B 在真实背景上同样为 1，不能单独
    作判据，因此只取 R。`band`（uAblate==10）R=SDXL 生成、G=前景、B=OVIE，两者
    并集才是完整的"非真实内容带"。
    """
    if mode == "band":
        return (tag[..., 0] > 0.15) | (tag[..., 2] > 0.15)
    return tag[..., 0] > 0.15


def pick_points(
    mask: np.ndarray, count: int, min_dist: int, score: np.ndarray | None
) -> list[tuple[int, int]]:
    """在带内取样。给定 score 时按分数从高到低取（用于优先命中有结构的位置）。"""
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return []
    if score is None:
        order = np.argsort(np.arctan2(ys - ys.mean(), xs - xs.mean()))
    else:
        order = np.argsort(-score[ys, xs])
    ys, xs = ys[order], xs[order]
    picked: list[tuple[int, int]] = []
    for y, x in zip(ys, xs):
        if all((y - py) ** 2 + (x - px) ** 2 >= min_dist**2 for py, px in picked):
            picked.append((int(y), int(x)))
        if len(picked) >= count:
            break
    return picked


def structure_score(path: Path, size: int) -> np.ndarray:
    """局部边缘能量：带上跨过直线结构（椅背横档、窗框竖边、桌沿）的位置得分高。"""
    import cv2

    gray = np.asarray(Image.open(path).convert("L")).astype(np.float32)
    mag = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0)) + np.abs(
        cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    )
    return cv2.blur(mag, (size, size))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--prov", type=Path, required=True)
    ap.add_argument("--frame", action="append", required=True,
                    help="标签=路径，可重复；按给出顺序纵向排列")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tiles", type=int, default=8)
    ap.add_argument("--size", type=int, default=110, help="瓦片原始边长(px)")
    ap.add_argument("--zoom", type=int, default=4)
    ap.add_argument("--map", choices=("prov", "band"), default="prov")
    ap.add_argument("--rank-structure", type=Path, default=None,
                    help="给定参考帧后按局部边缘能量排序取样，优先命中直线结构")
    args = ap.parse_args()

    tag = np.asarray(Image.open(args.prov).convert("RGB")).astype(np.float32) / 255.0
    band = reveal_band(tag, args.map)
    h, w = band.shape
    score = structure_score(args.rank_structure, args.size) if args.rank_structure else None
    points = pick_points(band, args.tiles, max(h, w) // (args.tiles + 2), score)
    if not points:
        raise SystemExit("显露带为空，来源标记图与 --map 可能不匹配")

    frames = []
    for spec in args.frame:
        label, _, path = spec.partition("=")
        frames.append((label, Image.open(path).convert("RGB")))

    half = args.size // 2
    tile = args.size * args.zoom
    pad, header = 4, 22
    sheet = Image.new(
        "RGB",
        (len(points) * (tile + pad) + pad, len(frames) * (tile + header + pad) + pad),
        (18, 18, 22),
    )
    draw = ImageDraw.Draw(sheet)
    for row, (label, img) in enumerate(frames):
        top = pad + row * (tile + header + pad)
        for col, (cy, cx) in enumerate(points):
            x0 = int(np.clip(cx - half, 0, w - args.size))
            y0 = int(np.clip(cy - half, 0, h - args.size))
            crop = img.crop((x0, y0, x0 + args.size, y0 + args.size))
            crop = crop.resize((tile, tile), Image.NEAREST)
            left = pad + col * (tile + pad)
            draw.text((left, top + 4), f"{label} @{x0},{y0} {args.zoom}x", fill=(255, 228, 90))
            sheet.paste(crop, (left, top + header))
    sheet.save(args.out, quality=93)
    print(f"{args.out}  {sheet.size}  tiles={len(points)}  band_px={int(band.sum())}")


if __name__ == "__main__":
    main()
