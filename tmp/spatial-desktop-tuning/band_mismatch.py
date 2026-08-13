#!/usr/bin/env python
"""量化显露带每类内容源与相邻真实背景的失配。

只用于**定位**，不用于验收——亮度/梯度指标验收不了结构连续性（2026-08-10 教训）。
输入是查看器 uAblate==10 的来源标记图（R=SDXL 生成、G=前景、B=OVIE）与同角度成品帧。

报三项：
  level  带内均值 − 相邻真实背景均值（8 位灰阶），即接缝处的亮度台阶；
  hf     带内高频能量 / 相邻真实背景高频能量，<1 表示内容比周围糊；
  chroma 带内与相邻背景的平均 RGB 距离。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def ring(mask: np.ndarray, inner: int, outer: int, exclude: np.ndarray) -> np.ndarray:
    """带外侧 inner..outer 像素的真实背景环，用作该段带的局部参照。"""
    k_in = cv2.dilate(mask.astype(np.uint8), np.ones((inner * 2 + 1,) * 2, np.uint8))
    k_out = cv2.dilate(mask.astype(np.uint8), np.ones((outer * 2 + 1,) * 2, np.uint8))
    return (k_out > 0) & (k_in == 0) & ~exclude


def hf_energy(gray: np.ndarray, mask: np.ndarray) -> float:
    lap = cv2.Laplacian(gray, cv2.CV_32F, ksize=3)
    return float(np.sqrt((lap[mask] ** 2).mean())) if mask.any() else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--band", type=Path, required=True, help="uAblate==10 来源标记图")
    ap.add_argument("--frame", type=Path, required=True, help="同角度成品帧")
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    tag = np.asarray(Image.open(args.band).convert("RGB")).astype(np.float32) / 255.0
    img = np.asarray(Image.open(args.frame).convert("RGB")).astype(np.float32)
    gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)

    sdxl, front, ovie = tag[..., 0] > 0.15, tag[..., 1] > 0.5, tag[..., 2] > 0.15
    unreal = sdxl | ovie
    print(f"=== {args.label or args.frame.name} ===")
    for name, mask in (("SDXL", sdxl), ("OVIE", ovie), ("合计", unreal)):
        if not mask.any():
            print(f"  {name}: 空")
            continue
        ref = ring(mask, 2, 8, front | unreal)
        if not ref.any():
            print(f"  {name}: 无参照环")
            continue
        level = gray[mask].mean() - gray[ref].mean()
        hf_in, hf_ref = hf_energy(gray, mask), hf_energy(gray, ref)
        chroma = float(np.linalg.norm(img[mask].mean(0) - img[ref].mean(0)))
        print(
            f"  {name:5s} px {int(mask.sum()):6d}  level {level:+6.2f}  "
            f"hf {hf_in:5.2f}/{hf_ref:5.2f} = {hf_in / max(hf_ref, 1e-6):.2f}  chroma {chroma:5.2f}"
        )


if __name__ == "__main__":
    main()
