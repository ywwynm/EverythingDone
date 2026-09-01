# -*- coding: utf-8 -*-
"""粒子云是「整体朝一个方向飞」还是「从中心膨胀」，以及云有没有清晰的前沿。

A. 膨胀与平移
   把粒子像素投影到飞行轴上，逐帧取质心与两个方向的标准差。
   - 质心沿轴位移速率 = 平移
   - 横向标准差的增长速率 = 膨胀
   膨胀/平移之比越大，看上去越像「从中心炸开」而不是「被风吹走」。

B. 前沿
   对粒子密度取 0.5 等值线作为云的轮廓：
   - 前沿锐度：沿轮廓法向，密度从 50% 落到 10% 需要多远（卡宽千分比）
   - 轮廓曲率半径中位数：大 = 大弧度的光滑曲线，小 = 碎边
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
STEPS = (0.25, 0.35, 0.45, 0.55, 0.65)


def moments(mask, direction, card_w, cx, cy):
    ys, xs = np.nonzero(mask)
    if len(xs) < 2000:
        return None
    dx = (xs - cx) / card_w
    dy = (ys - cy) / card_w
    along = dx * direction[0] + dy * direction[1]
    across = -dx * direction[1] + dy * direction[0]
    return float(along.mean()), float(along.std()), float(across.std()), len(xs)


def front(mask, card_w, label, n):
    box = max(5, int(round(card_w * 0.035)) | 1)
    dens = cv2.blur(mask.astype(np.float32), (box, box))
    peak = float(np.percentile(dens[dens > 0.02], 90)) if (dens > 0.02).sum() else 0.0
    if peak <= 0.02:
        return None
    inside = dens >= 0.5 * peak
    if inside.sum() < 2000 or (~inside).sum() < 2000:
        return None
    din = cv2.distanceTransform(inside.astype(np.uint8), cv2.DIST_L2, 5)
    dout = cv2.distanceTransform((~inside).astype(np.uint8), cv2.DIST_L2, 5)
    sd = din - dout
    edges = np.arange(-box * 2, box * 2 + 1, 1.0)
    prof, ctr = [], []
    for k in range(len(edges) - 1):
        m = (sd >= edges[k]) & (sd < edges[k + 1])
        if m.sum() > 80:
            prof.append(float(dens[m].mean()) / peak)
            ctr.append(0.5 * (edges[k] + edges[k + 1]))
    if len(prof) < 6:
        return None
    prof = np.array(prof)
    ctr = np.array(ctr)
    order = np.argsort(prof)
    d50 = np.interp(0.5, prof[order], ctr[order])
    d10 = np.interp(0.1, prof[order], ctr[order])
    sharp = (d50 - d10) / card_w * 1000.0

    cnts, _ = cv2.findContours(inside.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cnts = [c for c in cnts if cv2.contourArea(c) > card_w * card_w * 0.01]
    if not cnts:
        return None
    big = max(cnts, key=cv2.contourArea)[:, 0, :].astype(np.float64)
    step = max(4, int(round(card_w * 0.020)))
    p = big[:: max(1, step // 3)]
    if len(p) < 24:
        return None
    # 三点定圆求曲率半径，跨度取 ~2% 卡宽以避开像素级锯齿
    k = max(3, int(round(len(p) * 0.04)))
    a = p
    b = np.roll(p, -k, 0)
    c = np.roll(p, -2 * k, 0)
    ab = np.linalg.norm(b - a, axis=1)
    bc = np.linalg.norm(c - b, axis=1)
    ca = np.linalg.norm(a - c, axis=1)
    area = np.abs((b[:, 0] - a[:, 0]) * (c[:, 1] - a[:, 1])
                  - (c[:, 0] - a[:, 0]) * (b[:, 1] - a[:, 1])) * 0.5
    radius = (ab * bc * ca) / np.maximum(4.0 * area, 1e-6)
    radius = radius[np.isfinite(radius)]
    med = float(np.median(radius)) / card_w * 1000.0
    return sharp, med, float(inside.sum()) / (card_w * card_w)


def run(frames, card, direction, card_w, label):
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    card_mask = np.zeros(src.shape[:2], bool)
    card_mask[y0:y1, x0:x1] = True
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    print("%s（卡宽 %d）" % (label, card_w))
    rows = []
    for target in STEPS:
        k = min(range(len(frames)), key=lambda i: abs(frames[i][0] - target))
        n, a = frames[k]
        mask = (~((np.abs(a - src).max(2) <= 22) & card_mask)) & (np.abs(a - bg).max(2) > 26)
        mo = moments(mask, direction, card_w, cx, cy)
        fr = front(mask, card_w, label, n)
        if mo is None:
            continue
        along_c, along_sd, across_sd, cnt = mo
        rows.append((n, along_c, along_sd, across_sd))
        if fr is None:
            print("  n=%.2f  质心沿轴=%+.3f  沿轴sd=%.3f  横向sd=%.3f  （无可测前沿）"
                  % (n, along_c, along_sd, across_sd))
        else:
            print("  n=%.2f  质心沿轴=%+.3f  沿轴sd=%.3f  横向sd=%.3f  "
                  "前沿锐度=%4.1f  轮廓曲率半径中位=%5.1f  云面积=%.3f"
                  % (n, along_c, along_sd, across_sd, fr[0], fr[1], fr[2]))
    if len(rows) >= 3:
        ns = np.array([r[0] for r in rows])
        trans = np.polyfit(ns, [r[1] for r in rows], 1)[0]
        grow = np.polyfit(ns, [r[3] for r in rows], 1)[0]
        print("  平移速率=%.3f 卡宽/单位时间   横向膨胀速率=%.3f   膨胀/平移=%.2f"
              % (trans, grow, grow / max(abs(trans), 1e-6)))


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
    a = math.radians(118.0)
    run(frames, (45, 95, 525, 567), (math.cos(a), -math.sin(a)), 480, "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    frames = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32))
              for i, f in enumerate(fs)]
    b = math.radians(242.0)
    run(frames, (280, 240, 1000, 660), (math.cos(b), math.sin(b)), 720, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
