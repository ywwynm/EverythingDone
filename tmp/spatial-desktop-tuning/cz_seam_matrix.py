#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""渲染侧缝判据的**九场景 × 多角度矩阵**（D191 修对的那一版判据）。

判据本身沿用 `seam_render_metric.py`：把遮挡物掩膜按**前景深度**前向投影到同一视角，
第二层与第一层的交界里挨着主体的那一半剔掉，只量剩下的"第二层 vs 真实背景"那条缝。
D190 记过一次自己造错的指标——混在一起量，主体侧占大头，缝侧的改善被完全淹没。

这里只是把它铺到九场景 × 多角度，并按 D194 的纪律**并列全部场景**输出。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from band_source import occluder_mask
from seam_render_metric import MAG, project_mask

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def measure(frame: Path, tint: Path, occ, z, meta, deg, baseline_cm, margin) -> dict | None:
    if not (frame.is_file() and tint.is_file()):
        return None
    img = cv2.imread(str(frame))
    H, W = img.shape[:2]
    l2 = np.abs(cv2.imread(str(tint)).astype(np.float32) - MAG).sum(2) < 90
    a = np.deg2rad(deg)
    b = baseline_cm / 100.0
    subj = project_mask(occ, z, meta, b * np.cos(a), b * np.sin(a), margin, (H, W))
    k = lambda r: np.ones((2 * r + 1,) * 2, np.uint8)
    l1_bg = (~l2) & (~subj)
    in2 = (cv2.dilate(l1_bg.astype(np.uint8), k(6)) > 0) & l2 \
        & ~(cv2.dilate(subj.astype(np.uint8), k(3)) > 0)
    out1 = (cv2.dilate(l2.astype(np.uint8), k(6)) > 0) & l1_bg
    if in2.sum() < 256 or out1.sum() < 256:
        return None
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)[..., 0]
    sm = lambda v, s: cv2.blur(np.where(s, v, 0), (25, 25)) / \
        np.maximum(cv2.blur(s.astype(np.float32), (25, 25)), 1e-6)
    both = (cv2.blur(in2.astype(np.float32), (25, 25)) > 0.02) & \
           (cv2.blur(out1.astype(np.float32), (25, 25)) > 0.02)
    d = np.abs(sm(lab, in2) - sm(lab, out1))[both]
    hf = lab - cv2.GaussianBlur(lab, (0, 0), 1.5)
    e = lambda s: float(np.sqrt((hf[s] ** 2).mean()))
    return {"px": int(in2.sum()), "median": float(np.median(d)),
            "p90": float(np.percentile(d, 90)), "over4": float(100 * (d > 4).mean()),
            "hfRatio": e(in2) / max(e(out1), 1e-6)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, default=Path("qa/orbit/diag/probe"))
    ap.add_argument("--geometry", type=Path, default=Path("qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tags", nargs="+", required=True, help="变体后缀，如 _base _czl")
    ap.add_argument("--degs", nargs="*", type=float, default=[0, 45, 90, 135, 180, 225, 270, 315])
    ap.add_argument("--baseline-cm", type=float, default=4.5)
    ap.add_argument("--margin", type=float, default=0.059)
    ap.add_argument("--metric", default="median", choices=("median", "p90", "over4", "hfRatio"))
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    rows = []
    for scene in args.scenes:
        g = args.geometry / scene
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        occ = occluder_mask(args.assets / scene, w, h)
        for tag in args.tags:
            for deg in args.degs:
                r = measure(args.dir / f"{scene[:2]}{tag}-{deg:.0f}.png",
                            args.dir / f"{scene[:2]}{tag}-tint-{deg:.0f}.png",
                            occ, z, meta, deg, args.baseline_cm, args.margin)
                if r:
                    rows.append({"scene": scene[:2], "tag": tag, "deg": deg, **r})

    m = args.metric
    print(f"\n## 渲染侧缝落差 · {m}（越低越好；hfRatio 越接近 1 越好）\n")
    print("| 变体 | " + " | ".join(s[:2] for s in args.scenes) + " | 九场景中位 |")
    print("|---" * (len(args.scenes) + 2) + "|")
    lines = []
    for tag in args.tags:
        cells, alls = [], []
        for s in args.scenes:
            v = [r[m] for r in rows if r["tag"] == tag and r["scene"] == s[:2]]
            if not v:
                cells.append("—")
                continue
            alls.append(float(np.median(v)))
            cells.append(f"{np.median(v):.1f}")
        ov = float(np.median(alls)) if alls else float("nan")
        lines.append((ov, tag, cells, len([r for r in rows if r["tag"] == tag])))
    for ov, tag, cells, n in sorted(lines):
        print(f"| {tag}（{n} 帧） | " + " | ".join(cells) + f" | **{ov:.1f}** |")
    missing = [(s[:2], t, d) for s in args.scenes for t in args.tags for d in args.degs
               if not any(r["scene"] == s[:2] and r["tag"] == t and r["deg"] == d for r in rows)]
    if missing:
        print(f"\n**缺帧 {len(missing)} 个**（未计入，不是 0）：{missing[:12]}"
              f"{' …' if len(missing) > 12 else ''}")
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
