"""颗粒群运动的仿射分解（只作诊断）：平移、膨胀（散度）、旋转（旋度）随时间。

相邻两个原片独立帧之间做稠密光流（Farneback），只取两帧都在颗粒掩码里的像素（`Scene.observe` 的 particle，可观测区域内），
按与背景的差值加权，最小二乘拟合 v(p) = t + A (p − c)，c 为卡片中心；速度单位为卡片短边 / 单位进度，A 的单位为 1 / 单位进度。
- 散度 div = A11 + A22：颗粒群整体向外膨胀（正）或收拢（负）的速率；z 向抬升配合透视会表现为正的散度；
- 旋度 curl = A21 − A12：整体卷动；
- 平移 t：沿风向与横风向分量。
另按卡片中心为原点的径向速度 v·r̂ 在距中心 0～0.3、0.3～0.6、0.6～1.0 短边三个环带分别取加权中位数，看外圈是否被推得更远。
模型帧默认按原片码率编码（recording_codec），原片与模型用同样的帧对。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
RINGS = [(0., .3), (.3, .6), (.6, 1.)]


def gray(f):
    return cv2.cvtColor(np.ascontiguousarray(np.asarray(f)[..., :3]), cv2.COLOR_RGB2GRAY)


def fit(s, fa, fb, dt, ctr, wind, span):
    a, b = gray(fa), gray(fb)
    flow = cv2.calcOpticalFlowFarneback(a, b, None, .5, 4, 15, 3, 5, 1.1, 0)
    ma = s.observe(np.asarray(fa))['particle'] & s.roi; mb = s.observe(np.asarray(fb))['particle'] & s.roi
    m = ma & cv2.dilate(mb.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
    if m.sum() < 400: return None
    ys, xs = np.nonzero(m)
    w = np.clip(s.excess(np.asarray(fa), s.bgmax, s.bgmin)[m], 0, 120).astype(np.float64) + 1.
    X = (xs - ctr[0]) / span; Y = (ys - ctr[1]) / span
    U = flow[..., 0][m] / span / dt; V = flow[..., 1][m] / span / dt
    D = np.stack([np.ones_like(X), X, Y], 1) * np.sqrt(w)[:, None]
    cu, *_ = np.linalg.lstsq(D, U * np.sqrt(w), rcond=None)
    cv, *_ = np.linalg.lstsq(D, V * np.sqrt(w), rcond=None)
    t = np.array([cu[0], cv[0]]); A = np.array([[cu[1], cu[2]], [cv[1], cv[2]]])
    r = np.hypot(X, Y); vr = (U * X + V * Y) / np.maximum(r, 1e-6)
    rings = []
    for lo, hi in RINGS:
        k = (r >= lo) & (r < hi)
        if k.sum() < 100: rings.append(float('nan')); continue
        o = np.argsort(vr[k]); c = np.cumsum(w[k][o]); rings.append(float(vr[k][o][np.searchsorted(c, c[-1] / 2)]))
    cross = np.array([-wind[1], wind[0]])
    return dict(div=float(A[0, 0] + A[1, 1]), curl=float(A[1, 0] - A[0, 1]), along=float(t @ wind), across=float(t @ cross),
                rings=rings, n=int(m.sum()))


def run(name, variants, codec=True, step=2):
    c = Comparison(name); s = c.scene; x, y, x1, y1 = s.meta['rect']; ctr = ((x + x1) / 2, (y + y1) / 2)
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)])
    prog = np.asarray(c.ref.progress, float)
    idx = [i for i in c.index if .02 <= prog[i] <= .9 and i + step < len(prog)]
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for label, root, spec in variants:
        if codec:
            cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    out = {lab: [] for lab, _ in seqs}
    for i in idx[::step]:
        dt = float(prog[i + step] - prog[i])
        if dt <= 0: continue
        for lab, get in seqs:
            r = fit(s, get(i), get(i + step), dt, ctr, wind, s.span)
            if r: r['p'] = float(prog[i]); out[lab].append(r)
    return out


def draw(name, out, path):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']; plt.rcParams['axes.unicode_minus'] = False
    keys = [('div', '散度（膨胀率）'), ('curl', '旋度'), ('along', '沿风向平移速度'), ('across', '横风向平移速度')]
    fig, axes = plt.subplots(1, 7, figsize=(26, 3.8))
    cols = {'原片': 'k'}; palette = ['m', 'g', 'tab:orange', 'c', 'b']
    for k, lab in enumerate([l for l in out if l != '原片']): cols[lab] = palette[k % len(palette)]
    for ax, (k, t) in zip(axes[:4], keys):
        for lab, rows in out.items():
            if not rows: continue
            p = [r['p'] for r in rows]; v = [r[k] for r in rows]
            ax.plot(p, np.convolve(v, np.ones(3) / 3, mode='same'), color=cols[lab], lw=2 if lab == '原片' else 1.2, label=lab)
        ax.axhline(0, color='gray', lw=.6); ax.set_title(t); ax.set_xlabel('进度')
    for j, (lo, hi) in enumerate(RINGS):
        ax = axes[4 + j]
        for lab, rows in out.items():
            if not rows: continue
            p = [r['p'] for r in rows]; v = [r['rings'][j] for r in rows]
            ax.plot(p, v, color=cols[lab], lw=2 if lab == '原片' else 1.2, label=lab)
        ax.axhline(0, color='gray', lw=.6); ax.set_title(f'径向速度中位 · 距中心 {lo}～{hi} 短边'); ax.set_xlabel('进度')
    axes[0].legend()
    fig.suptitle(f'{name}：颗粒群运动的仿射分解（单位：短边 / 单位进度）')
    fig.tight_layout(); fig.savefig(path, dpi=70); plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--step', type=int, default=2, help='帧对间隔（独立帧数）')
    ap.add_argument('--tag', default='')
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        # 与其它诊断一致：@文件 读取配置文本（否则路径会被当成一个无效开关，渲染出的是另一套配置）。
        if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
        variants.append((label, root, spec))
    allres = {}
    for name in a.scenes:
        out = run(name, variants, codec=not a.raw, step=a.step)
        draw(name, out, OUT / f'flow-affine{a.tag}-{name}.png')
        allres[name] = out
        line = f'{name}：'
        for lab, rows in out.items():
            if not rows: continue
            line += f'  {lab} 散度均值 {np.mean([r["div"] for r in rows]):+.3f} 外圈径向 {np.nanmean([r["rings"][2] for r in rows]):+.3f}'
        print(line, flush=True)
    (OUT / f'flow-affine{a.tag}.json').write_text(json.dumps(allres, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
