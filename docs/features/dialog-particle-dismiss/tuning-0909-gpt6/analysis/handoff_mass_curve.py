"""画面上颗粒（含未释放表面）的总质量随进度（2026-09-30）：可观测区域内与背景的 Lab 距离之和（截到 80），原片与各变体，逐段。"""
import sys, numpy as np, cv2
from pathlib import Path
HERE = str(Path(__file__).resolve().parents[1])
sys.path[:0] = [HERE, HERE + '/analysis']
from pathlib import Path
from match_compare import Comparison
from handoff_review import render_all
def lab(f): return cv2.cvtColor(np.clip(f, 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)
scenes = sys.argv[1].split(','); cols = [tuple(a.split('=', 1)) for a in sys.argv[2:]]
P = [.30, .40, .50, .60, .70, .80, .90]
# 录像压缩噪声：运动期间静止背景被粗量化，逐像素 Lab 距离有 3～8 的本底；先减掉再累加（2026-09-30）。
FLOOR = 6.
for name in scenes:
    c = Comparison(name); s = c.scene; bgl = lab(s.bg)
    fr = {l: render_all(name, Path(HERE), l, spec=sp) for l, sp in cols}
    def mass(f):
        d = np.clip(np.linalg.norm(lab(np.asarray(f, np.float32)) - bgl, axis=2) - FLOOR, 0, 80); return float(d[s.roi].sum())
    i0, f0 = c.ref.nearest(0.); m0 = mass(np.asarray(f0))
    rows = {'原片': []}; rows.update({l: [] for l, _ in cols})
    for p in P:
        i, ref = c.ref.nearest(p); pp = float(c.ref.progress[i])
        rows['原片'].append(mass(np.asarray(ref)) / m0)
        for l, _ in cols: rows[l].append(mass(np.asarray(fr[l][round(pp * 120)])) / m0)
    print(f'== {name}  （总质量 / 进度 0 时的质量）进度 ' + ' '.join(f'{p:.2f}' for p in P))
    for l, v in rows.items(): print(f'   {l:6s} ' + ' '.join(f'{x:5.2f}' for x in v))
