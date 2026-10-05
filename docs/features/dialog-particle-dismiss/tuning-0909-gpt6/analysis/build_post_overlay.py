"""视频后期在动画途中才加上的叠加层（参考 5 的「小红书」「酱紫Oo」水印与顶部暗色渐变，约在进度 0.298 出现）。

背景图取自结束帧，顶部带着这层水印与渐变；而原片在切换之前顶部是白色窗帘壁纸。这层后期叠加在颗粒之上（颗粒从它下面经过时被压暗）。
做法（只处理卡片上沿以上那一带）：
  1. 切换前的壁纸 W：切换前若干帧逐像素取中位数（颗粒只在少数帧经过）；
  2. 切换后的画面 L：切换后各帧逐像素取中位数；
  3. 两态抠像：假设叠加色只有黑或白，L = W·(1 − a) + C·a 解出 a；黑白解释不了的像素（彩色头像）按不透明处理，C = L；
  4. 背景顶部换成 W（写入 background-clean.png），叠加层登记到 timed-overlays.json，从切换时刻起显示。
产物：assets/<段>/post-overlay.png、post-overlay.json；background-clean.png 先备份为 background-clean-before-post.png。

用法：python analysis/build_post_overlay.py <段名> <切换进度> <切换前中位数的起始进度> [切换后取帧的截止进度]
例：python analysis/build_post_overlay.py reference-0926-5 0.298 0.09 0.384
"""
from pathlib import Path
import json
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    name, switch, early_from = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    # 切换后的画面只取到下一次界面跳变（录屏计时跳秒）之前，否则叠加层会把之后的计时数字提前带进来
    late_to = float(sys.argv[4]) if len(sys.argv) > 4 else 9.0
    d = ROOT / 'assets' / name
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    x0, y0, x1, y1 = meta['rect']
    top = max(0, y0 - 4)
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    valid = meta['reference'].get('valid_progress', 1.0)
    early = [i for i in range(len(pr)) if early_from <= pr[i] <= switch - 0.015]
    late = [i for i in range(len(pr)) if switch + 0.015 <= pr[i] <= min(valid, late_to - 0.01)]
    W = np.median(np.stack([np.asarray(fr[i])[:top] for i in early]).astype(np.float32), 0)
    L = np.median(np.stack([np.asarray(fr[i])[:top] for i in late]).astype(np.float32), 0)
    lw = W @ np.float32([.299, .587, .114]); ll = L @ np.float32([.299, .587, .114])
    a = np.zeros(lw.shape, np.float32)
    C = np.zeros_like(W)
    darker = ll < lw - 2
    brighter = ll > lw + 2
    a[darker] = 1.0 - ll[darker] / np.maximum(lw[darker], 1.0)
    a[brighter] = (ll[brighter] - lw[brighter]) / np.maximum(255.0 - lw[brighter], 1.0)
    C[brighter] = 255.0
    a = a.clip(0, 1)
    fit = W * (1 - a[..., None]) + C * a[..., None]
    bad = np.abs(fit - L).max(2) > 12
    a[bad] = 1.0
    C[bad] = L[bad]
    rgba = np.zeros((meta['frame'][1], meta['frame'][0], 4), np.uint8)
    rgba[:top, :, :3] = C.clip(0, 255).astype(np.uint8)
    rgba[:top, :, 3] = (a * 255 + 0.5).astype(np.uint8)
    Image.fromarray(rgba, 'RGBA').save(d / 'post-overlay.png')
    (d / 'post-overlay.json').write_text(json.dumps(dict(progress=switch, early_frames=len(early), late_frames=len(late),
                                                         note='视频后期在动画途中加上的水印与渐变；参考进度到达 progress 后显示'),
                                                    ensure_ascii=False, indent=1), 'utf-8')
    clean = d / 'background-clean.png'
    base_path = clean if clean.exists() else d / 'background.png'
    if clean.exists() and not (d / 'background-clean-before-post.png').exists():
        shutil.copy(clean, d / 'background-clean-before-post.png')
    prior = d / 'background-clean-before-post.png'
    base = np.asarray(Image.open(prior if prior.exists() else base_path).convert('RGB')).copy()
    # 顶部换成切换前的壁纸，与下方原背景在最后 6 行羽化衔接
    wgt = np.ones((top, 1, 1), np.float32)
    wgt[-6:, 0, 0] = np.linspace(1, 0, 6)
    base[:top] = (W * wgt + base[:top].astype(np.float32) * (1 - wgt) + 0.5).clip(0, 255).astype(np.uint8)
    Image.fromarray(base).save(clean)
    print(name, '切换前帧', len(early), '切换后帧', len(late), '叠加层平均不透明度', round(float(a.mean()), 3),
          '不透明像素', int(bad.sum()))


if __name__ == '__main__':
    main()
