"""共同流场可见速度修正的多段联合拟合（研究候选，不改 shared/ 资源）。

每段用自己的布局映射（旋转、场尺寸、随机仿射、条件运动系数）把 PIV 残差 Δv 与纹理速度增量 Δg 联系起来：
Δv ≈ M_s(p,t) Δg(uvw)。对每个纹理格点累积加权正规方程 A=Σw·MᵀM、b=Σw·MᵀΔv（三线性散布），
空间／时间平滑后加正则 λI 求解 Δg=(A+λI)⁻¹b。非标准布局的 M 较小（引导乘 0.477），对共同流场的牵引自然较弱。
留出素材（向上参考、内部起点、红色手套）不参与拟合，只用于检验。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, moderngl
from scipy.ndimage import gaussian_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from flow_correction_fit import Mapping, residuals, OUT
from handoff_variants import build
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from unified_model import RULES, guidance
from match_refs import RefFrames

DEFAULT = ['ironman:3', 'thanos:1', 'reference-0926-6:1', 'reference-0926-7:1', 'spiderman-original:1', 'reference-0926-5:1',
           'reference-0926-4:1', 'reference-0926-1:1', 'kobe:1']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec', required=True)
    ap.add_argument('--label', default='flowfix-joint')
    ap.add_argument('--version', type=int, required=True)
    ap.add_argument('--scenes', nargs='+', default=DEFAULT, help='名称:权重')
    ap.add_argument('--gap-progress', type=float, default=.017)
    ap.add_argument('--phases', type=float, nargs=2, default=[.12, .85])
    ap.add_argument('--sigma-space', type=float, default=2.5)
    ap.add_argument('--sigma-time', type=float, default=2.)
    ap.add_argument('--ridge', type=float, default=.3, help='正则（相对单位：按全场 A 的中位迹缩放）')
    ap.add_argument('--base-version', type=int, default=0)
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    T, H, W = int(RULES['flow_time']), int(RULES['flow_height']), int(RULES['flow_width'])
    A = np.zeros((T, H, W, 2, 2)); b = np.zeros((T, H, W, 2)); report = {}
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    for item in a.scenes:
        name, _, wt = item.partition(':'); wt = float(wt or 1)
        r = cls(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **kw)
        try:
            mp = Mapping(r)
        except AssertionError as e:
            print(name, '跳过：', e); r.close(); continue
        r.close()
        prog = np.asarray(RefFrames(name).progress, float)
        gap = max(1, int(round(a.gap_progress / float(np.median(np.diff(prog))))))
        d = residuals(name, a.spec, a.label, gap, a.phases)
        if not len(d): print(name, '无残差样本'); continue
        uvw, M = mp.at(d[:, :2], d[:, 2])
        dv = d[:, 3:5]
        MtM = np.einsum('nki,nkj->nij', M, M); Mtv = np.einsum('nki,nk->ni', M, dv)
        q = np.stack([uvw[:, 2] * T - .5, uvw[:, 1] * H - .5, uvw[:, 0] * W - .5], 1)
        base = np.floor(q).astype(int); f = q - base
        for dt in (0, 1):
            for dy in (0, 1):
                for dx in (0, 1):
                    idx = base + [dt, dy, dx]
                    w = wt * (f[:, 0] if dt else 1 - f[:, 0]) * (f[:, 1] if dy else 1 - f[:, 1]) * (f[:, 2] if dx else 1 - f[:, 2])
                    ok = (idx[:, 0] >= 0) & (idx[:, 0] < T) & (idx[:, 1] >= 0) & (idx[:, 1] < H) & (idx[:, 2] >= 0) & (idx[:, 2] < W)
                    np.add.at(A, (idx[ok, 0], idx[ok, 1], idx[ok, 2]), MtM[ok] * w[ok, None, None])
                    np.add.at(b, (idx[ok, 0], idx[ok, 1], idx[ok, 2]), Mtv[ok] * w[ok, None])
        report[name] = dict(samples=int(len(d)), gap=gap, weight=wt, median_residual=float(np.median(np.hypot(*dv.T)) * .01))
        print(f'{name}: 样本 {len(d)} 间隔 {gap} 残差中位 {report[name]["median_residual"]:.2f} 像素/0.01 进度', flush=True)
    ctx.release()
    sig = (a.sigma_time, a.sigma_space, a.sigma_space)
    A_s = np.stack([np.stack([gaussian_filter(A[..., i, j], sig) for j in range(2)], -1) for i in range(2)], -2)
    b_s = np.stack([gaussian_filter(b[..., k], sig) for k in range(2)], -1)
    tr = np.trace(A_s, axis1=-2, axis2=-1)
    lam = a.ridge * float(np.median(tr[tr > np.quantile(tr, .9)])) if (tr > 0).any() else 1.
    corr = np.linalg.solve(A_s + lam * np.eye(2), b_s[..., None])[..., 0]
    if a.base_version:
        corr += np.load(OUT / f'flow-correction-v{a.base_version}.npy')
    flow = guidance()
    strong = tr > .1 * lam
    print('正则 λ', lam, '修正幅度中位（有数据处）', float(np.median(np.hypot(*corr[strong].T))) if strong.any() else 0.,
          '原流场幅度中位', float(np.median(np.hypot(flow[..., 0], flow[..., 1]))))
    np.save(OUT / f'flow-correction-v{a.version}.npy', corr.astype(np.float32))
    (OUT / f'flow-correction-v{a.version}.json').write_text(json.dumps(dict(spec=a.spec, scenes=report, sigma_space=a.sigma_space,
        sigma_time=a.sigma_time, ridge=a.ridge, lam=lam, base_version=a.base_version), ensure_ascii=False, indent=1), 'utf-8')
    print(OUT / f'flow-correction-v{a.version}.npy')


if __name__ == '__main__':
    main()
