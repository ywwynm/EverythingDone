"""用原片后段卡片消散后露出的像素重建卡片背后的壁纸，按颜色剔除颗粒样本（参考 3）。

参考 3 的壁纸是橙、黑、白（球面与亮边），颗粒来自卡片，是品红/粉红；两者在颜色上分得开。
逐像素收集「与卡片原图明显不同」（卡片已不在）的帧，丢掉偏品红的样本（颗粒），其余取中位数；
样本不够的像素用几何重建（rebuild_dome_background.py 的结果）补，交界羽化。
产物：assets/<段>/background-clean.png（几何重建先备份为 background-clean-dome.png）。

用法：python analysis/rebuild_revealed_by_color.py <段名> [进度下限] [最少样本数]
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
    name = sys.argv[1]
    lo = float(sys.argv[2]) if len(sys.argv) > 2 else 0.3
    need = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    d = ROOT / 'assets' / name
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    valid = meta['reference'].get('valid_progress', 1.0)
    x0, y0, x1, y1 = meta['rect']
    clean = d / 'background-clean.png'
    dome = d / 'background-clean-dome.png'
    if not dome.exists():
        shutil.copy(clean, dome)
    prior = np.asarray(Image.open(dome).convert('RGB')).astype(np.float32)
    src = np.asarray(Image.open(d / 'source.png').convert('RGB')).astype(np.int16)
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    pad = 6
    ys, xs = slice(max(0, y0 - pad), y1 + pad), slice(max(0, x0 - pad), x1 + pad)
    take = [i for i in range(len(pr)) if lo <= pr[i] <= valid]
    stack = np.stack([np.asarray(fr[i])[ys, xs] for i in take]).astype(np.int16)        # (n, h, w, 3)
    gone = np.abs(stack - src[ys, xs][None]).max(3) > 40
    r, g, b = stack[..., 0], stack[..., 1], stack[..., 2]
    pink = (r > g + 45) & (b > g + 5)                                                    # 品红颗粒
    ok = gone & ~pink
    vals = np.where(ok[..., None], stack, -1).astype(np.float32)
    vals[vals < 0] = np.nan
    cnt = ok.sum(0)
    with np.errstate(all='ignore'):
        med = np.nanmedian(vals, axis=0)
    good = cnt >= need
    med = np.where(good[..., None], np.nan_to_num(med), prior[ys, xs])
    # 去掉零星噪点：小窗中值
    med = cv2.medianBlur(med.clip(0, 255).astype(np.uint8), 5).astype(np.float32)
    w = cv2.GaussianBlur(good.astype(np.float32), (0, 0), 3.0)[..., None]
    region = prior.copy()
    region[ys, xs] = prior[ys, xs] * (1 - w) + med * w
    # 与卡片外的原背景羽化衔接
    edge = np.zeros(prior.shape[:2], np.float32)
    edge[ys, xs] = 1.0
    edge = cv2.GaussianBlur(edge, (0, 0), 2.0)[..., None]
    out = prior * (1 - edge) + region * edge
    Image.fromarray(out.clip(0, 255).astype(np.uint8)).save(clean)
    print(name, '卡片范围像素', int(good.size), '露出且非颗粒样本足够的像素', int(good.sum()), '用到的帧', len(take))


if __name__ == '__main__':
    main()
