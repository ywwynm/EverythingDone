# -*- coding: utf-8 -*-
"""粒子群的三项形态量。

1. 云面积 / 原控件面积：云有没有真正离开控件、铺开
2. 直边占比：外轮廓上落在原控件矩形边界 3px 以内的点占的比例
   （继承了控件的直边 = 轮廓"不知道是什么东西"）
3. 浓区结构：局部密度的变异系数，以及浓区（密度 >= 全场 P85）的连通块数
"""
import glob, os, math
import numpy as np, cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
STEPS = (0.35, 0.45, 0.55, 0.65)


def intact_surface(a, first, k):
    """仍是完整表面的像素。

    只看颜色不行：对话框是白的，粒子飞到原对话框区域上方时颜色与完整表面一样。
    只看"是否露出过背景"也不行：一个格子刚释放时它自己的粒子就停在原位，
    那个像素从来没露出过背景。判据也不能只在对话框区域内生效：对话框背后的
    遮罩覆盖整屏、消散后消失，"与末帧不同"在整屏都成立，轮廓会变成面板矩形。

    用局部结构判：r = 当前帧 / 首帧。完整表面即使被整体压暗，r 在局部也是
    **平滑**的（压暗是一个缓变的比例）；粒子群则是点与空隙相间，r 的局部标准差
    很大。因此 r 的局部标准差就是判据。
    """
    r = a.mean(2) / np.maximum(first.mean(2), 1.0)
    r = np.clip(r, 0.0, 2.0)
    mean = cv2.blur(r, (k, k))
    var = cv2.blur(r * r, (k, k)) - mean * mean
    std = np.sqrt(np.maximum(var, 0.0))
    return std < 0.11


def particle_mask(frames, upto, src, bg, card_w):
    """粒子 = 与背景不同，且所在位置不是"仍完整的表面"。"""
    surface = np.abs(src - bg).max(2) > 26
    k = max(5, int(round(card_w * 0.014)) | 1)
    a = frames[upto][1]
    still = intact_surface(a, src, k)
    return (np.abs(a - bg).max(2) > 26) & ~still


def shape(frames, card, card_w, label):
    x0, y0, x1, y1 = card
    src = frames[0][1]
    bg = frames[-1][1]
    cardm = np.zeros(src.shape[:2], bool)
    cardm[y0:y1, x0:x1] = True
    area = float((x1 - x0) * (y1 - y0))
    box = max(5, int(round(card_w * 0.022)) | 1)
    print("  %s   云面积/卡面积 / 直边占比 / 密度变异系数 / 浓区块数" % label)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        mask = particle_mask(frames, i, src, bg, card_w)
        dens = cv2.GaussianBlur(mask.astype(np.float32), (box, box), 0)
        cloud = (dens >= 0.05).astype(np.uint8)
        cloud = cv2.morphologyEx(cloud, cv2.MORPH_OPEN,
                                 cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
        if cloud.sum() < 2000:
            continue
        cs, _ = cv2.findContours(cloud, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cs = [c for c in cs if cv2.contourArea(c) > area * 0.01]
        if not cs:
            continue
        pts = np.vstack([c[:, 0, :] for c in cs]).astype(np.float64)
        near = ((np.abs(pts[:, 0] - x0) < 3) | (np.abs(pts[:, 0] - x1) < 3) |
                (np.abs(pts[:, 1] - y0) < 3) | (np.abs(pts[:, 1] - y1) < 3))
        inside_span = ((pts[:, 0] > x0 - 6) & (pts[:, 0] < x1 + 6) &
                       (pts[:, 1] > y0 - 6) & (pts[:, 1] < y1 + 6))
        straight = float((near & inside_span).mean())
        m = dens > 0.05
        cv_ = float(dens[m].std() / max(dens[m].mean(), 1e-9))
        hi = (dens >= np.percentile(dens[m], 85)).astype(np.uint8)
        hi = cv2.morphologyEx(hi, cv2.MORPH_OPEN,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
        cnt, lab, st, _ = cv2.connectedComponentsWithStats(hi, 8)
        blobs = int((st[1:, 4] > area * 0.004).sum()) if cnt > 1 else 0
        print("    n=%.2f   %.2f   %.3f   %.3f   %2d"
              % (n, cloud.sum() / area, straight, cv_, blobs))


def reference():
    fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
    ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
    T0, T1 = 3.28, 8.38
    fr = [((t - T0) / (T1 - T0), np.asarray(Image.open(f).convert("RGB"), np.float32))
          for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
    fr.append((1.0, np.asarray(Image.open(os.path.join(SP, "bg.png")).convert("RGB"), np.float32)))
    shape(fr, (45, 95, 525, 567), 480.0, "参考")


def model():
    fs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
    N = len(fs) - 1
    fr = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32)) for i, f in enumerate(fs)]
    shape(fr, (280, 240, 1000, 660), 720.0, "模型")


if not os.environ.get("SKIP_REF"):
    reference()
    print()
model()
