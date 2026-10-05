"""参考 3（蜘蛛侠 · 红色手套）卡片背后壁纸的几何重建。

壁纸是上下两个球面：上球的下沿、下球的上沿各有一道橙色亮边，亮边在卡片范围内是圆弧，中间离卡片边缘最远。
视频在颗粒散尽前截断，卡片背后从没有完整露出过，`background.png` 按行把卡片左右两侧的颜色外推进来，
画面上是一道道水平条纹。这里按原片里露出的亮边位置拟合两条圆弧，把卡片两侧的竖直剖面沿圆弧弯过去：
上球区域随上弧下移，下球区域随下弧上移，两弧之间线性过渡。

亮边位置（场景像素，x → y）取自原片 0.62～0.74 进度、卡片已消散处逐列的橙色亮度峰。
产物：assets/reference-0926-3/background-clean.png（底图取 background-clean-before-reveal.png，即修掉顶部残留颗粒的版本）。
用法：python analysis/rebuild_dome_background.py
"""
from pathlib import Path
import json

import cv2

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
NAME = 'reference-0926-3'
# 上球下沿（向下凸）与下球上沿（向上凸）的亮边：卡片外两侧取自 background.png，卡片内取自原片逐列峰值
UPPER = [(100, 252), (130, 268), (160, 285), (190, 296), (220, 305), (250, 311), (280, 316), (310, 320),
         (400, 318), (430, 315), (490, 306), (520, 297), (620, 252)]
LOWER = [(100, 463), (130, 456), (160, 446), (190, 438), (220, 430), (250, 427), (280, 422),
         (460, 434), (490, 440), (520, 445), (550, 456), (580, 465), (620, 469)]


def fit_circle(points):
    p = np.asarray(points, np.float64)
    A = np.column_stack([2 * p[:, 0], 2 * p[:, 1], np.ones(len(p))])
    b = (p ** 2).sum(1)
    cx, cy, c = np.linalg.lstsq(A, b, rcond=None)[0]
    r = np.sqrt(c + cx ** 2 + cy ** 2)
    return cx, cy, r


def measure_rims(fr, pr, lo_p, hi_p, x_range):
    """后段帧（卡片已消散）的低分位画面：颗粒是加光叠加，只会让像素变亮，8% 分位（接近逐像素最小值）接近壁纸本身。
    逐列在上、下两个窗口里找亮度峰作为亮边位置。"""
    from scipy.signal import find_peaks
    take = [i for i in range(len(pr)) if lo_p <= pr[i] <= hi_p]
    low = np.percentile(np.stack([np.asarray(fr[i]).astype(np.float32) for i in take]), 8, axis=0)
    low = cv2.medianBlur(low.clip(0, 255).astype(np.uint8), 3).astype(np.float32)
    lum = low @ np.float32([.299, .587, .114])
    up, dn = [], []
    for x in range(*x_range, 3):
        col = lum[:, max(0, x - 1):x + 2].mean(1)
        for (a, b), out in (((220, 360), up), ((350, 480), dn)):
            seg = col[a:b]
            pk, _ = find_peaks(seg, prominence=25)
            if len(pk):
                out.append((x, a + int(pk[np.argmax(seg[pk])]), float(seg[pk].max())))
    return low, up, dn


def robust_poly(points, deg=4, iters=4):
    """多项式拟合亮边，逐轮剔除离拟合曲线超过 3 倍中位残差的点（卡片中部残留的人脸会给出假峰）。"""
    p = np.asarray(points, np.float64)
    keep = np.ones(len(p), bool)
    for _ in range(iters):
        c = np.polyfit(p[keep, 0], p[keep, 1], deg)
        r = np.abs(np.polyval(c, p[:, 0]) - p[:, 1])
        keep = r <= max(3.0 * np.median(r[keep]), 3.0)
    return c, keep


def main():
    d = ROOT / 'assets' / NAME
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    x0, y0, x1, y1 = meta['rect']
    clean = d / 'background-clean.png'
    # 底图：修掉了顶部残留颗粒的版本（rebuild_revealed_background.py 首次运行时备份的那份）
    prior = d / 'background-clean-before-reveal.png'
    base = np.asarray(Image.open(prior if prior.exists() else d / 'background.png').convert('RGB')).astype(np.float32)
    H, W = base.shape[:2]
    xs = np.arange(W, dtype=np.float64)
    c = ROOT / 'analysis/match-cache' / NAME
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    valid = meta['reference'].get('valid_progress', 1.0)
    low, up, dn = measure_rims(fr, pr, 0.6, valid, (x0 - 20, x1 + 20))
    pu, ku = robust_poly(up)
    pl, kl = robust_poly(dn)
    yu = np.polyval(pu, xs)
    yl = np.polyval(pl, xs)
    print('实测上亮边 %d 点（剔除 %d）、下亮边 %d 点（剔除 %d）；中心 x=360 处 上 %.0f 下 %.0f'
          % (len(up), int((~ku).sum()), len(dn), int((~kl).sum()), np.polyval(pu, 360), np.polyval(pl, 360)))
    xl, xr = x0 - 20, x1 + 20
    yu_l, yu_r = np.interp([xl, xr], xs, yu)
    yl_l, yl_r = np.interp([xl, xr], xs, yl)
    ys_all = np.arange(H, dtype=np.float64)

    def aligned_profile(cols, ref_u, ref_l):
        """把每一列按它自己的亮边位置对齐到参考列（ref_u / ref_l）再平均：
        亮边在卡片两侧斜率很大，直接取平均会把亮边抹宽抹淡。"""
        acc = np.zeros((H, 3)); n = 0
        for cx in cols:
            col = base[:, cx]
            # 参考列的 y → 本列的 y（上球随上弧、下球随下弧、两弧之间线性）
            src = np.where(ys_all <= ref_u, ys_all + (yu[cx] - ref_u),
                           np.where(ys_all >= ref_l, ys_all + (yl[cx] - ref_l),
                                    yu[cx] + (ys_all - ref_u) * (yl[cx] - yu[cx]) / max(ref_l - ref_u, 1)))
            src = np.clip(src, 0, H - 1)
            acc += np.stack([np.interp(src, ys_all, col[:, ch]) for ch in range(3)], 1)
            n += 1
        return acc / max(n, 1)

    left = aligned_profile(range(max(0, x0 - 30), x0 - 10), yu_l, yl_l)       # (H, 3)，对齐到 xl 处的亮边
    right = aligned_profile(range(x1 + 10, min(W, x1 + 30)), yu_r, yl_r)
    out = base.copy()
    ys = np.arange(H, dtype=np.float64)
    pad = 8
    for x in range(max(0, x0 - pad), min(W, x1 + pad)):
        t = np.clip((x - xl) / (xr - xl), 0, 1)
        ref_u = yu_l * (1 - t) + yu_r * t
        ref_l = yl_l * (1 - t) + yl_r * t
        # 本列的 y → 两侧剖面里的 y'
        src = np.where(ys <= yu[x], ys - (yu[x] - ref_u),
                       np.where(ys >= yl[x], ys - (yl[x] - ref_l),
                                ref_u + (ys - yu[x]) * (ref_l - ref_u) / max(yl[x] - yu[x], 1)))
        src = np.clip(src, 0, H - 1)
        col = np.stack([np.interp(src, ys, left[:, c]) * (1 - t) + np.interp(src, ys, right[:, c]) * t for c in range(3)], 1)
        # 只替换卡片范围（上下各留出 8 像素与原背景衔接）
        rows = slice(max(0, y0 - pad), min(H, y1 + pad))
        w = 1.0
        if x < x0:
            w = (x - (x0 - pad)) / pad
        elif x >= x1:
            w = ((x1 + pad) - x) / pad
        out[rows, x] = base[rows, x] * (1 - w) + col[rows] * w
    # 亮边附近（±28 像素）且后段已露出的像素直接用低分位画面：亮边的亮度沿弧变化（中间更亮更厚），两侧剖面推不出来
    src = np.asarray(Image.open(d / 'source.png').convert('RGB')).astype(np.float32)
    ys_g = np.arange(H, dtype=np.float64)[:, None]
    near = (np.abs(ys_g - yu[None, :]) < 28) | (np.abs(ys_g - yl[None, :]) < 28)
    r_, g_, b_ = low[..., 0], low[..., 1], low[..., 2]
    clean_px = (np.abs(low - src).max(2) > 45) & ~((r_ > g_ + 45) & (b_ > g_ + 5))
    card = np.zeros((H, W), bool)
    card[max(0, y0 - pad):min(H, y1 + pad), max(0, x0 - pad):min(W, x1 + pad)] = True
    use = near & clean_px & card
    use = cv2.morphologyEx(use.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)) > 0
    wgt = cv2.GaussianBlur(use.astype(np.float32), (0, 0), 2.5)[..., None]
    out = out * (1 - wgt) + low * wgt
    Image.fromarray(np.clip(out + 0.5, 0, 255).astype(np.uint8)).save(clean)
    print('亮边附近直接取用后段低分位画面的像素', int(use.sum()))


if __name__ == '__main__':
    main()
