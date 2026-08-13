#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""取块来源集合（工单步骤 0）：**逐带段**的深度窗口 + 前景排除。

为什么必须逐段（2026-08-11 废止实验没过）：整条带的背景层级取 p2–p98 是一个**全局**
窗口，05 场景得到深度 [1.58m, 2.48m]，而蛋糕下层在 1.63m——落在窗口里，可以被当来源
取块，正是 D179 抄前景的复现。而该带有 36 个连通段，各段自己的窗口差别极大
（段 19 [2.19m, 2.56m] vs 段 28 [1.70m, 2.20m]）。这是 D180「被一个全局深度阈值卡死」
在取块这一步的复发。

逐段的两条判据，都是物理量：
1. **背景窗口**：来源逆深度落在该段自己的 `inv_layer` p2–p98 内（外扩一点容差）；
2. **必须比遮挡物更远**：来源逆深度 ≤ 该段遮挡侧的逆深度 − `sep`，`sep` 取与断边判据
   同一个常量（最大基线下错开 1.5px 对应的逆深度差，D154）。这一条与 matte 无关，
   因此不会像"来源 ∩ matte"那样变成循环论证。
3. 再叠 `--exclude-fg` 语义（D166）：遮挡物及其膨胀区一律不得为来源，半径 12
   （实测天花板：24 时 08 场景只剩 54.7% 的带像素还能找到来源）。
"""
from __future__ import annotations

import cv2
import numpy as np


def occluder_mask(assets_dir, w, h):
    """遮挡物集合：SAM3∪matte 的 `occluders.png`，并上软 matte 的核心。"""
    occ = np.zeros((h, w), bool)
    for nm in ("occluders.png", "matte_soft.png"):
        p = assets_dir / nm
        if not p.is_file():
            continue
        m = cv2.imread(str(p), 0)
        if m is None:
            continue
        if (m.shape[1], m.shape[0]) != (w, h):
            m = cv2.resize(m, (w, h), interpolation=cv2.INTER_NEAREST)
        occ |= m > 127
    return occ


def build_source(band, inv, inv_layer, occ, sep, exclude_fg=12,
                 window_pad=0.02, min_seg=64, verbose=True):
    """返回 (source 掩膜, 逐段信息 list)。source 是所有段各自来源的并集。

    注意并集只用于"哪些像素**可能**被当来源"的整体统计；真正取块时必须按段用
    `seg_src[k]`，否则又退回全局窗口。
    """
    h, w = band.shape
    n, lab = cv2.connectedComponents(band.astype(np.uint8))
    ex = occ if exclude_fg <= 0 else (
        cv2.dilate(occ.astype(np.uint8), np.ones((2 * exclude_fg + 1,) * 2, np.uint8)) > 0)
    base = (~band) & (~ex)

    union = np.zeros((h, w), bool)
    segs = []
    for k in range(1, n):
        seg = lab == k
        npx = int(seg.sum())
        if npx < min_seg:
            continue
        lo = float(np.percentile(inv_layer[seg], 2)) - window_pad
        hi = float(np.percentile(inv_layer[seg], 98)) + window_pad
        # 该段遮挡侧的逆深度：段内像素自身（band 圈的就是遮挡侧像素）的中位
        occ_lvl = float(np.median(inv[seg]))
        src = base & (inv >= lo) & (inv <= hi) & (inv <= occ_lvl - sep)
        segs.append({"label": k, "px": npx, "lo": lo, "hi": hi,
                     "occLevel": occ_lvl, "src": src, "mask": seg,
                     "srcPx": int(src.sum())})
        union |= src
    if verbose:
        tot = sum(s["px"] for s in segs)
        starved = sum(1 for s in segs if s["srcPx"] < 256)
        print(f"    逐段来源：{len(segs)} 段（覆盖带的 {100*tot/max(int(band.sum()),1):.1f}%），"
              f"其中来源不足 256px 的 {starved} 段")
    return union, segs
