"""按源位置分区读回材料：出生、寿命、在指定进度的存活、位移与显示位置。

用于回答「某区域的材料在某时刻去了哪里、是否还活着」。只读，不改模型。
"""
from pathlib import Path
import argparse, math, sys
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
    ap.add_argument('--phases', type=float, nargs='+', default=[.40, .52, .64, .76])
    ap.add_argument('--regions', nargs='+', default=['left:0:.12:.1:.7', 'top:.1:.9:0:.12', 'right:.88:1:.1:.7', 'bottom:.1:.9:.85:1', 'center:.3:.6:.3:.6'],
                    help='名称:x0:x1:y0:y1（控件归一化源位置）')
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    r = cls(a.scene, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[a.scene]], **kw)
    read, close = trajectory_reader(r)
    b = r.base; sx = b[:, 0] / r.cw; sy = b[:, 1] / r.ch; born = b[:, 2]; life = b[:, 6]
    primary = b[:, 3] < r.nx * r.ny
    print(f'{a.scene} spec={a.spec!r} g={r.layout_change:.3f} keep={r.program["conditional_keep"].value:.3f} size={r.program["conditional_size"].value:.3f}')
    regions = []
    for spec in a.regions:
        name, x0, x1, y0, y1 = spec.split(':'); x0, x1, y0, y1 = map(float, (x0, x1, y0, y1))
        m = primary & (sx >= x0) & (sx < x1) & (sy >= y0) & (sy < y1)
        regions.append((name, m))
        print(f'  {name:>6}: 母片 {m.sum():6d} 出生分位 {np.round(np.quantile(born[m], [.1, .5, .9]), 3).tolist()} 寿命分位 {np.round(np.quantile(life[m], [.1, .5, .9]), 3).tolist()}')
    for p in a.phases:
        r.render(p); tr = read()
        age = tr[:, 4]; lif = tr[:, 5]; shown = tr[:, 2:4]
        print(f'-- 进度 {p:.2f}')
        for name, m in regions:
            alive = m & (age > 0) & (age < lif)
            fade_ok = alive & (age < np.maximum(lif - .075, lif * .55))
            d = (shown[alive] - b[alive, :2]) / r.span
            cx = shown[alive, 0] / r.cw; cy = shown[alive, 1] / r.ch
            print(f'  {name:>6}: 已出生 {(m & (age > 0)).sum() / max(m.sum(), 1):.2f} 存活 {alive.sum() / max(m.sum(), 1):.2f} 未开始淡出 {fade_ok.sum() / max(m.sum(), 1):.2f}'
                  + (f' 位移中位 ({np.median(d[:, 0]):+.3f},{np.median(d[:, 1]):+.3f}) 显示位置中位 ({np.median(cx):.2f},{np.median(cy):.2f})' if alive.any() else ''))
    close(); r.close(); ctx.release()


if __name__ == '__main__':
    main()
