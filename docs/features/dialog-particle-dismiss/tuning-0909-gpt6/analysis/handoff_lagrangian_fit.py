"""原版钢铁侠实际材料轨迹能在多大程度上由「前沿相对量」解释（诊断，不进入运行）。

读回真实 GPU 母片的积分位置与显示位置，按年龄取位移（顺风／横风分量，以短边归一化），
分别用三组输入做同一回归并比较留出解释度：
  A 前沿相对：年龄、出生时刻、出生处前沿法向相对风向的角度、前沿推进速度；
  B 在 A 上加源位置（风向坐标）——用于衡量布局特有结构；
  C 只有年龄和源位置。
解释度用留出材料计算；B 明显高于 A 的部分，是随布局而变、不能直接平移到其它布局的运动。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, moderngl
from scipy.ndimage import gaussian_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from diagnose_motion_continuity import trajectory_reader
from handoff_variants import build

OUT = HERE / 'analysis/match-cache/handoff-review'


def features(r, which):
    b = r.base; span = r.span
    wind = np.array([math.cos(math.radians(r.direction)), -math.sin(math.radians(r.direction))])
    cross = np.array([-wind[1], wind[0]])
    n = b[:, 4:6]; T = b[:, 2]
    theta_c = n @ wind; theta_s = n @ cross
    # 出生处前沿推进速度：由释放场梯度得到（短边/单位时间），与布局无关的局部量。
    nx, ny = r.nx, r.ny; ids = b[:, 3].astype(int) % (nx * ny)
    field = np.zeros(nx * ny, np.float32); field[ids] = T; field = field.reshape(ny, nx)
    gy, gx = np.gradient(gaussian_filter(field, 2), r.cell[1] / span, r.cell[0] / span)
    speed = 1. / np.maximum(np.hypot(gx, gy), .3)
    s0 = speed.ravel()[ids]
    q = (b[:, :2] - np.array([r.cw, r.ch]) * .5) / span
    u = q @ wind; v = q @ cross
    cols = dict(A=[T, theta_c, theta_s, np.log(s0)], B=[T, theta_c, theta_s, np.log(s0), u, v], C=[u, v])[which]
    return np.stack(cols, 1).astype(np.float32)


def fit_score(X, Y, holdout):
    """小型随机特征岭回归：非线性、可复现，比较三组输入的相对解释度即可。"""
    rng = np.random.default_rng(0)
    mu = X[~holdout].mean(0); sd = X[~holdout].std(0) + 1e-6; Z = (X - mu) / sd
    W = rng.normal(size=(Z.shape[1], 600)) * .9; bias = rng.uniform(-np.pi, np.pi, 600)
    H = np.concatenate([np.cos(Z @ W + bias), Z, np.ones((len(Z), 1))], 1)
    Htr = H[~holdout]; A = Htr.T @ Htr + 1e-2 * np.eye(H.shape[1])
    coef = np.linalg.solve(A, Htr.T @ Y[~holdout])
    pred = H[holdout] @ coef
    res = ((Y[holdout] - pred) ** 2).sum(0); tot = ((Y[holdout] - Y[holdout].mean(0)) ** 2).sum(0)
    return (1 - res / tot).tolist()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--ages', type=float, nargs='+', default=[.05, .10, .15, .20, .25, .30, .35])
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, '')
    r = cls(a.scene, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[a.scene]], **kw)
    read, close = trajectory_reader(r)
    phases = np.arange(0, 1.0001, 1 / 60)
    traces = []
    for p in phases:
        r.render(float(p)); traces.append(read()[:, :4].copy())
    tr = np.array(traces)                                  # (T, N, 4)：积分 xy、显示 xy
    b = r.base; primary = b[:, 3] < r.nx * r.ny; born = b[:, 2]; life = b[:, 6]
    wind = np.array([math.cos(math.radians(r.direction)), -math.sin(math.radians(r.direction))])
    cross = np.array([-wind[1], wind[0]])
    rng = np.random.default_rng(1)
    report = {}
    for which_pos, sl in [('积分', slice(0, 2)), ('显示', slice(2, 4))]:
        rows = {}
        for age in a.ages:
            t = born + age
            ok = primary & (t <= 1.) & (age < life)
            k = np.clip(np.round(t * 60).astype(int), 0, len(phases) - 1)
            d = (tr[k[ok], np.flatnonzero(ok), sl] - b[ok, :2]) / r.span
            Y = np.stack([d @ wind, d @ cross], 1)
            hold = rng.random(ok.sum()) < .25
            scores = {w: fit_score(features(r, w)[ok], Y, hold) for w in 'ABC'}
            rows[f'{age:.2f}'] = dict(count=int(ok.sum()), along_std=float(Y[:, 0].std()), across_std=float(Y[:, 1].std()), **scores)
            print(f'{which_pos} 年龄 {age:.2f} 材料 {ok.sum():6d} 顺风σ {Y[:, 0].std():.3f} 横风σ {Y[:, 1].std():.3f}  '
                  + '  '.join(f'{w}: 顺{scores[w][0]:.2f}/横{scores[w][1]:.2f}' for w in 'ABC'), flush=True)
        report[which_pos] = rows
    close(); r.close(); ctx.release()
    (OUT / f'lagrangian-fit-{a.scene}.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
