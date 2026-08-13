#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""差异引导的放大对照：选区直接按 |off−on| 打分，只看软 α 真正动到的地方。

`zoom_matrix.py` 按"带密度 × 边缘能量"选区，那是找**补全**缺陷用的；软 α 只动剪影
上很窄的一条，那套选区会把镜头对到别处去（00 场景实测选到了桌面与酒杯，而改动集中
在人物轮廓）。这里按差异本身选，看到的就是改动本身。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from zoom_matrix import crop, label


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/softdef"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/zoom-soft"))
    ap.add_argument("--off", required=True)
    ap.add_argument("--on", required=True)
    ap.add_argument("--deg", type=int, required=True)
    ap.add_argument("--tile", type=int, default=100)
    ap.add_argument("--zoom", type=int, default=4)
    ap.add_argument("--count", type=int, default=4)
    ap.add_argument("--min-sep", type=int, default=160)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    a = cv2.imread(str(args.dir / f"{args.off}-{args.deg}.png"))
    b = cv2.imread(str(args.dir / f"{args.on}-{args.deg}.png"))
    if a is None or b is None:
        raise SystemExit("缺帧")
    diff = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(2)
    score = cv2.blur(diff, (args.tile, args.tile))
    h2 = args.tile // 2
    score[:h2, :] = score[-h2:, :] = 0
    score[:, :h2] = score[:, -h2:] = 0

    picks, work = [], score.copy()
    for i in range(args.count):
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        if work[y, x] <= 0:
            break
        picks.append((int(x), int(y), float(work[y, x])))
        cv2.circle(work, (x, y), args.min_sep, 0.0, -1)

    heat = cv2.applyColorMap(np.clip(diff * 8, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
    rows = []
    for i, (x, y, s) in enumerate(picks):
        rows.append(np.concatenate([
            label(crop(a, x, y, args.tile, args.zoom), f"d{i} ({x},{y}) off {args.deg}deg"),
            label(crop(b, x, y, args.tile, args.zoom), f"on {args.deg}deg"),
            label(crop(heat, x, y, args.tile, args.zoom), f"diff x8  mean {s:.1f}"),
        ], axis=1))
    p = args.out / f"{args.off}-vs-{args.on}-{args.deg}-diffzoom.jpg"
    cv2.imwrite(str(p), np.concatenate(rows, axis=0), [cv2.IMWRITE_JPEG_QUALITY, 94])
    print(f"-> {p}")


if __name__ == "__main__":
    main()
