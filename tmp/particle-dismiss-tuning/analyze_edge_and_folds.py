# -*- coding: utf-8 -*-
"""两组几何量：侵蚀边界的柔和度，以及折叠脊的走向与尺度。

A. 侵蚀边界
   未粒子化区域 = 与首帧仍逐像素相同的部分。对它的局部完整率做 0.5 等值线，
   量「完整率从 0.9 掉到 0.1 需要多远」（过渡带宽度，卡宽千分比），
   以及边界的卷曲度与转角分布（尖角有多尖、有多少）。

B. 折叠脊
   把局部密度里能被年龄与源亮度解释的部分回归掉，对残差做结构张量：
   - 取向弥散度：脊的走向在整张卡上变化多少（笔直平行 -> 接近 0）
   - 沿脊相关长度：脊能连贯延伸多远（横跨控件 -> 接近卡宽）
   - 跨脊相关长度：脊的间距（半波长）
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
STEPS = (0.20, 0.30, 0.40, 0.50)


# ---------------------------------------------------------------- A
def edge_softness(intact, card_w, label, n):
    """intact: bool，卡片区域内是否仍与首帧相同。"""
    box = max(5, int(round(card_w * 0.030)) | 1)
    frac = cv2.blur(intact.astype(np.float32), (box, box))
    inside = frac >= 0.5
    if inside.sum() < 500 or (~inside).sum() < 500:
        return None
    din = cv2.distanceTransform(inside.astype(np.uint8), cv2.DIST_L2, 5)
    dout = cv2.distanceTransform((~inside).astype(np.uint8), cv2.DIST_L2, 5)
    sd = din - dout
    edges = np.arange(-box * 2, box * 2 + 1, 1.0)
    prof = []
    for k in range(len(edges) - 1):
        m = (sd >= edges[k]) & (sd < edges[k + 1])
        prof.append(float(frac[m].mean()) if m.sum() > 80 else np.nan)
    prof = np.array(prof)
    ctr = 0.5 * (edges[:-1] + edges[1:])
    ok = np.isfinite(prof)
    if ok.sum() < 6:
        return None
    d90 = np.interp(0.9, prof[ok], ctr[ok])
    d10 = np.interp(0.1, prof[ok], ctr[ok])
    width = (d90 - d10) / card_w * 1000.0

    cnts, _ = cv2.findContours(inside.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cnts = [c for c in cnts if cv2.contourArea(c) > card_w * card_w * 0.004]
    if not cnts:
        return None
    big = max(cnts, key=cv2.contourArea)
    area = float(cv2.contourArea(big))
    per = float(cv2.arcLength(big, True))
    convol = per / (2.0 * math.sqrt(math.pi * max(area, 1.0)))
    pts = big[:, 0, :].astype(np.float64)
    step = max(3, int(round(card_w * 0.018)))
    idx = np.arange(0, len(pts), step)
    if len(idx) < 12:
        return None
    p = pts[idx]
    v = np.roll(p, -1, 0) - p
    ang = np.arctan2(v[:, 1], v[:, 0])
    turn = np.abs((np.roll(ang, -1) - ang + np.pi) % (2 * np.pi) - np.pi)
    turn = np.degrees(turn[:-1])
    print("  %-4s n=%.2f  过渡带宽=%5.1f/1000卡宽  卷曲度=%.2f  转角P90=%4.0f度  尖角(>60度)占比=%.3f"
          % (label, n, width, convol, np.percentile(turn, 90), float((turn > 60).mean())))
    return width


# ---------------------------------------------------------------- B
def ridge_geometry(dens, age, src, card_w, label, n):
    m = np.isfinite(age) & (dens > 0.02)
    if m.sum() < 20000:
        return
    y = dens[m].astype(np.float64)
    a = age[m].astype(np.float64)
    s = src[m].astype(np.float64) / 255.0
    X = np.stack([np.ones_like(a), a, a * a, a ** 3, s, s * a, s * s], 1)
    coef, *_ = np.linalg.lstsq(X, y, rcond=None)
    res = np.zeros_like(dens)
    res[m] = (y - X @ coef).astype(np.float32)

    g = cv2.GaussianBlur(res, (0, 0), card_w * 0.010)
    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=5)
    gy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=5)
    w = int(round(card_w * 0.05)) | 1
    Jxx = cv2.blur(gx * gx, (w, w))
    Jyy = cv2.blur(gy * gy, (w, w))
    Jxy = cv2.blur(gx * gy, (w, w))
    c2 = Jxx - Jyy
    s2 = 2.0 * Jxy
    coh = np.sqrt(c2 * c2 + s2 * s2) / np.maximum(Jxx + Jyy, 1e-12)
    wt = (coh * m).astype(np.float64)
    norm = np.hypot(c2, s2) + 1e-12
    cbar = float((wt * c2 / norm).sum()) / max(wt.sum(), 1e-9)
    sbar = float((wt * s2 / norm).sum()) / max(wt.sum(), 1e-9)
    R = math.hypot(cbar, sbar)
    disp = math.degrees(math.sqrt(max(-2.0 * math.log(max(R, 1e-9)), 0.0))) / 2.0
    ang = 0.5 * math.atan2(sbar, cbar)
    across = np.array([math.cos(ang), math.sin(ang)])
    along = np.array([-across[1], across[0]])

    v = res - res[m].mean() * m
    F = np.fft.fft2(v)
    ac = np.fft.fftshift(np.real(np.fft.ifft2(F * np.conj(F))))
    ac /= max(ac.max(), 1e-9)
    cy, cx = ac.shape[0] // 2, ac.shape[1] // 2
    limit = int(card_w * 0.55)

    def decay_len(d):
        prev = 1.0
        for r in range(1, limit):
            yy = int(round(cy + d[1] * r))
            xx = int(round(cx + d[0] * r))
            if not (0 <= yy < ac.shape[0] and 0 <= xx < ac.shape[1]):
                return r / card_w * 1000.0
            val = ac[yy, xx]
            if val < 1.0 / math.e:
                return (r - 1 + (prev - 1.0 / math.e) / max(prev - val, 1e-9)) / card_w * 1000.0
            prev = val
        return limit / card_w * 1000.0

    print("  %-4s n=%.2f  取向弥散=%4.1f度  沿脊相关=%5.1f  跨脊相关=%5.1f  残差sd=%.3f"
          % (label, n, disp, decay_len(along), decay_len(across), float(res[m].std())))


# ---------------------------------------------------------------- 参考
def run_reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    rel = np.load(os.path.join(SP, "release2.npy"))
    bg = np.asarray(Image.open(os.path.join(SP, "bg.png")).convert("RGB"), np.float32)
    r0 = np.asarray(Image.open(fs[0]).convert("RGB"), np.float32)
    X0, Y0, CW, CH = 45, 95, 480, 472
    card = np.zeros(r0.shape[:2], bool)
    card[Y0:Y0 + CH, X0:X0 + CW] = True
    print("参考（卡宽 480；相关长度单位 = 卡宽千分比）")
    for n in STEPS:
        a = np.asarray(Image.open(fs[int(np.argmin(np.abs(ts - (T0 + n * (T1 - T0)))))]).convert("RGB"), np.float32)
        intact = (np.abs(a - r0).max(2) <= 22)[Y0:Y0 + CH, X0:X0 + CW]
        edge_softness(intact, CW, "参考", n)
    print()
    for n in STEPS:
        a = np.asarray(Image.open(fs[int(np.argmin(np.abs(ts - (T0 + n * (T1 - T0)))))]).convert("RGB"), np.float32)
        part = ((~((np.abs(a - r0).max(2) <= 22) & card)) & (np.abs(a - bg).max(2) > 26)).astype(np.float32)
        dens = cv2.blur(part, (33, 33))[Y0:Y0 + CH, X0:X0 + CW]
        ridge_geometry(dens, n - rel, r0[Y0:Y0 + CH, X0:X0 + CW].mean(2), CW, "参考", n)


# ---------------------------------------------------------------- 模型
def run_model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    imgs = [np.asarray(Image.open(f).convert("RGB"), np.float32) for f in fs]
    src, bg = imgs[0], imgs[-1]
    X0, Y0, CW, CH = 280, 240, 720, 420
    card = np.zeros(src.shape[:2], bool)
    card[Y0:Y0 + CH, X0:X0 + CW] = True
    rel = np.full((CH, CW), np.nan, np.float32)
    run = np.zeros((CH, CW), np.int16)
    cand = np.full((CH, CW), np.nan, np.float32)
    for i, a in enumerate(imgs):
        nn = i / N
        ch = np.abs(a - src).max(2)[Y0:Y0 + CH, X0:X0 + CW] > 22
        cand[ch & (run == 0) & np.isnan(cand)] = nn
        run = np.where(ch, run + 1, 0)
        cand[(~ch) & (run == 0)] = np.nan
        lock = np.isnan(rel) & (run >= 3) & ~np.isnan(cand)
        rel[lock] = cand[lock]
    print("模型（卡宽 720；相关长度单位 = 卡宽千分比）")
    for n in STEPS:
        a = imgs[int(round(n * N))]
        intact = (np.abs(a - src).max(2) <= 22)[Y0:Y0 + CH, X0:X0 + CW]
        edge_softness(intact, CW, "模型", n)
    print()
    box = int(round(33 * 720 / 480)) | 1
    for n in STEPS:
        a = imgs[int(round(n * N))]
        part = ((~((np.abs(a - src).max(2) <= 22) & card)) & (np.abs(a - bg).max(2) > 26)).astype(np.float32)
        dens = cv2.blur(part, (box, box))[Y0:Y0 + CH, X0:X0 + CW]
        ridge_geometry(dens, n - rel, src[Y0:Y0 + CH, X0:X0 + CW].mean(2), CW, "模型", n)


if not os.environ.get("SKIP_REF"):
    run_reference()
    print()
run_model()
