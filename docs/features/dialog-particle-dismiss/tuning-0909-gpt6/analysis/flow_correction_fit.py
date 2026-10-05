"""共同流场的可见速度修正（研究候选，不改 shared/ 资源）。

在标准布局（原版钢铁侠）上，对原片与模型各做 PIV，取可见速度残差 Δv（像素 / 单位进度）；
按 renderer.py::guided_flow 同一套空间、时间变换与逆雅可比，把 Δv 换算成共同流场纹理坐标中的速度增量，
三线性散布到 48×64×64 网格，按权重归一并做空间／时间高斯平滑，存为 flow-correction-v<版本>.npy。
运行时由变体 `flowfix=<倍率>,flowfix-v=<版本>` 加到共同流场上，所有布局共用同一份修正场。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, moderngl
from scipy.ndimage import gaussian_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all
from handoff_variants import build
from handoff_piv import hp, piv
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from unified_model import RULES, guidance

OUT = HERE / 'analysis/match-cache/flow-correction'


class Mapping:
    """numpy 版 guided_flow：场景像素 p（卡片坐标）与时刻 t → 纹理坐标 uvw 与 纹理速度→屏幕速度 的 2×2 矩阵。"""
    def __init__(self, r):
        u = lambda k: np.array(r.compute[k].value, np.float64)
        self.card = u('card'); self.fg = u('field_geometry'); self.rot = u('guide_rotation')
        self.A = u('variation_affine'); self.Bv = u('variation_bend'); self.W = u('variation_wave'); self.C = u('variation_clock')
        reach = 1 - math.exp(-.65 / .85)
        self.gain = float(r.compute['guide_gain'].value) / .9 * (1.08 + .12 * reach)
        motion = r.compute['conditional_motion'].value if 'conditional_motion' in r.compute else 1.
        self.gain *= float(motion)
        assert self.fg[2] < 1e-5, '当前只支持单一局部锚点的布局'

    def at(self, p, t):
        ca, sa = self.rot
        cq = (p - self.card / 2) / self.fg[:2]
        local = np.stack([ca * cq[:, 0] - sa * cq[:, 1], sa * cq[:, 0] + ca * cq[:, 1]], 1)
        wave = np.stack([self.W[1] * local[:, 1] + self.W[2], self.W[0] * local[:, 0] + self.W[3]], 1)
        lookup = .5 + self.A[:2] * local + self.A[2:] * local[:, ::-1] + self.Bv[:2] + self.Bv[2:] * np.sin(wave)
        ft = t + t * (1 - t) * (self.C[0] + self.C[1] * (2 * t - 1))
        rate = 1 + self.C[0] * (1 - 2 * t) + self.C[1] * (-6 * t * t + 6 * t - 1)
        uvw = np.concatenate([(lookup + .45) / 1.9, ft[:, None]], 1)
        cross = self.A[2:] + self.Bv[2:] * self.W[[1, 0]] * np.cos(wave)
        det = self.A[0] * self.A[1] - cross[:, 0] * cross[:, 1]
        # 纹理速度 g → 屏幕速度：先逆雅可比、乘时钟速率，再旋转、乘场尺寸与增益。
        J = np.zeros((len(p), 2, 2))
        J[:, 0, 0] = self.A[1] / det; J[:, 0, 1] = -cross[:, 0] / det
        J[:, 1, 0] = -cross[:, 1] / det; J[:, 1, 1] = self.A[0] / det
        J *= rate[:, None, None]
        R = np.array([[ca, sa], [-sa, ca]])
        M = (self.fg[:2, None] * R)[None] @ J * self.gain
        return uvw, M


def residuals(scene, spec, label, gap, phases):
    c = Comparison(scene); s = c.scene
    frames = render_all(scene, HERE, label, spec=spec)
    x0, y0 = s.rect[:2]
    roi = s.roi.astype(np.uint8)
    prog = np.asarray(c.ref.progress, float)
    rows = []
    for i in range(len(prog) - gap):
        pa, pb = float(prog[i]), float(prog[i + gap])
        if not (phases[0] <= pa <= phases[1]): continue
        R = piv(hp(c.ref.frames[i]), hp(c.ref.frames[i + gap]), roi, 24, 12, 16, .45, 6.)
        k0 = round(pa * 120); k1 = k0 + max(1, round((pb - pa) * 120)); dm = (k1 - k0) / 120
        M = piv(hp(frames[k0]), hp(frames[min(k1, 120)]), roi, 24, 12, 16, .45, 6.)
        mod = {(int(x), int(y)): (dx, dy) for x, y, dx, dy, _ in M}
        for x, y, dx, dy, _ in R:
            key = (int(x), int(y))
            if key not in mod: continue
            vo = np.array([dx, dy]) / (pb - pa); vm = np.array(mod[key]) / dm
            if max(np.hypot(*vo), np.hypot(*vm)) * .01 < .3: continue      # 两边都几乎不动的块（静止表面或背景）
            rows.append((x - x0, y - y0, pa, *(vo - vm)))
    return np.array(rows, np.float64)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec', required=True, help='计算残差用的模型变体（不含 flowfix）')
    ap.add_argument('--label', default='flowfix-base')
    ap.add_argument('--version', type=int, required=True)
    ap.add_argument('--gap', type=int, default=2)
    ap.add_argument('--phases', type=float, nargs=2, default=[.12, .85])
    ap.add_argument('--sigma-space', type=float, default=1.5, help='纹理格点单位')
    ap.add_argument('--sigma-time', type=float, default=1.5)
    ap.add_argument('--prior', type=float, default=3., help='权重先验：格点权重 w 的修正乘 w/(w+prior)')
    ap.add_argument('--base-version', type=int, default=0, help='在已有修正版本上累加（迭代）')
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    r = cls('ironman', ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES['ironman']], **kw)
    mp = Mapping(r); r.close(); ctx.release()
    d = residuals('ironman', a.spec, a.label, a.gap, a.phases)
    print('残差样本', len(d), '中位 |Δv|', np.median(np.hypot(d[:, 3], d[:, 4])) * .01, '像素/0.01 进度')
    uvw, M = mp.at(d[:, :2], d[:, 2])
    dg = np.linalg.solve(M, d[:, 3:5][..., None])[..., 0]
    T, H, W = int(RULES['flow_time']), int(RULES['flow_height']), int(RULES['flow_width'])
    acc = np.zeros((T, H, W, 2)); wsum = np.zeros((T, H, W))
    q = np.stack([uvw[:, 2] * T - .5, uvw[:, 1] * H - .5, uvw[:, 0] * W - .5], 1)
    base = np.floor(q).astype(int); f = q - base
    for dt in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                idx = base + [dt, dy, dx]
                w = (f[:, 0] if dt else 1 - f[:, 0]) * (f[:, 1] if dy else 1 - f[:, 1]) * (f[:, 2] if dx else 1 - f[:, 2])
                ok = (idx[:, 0] >= 0) & (idx[:, 0] < T) & (idx[:, 1] >= 0) & (idx[:, 1] < H) & (idx[:, 2] >= 0) & (idx[:, 2] < W)
                np.add.at(acc, (idx[ok, 0], idx[ok, 1], idx[ok, 2]), dg[ok] * w[ok, None])
                np.add.at(wsum, (idx[ok, 0], idx[ok, 1], idx[ok, 2]), w[ok])
    sig = (a.sigma_time, a.sigma_space, a.sigma_space)
    acc_s = np.stack([gaussian_filter(acc[..., k], sig) for k in range(2)], -1)
    w_s = gaussian_filter(wsum, sig)
    corr = acc_s / np.maximum(w_s, 1e-9)[..., None] * (w_s / (w_s + a.prior))[..., None]
    if a.base_version:
        corr += np.load(OUT / f'flow-correction-v{a.base_version}.npy')
    flow = guidance()
    print('修正幅度中位（有权重处）', float(np.median(np.hypot(*corr[w_s > .5].T))) if (w_s > .5).any() else 0.,
          '原流场幅度中位', float(np.median(np.hypot(flow[..., 0], flow[..., 1]))))
    np.save(OUT / f'flow-correction-v{a.version}.npy', corr.astype(np.float32))
    (OUT / f'flow-correction-v{a.version}.json').write_text(json.dumps(dict(spec=a.spec, samples=int(len(d)), gap=a.gap, phases=a.phases,
        sigma_space=a.sigma_space, sigma_time=a.sigma_time, prior=a.prior, base_version=a.base_version), ensure_ascii=False, indent=1), 'utf-8')
    print(OUT / f'flow-correction-v{a.version}.npy')


if __name__ == '__main__':
    main()
