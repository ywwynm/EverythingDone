"""颗粒相对源色的颜色变换：按源图色相分组，比较原片与模型中同色相颗粒的亮峰颜色。

对每个进度，取可观测区域内的颗粒像素（match_observe 的 particle），用 3×3 局部亮度极大取「亮峰」，
再按亮峰颜色的色相找源图中最接近的色相组（源图按色相分桶的中位色），给出：
  源中位色、原片亮峰中位色、模型亮峰中位色（线性 RGB 的倍率与加性偏移各自估一次）。
只作诊断，不进入运行。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all


def linear(c):
    c = np.asarray(c, np.float32) / 255.
    return np.where(c <= .04045, c / 12.92, ((c + .055) / 1.055) ** 2.4)


def hue_of(rgb):
    hsv = cv2.cvtColor(np.clip(rgb, 0, 255).astype(np.uint8).reshape(-1, 1, 3), cv2.COLOR_RGB2HSV).reshape(-1, 3)
    return hsv[:, 0].astype(np.float32) * 2., hsv[:, 1].astype(np.float32) / 255., hsv[:, 2].astype(np.float32) / 255.


def peaks(scene, frame, box):
    o = scene.observe(frame)
    par = o['particle'] & scene.roi
    if box is not None:
        m = np.zeros_like(par); x0, y0, x1, y1 = box; m[y0:y1, x0:x1] = True; par &= m
    lum = frame.astype(np.float32) @ np.float32([.2126, .7152, .0722])
    pk = (lum >= maximum_filter(lum, size=3)) & par
    return frame[pk].astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='kobe')
    ap.add_argument('--phases', type=float, nargs='+', default=[.3, .35, .4, .45])
    ap.add_argument('--box', type=int, nargs=4)
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--hues', type=float, nargs='+', default=[20, 45, 200, 330], help='源图中关注的色相中心（度）')
    ap.add_argument('--width', type=float, default=14.)
    a = ap.parse_args()
    c = Comparison(a.scene); s = c.scene
    src = np.asarray(s.source, np.float32)
    frames = {}
    for v in a.variants:
        label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        frames[label] = render_all(a.scene, p if p.is_absolute() else HERE / p, label, spec=spec)
    # 源图（卡片内）按色相分桶：只取饱和度 ≥0.25 的像素，给出中位色。
    x0, y0, x1, y1 = s.rect
    card = src[y0:y1, x0:x1].reshape(-1, 3)
    h, sat, val = hue_of(card)
    def group(rgb, hc):
        hh, ss, vv = hue_of(rgb)
        d = np.abs((hh - hc + 180) % 360 - 180)
        return rgb[(d < a.width) & (ss >= .25)]
    for hc in a.hues:
        g = group(card, hc)
        if len(g) < 50: print(f'色相 {hc:.0f}: 源图像素太少'); continue
        sm = np.median(g, 0)
        print(f'== 色相 {hc:.0f}°  源中位 {sm.round(0).tolist()}  源亮端(p90 亮度) {g[np.argsort(g @ [.2126, .7152, .0722])[int(len(g) * .9)]].round(0).tolist()}')
        for p in a.phases:
            _, ref = c.ref.nearest(p)
            rows = [('原片', peaks(s, np.asarray(ref), a.box))]
            rows += [(l, peaks(s, np.asarray(fr[round(p * 120)]), a.box)) for l, fr in frames.items()]
            parts = []
            for l, pk in rows:
                g2 = group(pk, hc)
                if len(g2) < 20: parts.append(f'{l}: n={len(g2)}'); continue
                med = np.median(g2, 0); hi = np.quantile(g2 @ np.float32([.2126, .7152, .0722]), .9)
                gain = linear(med) / np.maximum(linear(sm), 1e-3)
                parts.append(f'{l}: n={len(g2)} 中位{med.round(0).astype(int).tolist()} 亮p90={hi:.0f} 线性倍率{gain.round(2).tolist()}')
            print(f'  {p:.2f}  ' + ' | '.join(parts))


if __name__ == '__main__':
    main()
