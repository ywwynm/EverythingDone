"""释放后在原处的颗粒停留：以解除时刻图为基准，统计每个 10 像素块在解除后 τ 时刻的颗粒占用。

原片与模型各用自己的解除时刻图（release_integrity 同口径）。τ 小时的占用反映前沿附近
新颗粒是否成带；随 τ 的衰减反映材料离开原处或淡出的快慢。经过该块的外来颗粒也会计入，
因此大 τ 的数值只作参考。只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all

OUT = HERE / 'analysis/match-cache/handoff-review'
BINS = np.round(np.arange(-.10, .42, .02), 3)


def profile(probe, T, frames_at, phases):
    s = probe.scene; x, y, x1, y1 = probe.rect
    sums = np.zeros(len(BINS)); counts = np.zeros(len(BINS))
    for p in phases:
        f = frames_at(p)
        o = s.observe(f); par = o['particle'][y:y1, x:x1]
        blocks = par[:probe.bh * B, :probe.bw * B].reshape(probe.bh, B, probe.bw, B).mean((1, 3))
        tau = p - T
        ok = probe.valid & np.isfinite(T) & (T < .95)
        k = np.round((tau - BINS[0]) / .02).astype(int)
        good = ok & (k >= 0) & (k < len(BINS))
        np.add.at(sums, k[good], blocks[good]); np.add.at(counts, k[good], 1)
    return sums / np.maximum(counts, 1), counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'ironman-up-reference', 'reference-0926-3', 'reference-0926-1', 'kobe'])
    ap.add_argument('--variants', nargs='*', default=[])
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        probe = Probe(name)
        huawei = probe.huawei()
        phases = [p for p in probe.progress if .05 <= p <= .85]
        c = probe.comp
        ref_prof, ref_n = profile(probe, huawei, lambda p: np.asarray(c.ref.nearest(p)[1]), phases)
        rows = {'原片': ref_prof.tolist()}
        for label, root, spec in variants:
            frames = render_all(name, root, label, spec=spec)
            import hashlib
            # 缓存按帧缓存文件名（已含配置与变体身份）区分，避免同一标签换变体时读到旧图。
            ident = hashlib.sha256(str(getattr(frames, 'filename', label)).encode()).hexdigest()[:12]
            cache = OUT / 'release-maps' / f'{name}-{ident}.npy'
            if cache.is_file():
                T = np.load(cache)
            else:
                # 模型自己的解除时刻图：与原片同一测量，读缓存帧，不重新渲染。
                class Cached:
                    def render(self, p): return np.asarray(frames[round(np.clip(p, 0, 1) * 120)])
                T = probe.model(Cached()); cache.parent.mkdir(parents=True, exist_ok=True); np.save(cache, T)
            prof, _ = profile(probe, T, lambda p: np.asarray(frames[round(np.clip(p, 0, 1) * 120)]), phases)
            rows[label] = prof.tolist()
        report[name] = dict(bins=BINS.tolist(), counts=ref_n.tolist(), profiles=rows)
        print(f'== {name}')
        print('τ      ' + '  '.join(f'{l:>6}' for l in rows))
        for i, t in enumerate(BINS):
            if ref_n[i] < 20: continue
            print(f'{t:+.2f}  ' + '  '.join(f'{rows[l][i]:6.3f}' for l in rows))
    (OUT / 'residence.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
