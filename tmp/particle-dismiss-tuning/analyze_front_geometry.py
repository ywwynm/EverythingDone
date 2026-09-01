# -*- coding: utf-8 -*-
"""前沿的两项几何：独立的粒子化区域有几个，边界上有多少尖锐特征。

判据必须排除「释放前的整体压暗」：压暗最深到 0.70，白卡上的绝对色差能超过 70，
用绝对阈值会把压暗当成已侵蚀。这里一律用相对判据。

1. 独立区域：已侵蚀区里面积 >= 1% 卡面的连通块个数，以及最大块之外的面积占比。
2. 边界尖锐度：完整区外轮廓上，曲率半径小于 0.03 卡宽的点占的比例，
   以及曲率半径的中位数。
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
STEPS = (0.15, 0.25, 0.35, 0.45)


def eroded_mask(sub, src, card_w):
    lum0 = src.mean(2)
    lum = sub.mean(2)
    er = ((lum < 0.55 * np.maximum(lum0, 1.0)) | (np.abs(sub - src).max(2) > 110))
    k = max(3, int(round(card_w * 0.02)) | 1)
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    er = cv2.morphologyEx(er.astype(np.uint8), cv2.MORPH_CLOSE, ker)
    return cv2.morphologyEx(er, cv2.MORPH_OPEN, ker)


def geometry(frames, card, card_w, label):
    x0, y0, x1, y1 = card
    src = frames[0][1][y0:y1, x0:x1]
    area = (x1 - x0) * (y1 - y0)
    print("  %s   已侵蚀 / 独立块数 / 主体外面积占比 / 边界尖点占比 / 曲率半径中位(卡宽)"
          % label)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        er = eroded_mask(a[y0:y1, x0:x1], src, card_w)
        cnt, lab, st, _ = cv2.connectedComponentsWithStats(er, 8)
        if cnt <= 1:
            continue
        areas = st[1:, 4].astype(np.float64)
        big = areas[areas > 0.01 * area]
        if len(big) == 0:
            continue
        outside = (big.sum() - big.max()) / max(big.sum(), 1.0)

        intact = (1 - er).astype(np.uint8)
        cs, _ = cv2.findContours(intact, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cs = [c for c in cs if cv2.contourArea(c) > 0.02 * area]
        if not cs:
            continue
        p = max(cs, key=cv2.contourArea)[:, 0, :].astype(np.float64)
        step = max(2, int(round(card_w * 0.008)))
        p = p[::step]
        if len(p) < 30:
            continue
        A, B, C = p, np.roll(p, -2, 0), np.roll(p, -4, 0)
        ab = np.linalg.norm(B - A, axis=1)
        bc = np.linalg.norm(C - B, axis=1)
        ca = np.linalg.norm(A - C, axis=1)
        cross = np.abs((B[:, 0] - A[:, 0]) * (C[:, 1] - A[:, 1])
                       - (C[:, 0] - A[:, 0]) * (B[:, 1] - A[:, 1]))
        radius = (ab * bc * ca) / np.maximum(2.0 * cross, 1e-6) / card_w
        radius = radius[np.isfinite(radius)]
        if len(radius) < 20:
            continue
        print("    n=%.2f   %.3f   %2d   %.3f   %.3f   %.3f"
              % (n, er.mean(), len(big), outside,
                 float((radius < 0.03).mean()), float(np.median(radius))))


def reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    frames = [((t - T0) / (T1 - T0),
               np.asarray(Image.open(f).convert("RGB"), np.float32))
              for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
    geometry(frames, (45, 95, 525, 567), 480.0, "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    frames = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32))
              for i, f in enumerate(fs)]
    geometry(frames, (280, 240, 1000, 660), 720.0, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
