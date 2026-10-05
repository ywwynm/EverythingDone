"""弹窗投影检验：卡片边缘外侧一圈（按距边缘分成几档）相对重建背景的亮度差随进度的变化。

若原片弹窗带投影且投影不随粒子消散而立即消失，开场时贴近边缘的一圈会比背景暗，并随进度逐渐恢复；
深色背景上投影不可见。只统计可观测区域中没有颗粒的像素（match_observe 的 particle 之外），只作诊断。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison

LUM = np.float32([.2126, .7152, .0722])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['reference-0926-5', 'spiderman-original', 'kobe', 'reference-0926-1', 'reference-0926-4', 'ironman', 'reference-0926-6'])
    ap.add_argument('--bands', type=int, nargs='+', default=[2, 8, 16, 28, 44])
    ap.add_argument('--phases', type=float, nargs='+', default=[0., .1, .2, .3, .4, .5, .6, .7, .8])
    a = ap.parse_args()
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        x0, y0, x1, y1 = s.rect
        inside = np.zeros(s.roi.shape, np.uint8); inside[y0:y1, x0:x1] = 1
        dist = cv2.distanceTransform(1 - inside, cv2.DIST_L2, 5)
        bgl = s.bg.astype(np.float32) @ LUM
        rows = []
        for lo, hi in zip(a.bands[:-1], a.bands[1:]):
            ring = (dist > lo) & (dist <= hi) & s.roi
            vals = []
            for p in a.phases:
                _, f = c.ref.nearest(p); f = np.asarray(f)
                o = s.observe(f)
                m = ring & ~o['particle']
                vals.append(float(((f.astype(np.float32) @ LUM) - bgl)[m].mean()) if m.sum() > 200 else np.nan)
            rows.append((lo, hi, int(ring.sum()), vals))
        print(f'== {name} 背景亮度中位 {float(np.median(bgl[s.roi & (dist > 0)])):.0f}')
        for lo, hi, n, vals in rows:
            print(f'  距边 {lo:>2}～{hi:>2}px (n={n:6d}): ' + ' '.join(f'{p:.1f}:{v:+5.1f}' for p, v in zip(a.phases, vals)))


if __name__ == '__main__':
    main()
