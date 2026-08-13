#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把带的**深度**接到剪影另一侧的真实背景上，产出 `_dz` 变体。

这是"补全时没有条带、渲染时有"的真正原因（2026-08-12 实测）：带被赋予的
`hidden_z` 系统性地比它紧邻的真实背景**更近**（00 场景 4.80m vs 5.37m、
04 场景 14.89m vs 28.20m），于是同一基线下它比周围**多位移**了
`fx·基线·|1/z_带 − 1/z_邻近背景|` = 中位 0.5–1.0px、p90 1.0–2.5px、最大 6.4px，
19.8%–48.8% 的缝错位超过 1px。

补全出来的背景板本身是连贯的、真实背景也是连贯的——**露出来的那一条被放错了位置**，
一条内容连贯却相对邻居平移几像素的窄带，看起来就是"轮廓复制了一份 / 半透明条带"。
错位只在 warp 之后存在，所以在补全输出的诊断视图里根本看不到。

修法与 D190 同构，但作用在**逆深度**上而不是颜色上：缝上取失配
（带内侧逆深度 − 远侧真实背景逆深度），调和延拓进带内，减掉。带外一个像素不动。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from build_hidden_layer import harmonic_extend
from build_seam_matched_band import far_side_reference

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--src-tag", default="_tex")
    ap.add_argument("--tag", default="_dz")
    ap.add_argument("--sep-px", type=float, default=1.5)
    ap.add_argument("--baseline-cm", type=float, default=4.5)
    ap.add_argument("--blur", type=float, default=2.0)
    args = ap.parse_args()

    print(f"{'场景':<22} {'缝样本':>7} {'错位px 修前 中位/p90':>20} {'修后 中位/p90':>16} "
          f"{'>1px 前→后':>14}")
    stats = {}
    for scene in args.scenes:
        g = args.geometry / scene
        st = args.src_tag
        mp = g / f"hidden_mask{st}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        band = cv2.imread(str(mp), 0) > 127
        if band.sum() < 256:
            continue
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{st}.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        hinv = 1.0 / np.maximum(hz, 1e-6)
        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        sep = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)
        far, inner = far_side_reference(band, inv, sep)
        if inner.sum() < 128 or far.sum() < 128:
            print(f"{scene:<22} 远侧参照不足，跳过")
            continue

        # 缝上的逆深度失配：两侧各自带权平滑，互不污染
        sm = lambda v, s: cv2.GaussianBlur((v * s).astype(np.float32), (0, 0), 6.0) / \
            np.maximum(cv2.GaussianBlur(s.astype(np.float32), (0, 0), 6.0), 1e-6)
        mi, mo = sm(hinv, inner), sm(inv, far)
        support = (cv2.GaussianBlur(inner.astype(np.float32), (0, 0), 6.0) > 1e-3) & \
                  (cv2.GaussianBlur(far.astype(np.float32), (0, 0), 6.0) > 1e-3)
        seam = inner & support
        if seam.sum() < 128:
            continue
        # 失配用**该点自己的** hinv 减去远侧背景的局部均值，不是两边都取平滑值：
        # 取 mi（hinv 的平滑版）会把"hinv 自身相对其平滑版的起伏"留在残差里，
        # 实测缝上仍剩 0.34–0.71px 中位错位，正是这一项。
        d = cv2.GaussianBlur((hinv - mo) * seam, (0, 0), args.blur)
        c = harmonic_extend(d[..., None] * seam[..., None], seam)[..., 0]
        new_inv = np.where(band, hinv - c, hinv)
        # 第二层必须严格在第一层之后（与 build_hidden_layer 的同一条约束）
        new_z = 1.0 / np.maximum(new_inv, 1e-6)
        new_z = np.maximum(new_z, z * 1.001).astype(np.float32)

        f = meta["fx"] * args.baseline_cm / 100.0
        iy, ix = np.nonzero(seam)
        before = f * np.abs(hinv[iy, ix] - mo[iy, ix])
        after = f * np.abs((1.0 / np.maximum(new_z, 1e-6))[iy, ix] - mo[iy, ix])
        print(f"{scene:<22} {iy.size:>7d} {np.median(before):>9.2f}/{np.percentile(before,90):<10.2f} "
              f"{np.median(after):>7.2f}/{np.percentile(after,90):<8.2f} "
              f"{100*(before>1).mean():>6.1f}% → {100*(after>1).mean():<6.1f}%")
        stats[scene] = {"n": int(iy.size),
                        "beforeMedian": float(np.median(before)),
                        "afterMedian": float(np.median(after)),
                        "beforeP90": float(np.percentile(before, 90)),
                        "afterP90": float(np.percentile(after, 90)),
                        "beforeOver1": float(100 * (before > 1).mean()),
                        "afterOver1": float(100 * (after > 1).mean())}

        t = args.tag
        new_z.tofile(g / f"hidden_z{t}.f32")
        for name in (f"hidden_color{st}.png", f"hidden_mask{st}.png", f"selfocc_code{st}.png",
                     f"hidden_paint{st}.png", f"hidden_paint_aggr{st}.png",
                     f"hidden_paint_cons{st}.png", f"hidden_raw_aggr{st}.png",
                     f"hidden_raw_cons{st}.png", f"struct_conf{st}.png", f"band_src{st}.png"):
            p = g / name
            if p.is_file():
                shutil.copyfile(p, g / name.replace(st, t))

    (args.geometry.parent / "matte-soft-probe" / "depth_match_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
