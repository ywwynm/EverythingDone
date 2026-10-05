"""只看清晰可见颗粒的逐帧对照（只作诊断）：可见颗粒区域的交并比、面积比与颗粒群数。

可见颗粒区域的定义同 `handoff_groups.Groups.mask`：偏离背景且偏离原位控件、与背景 Lab 色差 ≥ 22，σ = 0.012 短边平滑后密度 ≥ 0.22，
孤立的淡点不计。逐个原片独立帧（0.1～0.85）计算，模型帧缺省按原片码率编码。按早（0.1～0.25）、中（0.25～0.5）、后（0.5～0.85）报中位数。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded
from handoff_groups import Groups

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
BINS = [(.1, .25, '早'), (.25, .5, '中'), (.5, .85, '后')]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件；空变体为交接默认）')
    ap.add_argument('--contrast', type=float, default=22.)
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--out', default=str(OUT / 'visible-iou.json'))
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
        c = Comparison(name); s = c.scene; prog = np.asarray(c.ref.progress, float); G = Groups(s, a.contrast)
        idx = [i for i in c.index if .1 <= prog[i] < .85]
        ref = {i: G.mask(np.asarray(c.ref.frames[i])) for i in idx}
        report[name] = {}
        line = f'{name:22s}'
        for label, root, spec in variants:
            if a.raw:
                fr = render_all(name, root, label, spec=spec); get = lambda i, fr=fr: np.asarray(fr[round(float(prog[i]) * 120)])
            else:
                cf = coded(name, label, spec, root=root, verbose=False)[0]; get = lambda i, cf=cf: np.asarray(cf[i])
            rows = []
            for i in idx:
                mo = ref[i]; mm = G.mask(get(i)); so = float(mo.sum())
                if so < 50: continue
                rows.append(dict(p=float(prog[i]), iou=float((mo & mm).sum() / max((mo | mm).sum(), 1)), area=float(mm.sum()) / so))
            summ = {}
            for lo, hi, tag in BINS:
                q = [r for r in rows if lo <= r['p'] < hi]
                if q: summ[tag] = dict(iou=float(np.median([r['iou'] for r in q])), area=float(np.median([r['area'] for r in q])))
            report[name][label] = dict(rows=rows, summary=summ)
            line += f'  {label}: ' + '/'.join(f'{summ[t]["iou"]:.2f}' if t in summ else '-' for _, _, t in BINS) \
                    + ' ×' + '/'.join(f'{summ[t]["area"]:.2f}' if t in summ else '-' for _, _, t in BINS)
        print(line, flush=True)
    print('== 十段平均 可见交并比（早 / 中 / 后） | 面积比')
    for label, _, _ in variants:
        iou = [np.mean([report[n][label]['summary'][t]['iou'] for n in report if t in report[n][label]['summary']]) for _, _, t in BINS]
        ar = [np.mean([report[n][label]['summary'][t]['area'] for n in report if t in report[n][label]['summary']]) for _, _, t in BINS]
        print(f'  {label:6s} ' + ' / '.join(f'{x:.3f}' for x in iou) + ' | ' + ' / '.join(f'{x:.2f}' for x in ar))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
