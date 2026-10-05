"""颗粒群的致密度（只作诊断，顾问 2026-09-30 提出的口径）：颗粒群是保持致密直到消失，还是先散成稀疏的一层再消失。

颗粒掩码（`Scene.observe` 的 particle，限可观测区域；模型帧缺省按原片码率编码）按 σ = 0.03 短边高斯平滑成密度 D：
- M30：颗粒像素里 D > 0.3 的比例（越高越致密，稀疏的雾接近 0）；
- A08/A30：D > 0.08 与 D > 0.3 的面积之比（外轮廓相对致密核的大小，越大越散）；
- 质量：颗粒像素数 / 短边²。
按早（0.1～0.25）、中（0.25～0.5）、后（0.5～0.85）报中位数；另可按进度点逐个输出（--at）。
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


def measure(scene, frame):
    m = scene.observe(frame)['particle'] & scene.roi
    n = int(m.sum())
    if n < 200: return None
    D = cv2.GaussianBlur(m.astype(np.float32), (0, 0), .03 * scene.span)
    a08 = int((D > .08).sum()); a30 = int((D > .30).sum())
    return dict(m30=float((D[m] > .30).mean()), spread=a08 / max(a30, 1), mass=n / scene.span ** 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件；空变体为交接默认）')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--at', type=float, nargs='*', default=None, help='另按这些进度点逐个输出')
    ap.add_argument('--out', default=str(OUT / 'compactness.json'))
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
        print(f'== {name}：M30 早/中/后 | 散开比 A08/A30 | 质量', flush=True)
        for label, get in seqs:
            rows = [dict(p=float(prog[i]), **r) for i in idx for r in [measure(s, get(i))] if r]
            summ = {}
            for lo, hi, tag in BINS:
                q = [r for r in rows if lo <= r['p'] < hi]
                if q: summ[tag] = {k: float(np.median([r[k] for r in q])) for k in ['m30', 'spread', 'mass']}
            report[name][label] = dict(rows=rows, summary=summ)
            line = f'   {label:6s} ' + '/'.join(f'{summ[t]["m30"]:.2f}' if t in summ else '-' for _, _, t in BINS)
            line += ' | ' + '/'.join(f'{summ[t]["spread"]:.1f}' if t in summ else '-' for _, _, t in BINS)
            line += ' | ' + '/'.join(f'{summ[t]["mass"]:.3f}' if t in summ else '-' for _, _, t in BINS)
            if a.at:
                pts = []
                for p in a.at:
                    near = min(rows, key=lambda r: abs(r['p'] - p)) if rows else None
                    if near and abs(near['p'] - p) < .02: pts.append(f'{near["p"]:.2f}:{near["m30"]:.2f}')
                line += '   ' + ' '.join(pts)
            print(line, flush=True)
    labels = ['原片'] + [v[0] for v in variants]
    print('== 十段平均 M30（早 / 中 / 后）')
    for label in labels:
        print(f'  {label:6s} ' + ' / '.join(f'{np.mean([report[n][label]["summary"][t]["m30"] for n in report if t in report[n][label]["summary"]]):.3f}' for _, _, t in BINS))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
