"""视频后期的「模糊放大底图」：科比的视频在手机录屏上方留了一条，用录屏本身放大、重度模糊后的画面填充，
所以那一条会随卡片消散从蓝变红。模型的背景取自结束帧，那一条在动画前段对不上。

拟合仿射映射：填充区像素 (x, y) 取自画面 (a·x + bx, a·y + by) 处的模糊值（模糊 σ，亮度 g·v + o），
用原片所有帧在填充区里、避开字幕与水印字形的像素做最小二乘。
产物：assets/<段>/blur-fill.json（rows：填充区行范围；a, bx, by, sigma, gain, offset），渲染器在合成之后、叠加层之前套用。

用法：python analysis/fit_blur_fill.py <段名> <填充区下沿行号>
例：python analysis/fit_blur_fill.py kobe 116
"""
from pathlib import Path
import json
import sys

import cv2
import numpy as np
from PIL import Image
from scipy.optimize import minimize

ROOT = Path(__file__).resolve().parents[1]


def sample(frame_blur, a, bx, by, rows, width):
    ys, xs = np.mgrid[0:rows, 0:width].astype(np.float32)
    mx = (a * xs + bx).astype(np.float32)
    my = (a * ys + by).astype(np.float32)
    return cv2.remap(frame_blur, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def main():
    name, rows = sys.argv[1], int(sys.argv[2])
    d = ROOT / 'assets' / name
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    W, H = meta['frame']
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    frames = [np.asarray(fr[i]).astype(np.float32) for i in range(len(pr))]
    # 避开字幕、水印：在填充区里与 9 像素中值滤波差得多的像素（字形），外扩
    med = np.median(np.stack([f[:rows] for f in frames]), 0).clip(0, 255).astype(np.uint8)
    glyph = np.abs(med.astype(np.int16) - cv2.medianBlur(med, 21).astype(np.int16)).max(2) > 20
    glyph = cv2.dilate(glyph.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
    use = ~glyph
    print('填充区可用像素', int(use.sum()), '/', use.size)
    take = list(range(0, len(frames), max(1, len(frames) // 12)))

    def loss(q, verbose=False):
        a, bx, by, sigma = q
        if a <= 0.05 or sigma < 1:
            return 1e9
        err, A, B = [], [], []
        for i in take:
            src = frames[i].copy()
            src[:rows] = src[rows:rows + 1]          # 填充区本身不参与取样（避免自引用）
            fb = cv2.GaussianBlur(src, (0, 0), float(sigma))
            pred = sample(fb, a, bx, by, rows, W)
            A.append(pred[use]); B.append(frames[i][:rows][use])
        A = np.concatenate(A).reshape(-1); B = np.concatenate(B).reshape(-1)
        g, o = np.polyfit(A, B, 1)
        e = float(np.mean(np.abs(g * A + o - B)))
        if verbose:
            return e, g, o
        return e

    best = None
    for a in (0.45, 0.55, 0.65, 0.75, 0.85, 1.0):
        for by in (100, 150, 200, 250):
            for bx in (0, 60, 120, 180):
                for sigma in (15, 30):
                    v = loss((a, bx, by, sigma))
                    if best is None or v < best[0]:
                        best = (v, (a, bx, by, sigma))
    print('粗搜最优', best)
    res = minimize(loss, np.array(best[1], np.float64), method='Nelder-Mead',
                   options=dict(xatol=0.5, fatol=0.01, maxiter=400, initial_simplex=None))
    a, bx, by, sigma = res.x
    e, g, o = loss(res.x, verbose=True)
    print(f'细化 a={a:.4f} bx={bx:.1f} by={by:.1f} σ={sigma:.1f} 亮度 {g:.3f}·v+{o:.1f}  平均绝对差 {e:.2f}')
    out = dict(rows=rows, a=float(a), bx=float(bx), by=float(by), sigma=float(sigma), gain=float(g), offset=float(o),
               note='视频后期用录屏本身放大、模糊后填充录屏上方的一条；填充区 (x, y) 取自画面 (a·x+bx, a·y+by) 的模糊值')
    (d / 'blur-fill.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
