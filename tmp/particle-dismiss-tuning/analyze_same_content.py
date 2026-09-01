# -*- coding: utf-8 -*-
"""同内容对照：参考 vs「我们的动画 + 参考内容」。

两列的快照、背景、卡片位置完全相同，因此可以用最朴素的判据，不再有此前那些
「压暗被当成粒子」「粒子飞过原卡片区域被当成完整表面」的口径问题：

    粒子 = 与首帧不同 且 与末帧（背景）不同

量两件用户指出的事：

1. 左上缺口的边缘有多柔和——完整表面的局部占比从 0.9 落到 0.1 需要多远。
2. 云的斜向边缘上有没有更亮更密的「帷幔」——沿外轮廓向内取一条带，量带内局部
   密度的 P90/P50，以及高密度块的个数与最大块尺寸。
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
HERE = os.path.dirname(os.path.abspath(__file__))
OURS = os.path.join(HERE, "frames-refcontent")
CARD = (45, 95, 525, 567)
CARD_W = CARD[2] - CARD[0]
STEPS = (0.20, 0.30, 0.40, 0.50)


def particles(a, src, bg):
    return (np.abs(a - src).max(2) > 26) & (np.abs(a - bg).max(2) > 26)


def notch_edge(a, src, label, n):
    """左上象限里，完整表面的边缘有多柔和。"""
    x0, y0, x1, y1 = CARD
    sub = a[y0:y1, x0:x1]
    ref = src[y0:y1, x0:x1]
    intact = (np.abs(sub - ref).max(2) <= 26).astype(np.float32)
    box = max(5, int(round(CARD_W * 0.035)) | 1)
    frac = cv2.blur(intact, (box, box))
    inside = frac >= 0.5
    if inside.sum() < 400 or (~inside).sum() < 400:
        return
    din = cv2.distanceTransform(inside.astype(np.uint8), cv2.DIST_L2, 5)
    dout = cv2.distanceTransform((~inside).astype(np.uint8), cv2.DIST_L2, 5)
    sd = din - dout
    edges = np.arange(-box * 2, box * 2 + 1, 1.0)
    prof, ctr = [], []
    for k in range(len(edges) - 1):
        m = (sd >= edges[k]) & (sd < edges[k + 1])
        if m.sum() > 60:
            prof.append(float(frac[m].mean()))
            ctr.append(0.5 * (edges[k] + edges[k + 1]))
    if len(prof) < 6:
        return
    prof, ctr = np.array(prof), np.array(ctr)
    order = np.argsort(prof)
    width = (np.interp(0.9, prof[order], ctr[order])
             - np.interp(0.1, prof[order], ctr[order])) / CARD_W * 1000.0
    print("    %-4s n=%.2f  全卡侵蚀过渡带宽=%5.1f‰卡宽" % (label, n, width))


def curtain(a, src, bg, label, n):
    """云的边缘带里有没有更亮更密的区域。"""
    part = particles(a, src, bg)
    if part.sum() < 4000:
        return
    box = max(9, int(round(CARD_W * 0.05)) | 1)
    dens = cv2.blur(part.astype(np.float32), (box, box))
    cloud = (dens >= 0.06).astype(np.uint8)
    cloud = cv2.morphologyEx(cloud, cv2.MORPH_OPEN,
                             cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    if cloud.sum() < 4000:
        return
    inner = cv2.erode(cloud, cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE, (int(CARD_W * 0.14) | 1,) * 2))
    band = (cloud > 0) & (inner == 0)          # 外轮廓向内 0.07 卡宽的一条带
    if band.sum() < 2000:
        return
    v = dens[band]
    p50, p90 = np.percentile(v, 50), np.percentile(v, 90)
    hi = ((dens >= p90) & band).astype(np.uint8)
    hi = cv2.morphologyEx(hi, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    cnt, lab, st, _ = cv2.connectedComponentsWithStats(hi, 8)
    areas = st[1:, 4] if cnt > 1 else np.array([0])
    keep = areas[areas > (CARD_W * 0.02) ** 2]
    print("    %-4s n=%.2f  边缘带 P90/P50=%.2f  高密块=%2d  最大块=%.4f卡面"
          % (label, n, p90 / max(p50, 1e-6), len(keep),
             (keep.max() if len(keep) else 0) / float(CARD_W * CARD_W)))


def load_reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    out = [((t - T0) / (T1 - T0), np.asarray(Image.open(f).convert("RGB"), np.float32))
           for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
    bg = np.asarray(Image.open(os.path.join(SP, "bg.png")).convert("RGB"), np.float32)
    return out, out[0][1], bg


def load_ours():
    fs = sorted(glob.glob(os.path.join(OURS, "frame-*.png")))
    n = len(fs) - 1
    out = [(i / n, np.asarray(Image.open(f).convert("RGB"), np.float32))
           for i, f in enumerate(fs)]
    return out, out[0][1], out[-1][1]


for label, loader in (("参考", load_reference), ("我们", load_ours)):
    frames, src, bg = loader()
    print("  %s" % label)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        notch_edge(a, src, label, n)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        curtain(a, src, bg, label, n)
