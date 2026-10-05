"""用原片里卡片消散后露出的像素重建卡片背后的壁纸。

参考 2、3 的视频在颗粒散尽前截断，卡片背后的壁纸从没有完整露出过，`background.png` 在卡片范围内是按行外推的，
画面上是一道道横向条纹；参考 3 的壁纸其实是上下两个带橙色亮边的球面，颗粒散开以后在原片里看得很清楚。

做法：逐像素收集「与卡片原图明显不同」（卡片已经消散）的帧；颗粒会在画面上留下细碎的高频纹理，壁纸是平滑渐变，
所以在这些帧里挑局部高频能量最低（最平滑）的几帧取中位数——参考 3 的红色颗粒在很多帧里都经过同一处，
直接取中位数会把颗粒染进壁纸。露出且足够平滑的帧不够的像素用 Telea 修补，并与原背景在边缘羽化衔接。
产物 assets/<段>/background-clean.png（原文件已存在时先备份为 background-clean-before-reveal.png）。

用法：python analysis/rebuild_revealed_background.py <段名> [进度下限 进度上限 最少帧数]
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
    hi = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
    need = int(sys.argv[4]) if len(sys.argv) > 4 else 6
    d = ROOT / 'assets' / name
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    valid = meta['reference'].get('valid_progress', 1.0)
    hi = min(hi, valid)
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    clean = d / 'background-clean.png'
    if clean.exists() and not (d / 'background-clean-before-reveal.png').exists():
        shutil.copy(clean, d / 'background-clean-before-reveal.png')
    base = np.asarray(Image.open(d / 'background-clean-before-reveal.png' if (d / 'background-clean-before-reveal.png').exists()
                                 else d / 'background.png').convert('RGB'))
    src = np.asarray(Image.open(d / 'source.png').convert('RGB')).astype(np.int16)
    x0, y0, x1, y1 = meta['rect']
    pad = 6
    ys, xs = slice(max(0, y0 - pad), y1 + pad), slice(max(0, x0 - pad), x1 + pad)
    take = [i for i in range(len(pr)) if lo <= pr[i] <= hi]
    stack = np.stack([np.asarray(fr[i])[ys, xs] for i in take]).astype(np.int16)       # (n, h, w, 3)
    gone = np.abs(stack - src[ys, xs][None]).max(3) > 40                                 # 卡片已不在
    # 局部高频能量：颗粒是细碎的点，壁纸是平滑渐变
    hf = []
    for f in stack.astype(np.float32):
        e = np.abs(f - cv2.GaussianBlur(f, (0, 0), 1.5)).max(2)
        hf.append(cv2.GaussianBlur(e, (0, 0), 3.0))
    hf = np.stack(hf)
    hf[~gone] = np.inf
    k = 3
    order = np.argsort(hf, axis=0)[:k]                                                   # 每个像素最平滑的 k 帧
    pick = np.take_along_axis(stack, order[..., None].repeat(3, -1), axis=0).astype(np.float32)
    best_hf = np.take_along_axis(hf, order, axis=0)
    usable = np.isfinite(best_hf)
    pick[~usable] = np.nan
    med = np.nanmedian(pick, axis=0)
    ok = (usable.sum(0) >= min(k, need)) & (gone.sum(0) >= need) & (best_hf[0] < 5.0)
    med = np.where(ok[..., None], np.nan_to_num(med), 0).astype(np.uint8)
    region = np.zeros(base.shape[:2], bool)
    region[ys, xs] = True
    known = np.zeros(base.shape[:2], bool)
    known[ys, xs] = ok
    out = base.copy()
    out[known] = med[ok]
    # 卡片范围内没露出的像素：修补
    hole = (region & ~known).astype(np.uint8)
    out = cv2.inpaint(out, hole, 7, cv2.INPAINT_TELEA)
    # 与原背景羽化衔接：卡片外扩 pad 以外保持原样
    w = cv2.GaussianBlur(region.astype(np.float32), (0, 0), 2.0)[..., None]
    out = (base * (1 - w) + out * w + 0.5).clip(0, 255).astype(np.uint8)
    Image.fromarray(out).save(clean)
    print(name, '卡片范围像素', int(region.sum()), '露出足够帧的像素', int(known.sum()), '修补像素', int(hole.sum()),
          '用到的帧', len(take), f'进度 {lo}～{hi:.3f}')


if __name__ == '__main__':
    main()
