"""颗粒密度的集中程度（只作诊断）：颗粒群是「成带、成团、中间有空」还是「均匀一片」。

颗粒掩码（`Scene.observe` 的 particle，限可观测区域；模型帧缺省按原片码率编码）按 8 像素分箱计数，
在颗粒群包络（分箱密度 ≥ 2%）内算：基尼系数（0 = 各处一样密，越大越集中），最密 20% 分箱占总颗粒的比例，
包络内空箱（密度 < 2%）的面积比例不计入（包络本身按平滑后的密度定，见下）。按早 / 中 / 后汇总中位数。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
BINS = [(.1, .25, '早'), (.25, .5, '中'), (.5, .85, '后')]
CELL = 8


def stats(scene, frame):
    par = (scene.observe(frame)['particle'] & scene.roi).astype(np.float32)
    h, w = par.shape[0] // CELL * CELL, par.shape[1] // CELL * CELL
    d = par[:h, :w].reshape(h // CELL, CELL, w // CELL, CELL).mean((1, 3))
    # 包络：σ=3 格平滑后的密度 ≥ 3%，包含颗粒群内部的空处。
    env = cv2.GaussianBlur(d, (0, 0), 3.) >= .03
    if env.sum() < 30 or d[env].sum() < 5: return None
    v = np.sort(d[env]); n = len(v); cum = np.cumsum(v)
    gini = float((n + 1 - 2 * (cum / cum[-1]).sum()) / n)
    top = float(v[-max(1, n // 5):].sum() / v.sum())
    empty = float((v < .02).mean())
    return dict(gini=gini, top20=top, empty=empty)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件；空变体为交接默认）')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--out', default=str(OUT / 'mass-concentration.json'))
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
        c = Comparison(name); s = c.scene; prog = np.asarray(c.ref.progress, float)
        idx = [i for i in c.index if .1 <= prog[i] < .85]
        seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
        for label, root, spec in variants:
            if a.raw:
                fr = render_all(name, root, label, spec=spec); seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(prog[i]) * 120)])))
            else:
                cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        report[name] = {}
        line = f'{name:22s}'
        for label, get in seqs:
            rows = [dict(p=float(prog[i]), **r) for i in idx for r in [stats(s, get(i))] if r]
            summ = {}
            for lo, hi, tag in BINS:
                q = [r for r in rows if lo <= r['p'] < hi]
                if q: summ[tag] = {k: float(np.median([r[k] for r in q])) for k in ['gini', 'top20', 'empty']}
            report[name][label] = summ
            line += f'  {label}: ' + ' '.join(f'{summ[t]["gini"]:.2f}' if t in summ else '-' for _, _, t in BINS)
        print(line, flush=True)
    labels = ['原片'] + [v[0] for v in variants]
    print('== 基尼系数平均（早 / 中 / 后）')
    for label in labels:
        print(f'  {label:6s} ' + ' / '.join(f'{np.mean([report[n][label][t]["gini"] for n in report if t in report[n][label]]):.3f}' for _, _, t in BINS))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
