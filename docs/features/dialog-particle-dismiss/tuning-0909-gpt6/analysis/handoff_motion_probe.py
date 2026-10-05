"""读回真实 GPU 母片状态，按出生时刻分组统计位移、方向与存续。

用于区分「释放太早」「运动太快」「寿命太短」三种解释；只读，不改模型。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, moderngl

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from diagnose_motion_continuity import trajectory_reader
from handoff_variants import build


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='reference-0926-2')
    ap.add_argument('--spec', default='')
    ap.add_argument('--phases', type=float, nargs='+', default=[.30, .40, .52, .64, .76])
    ap.add_argument('--births', type=float, nargs='+', default=[0., .15, .30, .45, .60])
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    sites = [Site(*s) for s in OBSERVED_SITES[a.scene]]
    r = cls(a.scene, ctx=ctx, quality=2, sites=sites, **kw)
    read, close = trajectory_reader(r)
    src = r.base[:, :2]; born = r.base[:, 2]; life = r.base[:, 6]
    primary = r.base[:, 3] < r.nx * r.ny
    wind = np.array([math.cos(math.radians(r.direction)), -math.sin(math.radians(r.direction))])
    cross = np.array([-wind[1], wind[0]])
    site = np.array([sites[0].x * r.cw, sites[0].y * r.ch])
    radial_dir = src - site; radial_dir /= np.maximum(np.linalg.norm(radial_dir, axis=1, keepdims=True), 1e-6)
    edge = np.minimum(np.minimum(src[:, 0], r.cw - src[:, 0]), np.minimum(src[:, 1], r.ch - src[:, 1])) / r.span
    print(f'{a.scene} spec={a.spec!r} span={r.span} 母片 {primary.sum()} 条件距离 g={r.layout_change:.3f}')
    print('出生分位（母片）', np.round(np.quantile(born[primary], [.05, .25, .5, .75, .95]), 3).tolist(),
          '寿命分位', np.round(np.quantile(life[primary], [.05, .25, .5, .75, .95]), 3).tolist())
    near_edge = edge < .12
    print('距边 <0.12 短边的母片出生分位', np.round(np.quantile(born[primary & near_edge], [.05, .25, .5, .75, .95]), 3).tolist())
    rows = []
    for p in a.phases:
        r.render(p); tr = read()
        integ = tr[:, :2]; shown = tr[:, 2:4]; age = tr[:, 4]; lif = tr[:, 5]
        alive = primary & (age > 0) & (age < lif)
        for lo, hi in zip(a.births[:-1], a.births[1:]):
            m = alive & (born >= lo) & (born < hi)
            if m.sum() < 50: continue
            for label, pos in [('积分', integ), ('显示', shown)]:
                d = (pos[m] - src[m]) / r.span
                rows.append(dict(phase=p, birth=f'{lo:.2f}-{hi:.2f}', kind=label, count=int(m.sum()),
                                 alive_fraction=float(m.sum() / max((primary & (born >= lo) & (born < hi) & (born < p)).sum(), 1)),
                                 disp=float(np.median(np.linalg.norm(d, axis=1))), along=float(np.median(d @ wind)),
                                 cross_abs=float(np.median(np.abs(d @ cross))),
                                 radial=float(np.median(np.sum(d * radial_dir[m], 1)))))
    close(); r.close(); ctx.release()
    print(f'{"进度":>5} {"出生":>9} {"位置":>3} {"数量":>6} {"存活":>5} {"位移":>6} {"顺风":>6} {"横向":>6} {"径向":>6}')
    for q in rows:
        print(f'{q["phase"]:5.2f} {q["birth"]:>9} {q["kind"]:>3} {q["count"]:6d} {q["alive_fraction"]:5.2f} {q["disp"]:6.3f} {q["along"]:6.3f} {q["cross_abs"]:6.3f} {q["radial"]:6.3f}')


if __name__ == '__main__':
    main()
