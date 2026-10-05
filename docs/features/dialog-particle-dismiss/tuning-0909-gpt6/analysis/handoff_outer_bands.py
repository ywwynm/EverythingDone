"""控件四条边外侧带的颗粒占用：检验原片是否在所有边（含逆风侧）都向外扩出。

每条边外侧 0.30 短边宽的带，按带内可观测面积归一化；同一观测口径比较原片与模型。
背景无法观测的参考 2／3 改用「与重建背景明显不同且高亮」的颗粒近似，只作方向参考。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from match_observe import SCREEN
from handoff_review import render_all

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
OUT = HERE / 'analysis/match-cache/handoff-review'


def bands(scene, width=.30):
    x, y, x1, y1 = scene.rect; s = scene.span; w = round(s * width)
    H, W = scene.H, scene.W
    out = {}
    for side, (a, b, c, d) in dict(left=(x - w, y, x, y1), right=(x1, y, x1 + w, y1),
                                   top=(x, y - w, x1, y), bottom=(x, y1, x1, y1 + w)).items():
        m = np.zeros((H, W), bool); m[max(0, b):min(H, d), max(0, a):min(W, c)] = True
        out[side] = m
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--phases', type=float, nargs='+', default=[.20, .30, .40, .50, .60, .70])
    ap.add_argument('--variants', nargs='*', default=[])
    a = ap.parse_args()
    variants = [('当前', HERE, '')]
    for s in a.variants:
        label, rest = s.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        variants.append((label, p if p.is_absolute() else HERE / p, spec))
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        region = s.roi.copy()
        if not region.any() and name in SCREEN:
            sx, sy, sx1, sy1 = SCREEN[name]; region[sy:sy1, sx:sx1] = True
        bs = {k: v & region for k, v in bands(s).items()}
        frames = {label: render_all(name, root, label, spec=spec) for label, root, spec in variants}
        wind = np.array([math.cos(math.radians(s.meta['direction'])), -math.sin(math.radians(s.meta['direction']))])
        rows = []
        for p in a.phases:
            i, ref = c.ref.nearest(p)
            row = dict(phase=p)
            for label, img in [('原片', ref)] + [(l, np.asarray(f[round(p * 120)])) for l, f in frames.items()]:
                o = s.observe(np.asarray(img)); par = o['particle']
                row[label] = {k: (float((par & m).sum()) / max(float(m.sum()), 1.)) for k, m in bs.items()}
            rows.append(row)
        report[name] = rows
        upwind = [k for k, n in dict(left=(-1, 0), right=(1, 0), top=(0, -1), bottom=(0, 1)).items() if np.dot(n, wind) < -.3]
        print(f'== {name}  风向 {s.meta["direction"]}°  逆风侧 {upwind}')
        labels = ['原片'] + list(frames)
        print('进度  ' + '  '.join(f'{l}[左/右/上/下]' for l in labels))
        for row in rows:
            print(f'{row["phase"]:.2f}  ' + '  '.join('/'.join(f'{row[l][k]:.3f}' for k in ['left', 'right', 'top', 'bottom']) for l in labels))
    (OUT / 'outer-bands.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
