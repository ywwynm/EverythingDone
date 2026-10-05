"""沿风向与横风向的颗粒质量时空图（只作诊断）：看轨迹的斜率（速度）、带宽（扩散）与持续时间（寿命）。

逐个原片独立帧（不抽样）：颗粒掩码（`Scene.observe` 的 particle，可观测区域内）上以与背景的 Lab 距离（截到 0～80）为权得到质量，
按沿风向坐标（相对卡片中心、按卡片短边归一化）分箱求和，再除以该箱的可观测面积，得到每个时刻的一条剖面；按时间排成图像。
横风向同理。原片与各变体用同一色标并排；模型帧默认按原片码率编码（recording_codec）。
卡片沿风向的范围画成两条虚线；另把各变体与原片的差（模型 − 原片）画成红蓝图。
"""
from pathlib import Path
import argparse, math, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']


def lab(f):
    return cv2.cvtColor(np.clip(f, 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)


class Axes:
    def __init__(self, s):
        self.s = s
        ang = math.radians(s.meta['direction']); self.wind = np.array([math.cos(ang), -math.sin(ang)])
        self.cross = np.array([-self.wind[1], self.wind[0]])
        x, y, x1, y1 = s.meta['rect']; cx, cy = (x + x1) / 2, (y + y1) / 2
        H, W = s.roi.shape; ys, xs = np.mgrid[:H, :W].astype(np.float32)
        self.along = ((xs - cx) * self.wind[0] + (ys - cy) * self.wind[1]) / s.span
        self.across = ((xs - cx) * self.cross[0] + (ys - cy) * self.cross[1]) / s.span
        corners = np.array([[x, y], [x1, y], [x, y1], [x1, y1]], np.float32) - [cx, cy]
        self.card_along = (corners @ self.wind).min() / s.span, (corners @ self.wind).max() / s.span
        self.card_across = (corners @ self.cross).min() / s.span, (corners @ self.cross).max() / s.span
        self.bgl = lab(s.bg)
        self.edges = np.arange(-1.6, 1.6001, .02)
        self.area_a = np.histogram(self.along[s.roi], self.edges)[0].astype(np.float32)
        self.area_c = np.histogram(self.across[s.roi], self.edges)[0].astype(np.float32)

    def profile(self, frame):
        s = self.s; par = s.observe(frame)['particle'] & s.roi
        m = np.clip(np.linalg.norm(lab(frame) - self.bgl, axis=2), 0, 80) * par
        pa = np.histogram(self.along[par], self.edges, weights=m[par])[0] / np.maximum(self.area_a, 50)
        pc = np.histogram(self.across[par], self.edges, weights=m[par])[0] / np.maximum(self.area_c, 50)
        return pa.astype(np.float32), pc.astype(np.float32)


def run(name, variants, codec=True):
    c = Comparison(name); s = c.scene; ax = Axes(s)
    prog = np.asarray(c.ref.progress, float); use = [i for i in c.index if 0 <= prog[i] <= .95]
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for label, root, spec in variants:
        if codec:
            cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    out = {}
    for label, get in seqs:
        A, C = zip(*[ax.profile(get(i)) for i in use])
        out[label] = (np.stack(A, 1), np.stack(C, 1))
    return ax, prog[use], out


def draw(name, ax, times, out, path, vmax=None):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']; plt.rcParams['axes.unicode_minus'] = False
    labels = list(out); n = len(labels)
    ref_a = out['原片'][0]; vmax = vmax or float(np.percentile(ref_a[ref_a > 0], 97)) if (ref_a > 0).any() else 1.
    fig, axes = plt.subplots(2, 2 * n - 1, figsize=(3.2 * (2 * n - 1), 8.5), squeeze=False)
    e = ax.edges; ext = [times[0], times[-1], e[0], e[-1]]
    for row, (key, lim, title) in enumerate([(0, ax.card_along, '沿风向（正为顺风）'), (1, ax.card_across, '横风向')]):
        ref = out['原片'][key]
        for k, lab_ in enumerate(labels):
            a = axes[row, k]
            a.imshow(out[lab_][key], origin='lower', aspect='auto', extent=ext, cmap='magma', vmin=0, vmax=vmax)
            for v in lim: a.axhline(v, color='c', ls='--', lw=.8)
            a.set_title(f'{lab_} · {title}', fontsize=9); a.set_ylim(-1.2, 1.4) if key == 0 else a.set_ylim(-1.1, 1.1)
            a.set_xlabel('进度')
        for k, lab_ in enumerate(labels[1:]):
            a = axes[row, n + k]
            a.imshow(out[lab_][key] - ref, origin='lower', aspect='auto', extent=ext, cmap='RdBu_r', vmin=-vmax, vmax=vmax)
            for v in lim: a.axhline(v, color='k', ls='--', lw=.8)
            a.set_title(f'{lab_} − 原片', fontsize=9); a.set_ylim(-1.2, 1.4) if key == 0 else a.set_ylim(-1.1, 1.1)
    fig.suptitle(f'{name}：颗粒质量时空图（纵轴为相对卡片中心的位置，单位卡片短边；虚线为卡片范围）', fontsize=11)
    fig.tight_layout(); fig.savefig(path, dpi=70); plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（空变体为交接默认）')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--tag', default='')
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        variants.append((label, root, spec))
    for name in a.scenes:
        ax, times, out = run(name, variants, codec=not a.raw)
        path = OUT / f'kymo{a.tag}-{name}.png'; draw(name, ax, times, out, path)
        ref = out['原片'][0]; w = ref.sum(0)
        line = f'{name}：'
        for lab_ in list(out)[1:]:
            d = np.abs(out[lab_][0] - ref).sum() / max(ref.sum(), 1e-6)
            line += f'  {lab_} 沿风向相对差 {d:.3f}'
        print(line, path, flush=True)
        np.savez_compressed(OUT / f'kymo{a.tag}-{name}.npz', times=times, edges=ax.edges,
                            **{f'{k}_a': v[0] for k, v in out.items()}, **{f'{k}_c': v[1] for k, v in out.items()})


if __name__ == '__main__':
    main()
