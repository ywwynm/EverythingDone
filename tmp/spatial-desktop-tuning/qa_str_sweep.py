#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""结构档整圈验收：九场景 × 24 角度，`_mix` 与 `_str` 同口径逐项比。

四组量，缺一不可（D186 的教训固定为流程：指标改善必须同时给目检，指标单独不作数）：
- **缺陷四项**（`render_defects`）：边缘异常、台阶能量、几何粗糙、散点、空洞；
- **差异域内**（`soft_alpha_ab` 同法）：只在两档真的不同的像素上比台阶能量，
  避免被没动过的九成画面稀释；
- **整圈最坏角**：每项取 24 个角度里最差的那个，不看均值——用户看到的是最坏那一帧；
- **新增条纹判据**：沿一个方向的长程自相关。相干传输失手时会拖出条纹，
  均值类指标对它失明（第一版 μ 固定就在人脸上拖出过水平条纹，靠目检才发现）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from render_defects import geom_roughness, ring, second_layer_mask, speckle, step_energy

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]
TINT_DEGS = [0, 30, 60, 90, 120, 150, 180, 208, 240, 270, 300, 328]


def streak_score(rgb, sel, lag=8):
    """条纹判据：在 `sel` 内，水平/垂直方向 lag 像素的自相关取大者。

    拖影是"沿一个方向被抹开"，其表现就是该方向上远距离仍高度相关，而正交方向不。
    取两个方向的**最大值**，纹理正常的区域两个方向都不会特别高。
    """
    g = cv2.cvtColor(rgb.astype(np.float32), cv2.COLOR_RGB2GRAY)
    hi = g - cv2.GaussianBlur(g, (0, 0), 2.0)
    out = []
    for ax in (0, 1):
        a = hi if ax == 0 else hi.T
        s = sel if ax == 0 else sel.T
        m = s[:, :-lag] & s[:, lag:]
        if m.sum() < 256:
            out.append(np.nan)
            continue
        x, y = a[:, :-lag][m], a[:, lag:][m]
        d = np.sqrt((x * x).mean() * (y * y).mean())
        out.append(float((x * y).mean() / max(d, 1e-6)))
    return float(np.nanmax(out)) if np.isfinite(out).any() else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/qa"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/matte-soft-probe"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tags", nargs=2, default=["mix", "str"])
    args = ap.parse_args()

    print(f"{'场景':<20}{'档':>5} | {'边缘异常':>8} {'台阶(均/最坏)':>14} {'散点%':>7} "
          f"{'空洞%':>7} {'条纹(均/最坏)':>14} | {'差异域台阶 降幅%':>16}")
    summary = {}
    for scene in args.scenes:
        sc = scene[:2]
        per = {}
        for tag in args.tags:
            fr, st, spk, hl, strk = [], [], [], [], []
            for d in TINT_DEGS:
                p, pt = args.dir / f"{sc}{tag}-{d}.png", args.dir / f"{sc}{tag}-tint-{d}.png"
                if not (p.is_file() and pt.is_file()):
                    continue
                im = np.asarray(Image.open(p).convert("RGBA"))
                rgb = im[..., :3].astype(np.float32)
                hl.append(float((im[..., 3] < 8).mean()))
                hid = second_layer_mask(np.asarray(Image.open(pt).convert("RGB")))
                inner, outer = ring(hid, 1, 4), ring(hid, 6, 13)
                g = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
                if inner.sum() > 64 and outer.sum() > 64:
                    fr.append(float(np.median(g[inner]) - np.median(g[outer])))
                st.append(step_energy(rgb, hid))
                spk.append(speckle(rgb, hid))
                strk.append(streak_score(rgb, hid))
            if not st:
                continue
            f = lambda a: float(np.nanmean(a)) if a else float("nan")
            worst = lambda a: float(np.nanmax(a)) if a else float("nan")
            per[tag] = {"fringe": f(fr), "step": f(st), "stepWorst": worst(st),
                        "speckle": 100 * f(spk), "holes": 100 * f(hl),
                        "streak": f(strk), "streakWorst": worst(strk)}
        if len(per) < 2:
            continue
        # 差异域内的台阶能量：只在两档真的不同的像素上比
        drops = []
        for d in TINT_DEGS:
            pa, pb = args.dir / f"{sc}{args.tags[0]}-{d}.png", args.dir / f"{sc}{args.tags[1]}-{d}.png"
            pt = args.dir / f"{sc}{args.tags[0]}-tint-{d}.png"
            if not (pa.is_file() and pb.is_file() and pt.is_file()):
                continue
            a = np.asarray(Image.open(pa).convert("RGB")).astype(np.float32)
            b = np.asarray(Image.open(pb).convert("RGB")).astype(np.float32)
            ch = np.abs(a - b).max(2) > 2.0
            if ch.sum() < 256:
                continue
            ch = cv2.dilate(ch.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
            hid = second_layer_mask(np.asarray(Image.open(pt).convert("RGB")))
            sel = ring(hid, 0, 3) & ch
            if sel.sum() < 64:
                continue
            lap = lambda im: float(np.sqrt((cv2.Laplacian(
                cv2.cvtColor(im, cv2.COLOR_RGB2GRAY), cv2.CV_32F, ksize=3)[sel] ** 2).mean()))
            la, lb = lap(a), lap(b)
            drops.append(100 * (la - lb) / max(la, 1e-6))
        dm = float(np.nanmean(drops)) if drops else float("nan")
        for tag in args.tags:
            v = per[tag]
            print(f"{scene if tag==args.tags[0] else '':<20}{tag:>5} | {v['fringe']:>+8.2f} "
                  f"{v['step']:>6.3f}/{v['stepWorst']:<7.3f} {v['speckle']:>7.3f} "
                  f"{v['holes']:>7.3f} {v['streak']:>6.3f}/{v['streakWorst']:<7.3f} | "
                  + (f"{dm:>16.1f}" if tag == args.tags[1] else " " * 16))
        summary[scene] = {"per": per, "diffDomainStepDropPct": dm}
    (args.out / "qa_str_sweep.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
