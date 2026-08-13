#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""软 α 的 A/B：只在**它真正动到的像素**上比，不在全画面上比。

`render_defects.py` 的分母是整条第二层边界，而软 α 只作用在 matte 认领的那部分剪影
（00 场景里被否决的带像素比拿到软 α 的还多一倍）。整条边界上取平均，改动会被稀释到
读不出来——这正是"指标可能奖励缺陷"的反面：指标也可能对改进失明。

做法：先求出两张帧的差异域（|off−on| > 2 级，膨胀 2px 连成带），再在这个域内分别
量两张帧的台阶能量与边缘亮度异常。同一批像素、同一条公式，只有 α 的来源不同。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from render_defects import ring, second_layer_mask


def lap_rms(rgb: np.ndarray, sel: np.ndarray) -> float:
    g = cv2.cvtColor(rgb.astype(np.float32), cv2.COLOR_RGB2GRAY)
    lap = cv2.Laplacian(g, cv2.CV_32F, ksize=3)
    return float(np.sqrt((lap[sel] ** 2).mean())) if sel.sum() >= 64 else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/softdef"))
    ap.add_argument("--pairs", nargs="+", required=True, help="off配置:on配置，如 00off:00on")
    ap.add_argument("--degs", nargs="+", type=int, required=True)
    args = ap.parse_args()

    print(f"{'对比':16s} {'差异域px':>9s} {'台阶off':>8s} {'台阶on':>8s} {'降幅%':>7s} "
          f"{'亮差off':>8s} {'亮差on':>8s} {'|亮差|降幅%':>11s}")
    for pair in args.pairs:
        c_off, c_on = pair.split(":")
        n_px, s_off, s_on, f_off, f_on = [], [], [], [], []
        for d in args.degs:
            p0, p1 = args.dir / f"{c_off}-{d}.png", args.dir / f"{c_on}-{d}.png"
            pt = args.dir / f"{c_off}-tint-{d}.png"
            if not (p0.is_file() and p1.is_file() and pt.is_file()):
                continue
            a = np.asarray(Image.open(p0).convert("RGB")).astype(np.float32)
            b = np.asarray(Image.open(p1).convert("RGB")).astype(np.float32)
            changed = np.abs(a - b).max(2) > 2.0
            if changed.sum() < 64:
                continue
            changed = cv2.dilate(changed.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
            hid = second_layer_mask(np.asarray(Image.open(pt).convert("RGB")))
            edge = ring(hid, 0, 3) & changed
            if edge.sum() < 64:
                continue
            n_px.append(int(changed.sum()))
            s_off.append(lap_rms(a, edge)); s_on.append(lap_rms(b, edge))
            # 边缘亮度异常：剪影内侧 1–4px 减更内侧 6–13px，同样只在差异域里取
            inner, outer = ring(hid, 1, 4) & changed, ring(hid, 6, 13) & changed
            if inner.sum() > 64 and outer.sum() > 64:
                ga = cv2.cvtColor(a, cv2.COLOR_RGB2GRAY); gb = cv2.cvtColor(b, cv2.COLOR_RGB2GRAY)
                f_off.append(float(np.median(ga[inner]) - np.median(ga[outer])))
                f_on.append(float(np.median(gb[inner]) - np.median(gb[outer])))
        if not s_off:
            print(f"{pair:16s}  （无可比帧）")
            continue
        m = lambda v: float(np.nanmean(v)) if v else float("nan")
        so, sn = m(s_off), m(s_on)
        fo, fn = m(f_off), m(f_on)
        print(f"{pair:16s} {int(m(n_px)):>9d} {so:>8.3f} {sn:>8.3f} {100*(so-sn)/max(so,1e-9):>7.1f} "
              f"{fo:>+8.2f} {fn:>+8.2f} {100*(abs(fo)-abs(fn))/max(abs(fo),1e-9):>11.1f}")


if __name__ == "__main__":
    main()
