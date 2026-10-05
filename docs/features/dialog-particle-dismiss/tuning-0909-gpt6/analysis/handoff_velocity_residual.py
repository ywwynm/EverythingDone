"""速度残差回归（只作诊断）：原片与模型在同一格点、同一进度的颗粒图案速度之差，能否由少数几种共同的速度场解释。

对每段、每个进度对，原片与模型分别做 PIV（`handoff_piv.piv`，高通亮度块互相关），取两边都有值、至少一边在动的格点，
残差 r = v原片 − v模型（卡片短边 / 单位进度）。候选基场在格点处计算（都只依赖于起点、方向、卡片几何与释放后时间 τ，
τ 由原片解除时刻图给出）：
- radial：背离最近已启动起点的单位向量 × 年龄包络 e(τ)=exp(−τ/0.15)
- swirl：绕最近起点逆时针的切向单位向量 × e(τ)
- wind：风向单位向量（常数）；wind_age：风向 × τ
- cross：横风向单位向量（指向风向的左侧）
- card_radial：背离卡片中心、按距离（短边）线性增长（整体膨胀）
按段分别回归与十段联合回归，报系数与解释的残差比例（1 − |r − Bβ|² / |r|²）。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_piv import hp, piv
from v2_inputs import SITES

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'reference-0926-6', 'reference-0926-5',
          'ironman-up-reference', 'reference-0926-1', 'reference-0926-4', 'reference-0926-7']
NAMES = ['radial', 'swirl', 'wind', 'wind_age', 'cross', 'card_radial']


def collect(name, spec, root=HERE, label='P', phases=None, gap=.017):
    pr = Probe(name); c = pr.comp; s = pr.scene; T = pr.huawei()
    x, y, x1, y1 = s.meta['rect']; cw, ch = x1 - x, y1 - y; span = s.span; cx, cy = (x + x1) / 2, (y + y1) / 2
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)]); cross = np.array([wind[1], -wind[0]])
    sites = [(x + a * cw, y + b * ch, st) for a, b, st in SITES[name]]
    frames = render_all(name, root, label, spec=spec)
    prog = np.asarray(c.ref.progress, float)
    roi = s.roi.astype(np.uint8)
    rows = []
    for p in (phases or np.arange(.10, .72, .04)):
        i = int(np.argmin(abs(prog - p))); pa = float(prog[i])
        j = int(np.argmin(abs(prog - (pa + gap)))); j = max(j, i + 1); pb = float(prog[j]); dp = pb - pa
        R = piv(hp(c.ref.frames[i]), hp(c.ref.frames[j]), roi, 24, 16, 16, .45, 6.)
        k0 = round(pa * 120); k1 = k0 + max(1, round(dp * 120)); dm = (k1 - k0) / 120
        M = piv(hp(frames[k0]), hp(frames[min(k1, 120)]), roi, 24, 16, 16, .45, 6.)
        if not len(R) or not len(M): continue
        mod = {(int(a), int(b)): (dx / dm / span, dy / dm / span) for a, b, dx, dy, _ in M}
        act = [(sx, sy) for sx, sy, st in sites if st <= pa] or [(sites[0][0], sites[0][1])]
        for a, b, dx, dy, _ in R:
            key = (int(a), int(b))
            if key not in mod: continue
            vo = np.array([dx / dp / span, dy / dp / span]); vm = np.array(mod[key])
            if max(np.hypot(*vo), np.hypot(*vm)) < .06: continue
            bi, bj = int((b - y) // B), int((a - x) // B)
            if not (0 <= bi < pr.bh and 0 <= bj < pr.bw) or not np.isfinite(T[bi, bj]): tau = .15
            else: tau = max(pa - float(T[bi, bj]), 0.)
            d = [math.hypot(a - sx, b - sy) for sx, sy in act]; k = int(np.argmin(d))
            ux, uy = (a - act[k][0]) / max(d[k], 1e-3), (b - act[k][1]) / max(d[k], 1e-3)
            env = math.exp(-tau / .15)
            basis = [(ux * env, uy * env), (uy * env, -ux * env), tuple(wind), tuple(wind * tau), tuple(cross),
                     ((a - cx) / span, (b - cy) / span)]
            rows.append((vo - vm, basis))
    return rows


def fit(rows):
    if not rows: return None
    r = np.concatenate([q[0] for q in rows])
    Bm = np.stack([np.concatenate([np.array(q[1][k]) for q in rows]) for k in range(len(NAMES))], 1)
    beta, *_ = np.linalg.lstsq(Bm, r, rcond=None)
    expl = 1. - float(((r - Bm @ beta) ** 2).sum()) / max(float((r ** 2).sum()), 1e-9)
    return beta, expl, len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--spec', required=True)
    ap.add_argument('--label', default='P')
    a = ap.parse_args()
    spec = Path(a.spec[1:]).read_text('utf-8').strip() if a.spec.startswith('@') else a.spec
    allrows = []; out = {}
    for name in a.scenes:
        rows = collect(name, spec, label=a.label); allrows += rows
        f = fit(rows)
        if f:
            beta, expl, n = f; out[name] = dict(beta=beta.tolist(), explained=expl, n=n)
            print(f'{name:22s} n={n:5d} 解释 {expl:5.2f} | ' + ' '.join(f'{nm} {b:+.2f}' for nm, b in zip(NAMES, beta)), flush=True)
    beta, expl, n = fit(allrows)
    print(f'{"十段联合":22s} n={n:5d} 解释 {expl:5.2f} | ' + ' '.join(f'{nm} {b:+.2f}' for nm, b in zip(NAMES, beta)), flush=True)
    out['joint'] = dict(beta=beta.tolist(), explained=expl, n=n)
    (OUT / f'velocity-residual-{a.label}.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
