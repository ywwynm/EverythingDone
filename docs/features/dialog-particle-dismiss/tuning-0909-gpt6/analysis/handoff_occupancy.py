"""原片与模型的颗粒占用随进度变化（同一观测口径），并按控件内／控件外分开统计。

只统计既不像背景也不像原位表面的像素（match_observe 的 particle），按控件面积归一化。
模型帧来自 handoff_review 的缓存（完整继承链或指定变体）。只作诊断，不参与运行。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
OUT = HERE / 'analysis/match-cache/handoff-review'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='*', default=[], help='标签=配置目录[::变体]')
    ap.add_argument('--stride', type=int, default=4)
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        if not s.roi.any():
            print(name, '无可观测背景，跳过'); continue
        x, y, x1, y1 = s.rect
        inside = np.zeros_like(s.roi); inside[y:y1, x:x1] = True
        area = float(s.card.sum())
        frames = {label: render_all(name, root, label, spec=spec) for label, root, spec in variants}
        rows = []
        for i in c.index[::a.stride]:
            p = float(c.ref.progress[i])
            if p < 0 or p > 1: continue
            row = dict(phase=p)
            for label, get in [('原片', lambda: np.asarray(c.ref.frames[i]))] + [(l, (lambda f=f: np.asarray(f[round(p * 120)]))) for l, f in frames.items()]:
                o = s.observe(get()); par = o['particle'] & s.roi
                row[label] = dict(inside=float((par & inside).sum()) / area, outside=float((par & ~inside).sum()) / area)
            rows.append(row)
        report[name] = rows
        labels = ['原片'] + list(frames)
        print(f'== {name}')
        print('进度  ' + '  '.join(f'{l}:内/外' for l in labels))
        for row in rows:
            print(f'{row["phase"]:.2f}  ' + '  '.join(f'{row[l]["inside"]:.3f}/{row[l]["outside"]:.3f}' for l in labels))
    (OUT / 'occupancy.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
