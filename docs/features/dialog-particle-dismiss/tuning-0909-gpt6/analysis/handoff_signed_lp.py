"""低频亮度的有符号差：模型比原片更亮（红）还是更暗（蓝），按进度给出空间分布与区域均值。

σ=6 高斯模糊后的亮度差（模型 − 原片），只在可观测区域；每个进度一格，另列控件内／外的平均有符号差。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)


def lum(f, sigma):
    return cv2.GaussianBlur(f.astype(np.float32) @ np.float32([.2126, .7152, .0722]), (0, 0), sigma)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--phases', type=float, nargs='+', default=[.2, .3, .4, .5, .6, .7, .8])
    ap.add_argument('--variants', nargs='+', default=['当前=.'])
    ap.add_argument('--sigma', type=float, default=6.)
    ap.add_argument('--scale', type=float, default=.35)
    a = ap.parse_args()
    c = Comparison(a.scene); s = c.scene
    x, y, x1, y1 = s.rect; inside = np.zeros_like(s.roi); inside[y:y1, x:x1] = True
    rows = []
    for v in a.variants:
        label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        frames = render_all(a.scene, p if p.is_absolute() else HERE / p, label, spec=spec)
        cells = []; line = []
        for ph in a.phases:
            _, ref = c.ref.nearest(ph)
            diff = lum(np.asarray(frames[round(ph * 120)]), a.sigma) - lum(np.asarray(ref), a.sigma)
            diff[~s.roi] = 0
            line.append((float(diff[s.roi & inside].mean()), float(diff[s.roi & ~inside].mean()), float(np.abs(diff[s.roi]).mean())))
            rgb = np.zeros(diff.shape + (3,), np.uint8)
            rgb[..., 0] = np.uint8(np.clip(diff / 40, 0, 1) * 255); rgb[..., 2] = np.uint8(np.clip(-diff / 40, 0, 1) * 255)
            rgb[~s.roi] = (40, 40, 48)
            im = Image.fromarray(rgb); im = im.resize((round(im.width * a.scale), round(im.height * a.scale)), Image.Resampling.BILINEAR)
            cells.append((ph, im))
        rows.append((label, cells))
        print(f'{label}: ' + '  '.join(f'{ph:.2f}[内{i:+.1f} 外{o:+.1f} |{m:.1f}|]' for ph, (i, o, m) in zip(a.phases, line)))
    w, h = rows[0][1][0][1].size
    canvas = Image.new('RGB', (len(a.phases) * (w + 4) + 110, len(rows) * (h + 22) + 4), (14, 20, 30)); dr = ImageDraw.Draw(canvas)
    for r, (label, cells) in enumerate(rows):
        dr.text((4, r * (h + 22) + h // 2), label, font=FONT, fill='white')
        for j, (ph, im) in enumerate(cells):
            canvas.paste(im, (110 + j * (w + 4), r * (h + 22) + 20)); dr.text((110 + j * (w + 4) + 3, r * (h + 22) + 2), f'{ph:.2f}', font=FONT, fill='white')
    out = OUT / f'signed-lp-{a.scene}.jpg'; canvas.save(out, quality=92); print(out)


if __name__ == '__main__':
    main()
