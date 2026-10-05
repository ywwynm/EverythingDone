"""颗粒群形状的搬运距离（只作诊断）：同一进度原片与模型的颗粒分布各自归一化后，比较「颗粒在哪里」而不比较「有多少」。

颗粒分布取颗粒掩码（`Scene.observe` 的 particle，限可观测区域）的像素密度，按 4 像素分箱；模型帧缺省先按原片码率编码。
- 切片 Wasserstein-1 距离：32 个方向上一维分布的 W1 平均（短边为单位），相当于把一边的颗粒搬成另一边平均要搬多远；
- 沿风向、横风向两个一维分布的 W1，以及沿风向质心差（带符号，正 = 模型偏顺风）与横风向标准差之比（模型 / 原片）。
按早（0.1～0.25）、中（0.25～0.5）、后（0.5～0.85）汇总，另报总颗粒像素之比（模型 / 原片），把「多少」与「在哪」分开。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
BINS = [(.1, .25, '早'), (.25, .5, '中'), (.5, .85, '后')]
BIN_PX = 4


def w1_1d(xa, wa, xb, wb):
    """两个加权一维分布的 W1（累积分布之差的积分）。"""
    xs = np.concatenate([xa, xb]); order = np.argsort(xs); xs = xs[order]
    ca = np.cumsum(np.concatenate([wa, np.zeros_like(wb)])[order]); cb = np.cumsum(np.concatenate([np.zeros_like(wa), wb])[order])
    return float(np.sum(np.abs(ca[:-1] - cb[:-1]) * np.diff(xs)))


class Dist:
    def __init__(self, scene, wind):
        self.s = scene; self.span = scene.span
        h, w = scene.roi.shape; self.h, self.w = h // BIN_PX * BIN_PX, w // BIN_PX * BIN_PX
        ys, xs = np.mgrid[:self.h // BIN_PX, :self.w // BIN_PX].astype(np.float64)
        self.px = (xs.ravel() + .5) * BIN_PX / self.span; self.py = (ys.ravel() + .5) * BIN_PX / self.span
        self.wind = wind; self.cross = np.array([-wind[1], wind[0]])
        ang = np.linspace(0, math.pi, 32, endpoint=False); self.dirs = np.stack([np.cos(ang), np.sin(ang)], 1)

    def of(self, frame):
        par = (self.s.observe(frame)['particle'] & self.s.roi)[:self.h, :self.w].astype(np.float64)
        d = par.reshape(self.h // BIN_PX, BIN_PX, self.w // BIN_PX, BIN_PX).sum((1, 3)).ravel()
        keep = d > 0
        return self.px[keep], self.py[keep], d[keep], float(par.sum())

    def compare(self, a, b):
        xa, ya, wa, na = a; xb, yb, wb, nb = b
        if na < 300 or nb < 300: return None
        wa = wa / wa.sum(); wb = wb / wb.sum()
        pa = np.stack([xa, ya], 1); pb = np.stack([xb, yb], 1)
        sw = float(np.mean([w1_1d(pa @ d, wa, pb @ d, wb) for d in self.dirs]))
        aa, ab = pa @ self.wind, pb @ self.wind; ca, cb = pa @ self.cross, pb @ self.cross
        ma, mb = float(aa @ wa), float(ab @ wb)
        sa = math.sqrt(max(float(((ca - ca @ wa) ** 2) @ wa), 1e-12)); sb = math.sqrt(max(float(((cb - cb @ wb) ** 2) @ wb), 1e-12))
        return dict(sw=sw, along=w1_1d(aa, wa, ab, wb), cross=w1_1d(ca, wa, cb, wb), shift=mb - ma, spread=sb / sa, amount=nb / na)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件 读取配置；空变体为交接默认）')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--stride', type=int, default=1)
    ap.add_argument('--out', default=str(OUT / 'mass-transport.json'))
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
        variants.append((label, root, spec))
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)])
        dist = Dist(s, wind); prog = np.asarray(c.ref.progress, float)
        idx = [i for i in c.index if .1 <= prog[i] < .85][::a.stride]
        refs = {i: dist.of(np.asarray(c.ref.frames[i])) for i in idx}
        report[name] = {}
        for label, root, spec in variants:
            if a.raw:
                fr = render_all(name, root, label, spec=spec); get = lambda i, fr=fr: np.asarray(fr[round(float(prog[i]) * 120)])
            else:
                cf = coded(name, label, spec, root=root, verbose=False)[0]; get = lambda i, cf=cf: np.asarray(cf[i])
            rows = []
            for i in idx:
                r = dist.compare(refs[i], dist.of(get(i)))
                if r: rows.append(dict(p=float(prog[i]), **r))
            summ = {}
            for lo, hi, tag in BINS:
                q = [r for r in rows if lo <= r['p'] < hi]
                if q: summ[tag] = {k: float(np.median([r[k] for r in q])) for k in ['sw', 'along', 'cross', 'shift', 'spread', 'amount']}
            report[name][label] = dict(rows=rows, summary=summ)
            print(f'{name:22s} {label:6s} ' + '  '.join(
                f'{t}: 搬运{v["sw"]:.3f} 沿{v["along"]:.3f} 横{v["cross"]:.3f} 偏{v["shift"]:+.3f} 宽比{v["spread"]:.2f} 量比{v["amount"]:.2f}'
                for t, v in summ.items()), flush=True)
    labels = [v[0] for v in variants]
    print('== 平均（各段中位数的平均，搬运 / 沿 / 横，短边）')
    for label in labels:
        parts = []
        for _, _, tag in BINS:
            vals = [report[n][label]['summary'][tag] for n in report if tag in report[n][label]['summary']]
            if vals: parts.append(f'{tag} ' + '/'.join(f'{np.mean([v[k] for v in vals]):.3f}' for k in ['sw', 'along', 'cross']))
        print(f'  {label:6s} ' + '  '.join(parts))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
