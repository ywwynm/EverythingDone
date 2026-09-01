# -*- coding: utf-8 -*-
"""前沿是「一条推进的锋面」还是「各处同时成核」？

已侵蚀区 {T <= t} 的拓扑直接区分两者：
- 锋面推进：连通块数接近 1，最大块占绝大部分面积，且它贴着卡片边界。
- 各处成核：连通块数很多，最大块占比低，出现不贴边界的孤岛。

加性噪声叠在方向斜坡上会在场里造出孤立的低值坑，必然产生成核；
而把噪声用作坐标形变（domain warping）则只会把等值线弯曲，不会破坏连通性。
"""
import os, math, sys
import numpy as np
import cv2

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
sys.path.insert(0, r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning")


def topology(T, card_w, label):
    m = np.isfinite(T)
    fill = np.where(m, T, 9.0)
    # 在 cell 尺度上做形态学闭运算，避免逐格抖动把连通块打碎——
    # 我们要判的是宏观拓扑，不是单个格子的通断。
    k = max(3, int(round(card_w * 0.012)) | 1)
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    use_close = not os.environ.get("NO_CLOSE")
    H, W = T.shape
    print("  %s   已侵蚀比例 / 连通块数 / 最大块占比 / 不贴边界的孤岛面积占比" % label)
    for frac in (0.03, 0.06, 0.10, 0.16, 0.24):
        lv = float(np.nanquantile(T[m], frac))
        raw = (fill <= lv).astype(np.uint8)
        inside = cv2.morphologyEx(raw, cv2.MORPH_CLOSE, ker) if use_close else raw
        n, lab, st, _ = cv2.connectedComponentsWithStats(inside, 8)
        if n <= 1:
            continue
        areas = st[1:, 4].astype(np.float64)
        total = areas.sum()
        # 只统计有实际面积的块，去掉一两个像素的碎屑
        keep = areas > (card_w * 0.02) ** 2
        areas_k = areas[keep]
        ids = np.flatnonzero(keep) + 1
        touch = 0.0
        for i in ids:
            ys, xs = np.where(lab == i)
            if (xs.min() <= 1 or ys.min() <= 1 or xs.max() >= W - 2 or ys.max() >= H - 2):
                continue
            touch += st[i, 4]
        cy_, cx_ = np.nonzero(inside)
        spread = 0.0
        if len(cx_) > 10:
            spread = float(np.hypot(cx_.std(), cy_.std())) / card_w
        print("    t@%.2f  %.3f   %3d   %.3f   %.3f   空间散布=%.3f"
              % (frac, total / (H * W), len(areas_k),
                 areas_k.max() / max(total, 1.0), touch / max(total, 1.0), spread))


def reference():
    T = np.load(os.path.join(SP, "release2.npy"))
    print("参考（卡 480x472）")
    topology(T, 480.0, "参考")


def model():
    import render_curtain_model as R
    r = R.CurtainRenderer()
    r.configure(R.Scenario("left-up", 242.0, 42))
    pure, T, raw = r.release_probe()
    print("模型（卡 720x420）")
    topology(T, 720.0, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
