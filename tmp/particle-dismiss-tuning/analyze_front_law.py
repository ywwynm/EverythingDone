# -*- coding: utf-8 -*-
"""粒子化前沿服从什么规律？

释放场 T(x) 就是前沿的到达时间函数，前沿即它的等值线。两种候选物理留下的
指纹完全不同：

- **平面扫掠**：T = a + b·(x·d)，梯度方向高度集中在一个方向上，等值线是直线。
- **常速法向传播（程函方程）**：|∇T| = 1/v 处处近似相等，梯度方向从种子处
  发散，等值线是以种子为心的圆弧；多个种子合并时出现光滑弧段与尖点。

因此逐项量：
1. |∇T| 的离散度（法向传播 -> 集中；平面扫掠 -> 也集中，区分不了，看下一条）
2. ∇T 方向的圆周标准差（平面扫掠 -> 很小；法向传播 -> 很大）
3. 三个解析模型的拟合优度：平面 / 到某点的距离 / 到卡片顺风角的距离
4. 等值线本身的曲率：符号分布与半径中位数
"""
import glob, os, math, sys
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
sys.path.insert(0, r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning")


def gradient_signature(T, card_w, label):
    m = np.isfinite(T)
    fill = np.where(m, T, np.nanmedian(T))
    s = cv2.GaussianBlur(fill.astype(np.float32), (0, 0), card_w * 0.035)
    gx = cv2.Sobel(s, cv2.CV_32F, 1, 0, ksize=5) / 8.0
    gy = cv2.Sobel(s, cv2.CV_32F, 0, 1, ksize=5) / 8.0
    mag = np.hypot(gx, gy)
    good = m & (mag > 1e-6)
    # 只在场的中段统计，避开被 clamp 的两端
    lo, hi = np.nanpercentile(T[m], [12, 88])
    good &= (T > lo) & (T < hi)
    if good.sum() < 5000:
        print("  %s 样本不足" % label)
        return
    v = mag[good]
    ang = np.arctan2(gy[good], gx[good])
    R = math.hypot(float(np.cos(ang).mean()), float(np.sin(ang).mean()))
    circ_sd = math.degrees(math.sqrt(max(-2.0 * math.log(max(R, 1e-9)), 0.0)))
    print("  %s |∇T| 中位=%.5f/px  四分位距/中位=%.2f   ∇T 方向圆周sd=%4.1f度"
          % (label, np.median(v), (np.percentile(v, 75) - np.percentile(v, 25)) / max(np.median(v), 1e-9),
             circ_sd))


def fit_models(T, card_w, direction, label):
    H, W = T.shape
    m = np.isfinite(T)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    t = T[m]
    x = xx[m] / card_w
    y = yy[m] / card_w

    def r2(pred):
        return 1.0 - ((t - pred) ** 2).sum() / ((t - t.mean()) ** 2).sum()

    def lstsq(cols):
        A = np.stack(cols, 1)
        c, *_ = np.linalg.lstsq(A, t, rcond=None)
        return r2(A @ c), c

    d = np.array(direction, float)
    d /= np.linalg.norm(d)
    plane, _ = lstsq([np.ones_like(x), x * d[0] + y * d[1]])

    # 到某点的距离：中心在整卡外扩两倍的范围里网格搜索
    best = (-9.9, None)
    W_c, H_c = W / card_w, H / card_w
    for cy in np.linspace(-H_c, 2 * H_c, 31):
        for cx in np.linspace(-W_c, 2 * W_c, 31):
            r = np.hypot(x - cx, y - cy)
            score, _ = lstsq([np.ones_like(x), r])
            if score > best[0]:
                best = (score, (cx, cy))
    radial, centre = best

    # 到顺风角的距离（不拟合位置，直接用几何角点）
    corner = (0.0 if d[0] < 0 else W_c, 0.0 if d[1] < 0 else H_c)
    rc = np.hypot(x - corner[0], y - corner[1])
    corner_fit, _ = lstsq([np.ones_like(x), rc])

    print("  %s 拟合 R2：平面扫掠=%.3f   到最佳点距离=%.3f（心 %.2f, %.2f）   "
          "到顺风角距离=%.3f"
          % (label, plane, radial, centre[0], centre[1], corner_fit))
    return plane, radial


def scale_budget(T, card_w, direction, label):
    """把 T 的方差拆成：方向分量 / 大尺度残差 / 中尺度残差 / 细尺度残差。

    前沿的"优美"来自大中尺度的起伏；方向分量只决定整体推进。
    """
    H, W = T.shape
    m = np.isfinite(T)
    fill = np.where(m, T, np.nanmedian(T)).astype(np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    d = np.array(direction, float)
    d /= np.linalg.norm(d)
    proj = (xx / card_w) * d[0] + (yy / card_w) * d[1]
    A = np.stack([np.ones(m.sum()), proj[m]], 1)
    c, *_ = np.linalg.lstsq(A, T[m], rcond=None)
    trend = c[0] + c[1] * proj
    total = float(T[m].var())
    res = fill - trend
    bands = []
    prev = res
    for frac in (0.28, 0.11, 0.045):
        low = cv2.GaussianBlur(res, (0, 0), card_w * frac)
        bands.append(prev - low)
        prev = low
    parts = [float(trend[m].var())] + [float(b[m].var()) for b in bands[::-1]] + [float(prev[m].var())]
    names = ["方向", "细(<0.05)", "中(0.05-0.11)", "大(0.11-0.28)", "特大(>0.28)"]
    order = [0, 4, 3, 2, 1]
    print("  %s 方差占比：%s" % (label, "  ".join(
        "%s=%.2f" % (names[i], parts[i] / max(total, 1e-12)) for i in order)))


def front_curvature(T, card_w, label):
    levels = np.nanpercentile(T[np.isfinite(T)], [25, 40, 55, 70])
    out = []
    for lv in levels:
        inside = (np.nan_to_num(T, nan=9.0) <= lv).astype(np.uint8)
        cs, _ = cv2.findContours(inside, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cs = [c for c in cs if cv2.contourArea(c) > card_w * card_w * 0.01]
        if not cs:
            continue
        p = max(cs, key=cv2.contourArea)[:, 0, :].astype(np.float64)
        k = max(4, int(round(len(p) * 0.05)))
        a, b, c = p, np.roll(p, -k, 0), np.roll(p, -2 * k, 0)
        # 有符号曲率：叉积符号给出前沿是凸向未侵蚀区还是凹向
        cross = ((b[:, 0] - a[:, 0]) * (c[:, 1] - a[:, 1])
                 - (c[:, 0] - a[:, 0]) * (b[:, 1] - a[:, 1]))
        ab = np.linalg.norm(b - a, axis=1)
        bc = np.linalg.norm(c - b, axis=1)
        ca = np.linalg.norm(a - c, axis=1)
        radius = (ab * bc * ca) / np.maximum(2.0 * np.abs(cross), 1e-6)
        ok = np.isfinite(radius) & (radius < card_w * 4)
        if ok.sum() < 20:
            continue
        out.append((float(np.median(radius[ok])) / card_w,
                    float((cross[ok] > 0).mean())))
    if out:
        print("  %s 等值线曲率半径中位（卡宽倍数）: %s   凸向一侧的比例: %s"
              % (label,
                 " ".join("%.2f" % v[0] for v in out),
                 " ".join("%.2f" % v[1] for v in out)))


def reference():
    T = np.load(os.path.join(SP, "release2.npy"))
    a = math.radians(118.0)
    print("参考（卡 480x472）")
    gradient_signature(T, 480.0, "参考")
    fit_models(T, 480.0, (math.cos(a), -math.sin(a)), "参考")
    scale_budget(T, 480.0, (math.cos(a), -math.sin(a)), "参考")
    front_curvature(T, 480.0, "参考")


def model():
    import render_curtain_model as R
    r = R.CurtainRenderer()
    r.configure(R.Scenario("left-up", 242.0, 42))
    pure, T, raw = r.release_probe()
    b = math.radians(242.0)
    print("模型（卡 720x420）")
    gradient_signature(T, 720.0, "模型")
    fit_models(T, 720.0, (math.cos(b), math.sin(b)), "模型")
    scale_budget(T, 720.0, (math.cos(b), math.sin(b)), "模型")
    front_curvature(T, 720.0, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
if not os.environ.get("SKIP_MODEL"):
    model()
