"""释放过渡期是「整片半透明淡化」还是「颗粒与空隙非此即彼」（只作诊断）。

对每个像素，取源图亮度 s 与背景亮度 b，当前帧亮度 l，归一化位置 u = (l − b)/(s − b)。
- 整片半透明淡化：同一块里的像素都在 s 与 b 之间，u ≈ 平均值，块内 u 的方差小；
- 颗粒与空隙：像素要么接近源色（u≈1）要么接近背景（u≈0），块内 u 的方差大，接近 m(1−m)。
报告混合指数 W = 1 − Var(u) / (m(1−m))（m 为块内 u 的均值），W≈1 为半透明淡化，W≈0 为非此即彼。
只用 |s − b| 足够大（≥40）、按各自解除时刻图处于 τ ∈ [lo, hi) 的 8×8 块，块内平均 m 在 0.2～0.8 之间。
原片与模型各用自己的解除时刻图；模型帧先经录像口径（静态边的 σ + 4:2:0）；变体写成 codec:变体 时改用按原片码率编码后的帧。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release
from recording_sim import chroma420
from recording_codec import coded

LUM = np.float32([.2126, .7152, .0722])
EDGE = OUT / 'edge-blur.json'
BINS = [(-.03, -.01), (-.01, .01), (.01, .03), (.03, .06)]


def sigma(name):
    try:
        d = json.loads(EDGE.read_text('utf-8'))[name]
        return float(d['extra']) if d else .5
    except (OSError, KeyError, TypeError, ValueError):
        return .5


def index(frame, s, b, Tp, p, roi):
    l = np.asarray(frame, np.float32) @ LUM
    d = s - b; ok = (np.abs(d) >= 40) & roi & np.isfinite(Tp)
    u = np.where(ok, (l - b) / np.where(ok, d, 1.), np.nan)
    H, W = u.shape; k = 8; out = {}
    for lo, hi in BINS:
        vals = []
        for y in range(0, H - k, k):
            for x in range(0, W - k, k):
                t = Tp[y:y + k, x:x + k]; uu = u[y:y + k, x:x + k]
                m_ok = np.isfinite(uu) & np.isfinite(t)
                if m_ok.sum() < 40: continue
                tau = p - np.nanmedian(t[m_ok])
                if not (lo <= tau < hi): continue
                v = np.clip(uu[m_ok], -.2, 1.2); m = float(v.mean())
                if not (.2 <= m <= .8): continue
                vals.append(1. - float(v.var()) / (m * (1 - m)))
        out[(lo, hi)] = (float(np.median(vals)), len(vals)) if len(vals) >= 5 else (np.nan, len(vals))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'reference-0926-5', 'kobe', 'thanos', 'reference-0926-1'])
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--phases', type=float, nargs='+', default=[.25, .30, .35, .40, .45, .50, .55, .60])
    a = ap.parse_args()
    for name in a.scenes:
        pr = Probe(name); c = pr.comp; sc = c.scene; x, y, x1, y1 = pr.rect
        s = sc.source.astype(np.float32) @ LUM; b = sc.bg.astype(np.float32) @ LUM
        def expand(T):
            Tp = np.full(sc.roi.shape, np.nan, np.float32)
            Tp[y:y + pr.bh * B, x:x + pr.bw * B] = np.repeat(np.repeat(np.where(pr.valid, T, np.nan), B, 0), B, 1)
            return Tp
        rows = [('原片', expand(pr.huawei()), lambda p: np.asarray(c.ref.nearest(p)[1]))]
        for v in a.variants:
            label, spec = v.split('=', 1)
            codec = spec.startswith('codec:')
            if codec: spec = spec[6:]
            fr = render_all(name, HERE, label, spec=spec)
            T = expand(model_release(pr, name, fr))
            if codec:
                # 按原片码率编码后的模型帧，与原片独立帧一一对应（recording_codec）。
                cf = coded(name, label, spec, verbose=False)[0]
                rows.append((label, T, lambda p, cf=cf: np.asarray(cf[c.ref.nearest(p)[0]])))
            else:
                sig = sigma(name)
                rows.append((label, T, lambda p, fr=fr, sig=sig: chroma420(np.asarray(fr[round(np.clip(p, 0, 1) * 120)]), sig)))
        acc = {lab: {bn: [] for bn in BINS} for lab, _, _ in rows}
        for p in a.phases:
            i, _ = c.ref.nearest(p); pp = float(c.ref.progress[i])
            for lab, Tp, get in rows:
                r = index(get(pp), s, b, Tp, pp, sc.roi)
                for bn, (w, n) in r.items():
                    if np.isfinite(w): acc[lab][bn].append((w, n))
        print(f'== {name}  混合指数 W（1=整片半透明，0=颗粒与空隙非此即彼），按 τ 箱：' + '  '.join(f'[{lo:+.2f},{hi:+.2f})' for lo, hi in BINS), flush=True)
        for lab in acc:
            cells = []
            for bn in BINS:
                v = acc[lab][bn]
                if not v: cells.append('    -     '); continue
                w = np.average([q[0] for q in v], weights=[q[1] for q in v]); n = sum(q[1] for q in v)
                cells.append(f'{w:5.2f}({n:4d})')
            print(f'   {lab:6s} ' + '  '.join(cells), flush=True)


if __name__ == '__main__':
    main()
