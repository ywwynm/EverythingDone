#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把带内容与**剪影另一侧的真实背景**做局部连续化，产出 `_seam` 变体。

为什么是这条缝（2026-08-12 定位）：用户看到的条带出现在**渲染之后**。渲染帧上量
"第二层 / 第一层"交界两侧，**87.9% 的交界 |ΔL| > 4**（局部 p10 −48.4、p90 +40.6），
而全局中位只有 +1.0——正负相抵，把它整个藏住了。**这条带没有一致偏移，它是逐点几十级
的跳变**，所以先前所有"整体偏暗/对比度塌陷"的全局测量都查不出东西。

为什么以前没修上：
- 梯度域合成（`seamless_blend`）的 Ω 是「带 ∪ 遮挡物膨胀」，Dirichlet 边界取在整个
  主体外圈——它只锚住**整块背景板的全局层级**，而带内容在剪影处离那圈边界很远，
  "带内容要与剪影另一侧的真实背景连续"**从来没有任何约束**；
- D137 的膜校正把参照取在**近侧**（被前景污染的那一侧），越校越暗；
- D159 是全局标量偏置；D181 在背景板档直接关掉了。

这一版的边界取法（关键差异）：Dirichlet 值取在带的**远侧**边界上——即跨过深度断边、
逆深度更小（更远）的那一侧的**真实像素**。那正是渲染时会与带内容贴在一起的东西。
逐带段各解各的，不跨段共用（D180 的教训）。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from band_source import occluder_mask
from build_hidden_layer import harmonic_extend

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def far_side_reference(band, inv, sep, ring_in=3, ring_out=4):
    """返回 (远侧参照掩膜, 带的远侧边界掩膜)。

    远侧 = 跨过断边、比带自身更远（逆深度更小）的真实像素。判据用与断边同一个常量
    `sep`，因此"哪边算远"与网格切断处的判定同源，不会两处打架。
    """
    h, w = band.shape
    k = lambda r: np.ones((2 * r + 1,) * 2, np.uint8)
    outside = ~band
    # 带外、且比"紧邻的带像素"更远的像素
    band_lvl = cv2.dilate(np.where(band, inv, 0).astype(np.float32), k(1))
    cnt = cv2.dilate(band.astype(np.float32), k(1))
    band_lvl = np.where(cnt > 0, band_lvl, 0.0)      # 邻域内带像素的逆深度（取最大=最近）
    far = outside & (cnt > 0) & (inv < band_lvl - sep)
    far = cv2.dilate(far.astype(np.uint8), k(ring_out)) > 0
    far &= outside
    inner = band & (cv2.dilate(far.astype(np.uint8), k(ring_in)) > 0)
    return far, inner


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--src-tag", default="_str")
    ap.add_argument("--tag", default="_seam")
    ap.add_argument("--sep-px", type=float, default=1.5)
    ap.add_argument("--blur", type=float, default=2.0, help="缝上失配的平滑，抑制单点噪声")
    ap.add_argument("--clip", type=float, default=60.0, help="单点校正上限（灰阶），防跑飞")
    args = ap.parse_args()

    print(f"{'场景':<22} {'缝样本px':>9} {'缝失配 中位/p90':>16} {'校正后':>16} {'带内校正 中位':>12}")
    stats = {}
    for scene in args.scenes:
        g = args.geometry / scene
        st = args.src_tag
        mp = g / f"hidden_mask{st}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        img = cv2.imread(str(args.assets / scene / "center.jpg")).astype(np.float32)
        band = cv2.imread(str(mp), 0) > 127
        col = cv2.imread(str(g / f"hidden_color{st}.png")).astype(np.float32)
        if band.sum() < 256:
            continue
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        sep = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)

        far, inner = far_side_reference(band, inv, sep)
        if inner.sum() < 128 or far.sum() < 128:
            print(f"{scene:<22} 远侧参照不足（内 {int(inner.sum())} 外 {int(far.sum())}），跳过")
            continue

        # 缝上的失配 d = 带内侧局部均值 − 远侧真实背景局部均值。两侧各自只用自己那一侧
        # 的像素做带权平滑，绝不互相污染（D137 的坑就是参照里混进了被测对象）。
        def side_mean(vals, sel):
            s = cv2.GaussianBlur((vals * sel[..., None]).astype(np.float32), (0, 0), 6.0)
            n = cv2.GaussianBlur(sel.astype(np.float32), (0, 0), 6.0)[..., None]
            return np.where(n > 1e-3, s / np.maximum(n, 1e-6), 0.0)
        mi, mo = side_mean(col, inner), side_mean(img, far)
        support = (cv2.GaussianBlur(inner.astype(np.float32), (0, 0), 6.0) > 1e-3) & \
                  (cv2.GaussianBlur(far.astype(np.float32), (0, 0), 6.0) > 1e-3)
        seam = inner & support
        if seam.sum() < 128:
            print(f"{scene:<22} 缝上有效样本不足，跳过")
            continue
        d = np.clip(mi - mo, -args.clip, args.clip)
        d = cv2.GaussianBlur(d, (0, 0), args.blur)

        before = np.abs(d[seam]).mean(1)
        # 只在缝上给定值，向带内调和延拓；带外恒为 0（带外一个像素都不许动，D133）
        c = harmonic_extend(d * seam[..., None], seam)
        c = np.where(band[..., None], c, 0.0)
        out = np.clip(np.where(band[..., None], col - c, img), 0, 255)

        mi2 = side_mean(out, inner)
        after = np.abs((mi2 - mo)[seam]).mean(1)
        cm = float(np.median(np.abs(c[band]).mean(1)))
        print(f"{scene:<22} {int(seam.sum()):>9d} "
              f"{np.median(before):>7.1f}/{np.percentile(before,90):<8.1f} "
              f"{np.median(after):>7.1f}/{np.percentile(after,90):<8.1f} {cm:>12.1f}")
        stats[scene] = {"seamPx": int(seam.sum()),
                        "beforeMedian": float(np.median(before)),
                        "beforeP90": float(np.percentile(before, 90)),
                        "afterMedian": float(np.median(after)),
                        "afterP90": float(np.percentile(after, 90)),
                        "corrMedian": cm}

        t = args.tag
        cv2.imwrite(str(g / f"hidden_color{t}.png"), out.astype(np.uint8))
        cv2.imwrite(str(g / f"seam_corr{t}.png"),
                    np.clip(np.abs(c).mean(2) * 4, 0, 255).astype(np.uint8))
        cv2.imwrite(str(g / f"seam_ring{t}.png"),
                    (seam.astype(np.uint8) * 128 + far.astype(np.uint8) * 127))
        for name in (f"hidden_mask{st}.png", f"selfocc_code{st}.png", f"hidden_paint{st}.png",
                     f"hidden_paint_aggr{st}.png", f"hidden_paint_cons{st}.png",
                     f"hidden_raw_aggr{st}.png", f"hidden_raw_cons{st}.png",
                     f"hidden_z{st}.f32", f"struct_conf{st}.png", f"band_src{st}.png"):
            p = g / name
            if p.is_file():
                shutil.copyfile(p, g / name.replace(st, t))

    (args.geometry.parent / "matte-soft-probe" / "seam_match_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
