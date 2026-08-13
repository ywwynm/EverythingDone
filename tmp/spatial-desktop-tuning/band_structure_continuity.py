#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""新判据「结构延续率」（工单验收项）：带外边界的强结构，有多少在带内延续下去了。

定义（全部在资产空间，不经渲染，因此与视角无关）：
1. 取带的**外边界**像素（与合法来源相邻的那一圈带像素）；
2. 在**真实背景**一侧退开 3px 处取参考方向与相干度，只保留相干度 > `--coh-ref`
   的点——那才叫"有结构可延续"；
3. 沿**向带内**的法向走 `--depth-px` 像素，在**待评图**上取该处的方向与相干度；
4. 判为延续：方向夹角 < `--ang`，且该处相干度 > `--coh-in`。

方向一律取等照度线方向（结构张量主特征向量的垂向），夹角按无向线段算（差 180° 等价）。
参考侧的方向来自 `center.jpg`（真实像素），带内的方向来自被评的那一档——所以这个数
比的是"补出来的内容有没有把外面的线接上"，不是"两张图像不像"。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def orient(gray, sigma=2.0):
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, 3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, 3)
    g = lambda a: cv2.GaussianBlur(a, (0, 0), sigma)
    jxx, jyy, jxy = g(gx * gx), g(gy * gy), g(gx * gy)
    tr = jxx + jyy
    disc = np.sqrt(np.maximum(tr * tr / 4.0 - (jxx * jyy - jxy * jxy), 0.0))
    l1, l2 = tr / 2.0 + disc, tr / 2.0 - disc
    vx, vy = jxy, l1 - jxx
    n = np.maximum(np.sqrt(vx * vx + vy * vy), 1e-6)
    # 等照度线方向 = 梯度方向的垂向
    return (-vy / n).astype(np.float32), (vx / n).astype(np.float32), \
           ((l1 - l2) / np.maximum(l1 + l2, 1e-6)).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tags", nargs="+", default=["_mix", "_str"])
    ap.add_argument("--depth-px", type=int, default=6)
    ap.add_argument("--ang", type=float, default=30.0)
    ap.add_argument("--coh-ref", type=float, default=0.5)
    ap.add_argument("--coh-in", type=float, default=0.3)
    args = ap.parse_args()

    hdr = "".join(f"{t:>12}" for t in args.tags)
    print(f"{'场景':<22} {'样本点':>7}{hdr}   {'相对提升':>8}")
    out = {}
    for scene in args.scenes:
        g = args.geometry / scene
        base = args.tags[0]
        mp = g / f"hidden_mask{base}.png"
        if not mp.is_file():
            continue
        band = cv2.imread(str(mp), 0) > 127
        if band.sum() < 256:
            continue
        img = cv2.imread(str(args.assets / scene / "center.jpg"))
        h, w = band.shape
        srcp = g / f"band_src{args.tags[-1]}.png"
        src = (cv2.imread(str(srcp), 0) > 127) if srcp.is_file() else ~band

        # 外边界 + 指向带内的法向（距离变换梯度）
        dist = cv2.distanceTransform(band.astype(np.uint8), cv2.DIST_L2, 5)
        nx = cv2.Sobel(dist, cv2.CV_32F, 1, 0, 3)
        ny = cv2.Sobel(dist, cv2.CV_32F, 0, 1, 3)
        nn = np.maximum(np.sqrt(nx * nx + ny * ny), 1e-6)
        nx, ny = nx / nn, ny / nn                      # 指向带内（距离增大方向）
        outer = band & (cv2.dilate(src.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)
        ys, xs = np.nonzero(outer)
        if ys.size < 64:
            continue

        rx, ry, rc = orient(cv2.cvtColor(img.astype(np.float32), cv2.COLOR_BGR2GRAY))
        # 参考点：沿法向**往外** 3px（真实背景侧）
        py = np.clip((ys - 3 * ny[ys, xs]).round().astype(int), 0, h - 1)
        px = np.clip((xs - 3 * nx[ys, xs]).round().astype(int), 0, w - 1)
        keep = (rc[py, px] > args.coh_ref) & src[py, px]
        if keep.sum() < 32:
            print(f"{scene:<22} 强结构样本不足（{int(keep.sum())}）")
            continue
        ys, xs = ys[keep], xs[keep]
        tx, ty = rx[py[keep], px[keep]], ry[py[keep], px[keep]]
        # 评估点：沿法向往带内 depth-px
        qy = np.clip((ys + args.depth_px * ny[ys, xs]).round().astype(int), 0, h - 1)
        qx = np.clip((xs + args.depth_px * nx[ys, xs]).round().astype(int), 0, w - 1)
        inband = band[qy, qx]

        row, rates = {}, []
        for t in args.tags:
            cp = g / f"hidden_color{t}.png"
            if not cp.is_file():
                rates.append(float("nan"))
                continue
            c = cv2.imread(str(cp)).astype(np.float32)
            ox, oy, oc = orient(cv2.cvtColor(c, cv2.COLOR_BGR2GRAY))
            dot = np.abs(ox[qy, qx] * tx + oy[qy, qx] * ty)   # 无向夹角
            ok = inband & (oc[qy, qx] > args.coh_in) & (dot > np.cos(np.deg2rad(args.ang)))
            r = 100.0 * float(ok.sum()) / max(int(inband.sum()), 1)
            rates.append(r)
            row[t] = r
        gain = (rates[-1] - rates[0]) / max(rates[0], 1e-6) * 100
        print(f"{scene:<22} {int(inband.sum()):>7d}"
              + "".join(f"{r:>11.1f}%" for r in rates) + f"{gain:>+8.1f}%")
        out[scene] = {"samples": int(inband.sum()), "rates": row, "relGainPct": gain}

    (args.geometry.parent / "matte-soft-probe" / "structure_continuity.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
