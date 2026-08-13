#!/usr/bin/env python
"""补全后端 A/B 的放大巡检表。

纪律（memory/visual-acceptance-zoom-and-matrix）：整图缩略看不出台阶、锯齿、暗边和
糊斑，必须裁到关键区放大 ≥2× 逐块查，且要覆盖多个视角方向。

选区不靠肉眼：按 **遮挡带密度 × 原图边缘能量** 排序取前 N 块——带越宽、周围结构越强的
地方，补全失败越显眼。每块输出一张 2×N 的表：上排 A 后端、下排 B 后端，列为各视角。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def pick_tiles(mask: np.ndarray, image: np.ndarray, tile: int, count: int,
               min_sep: int) -> list[tuple[int, int]]:
    """按 带密度 × 边缘能量 打分，非极大抑制后取前 count 块（返回中心点，掩膜坐标系）。"""
    grey = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY).astype(np.float32)
    edge = np.abs(cv2.Sobel(grey, cv2.CV_32F, 1, 0, 3)) + np.abs(cv2.Sobel(grey, cv2.CV_32F, 0, 1, 3))
    k = tile
    band = cv2.blur(mask.astype(np.float32), (k, k))
    energy = cv2.blur(edge, (k, k))
    score = band * energy
    score[: k // 2, :] = score[-k // 2:, :] = 0
    score[:, : k // 2] = score[:, -k // 2:] = 0

    picks: list[tuple[int, int]] = []
    work = score.copy()
    for _ in range(count):
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        if work[y, x] <= 0:
            break
        picks.append((int(y), int(x)))
        cv2.circle(work, (x, y), min_sep, 0.0, -1)
    return picks


def label(img: np.ndarray, text: str, height: int = 26) -> np.ndarray:
    bar = np.zeros((height, img.shape[1], 3), np.uint8)
    cv2.putText(bar, text, (6, height - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (235, 235, 235), 1, cv2.LINE_AA)
    return np.concatenate([bar, img], axis=0)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True, help="含 <backend>-<deg>.png 的目录")
    ap.add_argument("--backends", nargs="+", required=True, help="按行排列，第一个为基准")
    ap.add_argument("--degs", nargs="+", type=int, default=[0, 45, 90, 135, 180, 225, 270, 315])
    ap.add_argument("--mask", type=Path, required=True)
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--tile", type=int, default=110, help="掩膜坐标系下的裁剪边长")
    ap.add_argument("--count", type=int, default=5)
    ap.add_argument("--zoom", type=float, default=2.6)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    mask = np.asarray(Image.open(args.mask).convert("L")) > 127
    image = np.asarray(Image.open(args.image).convert("RGB"))
    mh, mw = mask.shape
    picks = pick_tiles(mask, image, args.tile, args.count, args.tile)
    args.out.mkdir(parents=True, exist_ok=True)

    # 渲染图与掩膜的尺度比（渲染带内缩取景，但比例一致）
    probe = np.asarray(Image.open(args.dir / f"{args.backends[0]}-{args.degs[0]}.png").convert("RGB"))
    scale = probe.shape[1] / mw
    half = args.tile // 2
    cell = int(args.tile * scale * args.zoom / scale)  # 输出像素边长按 zoom 放大原生像素

    for i, (cy, cx) in enumerate(picks):
        rows = []
        for backend in args.backends:
            cols = []
            for d in args.degs:
                p = args.dir / f"{backend}-{d}.png"
                img = np.asarray(Image.open(p).convert("RGB"))
                ry, rx = int(cy * scale), int(cx * scale)
                rh = int(half * scale)
                y0, x0 = max(0, ry - rh), max(0, rx - rh)
                y1, x1 = min(img.shape[0], ry + rh), min(img.shape[1], rx + rh)
                crop = img[y0:y1, x0:x1]
                crop = cv2.resize(crop, (int(crop.shape[1] * args.zoom), int(crop.shape[0] * args.zoom)),
                                  interpolation=cv2.INTER_NEAREST)
                cols.append(label(crop, f"{backend} {d}°"))
            rows.append(np.concatenate(cols, axis=1))
        sheet = np.concatenate(rows, axis=0)
        name = args.out / f"tile{i}_y{cy}_x{cx}.png"
        Image.fromarray(sheet).save(name)
        print(f"{name}  {sheet.shape[1]}x{sheet.shape[0]}  中心(掩膜坐标) y={cy} x={cx}")


if __name__ == "__main__":
    main()
