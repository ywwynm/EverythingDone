"""按「距本块解除时刻 τ」分段，统计卡片内平均颜色的变化：朝背景淡出（α）还是朝白色提亮（β）。

每个 10 像素块用各自的解除时刻图（原片、模型各用自己的）；对每段 τ 取块内平均色 f、源图平均色 s、
背景平均色 b，按 f ≈ s + α(b−s) + β(w−s) 最小二乘分解（sRGB 0～255，w 为纯白）。
α 近 1 表示已露出背景；β>0 表示比源色更亮、更白（提亮或辉光）。只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT

BINS = np.round(np.arange(-.06, .2001, .02), 3)


def bands(probe, T, frame_at, phases):
    s = probe.scene; x, y, x1, y1 = probe.rect
    src = s.source[y:y1, x:x1]; bg = s.bg[y:y1, x:x1]
    bh, bw = probe.bh, probe.bw
    def blockmean(a):
        return a[:bh * B, :bw * B].reshape(bh, B, bw, B, -1).mean((1, 3))
    S = blockmean(src); G = blockmean(bg)
    acc = np.zeros((len(BINS), 3, 3)); n = np.zeros(len(BINS))
    ok = probe.valid & np.isfinite(T) & (T < .9)
    for p in phases:
        F = blockmean(frame_at(p)[y:y1, x:x1].astype(np.float32))
        k = np.round((p - T - BINS[0]) / .02)
        k = np.where(np.isfinite(k), k, -1).astype(int)
        good = ok & (k >= 0) & (k < len(BINS))
        for idx in np.unique(k[good]):
            m = good & (k == idx)
            acc[idx, 0] += F[m].sum(0); acc[idx, 1] += S[m].sum(0); acc[idx, 2] += G[m].sum(0); n[idx] += m.sum()
    rows = []
    for i, t in enumerate(BINS):
        if n[i] < 30: rows.append(None); continue
        f, sc, g = acc[i] / n[i]
        A = np.stack([g - sc, 255. - sc], 1)
        (alpha, beta), *_ = np.linalg.lstsq(A, f - sc, rcond=None)
        rows.append(dict(tau=float(t), alpha=float(alpha), beta=float(beta), f=f.round(1).tolist(), s=sc.round(1).tolist(), b=g.round(1).tolist(), n=int(n[i])))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['kobe', 'reference-0926-5', 'reference-0926-1', 'reference-0926-7', 'reference-0926-6', 'ironman', 'thanos', 'spiderman-original', 'reference-0926-4'])
    ap.add_argument('--variants', nargs='*', default=[])
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        probe = Probe(name)
        if not probe.scene.roi.any(): print(name, '背景不可观测，跳过'); continue
        T = probe.huawei(); c = probe.comp
        phases = [float(p) for p in c.ref.progress if .02 <= p <= .9]
        out = {'原片': bands(probe, T, lambda p: np.asarray(c.ref.nearest(p)[1]).astype(np.float32), phases)}
        for label, root, spec in variants:
            frames = render_all(name, root, label, spec=spec)
            import hashlib
            ident = hashlib.sha256(str(getattr(frames, 'filename', label)).encode()).hexdigest()[:12]
            cache = OUT / 'release-maps' / f'{name}-{ident}.npy'
            if cache.is_file(): TM = np.load(cache)
            else:
                class Cached:
                    def render(self, p): return np.asarray(frames[round(np.clip(p, 0, 1) * 120)])
                TM = probe.model(Cached()); cache.parent.mkdir(parents=True, exist_ok=True); np.save(cache, TM)
            out[label] = bands(probe, TM, lambda p: np.asarray(frames[round(np.clip(p, 0, 1) * 120)]).astype(np.float32), phases)
        report[name] = out
        print(f'== {name}  （α：朝背景淡出；β：朝白色提亮）')
        print('τ      ' + '  '.join(f'{l:>14}' for l in out))
        for i, t in enumerate(BINS):
            cells = []
            for l in out:
                r = out[l][i]
                cells.append(f'α{r["alpha"]:+.2f} β{r["beta"]:+.2f}' if r else '      -       ')
            print(f'{t:+.2f}  ' + '  '.join(cells))
    (OUT / 'band-color.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
