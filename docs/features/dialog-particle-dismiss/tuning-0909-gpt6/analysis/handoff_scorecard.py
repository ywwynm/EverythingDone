"""候选的同口径记分卡：释放后颜色变化（α、β 曲线）、颗粒占用（控件内／外）随进度、四边外侧占用。

只比较与颗粒逐个位置无关的统计量；每项给出与原片的平均绝对差，并与「当前默认」同表对照。
这些数值只用于排序候选与发现退步，最终仍以连续画面目视为准。
"""
from pathlib import Path
import argparse, json, sys, hashlib
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe
from handoff_review import render_all, OUT
from handoff_band_color import bands, BINS
from handoff_outer_bands import bands as outer_bands

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
BAND_OK = {'ironman', 'thanos', 'kobe', 'spiderman-original', 'reference-0926-1', 'reference-0926-4', 'reference-0926-6', 'reference-0926-7'}


def model_release(probe, name, frames):
    ident = hashlib.sha256(str(getattr(frames, 'filename', '')).encode()).hexdigest()[:12]
    cache = OUT / 'release-maps' / f'{name}-{ident}.npy'
    if cache.is_file(): return np.load(cache)
    class Cached:
        def render(self, p): return np.asarray(frames[round(np.clip(p, 0, 1) * 120)])
    T = probe.model(Cached()); cache.parent.mkdir(parents=True, exist_ok=True); np.save(cache, T)
    return T


def lowpass(frame, sigma):
    import cv2
    return cv2.GaussianBlur(frame.astype(np.float32), (0, 0), sigma)


def score(name, frames, probe, ref_cache):
    c = probe.comp; s = probe.scene; out = {}
    at = lambda p: np.asarray(frames[round(np.clip(p, 0, 1) * 120)])
    if s.roi.any():
        # 低频亮度分布：σ=6 与 σ=16 的模糊后逐像素差，只在可观测区域内、进度 0.1～0.9。
        if 'lp' not in ref_cache:
            ref_cache['lp'] = []
            for i in c.index[::3]:
                p = float(c.ref.progress[i])
                if .1 <= p <= .9:
                    f = np.asarray(c.ref.frames[i]); ref_cache['lp'].append((p, lowpass(f, 6), lowpass(f, 16)))
        e6, e16 = [], []
        for p, r6, r16 in ref_cache['lp']:
            f = at(p)
            e6.append(float(np.abs(lowpass(f, 6) - r6).mean(2)[s.roi].mean()))
            e16.append(float(np.abs(lowpass(f, 16) - r16).mean(2)[s.roi].mean()))
        out['lp6'] = float(np.mean(e6)); out['lp16'] = float(np.mean(e16))
    if name in BAND_OK:
        if 'band' not in ref_cache:
            phases = [float(p) for p in c.ref.progress if .02 <= p <= .9]
            ref_cache['band'] = bands(probe, probe.huawei(), lambda p: np.asarray(c.ref.nearest(p)[1]).astype(np.float32), phases)
            ref_cache['phases'] = phases
        mb = bands(probe, model_release(probe, name, frames), lambda p: at(p).astype(np.float32), ref_cache['phases'])
        pairs = [(r, m) for r, m in zip(ref_cache['band'], mb) if r and m and 0 <= r['tau'] <= .2]
        out['alpha'] = float(np.mean([abs(r['alpha'] - m['alpha']) for r, m in pairs]))
        out['beta'] = float(np.mean([abs(r['beta'] - m['beta']) for r, m in pairs]))
    x, y, x1, y1 = s.rect; inside = np.zeros_like(s.roi); inside[y:y1, x:x1] = True; area = float(s.card.sum())
    if s.roi.any():
        if 'occ' not in ref_cache:
            ref_cache['occ'] = {}
            for i in c.index[::6]:
                p = float(c.ref.progress[i])
                if 0 <= p <= 1:
                    o = s.observe(np.asarray(c.ref.frames[i])); par = o['particle'] & s.roi
                    ref_cache['occ'][p] = ((par & inside).sum() / area, (par & ~inside).sum() / area)
        diff_in, diff_out = [], []
        for p, (ri, ro) in ref_cache['occ'].items():
            o = s.observe(at(p)); par = o['particle'] & s.roi
            mi, mo = (par & inside).sum() / area, (par & ~inside).sum() / area
            diff_in.append(abs(mi - ri)); diff_out.append(abs(mo - ro))
        out['occ_in'] = float(np.mean(diff_in)); out['occ_out'] = float(np.mean(diff_out))
        if 'outer' not in ref_cache:
            ref_cache['outer_masks'] = {k: v & s.roi for k, v in outer_bands(s).items()}
            ref_cache['outer'] = {}
            for p in [.3, .45, .6, .75]:
                _, f = c.ref.nearest(p); par = s.observe(np.asarray(f))['particle']
                ref_cache['outer'][p] = {k: float((par & m).sum()) / max(float(m.sum()), 1.) for k, m in ref_cache['outer_masks'].items()}
        errs = []
        for p, ref in ref_cache['outer'].items():
            par = s.observe(at(p))['particle']
            for k, m in ref_cache['outer_masks'].items():
                errs.append(abs(float((par & m).sum()) / max(float(m.sum()), 1.) - ref[k]))
        out['outer'] = float(np.mean(errs))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=配置目录[::变体]')
    ap.add_argument('--out', default=str(OUT / 'scorecard.json'))
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for v in a.variants:
        label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label, p if p.is_absolute() else HERE / p, spec))
    table = {}
    for name in a.scenes:
        probe = Probe(name); ref_cache = {}
        table[name] = {}
        for label, root, spec in variants:
            frames = render_all(name, root, label, spec=spec)
            table[name][label] = score(name, frames, probe, ref_cache)
        print(f'== {name}')
        for label in table[name]:
            print(f'  {label:>12}: ' + '  '.join(f'{k} {v:.4f}' for k, v in table[name][label].items()), flush=True)
    keys = ['lp6', 'lp16', 'alpha', 'beta', 'occ_in', 'occ_out', 'outer']
    print('== 平均（各序列等权）')
    for label, _, _ in variants:
        row = []
        for k in keys:
            vals = [table[n][label][k] for n in table if k in table[n][label]]
            row.append(f'{k} {np.mean(vals):.4f}' if vals else f'{k} -')
        print(f'  {label:>12}: ' + '  '.join(row))
    Path(a.out).write_text(json.dumps(table, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
