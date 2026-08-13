#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""渲染侧的正确缝判据：**只量"第二层 vs 真实背景"那一半交界**。

D190 记过一个自己造错的指标：第二层与第一层的交界有两部分——一部分贴着**位移后的
主体**（那里有大落差是正确的，那就是剪影），另一部分才是贴着真实背景的缝。混在一起
量，主体侧占大头，缝侧的改善完全被淹没（修前 87.9% → 修后 88.0%，纹丝不动）。

这里把两者分开：把遮挡物掩膜按**前景深度**前向投影到同一视角，得到"主体在屏幕上的
位置"，交界里挨着它的那一半剔除，剩下的才是要量的缝。投影公式与 `SPLAT_VS` 逐字一致。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from band_source import occluder_mask

MAG = np.array([229, 0, 255], np.float32)


def project_mask(mask, z, meta, tx, ty, margin, out_hw):
    """把 mask 按前景深度前向投影，得到屏幕占位（点泼溅，半径 1）。"""
    h, w = z.shape
    H, W = out_hw
    yy, xx = np.nonzero(mask)
    zz = np.maximum(z[yy, xx], 1e-6)
    X = (xx + 0.5 - meta["cx"]) * zz / meta["fx"]
    Y = (yy + 0.5 - meta["cy"]) * zz / meta["fy"]
    u = meta["fx"] * (X - tx) / zz + meta["cx"] + meta["fx"] * tx / meta["pivotZ"]
    v = meta["fy"] * (Y - ty) / zz + meta["cy"] + meta["fy"] * ty / meta["pivotZ"]
    cu = (u / w - margin) / (1 - 2 * margin)
    cv_ = (v / h - margin) / (1 - 2 * margin)
    px = np.round(cu * W).astype(int)
    py = np.round(cv_ * H).astype(int)
    ok = (px >= 0) & (px < W) & (py >= 0) & (py < H)
    out = np.zeros((H, W), np.uint8)
    out[py[ok], px[ok]] = 1
    return cv2.dilate(out, np.ones((5, 5), np.uint8)) > 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/probe"))
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scene", default="00_original_single")
    ap.add_argument("--tags", nargs="+", default=["str", "seam", "tex"])
    ap.add_argument("--deg", type=float, default=315.0)
    ap.add_argument("--baseline-cm", type=float, default=4.5)
    ap.add_argument("--margin", type=float, default=0.059)
    args = ap.parse_args()

    g = args.geometry / args.scene
    meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
    h, w = meta["height"], meta["width"]
    z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
    occ = occluder_mask(args.assets / args.scene, w, h)

    a = np.deg2rad(args.deg)
    b = args.baseline_cm / 100.0
    tx, ty = b * np.cos(a), b * np.sin(a)
    k = lambda r: np.ones((2 * r + 1,) * 2, np.uint8)

    print(f"{args.scene} @ {args.deg:.0f}deg")
    print(f"{'档':>6} {'缝侧px':>8} {'局部|ΔL| 中位':>13} {'p90':>7} {'|Δ|>4 占比':>10} "
          f"{'高频比 带/背景':>14}")
    for tag in args.tags:
        p, pt = args.dir / f"{tag}-{args.deg:.0f}.png", args.dir / f"{tag}-tint-{args.deg:.0f}.png"
        if not (p.is_file() and pt.is_file()):
            print(f"{tag:>6}  缺帧")
            continue
        img = cv2.imread(str(p))
        H, W = img.shape[:2]
        l2 = np.abs(cv2.imread(str(pt)).astype(np.float32) - MAG).sum(2) < 90
        subj = project_mask(occ, z, meta, tx, ty, args.margin, (H, W))
        l1_bg = (~l2) & (~subj)                       # 第一层里**不是主体**的部分
        in2 = (cv2.dilate(l1_bg.astype(np.uint8), k(6)) > 0) & l2 \
            & ~(cv2.dilate(subj.astype(np.uint8), k(3)) > 0)
        out1 = (cv2.dilate(l2.astype(np.uint8), k(6)) > 0) & l1_bg
        if in2.sum() < 256 or out1.sum() < 256:
            print(f"{tag:>6}  缝侧样本不足 {int(in2.sum())}/{int(out1.sum())}")
            continue
        lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)[..., 0]
        sm = lambda v, s: cv2.blur(np.where(s, v, 0), (25, 25)) / \
            np.maximum(cv2.blur(s.astype(np.float32), (25, 25)), 1e-6)
        both = (cv2.blur(in2.astype(np.float32), (25, 25)) > 0.02) & \
               (cv2.blur(out1.astype(np.float32), (25, 25)) > 0.02)
        d = np.abs(sm(lab, in2) - sm(lab, out1))[both]
        # 高频比：带内 vs 缝外真实背景
        hf = lab - cv2.GaussianBlur(lab, (0, 0), 1.5)
        e = lambda s: float(np.sqrt((hf[s] ** 2).mean()))
        print(f"{tag:>6} {int(in2.sum()):>8d} {np.median(d):>13.1f} "
              f"{np.percentile(d, 90):>7.1f} {100*(d > 4).mean():>9.1f}% "
              f"{e(in2)/max(e(out1),1e-6):>14.2f}")


if __name__ == "__main__":
    main()
