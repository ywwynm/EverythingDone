"""刚释放颗粒的颜色变换：按各自的解除时刻图选出释放后 τ 较小的块，只取其中的颗粒亮峰，
逐通道回归「亮峰颜色 ≈ a·源色 + b·背景色 + c」，区分朝白色混合（a<1、c>0）、乘性增益（a>1、c≈0）
与叠加背景（b≈1）三种解释。源色取 σ=2 模糊后的源图，减小颗粒离开原处几像素造成的错位。

原片与模型各用自己的解除时刻图；只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release
from recording_sim import recorded

LUM = np.float32([.2126, .7152, .0722])


def collect(probe, T, frame_at, phases, tau):
    s = probe.scene; x, y, x1, y1 = probe.rect
    src = cv2.GaussianBlur(s.source, (0, 0), 2.)[y:y1, x:x1]; bg = s.bg[y:y1, x:x1]
    bh, bw = probe.bh, probe.bw
    Tp = np.full((y1 - y, x1 - x), np.nan, np.float32)
    Tp[:bh * B, :bw * B] = np.repeat(np.repeat(np.where(probe.valid, T, np.nan), B, 0), B, 1)
    rows = []
    for p in phases:
        f = frame_at(p)
        o = s.observe(f)
        par = (o['particle'] & s.roi)[y:y1, x:x1]
        f = f[y:y1, x:x1].astype(np.float32)
        lum = f @ LUM
        pk = (lum >= maximum_filter(lum, size=3)) & par
        age = p - Tp
        m = pk & np.isfinite(age) & (age >= tau[0]) & (age < tau[1])
        if m.sum() == 0: continue
        rows.append(np.concatenate([f[m], src[m], bg[m]], 1))
    return np.concatenate(rows, 0) if rows else np.zeros((0, 9), np.float32)


def fit(data):
    out = {}
    for c, name in enumerate('RGB'):
        A = np.stack([data[:, 3 + c], data[:, 6 + c], np.ones(len(data))], 1)
        coef, *_ = np.linalg.lstsq(A, data[:, c], rcond=None)
        out[name] = coef.round(3).tolist()
    return out


def summary(data):
    peak, src, bg = data[:, :3], data[:, 3:6], data[:, 6:9]
    pl, sl, bl = peak @ LUM, src @ LUM, bg @ LUM
    sat = lambda a: (a.max(1) - a.min(1)) / np.maximum(a.max(1), 1)
    # 朝白色的比例（按亮度）与饱和度比；源色越暗越能分辨提亮。
    white = (pl - sl) / np.maximum(255. - sl, 1.)
    return dict(n=int(len(data)), peak_lum=float(np.median(pl)), src_lum=float(np.median(sl)), bg_lum=float(np.median(bl)),
                white_med=float(np.median(white)), sat_peak=float(np.median(sat(peak))), sat_src=float(np.median(sat(src))),
                fit=fit(data) if len(data) > 50 else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'kobe', 'thanos', 'spiderman-original', 'reference-0926-5'])
    ap.add_argument('--taus', type=float, nargs='+', default=[0., .03, .06, .1, .16, .25])
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--out', default=str(OUT / 'fresh-color.json'))
    ap.add_argument('--prange', type=float, nargs=2, default=[.05, .85], help='只用这个全局进度范围内的帧')
    ap.add_argument('--sim', action='store_true', help='另加各变体经录像口径（模糊＋4:2:0 色度）处理后的行，标签加「·录」')
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        probe = Probe(name); c = probe.comp
        if not probe.scene.roi.any(): print(name, '背景不可观测，跳过'); continue
        phases = [float(p) for p in c.ref.progress if a.prange[0] <= p <= a.prange[1]]
        ref_at = lambda p: np.asarray(c.ref.nearest(p)[1])
        rows = {'原片': (probe.huawei(), ref_at)}
        for v in a.variants:
            label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); pth = Path(d)
            frames = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            rows[label] = (model_release(probe, name, frames), lambda p, fr=frames: np.asarray(fr[round(np.clip(p, 0, 1) * 120)]))
            if a.sim:
                # 解除时刻图仍用未处理的帧求得，只把取色的帧换成录像口径。
                rows[label + '·录'] = (rows[label][0], lambda p, fr=frames, n=name: recorded(n, np.asarray(fr[round(np.clip(p, 0, 1) * 120)])))
        report[name] = {}
        print('==', name)
        for lo, hi in zip(a.taus[:-1], a.taus[1:]):
            for label, (T, at) in rows.items():
                d = collect(probe, T, at, phases, (lo, hi))
                if len(d) < 30: continue
                sm = summary(d); report[name].setdefault(f'{lo:.2f}-{hi:.2f}', {})[label] = sm
                fitted = ' '.join(f'{k}=[{v[0]:+.2f}src {v[1]:+.2f}bg {v[2]:+.0f}]' for k, v in sm['fit'].items()) if sm['fit'] else ''
                print(f'  τ {lo:.2f}-{hi:.2f} {label:>4}: n={sm["n"]:5d} 峰亮{sm["peak_lum"]:5.1f} 源亮{sm["src_lum"]:5.1f} 背景亮{sm["bg_lum"]:5.1f} '
                      f'朝白{sm["white_med"]:+.2f} 饱和 峰{sm["sat_peak"]:.2f}/源{sm["sat_src"]:.2f}  {fitted}', flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8'); print(a.out)


if __name__ == '__main__':
    main()
