# -*- coding: utf-8 -*-
"""有没有「前沿还没到、却已经开始粒子化」的孤立区域？

用实帧量，不用释放场——用户看到的是画面。

每一时刻：
1. 已侵蚀区 = 卡片内不再与首帧相同的像素（在 cell 尺度上闭运算，去掉逐格抖动的碎屑）
2. 前沿主体 = 最大连通块
3. 其余每个块，量它到主体的最短距离（卡宽归一）
4. 报「离主体超过阈值的侵蚀面积占比」与「最远的那个块的距离」

前沿式推进：孤立面积占比接近 0，最远距离很小。
超前成核：占比明显为正，且能出现远在前沿之前的块。
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
STEPS = (0.15, 0.22, 0.30, 0.38, 0.46)


def detached(frames, card, card_w, label):
    x0, y0, x1, y1 = card
    src = frames[0][1][y0:y1, x0:x1]
    k = max(3, int(round(card_w * 0.018)) | 1)
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    far = card_w * 0.06
    print("  %s   已侵蚀 / 块数 / 主体占比 / 远离主体的面积占比 / 最远距离(卡宽)" % label)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        sub = a[y0:y1, x0:x1]
        er = (np.abs(sub - src).max(2) > 22).astype(np.uint8)
        er = cv2.morphologyEx(er, cv2.MORPH_CLOSE, ker)
        er = cv2.morphologyEx(er, cv2.MORPH_OPEN,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
        cnt, lab, st, _ = cv2.connectedComponentsWithStats(er, 8)
        if cnt <= 1:
            continue
        areas = st[1:, 4].astype(np.float64)
        keep = areas > (card_w * 0.025) ** 2
        if not keep.any():
            continue
        ids = np.flatnonzero(keep) + 1
        areas_k = areas[keep]
        main = ids[int(np.argmax(areas_k))]
        # 到主体的距离场
        dist = cv2.distanceTransform((lab != main).astype(np.uint8), cv2.DIST_L2, 5)
        total = areas_k.sum()
        far_area = 0.0
        worst = 0.0
        for j in ids:
            if j == main:
                continue
            d = float(dist[lab == j].min())
            worst = max(worst, d)
            if d > far:
                far_area += st[j, 4]
        print("    n=%.2f   %.3f   %3d   %.3f   %.4f   %.3f"
              % (n, float(er.mean()), len(areas_k), areas_k.max() / total,
                 far_area / max(total, 1.0), worst / card_w))


def reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    frames = []
    for f, t in zip(fs, ts):
        if t < T0 - 0.02 or t > T1:
            continue
        frames.append(((t - T0) / (T1 - T0),
                       np.asarray(Image.open(f).convert("RGB"), np.float32)))
    detached(frames, (45, 95, 525, 567), 480.0, "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    frames = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32))
              for i, f in enumerate(fs)]
    detached(frames, (280, 240, 1000, 660), 720.0, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
