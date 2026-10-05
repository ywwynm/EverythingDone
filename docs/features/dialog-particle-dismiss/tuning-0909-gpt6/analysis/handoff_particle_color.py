"""原片与模型「颗粒像素」的明度、饱和度分布（与位置是否对准无关）。

颗粒像素用 match_observe 的同一判据（既不像背景也不像原位表面）。按进度段汇总亮度分位数、
相对源图同位置明度的提亮量，以及颗粒像素与局部背景的对比。只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-5', 'reference-0926-6', 'reference-0926-7']
OUT = HERE / 'analysis/match-cache/handoff-review'


def stats(scene, frame):
    o = scene.observe(frame); m = o['particle'] & scene.roi
    if m.sum() < 200: return None
    f = frame.astype('float32') / 255.
    hsv = cv2.cvtColor(frame, cv2.COLOR_RGB2HSV).astype('float32')
    lum = f @ np.float32([.2126, .7152, .0722])
    bg = scene.bg / 255. @ np.float32([.2126, .7152, .0722])
    return dict(lum=np.quantile(lum[m], [.25, .5, .75, .95]).tolist(), sat=float(np.median(hsv[..., 1][m]) / 255.),
                contrast=float(np.median(lum[m] - bg[m])), pixels=int(m.sum()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--phases', type=float, nargs='+', default=[.30, .45, .60])
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        frames = render_all(name, HERE, 'current')
        rows = []
        for p in a.phases:
            _, ref = c.ref.nearest(p)
            r0 = stats(s, np.asarray(ref)); r1 = stats(s, np.asarray(frames[round(p * 120)]))
            rows.append(dict(phase=p, original=r0, model=r1))
            if r0 and r1:
                print(f'{name:>22} {p:.2f}  亮度中位 原{r0["lum"][1]:.3f}/模{r1["lum"][1]:.3f}  95%分位 原{r0["lum"][3]:.3f}/模{r1["lum"][3]:.3f}'
                      f'  饱和度 原{r0["sat"]:.2f}/模{r1["sat"]:.2f}  对背景亮度差 原{r0["contrast"]:+.3f}/模{r1["contrast"]:+.3f}  像素 原{r0["pixels"]}/模{r1["pixels"]}')
        report[name] = rows
    (OUT / 'particle-color.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
