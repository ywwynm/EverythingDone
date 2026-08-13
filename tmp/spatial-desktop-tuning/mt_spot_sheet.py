#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""一个定点、全部视角的 A/B 放大表：每行一个角度，列为 A / B / 差异。

`mt_compare.py` 是"每角度一张、含多个定点"，看单点跨视角的表现要来回翻；
这个反过来排，用于对用户圈出的那一处下判断。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np


def label(img: np.ndarray, text: str, height: int = 22) -> np.ndarray:
    bar = np.zeros((height, img.shape[1], 3), np.uint8)
    cv2.putText(bar, text, (5, height - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.45,
                (240, 240, 240), 1, cv2.LINE_AA)
    return np.concatenate([bar, img], axis=0)


def crop(img: np.ndarray, cy: int, cx: int, tile: int, zoom: int) -> np.ndarray:
    h, w = img.shape[:2]
    y0 = int(np.clip(cy - tile // 2, 0, h - tile))
    x0 = int(np.clip(cx - tile // 2, 0, w - tile))
    return cv2.resize(img[y0:y0 + tile, x0:x0 + tile], (tile * zoom, tile * zoom),
                      interpolation=cv2.INTER_NEAREST)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mt"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mt-spot"))
    ap.add_argument("--scene2", required=True)
    ap.add_argument("--tag-a", default="mtON")
    ap.add_argument("--tag-b", default="mtOFF")
    ap.add_argument("--name", required=True)
    ap.add_argument("--xy", required=True, help="源图坐标 x,y")
    ap.add_argument("--scale", type=float, default=2.0)
    ap.add_argument("--angles", nargs="*", type=int,
                    default=[0, 45, 90, 135, 180, 225, 270, 315])
    ap.add_argument("--tile", type=int, default=120)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--orig", type=Path, default=None,
                    help="原图 center.jpg；给了就多一列参照（0° 视角，只做粗对齐）")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    x, y = (float(v) * args.scale for v in args.xy.split(","))
    cx, cy = int(round(x)), int(round(y))
    orig = None
    if args.orig is not None:
        o = cv2.imread(str(args.orig))
        orig = cv2.resize(o, (int(o.shape[1] * args.scale), int(o.shape[0] * args.scale)),
                          interpolation=cv2.INTER_NEAREST)
    rows = []
    for deg in args.angles:
        a = cv2.imread(str(args.frames / f"{args.scene2}-{args.tag_a}-{deg:03d}.png"))
        b = cv2.imread(str(args.frames / f"{args.scene2}-{args.tag_b}-{deg:03d}.png"))
        if a is None or b is None:
            continue
        diff = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(axis=2)
        heat = cv2.applyColorMap(np.clip(diff * 4, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
        cols = [
            label(crop(a, cy, cx, args.tile, args.zoom), f"{deg}deg A={args.tag_a}"),
            label(crop(b, cy, cx, args.tile, args.zoom), f"B={args.tag_b}"),
            label(crop(heat, cy, cx, args.tile, args.zoom), "diff x4"),
        ]
        if orig is not None:
            cols.append(label(crop(orig, cy, cx, args.tile, args.zoom), "orig (0deg)"))
        rows.append(np.concatenate(cols, axis=1))
    sheet = np.concatenate(rows, axis=0)
    p = args.out / f"{args.scene2}-{args.name}-{args.tag_a}-vs-{args.tag_b}.jpg"
    cv2.imwrite(str(p), sheet, [cv2.IMWRITE_JPEG_QUALITY, 94])
    print(f"-> {p}  {sheet.shape[1]}x{sheet.shape[0]}")


if __name__ == "__main__":
    main()
