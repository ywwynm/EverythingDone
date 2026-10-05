"""离线测量材料年龄基函数的表达误差，不导出逐片运行控制。

独立拟合每片只能给出表达上限；它不是共同模型，也不是华为运动真值。
在已可见材料上求解，记录所需控制幅度，避免把不受限解当作合格运动。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from material_motion_constraints import trace_path


def basis(age, degree, birth):
    horizon = .65
    a = np.clip(age / horizon, 0., 1.)
    values = []
    if birth:
        young = np.clip(age / .08, 0., 1.)
        values.append(young ** 2 * (3. - 2. * young))
    for k in range(degree + 1):
        integral = np.zeros_like(a)
        for j in range(degree - k + 1):
            integral += (math.comb(degree, k) * math.comb(degree - k, j)
                         * (-1.) ** j * a ** (k + j + 1) / (k + j + 1))
        integral *= horizon
        if k == degree:
            integral += np.maximum(age - horizon, 0.)
        values.append(integral)
    return np.stack(values, -1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman-up-reference', 'reference-0926-2', 'ironman'])
    ap.add_argument('--root', type=Path, default=HERE / 'analysis/match-cache/motion-continuity/all-material-return')
    ap.add_argument('--bounded', action='store_true')
    a = ap.parse_args()
    out = HERE / 'analysis/match-cache/causal-velocity/basis-capacity'
    out.mkdir(parents=True, exist_ok=True)
    report = {}
    for name in a.scenes:
        with np.load(a.root / f'{name}.npz') as saved:
            p = trace_path(saved, maximum=1536, seed=9284)
        physical = (p['query'] * 1.76 - .38) / p['scale']
        delta = p['base'] - physical
        visible = np.pad(p['pair'], ((1, 0), (0, 0))) | np.pad(p['pair'], ((0, 1), (0, 0)))
        rows = []
        for degree, birth in [(2, False), (2, True), (3, True), (5, True)]:
            x = basis(p['age'], degree, birth)
            weighted = x * visible[..., None]
            normal = np.einsum('tnk,tnj->nkj', weighted, x)
            rhs = np.einsum('tnk,tnc->nkc', weighted, delta)
            controls = np.linalg.solve(normal + np.eye(x.shape[-1])[None] * 1e-8, rhs)
            prediction = np.einsum('tnk,nkc->tnc', x, controls)
            error = np.linalg.norm(prediction - delta, axis=-1)[visible]
            aligned = np.stack([controls @ p['wind'], controls @ p['cross']], -1)
            long = aligned[:, int(birth):]
            rows.append(dict(degree=degree, birth_response=birth,
                error_quantiles=np.quantile(error, [.5, .9, .99]).tolist(),
                coefficient_absolute_q95=np.quantile(abs(long), .95, axis=(0, 1)).tolist(),
                axial_floor_violation=float((long[..., 0] < -.10).mean()),
                lateral_limit_violation=float((abs(long[..., 1]) > 1.8).mean())))
        if a.bounded:
            from scipy.optimize import lsq_linear
            x = basis(p['age'], 2, True)
            aligned = np.stack([delta @ p['wind'], delta @ p['cross']], -1)
            ids = np.arange(0, delta.shape[1], max(1, delta.shape[1] // 192))
            for kind in ['previous', 'symmetric-4', 'symmetric-8']:
                errors = []
                for index in ids:
                    keep = visible[:, index]
                    design = x[keep, index]
                    target = aligned[keep, index]
                    solutions = []
                    for component in range(2):
                        limit = 1.8 if kind == 'previous' else float(kind.split('-')[-1])
                        low = -.10 if kind == 'previous' and component == 0 else -limit
                        high = np.inf if kind == 'previous' and component == 0 else limit
                        lower = np.array([-.14, low, low, low])
                        upper = np.array([.14, high, high, high])
                        solution = lsq_linear(design, target[:, component], bounds=(lower, upper), tol=1e-9)
                        solutions.append(solution.x)
                    prediction = design @ np.stack(solutions, -1)
                    errors.extend(np.linalg.norm(prediction - target, axis=-1))
                rows.append(dict(bounds=kind, materials=len(ids), error_quantiles=np.quantile(errors, [.5, .9, .99]).tolist()))
        report[name] = rows
        print(name, json.dumps(rows), flush=True)
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
