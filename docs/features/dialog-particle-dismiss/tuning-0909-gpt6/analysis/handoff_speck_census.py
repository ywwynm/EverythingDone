"""颗粒普查：在已知干净背景的屏幕录制段，统计原片与模型的颗粒密度、单颗大小与对背景的对比。

- 颗粒信号：逐像素与干净背景（5×5 包络）的最大通道差；同时不像原位表面（match_observe 的 particle）。
- 颗粒中心：信号在 3×3 内的局部极大且超过阈值；按「云团区域」面积归一化得到密度（每 100 像素²）。
- 单颗大小：以中心为峰，在 7×7 窗口内信号高于峰值一半的像素数（近似半高面积）。
原片与模型同一口径；手持拍屏段有相机模糊，只作参考。只作诊断，不参与运行模型。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter, label

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all

SCREEN = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'reference-0926-5', 'reference-0926-6']
OUT = HERE / 'analysis/match-cache/handoff-review'


def census(scene, frame, threshold=40.):
    o = scene.observe(frame)
    signal = o['energy']
    particle = o['particle'] & scene.roi
    cloud = cv2.dilate(particle.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool) & scene.roi
    peaks = (signal >= maximum_filter(signal, size=3)) & (signal > threshold) & particle
    ys, xs = np.nonzero(peaks)
    if len(ys) < 30 or cloud.sum() < 2000:
        return None
    sizes = []
    for y, x in zip(ys[::max(1, len(ys) // 400)], xs[::max(1, len(xs) // 400)]):
        y0, y1, x0, x1 = max(0, y - 3), y + 4, max(0, x - 3), x + 4
        win = signal[y0:y1, x0:x1] >= .5 * signal[y, x]
        lab, _ = label(win)
        sizes.append(int((lab == lab[y - y0, x - x0]).sum()))
    return dict(density=100. * len(ys) / float(cloud.sum()), size=float(np.median(sizes)), size90=float(np.quantile(sizes, .9)),
                peak=float(np.median(signal[peaks])), fill=float(particle.sum() / cloud.sum()), count=int(len(ys)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCREEN)
    ap.add_argument('--phases', type=float, nargs='+', default=[.2, .3, .4, .5, .6, .7])
    ap.add_argument('--variants', nargs='*', default=[], help='标签=配置目录::变体')
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label_, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label_, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        frames = {l: render_all(name, root, l, spec=spec) for l, root, spec in variants}
        rows = []
        print(f'== {name}  （密度：每 100 像素²颗粒数；大小：半高像素数；填充：颗粒像素占云团比例；峰值：对背景差）')
        for p in a.phases:
            _, ref = c.ref.nearest(p)
            row = dict(phase=p, 原片=census(s, np.asarray(ref)))
            for l, f in frames.items():
                row[l] = census(s, np.asarray(f[round(p * 120)]))
            rows.append(row)
            print(f'{p:.2f}  ' + '  '.join(
                f'{l}: 密{row[l]["density"]:.1f} 大{row[l]["size"]:.0f}/{row[l]["size90"]:.0f} 填{row[l]["fill"]:.2f} 峰{row[l]["peak"]:.0f}' if row[l] else f'{l}: -'
                for l in ['原片'] + list(frames)))
        report[name] = rows
    (OUT / 'speck-census.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
