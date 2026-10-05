"""云团时序观测量（同 check_cloud_evolution.py 的口径）：占用图误差、质心、顺风前后沿与横向展宽相对原片的误差。

模型帧来自 handoff_review 的缓存（handoff_variants 变体），适合比较多个研究变体；长度单位为卡片短边。只作诊断。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1', 'reference-0926-4',
          'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
METRICS = ['cloud_map', 'centroid', 'front', 'back', 'width']


def measure(scene, f, wind, cross):
    o = scene.observe(f); par = o['particle'] & scene.roi; y, x = np.nonzero(par)
    occ, weight = scene.blocks(par); pos = np.stack([x, y], -1).astype('float32') / scene.span
    geo = None
    if len(x) >= 200:
        along = pos @ wind; across = pos @ cross
        geo = dict(centroid=pos.mean(0), front=float(np.quantile(along, .95)), back=float(np.quantile(along, .05)),
                   width=float(np.quantile(across, .95) - np.quantile(across, .05)))
    return occ, weight > .5, geo


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True)
    ap.add_argument('--out', default=str(OUT / 'cloud.json'))
    a = ap.parse_args()
    report = {}; total = {}
    for name in a.scenes:
        c = Comparison(name, stride=3); s = c.scene
        ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)]); cross = np.array([-wind[1], wind[0]])
        refs = {}
        for i in c.index:
            p = float(c.ref.progress[i])
            if 0 <= p <= 1: refs[p] = measure(s, np.asarray(c.ref.frames[i]), wind, cross)
        report[name] = {}
        for v in a.variants:
            label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); pth = Path(d)
            frames = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            errs = {m: [] for m in METRICS}; signed = {k: [] for k in ['front', 'back', 'width', 'occ']}
            for p, (ro, w, rg) in refs.items():
                mo, _, mg = measure(s, np.asarray(frames[round(p * 120)]), wind, cross)
                errs['cloud_map'].append(float(np.abs(mo - ro)[w].mean())); signed['occ'].append(float((mo - ro)[w].mean()))
                if rg is not None and mg is not None:
                    errs['centroid'].append(float(np.linalg.norm(mg['centroid'] - rg['centroid'])))
                    for k in ['front', 'back', 'width']: errs[k].append(abs(mg[k] - rg[k])); signed[k].append(mg[k] - rg[k])
            r = {m: float(np.mean(e)) for m, e in errs.items() if e}
            r.update({f'{k}_signed': float(np.mean(e)) for k, e in signed.items() if e})
            report[name][label] = r; total.setdefault(label, []).append(r)
            print(f'{name:22s} {label:>6}: ' + '  '.join(f'{m} {r.get(m, float("nan")):.4f}' for m in METRICS)
                  + '  | 有符号 ' + ' '.join(f'{k} {r.get(k + "_signed", float("nan")):+.4f}' for k in ['occ', 'front', 'back', 'width']), flush=True)
    print('== 平均')
    for label, rows in total.items():
        print(f'{label:>6}: ' + '  '.join(f'{m} {np.mean([r[m] for r in rows if m in r]):.4f}' for m in METRICS))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
