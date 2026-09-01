# -*- coding: utf-8 -*-
"""三组量，对应用户 2026-08-31 提的第 1、3、4 条。

1. 粒子化之前是否先变暗
   逐像素求「硬释放时刻」（与源色差 > 60 的首帧），再回看它之前若干个归一化
   时间步的亮度比 lum(t)/lum(src)。只统计源亮度足够高的像素，避免比值失稳。

3. 粒子越界的时序
   分四侧统计越出原控件矩形的粒子占全部粒子的比例，随时间给出曲线。

4. 粒子的数量与大小
   在稀疏区（局部密度低）取连通块作为单颗粒子，给出等效直径的分布，
   以及每卡片面积上的粒子数。
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"


# ---------------------------------------------------------------- 1
def predarken(frames, card, label):
    x0, y0, x1, y1 = card
    src = frames[0][1][y0:y1, x0:x1]
    lum_src = src.mean(2)
    bright = lum_src > 60.0
    H, W = lum_src.shape
    hard = np.full((H, W), np.nan, np.float32)
    stack = []
    for n, a in frames:
        sub = a[y0:y1, x0:x1]
        d = np.abs(sub - src).max(2)
        # 硬释放必须用相对判据：整片预变暗本身就能让白色像素的绝对色差超过
        # 60，被当成已释放后，回看的对齐就变成了循环论证（2026-08-31 实际
        # 发生，导致"模型没有预变暗"的错误结论）。
        new = np.isnan(hard) & ((sub.mean(2) < 0.55 * lum_src) | (d > 110.0))
        hard[new] = n
        stack.append((n, sub.mean(2)))
    print("%s 粒子化之前的亮度比（1.0 = 未变暗；取中位数）" % label)
    out = []
    for lead in (0.20, 0.15, 0.10, 0.06, 0.03, 0.015):
        vals = []
        for n, lum in stack:
            target = n + lead
            sel = bright & np.isfinite(hard) & (np.abs(hard - target) < 0.02)
            if sel.sum() < 400:
                continue
            vals.append(float(np.median(lum[sel] / np.maximum(lum_src[sel], 1.0))))
        if vals:
            out.append((lead, float(np.mean(vals)), len(vals)))
    for lead, ratio, cnt in out:
        print("    释放前 %.3f：亮度比 %.3f （%d 帧参与）" % (lead, ratio, cnt))
    return out


# ---------------------------------------------------------------- 3
def escape(frames, card, label):
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    card_mask = np.zeros(src.shape[:2], bool)
    card_mask[y0:y1, x0:x1] = True
    print("%s 越界粒子占比（右 / 下 / 上 / 左）" % label)
    for target in (0.20, 0.30, 0.40, 0.50, 0.60, 0.75):
        k = min(range(len(frames)), key=lambda i: abs(frames[i][0] - target))
        n, a = frames[k]
        mask = (~((np.abs(a - src).max(2) <= 22) & card_mask)) & (np.abs(a - bg).max(2) > 26)
        total = int(mask.sum())
        if total < 2000:
            continue
        ys, xs = np.nonzero(mask)
        right = float((xs >= x1).mean())
        below = float((ys >= y1).mean())
        above = float((ys < y0).mean())
        left = float((xs < x0).mean())
        print("    n=%.2f  %6.3f %6.3f %6.3f %6.3f" % (n, right, below, above, left))


# ---------------------------------------------------------------- 4
def grain(frames, card, card_w, label):
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    card_mask = np.zeros(src.shape[:2], bool)
    card_mask[y0:y1, x0:x1] = True
    print("%s 稀疏区里的单颗粒子（等效直径按卡宽千分比）" % label)
    for target in (0.35, 0.50, 0.65):
        k = min(range(len(frames)), key=lambda i: abs(frames[i][0] - target))
        n, a = frames[k]
        mask = ((~((np.abs(a - src).max(2) <= 22) & card_mask))
                & (np.abs(a - bg).max(2) > 26))
        box = max(9, int(round(card_w * 0.05)) | 1)
        dens = cv2.blur(mask.astype(np.float32), (box, box))
        sparse = mask & (dens > 0.01) & (dens < 0.12)
        if sparse.sum() < 800:
            continue
        nl, lab, st, _ = cv2.connectedComponentsWithStats(sparse.astype(np.uint8), 8)
        areas = st[1:, 4].astype(np.float64)
        areas = areas[areas <= (card_w * 0.03) ** 2]
        if len(areas) < 200:
            continue
        diam = 2.0 * np.sqrt(areas / math.pi) / card_w * 1000.0
        # 稀疏区的粒子面数密度：每 (卡宽/10)^2 里几颗
        cellcount = len(areas) / max(sparse.sum() / (dens[sparse].mean() + 1e-9), 1.0)
        print("    n=%.2f  颗数=%5d  直径 中位=%.2f  P10=%.2f  P90=%.2f  "
              "P90/P10=%.2f  覆盖率=%.4f"
              % (n, len(areas), float(np.median(diam)), float(np.percentile(diam, 10)),
                 float(np.percentile(diam, 90)),
                 float(np.percentile(diam, 90) / max(np.percentile(diam, 10), 1e-6)),
                 float(mask.sum()) / (card_w * card_w)))


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
    frames.append((1.0, np.asarray(Image.open(os.path.join(SP, "bg.png")).convert("RGB"), np.float32)))
    card = (45, 95, 525, 567)
    predarken(frames, card, "参考")
    escape(frames, card, "参考")
    grain(frames, card, 480, "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    frames = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32))
              for i, f in enumerate(fs)]
    card = (280, 240, 1000, 660)
    predarken(frames, card, "模型")
    escape(frames, card, "模型")
    grain(frames, card, 720, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
