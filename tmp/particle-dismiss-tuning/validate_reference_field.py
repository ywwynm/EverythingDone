# -*- coding: utf-8 -*-
"""参考释放场里的「非方向结构」有多少是真的，有多少是检测误差？

参考素材是一张照片在消散。逐像素判「何时开始变化」时，照片暗处、低对比处的
变化幅度小，阈值触发得晚；亮处、高对比处触发得早。这会在反推出来的释放场里
制造出与**图像内容**相关的假结构，抬高非方向方差、压低平面扫掠的拟合优度。

三项检验：
1. 去掉方向趋势后的残差，与源图的亮度、局部对比度的相关性有多强
2. 用不同检测阈值反推两份释放场，看它们彼此差多少（测量噪声的下界）
3. 把内容相关的部分也回归掉之后，平面扫掠的拟合优度回升到多少
"""
import glob, os, math
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
X0, Y0, CW, CH = 45, 95, 480, 472
T0, T1 = 3.28, 8.38


def load_frames():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    out = []
    for f, t in zip(fs, ts):
        if t < T0 - 0.02 or t > T1:
            continue
        out.append(((t - T0) / (T1 - T0),
                    np.asarray(Image.open(f).convert("RGB"), np.float32)[Y0:Y0 + CH, X0:X0 + CW]))
    return out


def release_map(frames, threshold):
    src = frames[0][1]
    rel = np.full(src.shape[:2], np.nan, np.float32)
    for n, a in frames:
        new = np.isnan(rel) & (np.abs(a - src).max(2) > threshold)
        rel[new] = n
    return rel


def main():
    frames = load_frames()
    src = frames[0][1]
    lum = src.mean(2)
    contrast = cv2.GaussianBlur(
        np.abs(cv2.Sobel(lum, cv2.CV_32F, 1, 0, 3)) + np.abs(cv2.Sobel(lum, cv2.CV_32F, 0, 1, 3)),
        (0, 0), 9.0)

    maps = {t: release_map(frames, t) for t in (22, 45, 80)}
    base = maps[45]

    print("检验 2：不同检测阈值反推出的释放场彼此差多少")
    m_all = np.isfinite(maps[22]) & np.isfinite(maps[45]) & np.isfinite(maps[80])
    for a, b in ((22, 45), (45, 80), (22, 80)):
        d = maps[a][m_all] - maps[b][m_all]
        print("    阈值 %d vs %d：差值 sd=%.4f  P90-P10=%.4f" % (a, b, d.std(),
              np.percentile(d, 90) - np.percentile(d, 10)))
    print("    对照：释放场自身的 sd=%.4f" % base[m_all].std())

    a = math.radians(118.0)
    d = np.array([math.cos(a), -math.sin(a)])
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float64)
    proj = (xx / CW) * d[0] + (yy / CW) * d[1]

    m = np.isfinite(base)
    t = base[m]

    def r2(cols):
        A = np.stack(cols, 1)
        c, *_ = np.linalg.lstsq(A, t, rcond=None)
        return 1.0 - ((t - A @ c) ** 2).sum() / ((t - t.mean()) ** 2).sum()

    one = np.ones(m.sum())
    p = proj[m]
    L = lum[m] / 255.0
    C = np.log1p(contrast[m]) / 6.0
    plane = r2([one, p])
    content = r2([one, L, C, L * L, C * C, L * C])
    both = r2([one, p, L, C, L * L, C * C, L * C])
    print()
    print("检验 1/3：拟合优度")
    print("    仅方向趋势                  R2=%.3f" % plane)
    print("    仅源图内容（亮度+对比度）    R2=%.3f" % content)
    print("    方向 + 内容                 R2=%.3f" % both)
    print("    内容能解释掉的额外方差       %.3f" % (both - plane))

    # 把内容项回归掉之后，方向趋势在剩余方差里占多少
    A = np.stack([one, L, C, L * L, C * C, L * C], 1)
    c, *_ = np.linalg.lstsq(A, t, rcond=None)
    resid = t - A @ c
    A2 = np.stack([one, p], 1)
    c2, *_ = np.linalg.lstsq(A2, resid, rcond=None)
    r2_after = 1.0 - ((resid - A2 @ c2) ** 2).sum() / ((resid - resid.mean()) ** 2).sum()
    print("    去掉内容项后，方向趋势的 R2=%.3f" % r2_after)


main()
