"""同一小格内颗粒位移的离散程度：读回真实 GPU 母片状态，比较 Δp 内各颗粒位移与所在小格平均位移之差。

用于解释斑点图案为什么很快去相关：若同一位置的颗粒来自不同出生时刻、速度差异大（多股流叠加），
格内离散就大。按年龄分组给出离散，并给出格内年龄跨度。只读，不改模型。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, moderngl

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from diagnose_motion_continuity import trajectory_reader
from handoff_variants import build


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--spec', default='')
    ap.add_argument('--box', type=int, nargs=4, required=True, help='场景像素 x0 y0 x1 y1')
    ap.add_argument('--phases', type=float, nargs='+', default=[.42, .46, .50])
    ap.add_argument('--delta', type=float, default=4 / 120)
    ap.add_argument('--cell', type=float, default=6.)
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    sites = [Site(*s) for s in OBSERVED_SITES[a.scene]]
    r = cls(a.scene, ctx=ctx, quality=2, sites=sites, **kw)
    read, close = trajectory_reader(r)
    meta = json.loads((HERE / 'assets' / a.scene / 'scene.json').read_text('utf-8'))
    ox, oy = meta['rect'][:2]
    primary = r.base[:, 3] < r.nx * r.ny
    x0, y0, x1, y1 = a.box
    print(f'{a.scene} spec={a.spec!r} Δp={a.delta:.4f} 格={a.cell}px')
    for p in a.phases:
        r.render(p); A = read(); r.render(p + a.delta); B = read()
        pa = A[:, 2:4] + [ox, oy]; pb = B[:, 2:4] + [ox, oy]
        vis = lambda T: (T[:, 4] > .01) & (T[:, 4] < T[:, 5] - .02) & (T[:, 6] < .6)
        m = primary & vis(A) & vis(B) & (pa[:, 0] >= x0) & (pa[:, 0] < x1) & (pa[:, 1] >= y0) & (pa[:, 1] < y1)
        if m.sum() < 50: print(f'  {p:.2f}: 颗粒太少 {m.sum()}'); continue
        d = (pb - pa)[m]; age = A[m, 4]
        gx = ((pa[m, 0] - x0) // a.cell).astype(int); gy = ((pa[m, 1] - y0) // a.cell).astype(int)
        key = gy * 10000 + gx
        spread, age_span, resid = [], [], np.zeros(len(d), np.float32); has = np.zeros(len(d), bool)
        for k in np.unique(key):
            idx = np.nonzero(key == k)[0]
            if len(idx) < 4: continue
            dm = d[idx] - d[idx].mean(0)
            spread.append(float(np.sqrt((dm ** 2).sum(1).mean()))); age_span.append(float(np.ptp(age[idx])))
            resid[idx] = np.sqrt((dm ** 2).sum(1)); has[idx] = True
        mean = d.mean(0)
        bins = [(0, .05), (.05, .1), (.1, .2), (.2, .4), (.4, 1)]
        by_age = ' '.join(f'{lo:.2f}-{hi:.2f}:{np.median(resid[has & (age >= lo) & (age < hi)]):.2f}({(has & (age >= lo) & (age < hi)).sum()})'
                          for lo, hi in bins if (has & (age >= lo) & (age < hi)).sum() >= 20)
        print(f'  {p:.2f}: 颗粒{m.sum()} 平均位移({mean[0]:+.1f},{mean[1]:+.1f})px 格内离散中位{np.median(spread):.2f}px '
              f'格内年龄跨度中位{np.median(age_span):.3f}  按年龄的离散 {by_age}', flush=True)
    close(); r.close(); ctx.release()


if __name__ == '__main__':
    main()
