"""颗粒相对粒子化起点的径向运动（只作诊断）：检验「从起点向外膨胀」。

相邻原片独立帧之间做稠密光流（Farneback），只取颗粒掩码（`Scene.observe` 的 particle，可观测区域内）上的像素，
以与背景的差值为权。对每个像素取已启动起点中最近的一个（`v2_inputs.SITES`，控件归一化坐标与启动进度），
把光流分解成背离该起点的径向分量与切向分量，并扣掉风向分量后再算一次径向分量（看去掉整体吹动之后还剩多少膨胀）。
按到起点的距离（卡片短边为单位）分箱、按进度分段，报加权中位数（短边 / 单位进度）。模型帧默认按原片码率编码。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded
from v2_inputs import SITES

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
DIST = [(0., .25), (.25, .5), (.5, .8), (.8, 1.3)]
PHASES = [(.03, .15), (.15, .3), (.3, .5), (.5, .75)]


def gray(f):
    return cv2.cvtColor(np.ascontiguousarray(np.asarray(f)[..., :3]), cv2.COLOR_RGB2GRAY)


def wmed(v, w):
    if len(v) < 60: return float('nan')
    o = np.argsort(v); c = np.cumsum(w[o]); return float(v[o][np.searchsorted(c, c[-1] / 2)])


def run(name, variants, codec=True, step=2):
    c = Comparison(name); s = c.scene; x, y, x1, y1 = s.meta['rect']; cw, ch = x1 - x, y1 - y
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)])
    sites = [(x + sx * cw, y + sy * ch, st) for sx, sy, st in SITES[name]]
    prog = np.asarray(c.ref.progress, float)
    idx = [i for i in c.index if .03 <= prog[i] <= .75 and i + step < len(prog)]
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for label, root, spec in variants:
        if codec:
            cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    H, W = s.roi.shape; ys, xs = np.mgrid[:H, :W].astype(np.float32)
    acc = {lab: {(pb, db): [[], [], []] for pb in PHASES for db in DIST} for lab, _ in seqs}
    for i in idx[::step]:
        p = float(prog[i]); dt = float(prog[i + step] - p)
        pb = next((q for q in PHASES if q[0] <= p < q[1]), None)
        if pb is None or dt <= 0: continue
        act = [(sx, sy) for sx, sy, st in sites if st <= p] or [(sites[0][0], sites[0][1])]
        d2 = np.stack([(xs - sx) ** 2 + (ys - sy) ** 2 for sx, sy in act]); k = d2.argmin(0)
        ox = np.array([a[0] for a in act], np.float32)[k]; oy = np.array([a[1] for a in act], np.float32)[k]
        rx, ry = xs - ox, ys - oy; rr = np.maximum(np.hypot(rx, ry), 1e-3); ux, uy = rx / rr, ry / rr
        dist = rr / s.span
        for lab, get in seqs:
            fa, fb = get(i), get(i + step)
            flow = cv2.calcOpticalFlowFarneback(gray(fa), gray(fb), None, .5, 4, 15, 3, 5, 1.1, 0) / s.span / dt
            m = s.observe(fa)['particle'] & s.roi
            if m.sum() < 400: continue
            w = np.clip(s.excess(np.asarray(fa), s.bgmax, s.bgmin), 0, 120).astype(np.float64) + 1.
            vr = flow[..., 0] * ux + flow[..., 1] * uy
            along = flow[..., 0] * wind[0] + flow[..., 1] * wind[1]
            vr_nw = vr - along * (wind[0] * ux + wind[1] * uy)
            for db in DIST:
                mm = m & (dist >= db[0]) & (dist < db[1])
                if mm.sum() < 60: continue
                acc[lab][(pb, db)][0].append(vr[mm]); acc[lab][(pb, db)][1].append(vr_nw[mm]); acc[lab][(pb, db)][2].append(w[mm])
    out = {}
    for lab, d in acc.items():
        out[lab] = {}
        for key, (a, b, w) in d.items():
            if not a: continue
            a, b, w = np.concatenate(a), np.concatenate(b), np.concatenate(w)
            out[lab][f'{key[0][0]:.2f}-{key[0][1]:.2f}|{key[1][0]:.2f}-{key[1][1]:.2f}'] = (wmed(a, w), wmed(b, w), int(len(a)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--tag', default='')
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        variants.append((label, root, spec))
    allres = {}
    for name in a.scenes:
        res = run(name, variants, codec=not a.raw); allres[name] = res
        print(f'== {name}：背离最近起点的径向速度中位（短边/单位进度；括号内为扣掉风向分量后）', flush=True)
        print('   进度段 \\ 距起点 ' + ''.join(f'{db[0]:.2f}-{db[1]:.2f}'.rjust(26) for db in DIST), flush=True)
        for pb in PHASES:
            for lab in res:
                cells = []
                for db in DIST:
                    k = f'{pb[0]:.2f}-{pb[1]:.2f}|{db[0]:.2f}-{db[1]:.2f}'
                    cells.append(f'{res[lab][k][0]:+.2f}({res[lab][k][1]:+.2f})' if k in res[lab] else '-')
                print(f'   {pb[0]:.2f}-{pb[1]:.2f} {lab:6s}' + ''.join(q.rjust(26) for q in cells), flush=True)
    (OUT / f'origin-radial{a.tag}.json').write_text(json.dumps(allres, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
