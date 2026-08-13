#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""`--use-matte` 定点目检：只切这一个开关，做渲染帧的放大对比表。

纪律：
- 选区**不靠肉眼挑**。两版渲染帧的差异图自动定位：按 tile 内平均差异排序，非极大抑制取前 N 块。
  这样"哪里被这个开关改动了"由数据说了算，避免只看自己想看的地方。
- 每块放大 ≥2×（`--zoom`），左右并排（A=开 / B=关），额外给一列差异热图。
- 另支持 `--spots` 手工定点（用户圈出的位置），与自动选区并列输出。

输入是 `__shot` 落盘的整帧 PNG，命名 `<scene2>-<tagA|tagB>-<deg>.png`。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def label(img: np.ndarray, text: str, height: int = 24) -> np.ndarray:
    bar = np.zeros((height, img.shape[1], 3), np.uint8)
    cv2.putText(bar, text, (5, height - 7), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                (240, 240, 240), 1, cv2.LINE_AA)
    return np.concatenate([bar, img], axis=0)


def pick_by_diff(diff: np.ndarray, tile: int, count: int, min_sep: int
                 ) -> list[tuple[int, int, float]]:
    """按 tile 内平均差异排序取前 count 块（返回中心 y, x, 分数）。"""
    score = cv2.blur(diff, (tile, tile))
    h2 = tile // 2
    score[:h2, :] = 0
    score[-h2:, :] = 0
    score[:, :h2] = 0
    score[:, -h2:] = 0
    picks: list[tuple[int, int, float]] = []
    work = score.copy()
    for _ in range(count):
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        if work[y, x] <= 1e-6:
            break
        picks.append((int(y), int(x), float(work[y, x])))
        cv2.circle(work, (x, y), min_sep, 0.0, -1)
    return picks


def crop(img: np.ndarray, cy: int, cx: int, tile: int, zoom: int) -> np.ndarray:
    h, w = img.shape[:2]
    h2 = tile // 2
    y0 = int(np.clip(cy - h2, 0, h - tile))
    x0 = int(np.clip(cx - h2, 0, w - tile))
    patch = img[y0:y0 + tile, x0:x0 + tile]
    return cv2.resize(patch, (tile * zoom, tile * zoom), interpolation=cv2.INTER_NEAREST)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mt"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mt-cmp"))
    ap.add_argument("--scene2", required=True, help="帧名前缀，例如 00 / 05")
    ap.add_argument("--tag-a", default="mtON")
    ap.add_argument("--tag-b", default="mtOFF")
    ap.add_argument("--angles", nargs="*", type=int,
                    default=[0, 45, 90, 135, 180, 225, 270, 315])
    ap.add_argument("--tile", type=int, default=140)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--count", type=int, default=3, help="每个角度自动选几块")
    ap.add_argument("--min-sep", type=int, default=150)
    ap.add_argument("--spots", default="", help="手工定点，格式 名字:x,y;名字:x,y（帧坐标）")
    ap.add_argument("--spot-scale", type=float, default=1.0,
                    help="若定点给的是源图坐标，这里给帧/源的缩放比")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    spots = []
    for item in filter(None, args.spots.split(";")):
        name, xy = item.split(":")
        x, y = (float(v) * args.spot_scale for v in xy.split(","))
        spots.append((name, int(round(y)), int(round(x))))

    report: dict[str, object] = {"scene": args.scene2, "angles": {}}
    for deg in args.angles:
        pa = args.frames / f"{args.scene2}-{args.tag_a}-{deg:03d}.png"
        pb = args.frames / f"{args.scene2}-{args.tag_b}-{deg:03d}.png"
        a = cv2.imread(str(pa), cv2.IMREAD_COLOR)
        b = cv2.imread(str(pb), cv2.IMREAD_COLOR)
        if a is None or b is None:
            print(f"缺帧：{pa.name} / {pb.name}")
            continue
        diff = np.abs(a.astype(np.float32) - b.astype(np.float32)).max(axis=2)
        heat = cv2.applyColorMap(np.clip(diff * 4, 0, 255).astype(np.uint8), cv2.COLORMAP_TURBO)
        picks = pick_by_diff(diff, args.tile, args.count, args.min_sep)
        report["angles"][str(deg)] = {
            "meanDiff": float(diff.mean()),
            "p999Diff": float(np.percentile(diff, 99.9)),
            "over16pct": float((diff > 16).mean() * 100),
            "picks": [{"y": y, "x": x, "score": round(s, 2)} for y, x, s in picks],
        }
        rows = []
        for tagname, cy, cx in [(f"auto{i}", y, x) for i, (y, x, _) in enumerate(picks)] + spots:
            ca = crop(a, cy, cx, args.tile, args.zoom)
            cb = crop(b, cy, cx, args.tile, args.zoom)
            ch = crop(heat, cy, cx, args.tile, args.zoom)
            row = np.concatenate([
                label(ca, f"{tagname} {deg}deg  A={args.tag_a}  ({cx},{cy})"),
                label(cb, f"B={args.tag_b}"),
                label(ch, "diff x4"),
            ], axis=1)
            rows.append(row)
        if rows:
            sheet = np.concatenate(rows, axis=0)
            cv2.imwrite(str(args.out / f"{args.scene2}-{deg:03d}.jpg"), sheet,
                        [cv2.IMWRITE_JPEG_QUALITY, 94])
        print(f"{deg:3d}° 平均差 {diff.mean():6.2f}  >16级 {100*(diff>16).mean():5.2f}%  "
              f"选块 {[(x, y) for y, x, _ in picks]}")

    (args.out / f"{args.scene2}-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
