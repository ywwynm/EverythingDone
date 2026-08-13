#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 对照用的**带真值语料**：把真带按块搬到纯背景上，那里的原始像素就是真值。

沿用 D160 `band_gt_eval.build_test_mask` 的思想（局部形态原样保留、只换位置），
但换掉它的"纯背景"判据：

- D160 用的是 `深度 ≥ p55 且不在带的膨胀区内`。**深度分位不是"是不是前景"的判据**——
  这正是 D180/D188 反复栽的那个坑。实测九场景可用背景块少到不能用：08 场景只有 1 块
  48px，07 场景 4 块，据此报出的每场景数字没有意义。
- 这里改用 `occluders.png ∪ matte_soft`（`band_source.occluder_mask`，即分割出来的
  遮挡物本身）加带的膨胀区取反。同样九场景，可用块变成 14–167 个。

目标块按不重叠贪心铺，**优先挑与已选块相邻的**，让搬过去的带连成片而不是散成孤块——
孤块会让裁窗生成器给出与真带完全不同的窗几何（每块一个小窗、一律顶格放大），
台子上量到的东西就不再代表真带。
"""
from __future__ import annotations

import cv2
import numpy as np


def safe_background(band: np.ndarray, occ: np.ndarray, guard: int = 6) -> np.ndarray:
    k = 2 * guard + 1
    return ~(cv2.dilate((band | occ).astype(np.uint8), np.ones((k, k), np.uint8)) > 0)


def build_gt_mask(band: np.ndarray, occ: np.ndarray, *, block: int = 48,
                  seed: int = 20260812, cov_lo: float = 0.05, cov_hi: float = 0.60,
                  verbose: bool = True) -> tuple[np.ndarray, dict]:
    h, w = band.shape
    safe = safe_background(band, occ)
    rng = np.random.default_rng(seed)

    integ = cv2.integral(safe.astype(np.uint8))
    cand = []
    for y in range(0, h - block + 1, block):
        for x in range(0, w - block + 1, block):
            s = integ[y + block, x + block] - integ[y, x + block] \
                - integ[y + block, x] + integ[y, x]
            if s >= 0.995 * block * block:
                cand.append((y, x))
    if not cand:
        return np.zeros_like(band), {"targets": 0, "sources": 0}

    # 贪心：从候选里先取一个，之后每次优先取与已选集合相邻的（4 邻域块），
    # 让搬过去的带连成片。
    cand_set = set(cand)
    order, frontier = [], [cand[0]]
    seen = {cand[0]}
    while frontier:
        y, x = frontier.pop(0)
        order.append((y, x))
        for dy, dx in ((0, block), (0, -block), (block, 0), (-block, 0)):
            n = (y + dy, x + dx)
            if n in cand_set and n not in seen:
                seen.add(n)
                frontier.append(n)
        if not frontier:
            rest = [c for c in cand if c not in seen]
            if rest:
                seen.add(rest[0])
                frontier.append(rest[0])

    src = []
    for y in range(0, h - block + 1, block // 2):
        for x in range(0, w - block + 1, block // 2):
            c = band[y:y + block, x:x + block].mean()
            if cov_lo <= c <= cov_hi:
                src.append((y, x))
    rng.shuffle(src)
    if not src:
        return np.zeros_like(band), {"targets": len(order), "sources": 0}

    out = np.zeros_like(band)
    for i, (ty, tx) in enumerate(order):
        sy, sx = src[i % len(src)]
        out[ty:ty + block, tx:tx + block] = band[sy:sy + block, sx:sx + block]
    out &= safe
    info = {"targets": len(order), "sources": len(src),
            "maskPx": int(out.sum()), "maskFrac": float(out.mean())}
    if verbose:
        print(f"    真值掩膜：目标块 {len(order)}（{block}px，连片优先），源块池 {len(src)}，"
              f"掩膜 {int(out.sum())} px（{100*out.mean():.2f}%）")
    return out, info
