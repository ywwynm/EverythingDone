#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""带的**深度**与它紧邻的真实背景对不对得上——即"露出来的那一条有没有被放错位置"。

用户反复问的那句"补全的时候没有条带，为什么渲染的时候有"，指向一个此前从未量过的
可能：补全出来的背景板本身是连贯的，真实背景也是连贯的，那么**露出它的一条也应该
连贯**——除非那一条在屏幕上被**放错了位置**。

第二层按 `hidden_z` 前向投影。若带内的 `hidden_z` 与跨过剪影那一侧真实背景的 `z`
对不上，两者在同一基线下的像素位移就不同：

    错位量(px) = fx · 基线 · |1/z_带 − 1/z_邻近真实背景|

这个量若有几个像素，露出来的那条内容就相对周围**整体平移**了几像素——一条内容连贯、
但相对邻居错位的窄带，看起来正是"半透明条带 / 轮廓复制了一份"。

参照取**跨过深度断边、更远的那一侧**的真实像素（与 D190 同一套远侧定义），
逐带段各比各的。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from build_seam_matched_band import far_side_reference

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tag", default="_tex")
    ap.add_argument("--baseline-cm", type=float, default=4.5)
    ap.add_argument("--sep-px", type=float, default=1.5)
    args = ap.parse_args()

    print(f"{'场景':<22} {'缝样本':>7} {'带深度 中位':>10} {'邻近真背景':>10} "
          f"{'错位px 中位':>11} {'p90':>7} {'max':>6} {'>1px 占比':>9}")
    out = {}
    for scene in args.scenes:
        g = args.geometry / scene
        mp = g / f"hidden_mask{args.tag}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        band = cv2.imread(str(mp), 0) > 127
        if band.sum() < 256:
            continue
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{args.tag}.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        sep = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)
        far, inner = far_side_reference(band, inv, sep)
        if inner.sum() < 128 or far.sum() < 128:
            continue
        # 每个缝内点配一个"最近的远侧真实背景"的逆深度
        src = (~far).astype(np.uint8)
        _, lab = cv2.distanceTransformWithLabels(src, cv2.DIST_L2, 5,
                                                 labelType=cv2.DIST_LABEL_PIXEL)
        ys, xs = np.nonzero(far)
        order = np.lexsort((xs, ys))
        ty = np.zeros(ys.size + 1, np.int32); ty[1:] = ys[order]
        tx = np.zeros(ys.size + 1, np.int32); tx[1:] = xs[order]
        lab = np.clip(lab, 0, ys.size)
        iy, ix = np.nonzero(inner)
        ref_inv = inv[ty[lab[iy, ix]], tx[lab[iy, ix]]]
        band_inv = 1.0 / np.maximum(hz[iy, ix], 1e-6)
        shift = meta["fx"] * (args.baseline_cm / 100.0) * np.abs(band_inv - ref_inv)
        print(f"{scene:<22} {iy.size:>7d} {np.median(1/band_inv):>10.2f} "
              f"{np.median(1/ref_inv):>10.2f} {np.median(shift):>11.2f} "
              f"{np.percentile(shift,90):>7.2f} {shift.max():>6.1f} "
              f"{100*(shift>1).mean():>8.1f}%")
        out[scene] = {"n": int(iy.size), "shiftMedian": float(np.median(shift)),
                      "shiftP90": float(np.percentile(shift, 90)),
                      "shiftMax": float(shift.max()),
                      "over1pxPct": float(100 * (shift > 1).mean())}
    (args.geometry.parent / "matte-soft-probe" / "band_depth_mismatch.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
