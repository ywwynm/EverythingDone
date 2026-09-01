# -*- coding: utf-8 -*-
"""粒子群里有没有「浪一样的翻滚」？

翻滚在二维投影里留下两个可测的痕迹，两者都与"整体被吹走"无关：

1. **明暗带**：纱面法向随高度场起伏，迎光面亮、背光面暗。因此在粒子内部，局部
   平均亮度会有大尺度（0.1-0.3 卡宽）的起伏。量它的变异系数与相关长度。
2. **行进的密度脊**：翻滚是一列行波，密度脊在材料上**移动**，其在时空图里的斜率
   与材料自身的平流斜率不同。若密度脊完全随材料走，那只是被搬走，不是翻滚。

两项都只在"有粒子的地方"统计，避免被背景与完整表面污染。
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"


def bands(frames, card, card_w, direction, label):
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    cardm = np.zeros(src.shape[:2], bool)
    cardm[y0:y1, x0:x1] = True
    win = max(9, int(round(card_w * 0.15)) | 1)
    print("  %s   明暗带：局部亮度变异系数 / 相关长度(‰卡宽)" % label)
    for target in (0.35, 0.45, 0.55):
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        part = ((np.abs(a - src).max(2) > 26) & (np.abs(a - bg).max(2) > 26))
        if part.sum() < 5000:
            continue
        lum = a.mean(2)
        wsum = cv2.blur((lum * part).astype(np.float32), (win, win))
        wcnt = cv2.blur(part.astype(np.float32), (win, win))
        ok = wcnt > 0.04
        local = np.where(ok, wsum / np.maximum(wcnt, 1e-6), 0.0)
        v = local[ok]
        cv_ = float(v.std() / max(v.mean(), 1e-9))
        # 相关长度：局部亮度距平的自相关降到 1/e
        d = np.where(ok, local - v.mean(), 0.0)
        F = np.fft.fft2(d)
        ac = np.fft.fftshift(np.real(np.fft.ifft2(F * np.conj(F))))
        ac /= max(ac.max(), 1e-9)
        cy, cx = ac.shape[0] // 2, ac.shape[1] // 2
        corr = 0.0
        for r in range(1, int(card_w * 0.5)):
            ring = [ac[cy + int(round(r * math.sin(t))), cx + int(round(r * math.cos(t)))]
                    for t in np.linspace(0, 2 * math.pi, 24, endpoint=False)
                    if 0 <= cy + int(round(r * math.sin(t))) < ac.shape[0]
                    and 0 <= cx + int(round(r * math.cos(t))) < ac.shape[1]]
            if ring and np.mean(ring) < 1.0 / math.e:
                corr = r / card_w * 1000.0
                break
        print("    n=%.2f   %.4f   %5.1f" % (n, cv_, corr))


def ridge_motion(frames, card, card_w, direction, label):
    """密度脊在材料坐标系里是否移动。

    做法：把密度投影到飞行轴上得到一维剖面，逐帧求剖面的互相关位移，得到脊的
    推进速度；再与粒子质心的位移速度比较。两者相等 = 只是被搬走；脊更快或更慢
    = 存在行波。
    """
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    cardm = np.zeros(src.shape[:2], bool)
    cardm[y0:y1, x0:x1] = True
    d = np.array(direction, float)
    d /= np.linalg.norm(d)
    H, W = src.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    along = ((xx - cx) * d[0] + (yy - cy) * d[1]) / card_w
    edges = np.linspace(-1.2, 1.2, 121)
    idx = np.clip(np.digitize(along, edges) - 1, 0, 119)
    prof_prev, n_prev, centre_prev = None, None, None
    ridge, drift = [], []
    for n, a in frames:
        if n < 0.28 or n > 0.62:
            continue
        part = ((np.abs(a - src).max(2) > 26) & (np.abs(a - bg).max(2) > 26))
        if part.sum() < 5000:
            continue
        prof = np.bincount(idx[part].ravel(), minlength=120).astype(np.float64)
        prof -= prof.mean()
        centre = float(along[part].mean())
        if prof_prev is not None and n > n_prev:
            c = np.correlate(prof, prof_prev, "same")
            shift = (int(np.argmax(c)) - 60) * (edges[1] - edges[0])
            ridge.append(shift / (n - n_prev))
            drift.append((centre - centre_prev) / (n - n_prev))
        prof_prev, n_prev, centre_prev = prof, n, centre
    if ridge:
        print("  %s   密度脊速度=%.3f 卡宽/单位时间   质心速度=%.3f   之差=%.3f"
              % (label, float(np.median(ridge)), float(np.median(drift)),
                 float(np.median(ridge)) - float(np.median(drift))))


def reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    fr = [((t - T0) / (T1 - T0), np.asarray(Image.open(f).convert("RGB"), np.float32))
          for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
    fr.append((1.0, np.asarray(Image.open(os.path.join(SP, "bg.png")).convert("RGB"), np.float32)))
    a = math.radians(118.0)
    card = (45, 95, 525, 567)
    bands(fr, card, 480.0, (math.cos(a), -math.sin(a)), "参考")
    ridge_motion(fr, card, 480.0, (math.cos(a), -math.sin(a)), "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    fr = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32)) for i, f in enumerate(fs)]
    b = math.radians(242.0)
    card = (280, 240, 1000, 660)
    bands(fr, card, 720.0, (math.cos(b), math.sin(b)), "模型")
    ridge_motion(fr, card, 720.0, (math.cos(b), math.sin(b)), "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
