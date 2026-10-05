"""逐颗粒峰值亮度分布：原片是否存在接近白色的反光亮点，以及它们在相邻帧间是否闪烁。

在已知干净背景的屏幕录制段，取颗粒像素内的局部亮度极大（3×3），统计峰值亮度的分位数与
「近白峰」（三通道最小值 ≥ 200）的比例；再用相邻独立帧比较同一位置附近的峰值变化，估计闪烁。
只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all

OUT = HERE / 'analysis/match-cache/handoff-review'


def peaks(scene, frame, region=None):
    o = scene.observe(frame)
    par = o['particle'] & scene.roi
    if region is not None: par &= region
    lum = frame.astype('float32') @ np.float32([.2126, .7152, .0722])
    pk = (lum >= maximum_filter(lum, size=3)) & par & (o['energy'] > 40)
    ys, xs = np.nonzero(pk)
    return ys, xs, lum[ys, xs], frame[ys, xs].min(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'thanos', 'kobe', 'spiderman-original'])
    ap.add_argument('--phases', type=float, nargs='+', default=[.3, .45, .6])
    ap.add_argument('--variants', nargs='*', default=[])
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label_, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label_, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        frames = {l: render_all(name, root, l, spec=spec) for l, root, spec in variants}
        print(f'== {name}  峰值亮度分位 50/90/99，近白峰比例（三通道最小 ≥200）')
        rows = []
        for p in a.phases:
            i, ref = c.ref.nearest(p)
            out = {}
            for l, img in [('原片', np.asarray(ref))] + [(l, np.asarray(f[round(p * 120)])) for l, f in frames.items()]:
                ys, xs, lum, low = peaks(s, img)
                if len(lum) < 50: out[l] = None; continue
                out[l] = dict(q=np.quantile(lum, [.5, .9, .99]).round(1).tolist(), white=float((low >= 200).mean()), n=int(len(lum)))
            rows.append(dict(phase=p, **out))
            print(f'{p:.2f}  ' + '  '.join(f'{l}: {v["q"]} 白{v["white"]:.3f} n{v["n"]}' if v else f'{l}: -' for l, v in out.items()))
        report[name] = rows
    (OUT / 'glint-stats.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
