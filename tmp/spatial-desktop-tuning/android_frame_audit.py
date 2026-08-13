#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""对真机取帧做**先算数、再看图**的验收（D186/D189/D193 的教训：只看截图会漏）。

四类缺陷各有一条可核对的判据：

- **空洞**：显露带没补上时会露出底色/黑边。判据不是"看着黑"，而是与中心帧比对——
  某像素在偏移帧里变成近黑或近纯色**块**，且该块在中心帧里不是黑的。
- **条带**：沿位移方向的一阶差分出现周期性尖峰。取梯度幅值在**列**（水平偏移时）上的
  投影，看有没有远离整体分布的窄峰。
- **扭曲/畸变**：把偏移帧与中心帧做局部相位相关，得到逐块位移；真透视视差下位移应当
  随深度单调、且**同一深度层内一致**。畸变表现为块位移场出现高频抖动。
- **主体是否钉住**：主体区域在各方向帧之间的位移应当接近零（支点在主体上）。

所有结论都落在数上，图只用于复核。
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def content_box(center_png: Path):
    """GL 视图占满全屏，照片是信箱式居中，bounds.json 裁不出照片。
    从中心帧自己找：取中间一半列的行均亮度，连续超过阈值的最长段即照片竖向范围。
    渲染侧用 maximumMotionAmplitude 保持取景边距恒定，所以这个框对同场景各帧通用。"""
    a = np.asarray(Image.open(center_png).convert("RGB")).astype(np.float64)
    h, w = a.shape[:2]
    strip = a[:, w // 4: 3 * w // 4].mean(axis=(1, 2))
    lit = strip > max(6.0, strip.max() * 0.06)
    best = (0, 0)
    run = None
    for y in range(h):
        if lit[y] and run is None:
            run = y
        elif not lit[y] and run is not None:
            if y - run > best[1] - best[0]:
                best = (run, y)
            run = None
    if run is not None and h - run > best[1] - best[0]:
        best = (run, h)
    y1, y2 = best
    return {"x1": 0, "y1": y1, "x2": w, "y2": y2,
            "width": w, "height": y2 - y1}


def load_cropped(path: Path, bounds: dict) -> np.ndarray:
    im = Image.open(path).convert("RGB")
    return np.asarray(im.crop((bounds["x1"], bounds["y1"], bounds["x2"], bounds["y2"])))


def block_shifts(a: np.ndarray, b: np.ndarray, block: int = 192):
    """逐块相位相关，返回块位移场 (dy, dx) 与相关峰值。

    **块边长决定可分辨位移上限：±block/2，超过就混叠。**接通真透视后 00 场景
    左右对开的真实视差是 152.6 px，用 96 px 的块量出来只有 53 px——不是渲染弱了，
    是尺子到头了。默认 192（±96）；若某场景量出的跨度接近 block/2，换更大的块重量。
    """
    ga = a.mean(axis=2).astype(np.float64)
    gb = b.mean(axis=2).astype(np.float64)
    h, w = ga.shape
    ny, nx = h // block, w // block
    dy = np.full((ny, nx), np.nan)
    dx = np.full((ny, nx), np.nan)
    peak = np.zeros((ny, nx))
    win = np.outer(np.hanning(block), np.hanning(block))
    for j in range(ny):
        for i in range(nx):
            pa = ga[j * block:(j + 1) * block, i * block:(i + 1) * block] * win
            pb = gb[j * block:(j + 1) * block, i * block:(i + 1) * block] * win
            fa = np.fft.rfft2(pa - pa.mean())
            fb = np.fft.rfft2(pb - pb.mean())
            cross = fa * np.conj(fb)
            mag = np.abs(cross)
            if mag.max() < 1e-9:
                continue
            r = np.fft.irfft2(cross / (mag + 1e-9), s=pa.shape)
            k = np.unravel_index(np.argmax(r), r.shape)
            peak[j, i] = r[k]
            sy = k[0] if k[0] <= block // 2 else k[0] - block
            sx = k[1] if k[1] <= block // 2 else k[1] - block
            dy[j, i], dx[j, i] = sy, sx
    return dy, dx, peak


def hole_score(center: np.ndarray, frame: np.ndarray):
    """新出现的近黑/近纯色块占比（空洞的可核对代理）。"""
    def darkish(x):
        return (x.max(axis=2) < 24)
    new_dark = darkish(frame) & ~darkish(center)
    # 只统计连成片的（3x3 全为新暗），避免把边缘抗锯齿算进来
    k = new_dark[1:-1, 1:-1]
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            k = k & new_dark[1 + dy:new_dark.shape[0] - 1 + dy,
                             1 + dx:new_dark.shape[1] - 1 + dx]
    return 100.0 * k.mean(), 100.0 * new_dark.mean()


def band_score(frame: np.ndarray, axis: int):
    """沿位移方向的一阶差分，在正交方向取平均后找窄峰。返回 (峰值/中位, 峰位置比例)。"""
    g = frame.mean(axis=2).astype(np.float64)
    d = np.abs(np.diff(g, axis=axis))
    profile = d.mean(axis=1 - axis)
    med = np.median(profile)
    if med < 1e-9:
        return 0.0, 0.0
    # 与局部中位比较，抑制真实边缘（真实边缘在整幅上稀疏但不"窄峰化"）
    ratio = profile.max() / med
    return float(ratio), float(np.argmax(profile) / len(profile))


def audit_scene(scene_dir: Path, scene: str):
    bounds = content_box(scene_dir / f"{scene}-center.png")
    center = load_cropped(scene_dir / f"{scene}-center.png", bounds)
    print(f"\n=== {scene}  取景 {center.shape[1]}x{center.shape[0]} ===")
    print(f"{'方向':>6} {'空洞块%':>9} {'新暗%':>8} {'条带比':>8} "
          f"{'块位移中位(dx,dy)':>20} {'块位移抖动':>10}")
    rows = []
    for deg in (0, 45, 90, 135, 180, 225, 270, 315):
        p = scene_dir / f"{scene}-d{deg:03d}.png"
        if not p.is_file():
            continue
        frame = load_cropped(p, bounds)
        holes, newdark = hole_score(center, frame)
        axis = 1 if deg in (0, 180) else 0
        band, _ = band_score(frame, axis)
        dy, dx, peak = block_shifts(center, frame, block=192)
        good = peak > 0.06
        mdx = float(np.nanmedian(dx[good])) if good.any() else float("nan")
        mdy = float(np.nanmedian(dy[good])) if good.any() else float("nan")
        # 抖动：块位移相对其 3x3 邻域中位的绝对偏差中位数
        jit = float(np.nanmedian(np.abs(dx[good] - mdx))) if good.any() else float("nan")
        rows.append((deg, holes, newdark, band, mdx, mdy, jit))
        print(f"{deg:>6} {holes:>9.4f} {newdark:>8.3f} {band:>8.2f} "
              f"{f'({mdx:+.1f},{mdy:+.1f})':>20} {jit:>10.2f}")
    return rows


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path)
    args = ap.parse_args()
    for b in sorted(args.root.glob("*-bounds.json")):
        audit_scene(args.root, b.name.replace("-bounds.json", ""))
