"""非标准布局（g≈1）的共同运动参数重新拟合（研究候选）：在圆点外观下，以云团时序观测量与颗粒占用为目标。

参数全部按布局差异 g 加权，原版钢铁侠（g=0）不受影响：
  m     引导与卷动系数（原 0.477，用 mramp-early=m,mramp-late=m 表示常数）
  w     顺风携带系数（原 1.086，cwind）
  life  寿命倍率（edge-life）
  peel  出生剥离倍率（edge-peel）
  acc   沿风向随年龄加速（accel）
目标：九段参与拟合的非标准布局（不含三段留出素材）在原片独立帧上的云团占用图误差、质心、前后沿、横向展宽与颗粒占用差，
按量级归一后等权平均。渲染在内存中逐帧进行、不写缓存；每次评估写入日志。

--lp-weight>0 时另加低频亮度差（与记分卡 lp6 同口径：σ=6 模糊后逐像素差，只在可观测区域；为省内存按 4 像素间隔取样），
按各段起点值归一成相对量后乘该权重计入。第一轮只看几何时，最优点以更短寿命、更强顺风换几何，lp6 在多数段变差。
"""
from pathlib import Path
import argparse, json, math, sys, time
import numpy as np, moderngl, cv2
from scipy.optimize import minimize

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_variants import build
from handoff_cloud import measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

SCENES = ['thanos', 'kobe', 'spiderman-original', 'reference-0926-1', 'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
NAMES = ['m', 'w', 'life', 'peel', 'acc']
LO = np.array([.25, .6, .6, .2, 0.]); HI = np.array([1.1, 1.6, 1.5, 1.6, 1.2])
X0 = np.array([.477, 1.086, 1., 1., 0.])
NORM = dict(cloud_map=.03, centroid=.05, front=.04, back=.04, width=.06, occ=.015)


def lowpass6(frame):
    return cv2.GaussianBlur(np.asarray(frame).astype(np.float32), (0, 0), 6.)[::4, ::4]


class Refs:
    def __init__(self, names, stride):
        self.data = {}; self.lp = {}
        for name in names:
            c = Comparison(name, stride=stride); s = c.scene
            ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)]); cross = np.array([-wind[1], wind[0]])
            rows = []; lps = []
            for i in c.index:
                p = float(c.ref.progress[i]); f = np.asarray(c.ref.frames[i])
                if .08 <= p <= .9: rows.append((p, measure(s, f, wind, cross)))
                if .1 <= p <= .9 and s.roi.any(): lps.append((p, lowpass6(f)))
            self.data[name] = (s, wind, cross, rows)
            self.lp[name] = (s.roi[::4, ::4], lps)


def spec_of(x, base):
    m, w, life, peel, acc = x
    return f'{base},mramp-early={m:.4f},mramp-late={m:.4f},cwind={w:.4f},edge-life={life:.4f},edge-peel={peel:.4f},accel={acc:.4f}'


def evaluate(x, base, refs, ctx, log, lp_weight=0., lp_base=None):
    t0 = time.time(); spec = spec_of(x, base)
    cls, kw = build(HERE, spec)
    per = {}
    for name, (s, wind, cross, rows) in refs.data.items():
        r = cls(name, ctx=ctx, quality=2, sites=[Site(*q) for q in OBSERVED_SITES[name]], **kw)
        errs = {k: [] for k in NORM}
        for p, (ro, w, rg) in rows:
            mo, _, mg = measure(s, r.render(p), wind, cross)
            errs['cloud_map'].append(float(np.abs(mo - ro)[w].mean())); errs['occ'].append(float((mo - ro)[w].mean()))
            if rg is not None and mg is not None:
                errs['centroid'].append(float(np.linalg.norm(mg['centroid'] - rg['centroid'])))
                for k in ['front', 'back', 'width']: errs[k].append(abs(mg[k] - rg[k]))
        e = {k: (abs(float(np.mean(v))) if k == 'occ' else float(np.mean(v))) for k, v in errs.items() if v}
        if lp_weight > 0:
            roi4, lps = refs.lp[name]
            if lps:
                e['lp6'] = float(np.mean([np.abs(lowpass6(r.render(p)) - ref).mean(2)[roi4].mean() for p, ref in lps]))
        r.close()
        per[name] = e
    if lp_weight > 0 and lp_base is not None and not lp_base:
        lp_base.update({n: e['lp6'] for n, e in per.items() if 'lp6' in e})
    def scene_score(n, e):
        geo = np.mean([e[k] / NORM[k] for k in e if k in NORM])
        if lp_weight > 0 and 'lp6' in e and lp_base: geo += lp_weight * (e['lp6'] / lp_base[n] - 1.)
        return geo
    score = float(np.mean([scene_score(n, e) for n, e in per.items()]))
    rec = dict(x=dict(zip(NAMES, map(float, x))), score=score, per=per, seconds=time.time() - t0)
    log.append(rec)
    print(f'[{len(log):3d}] 分数 {score:.4f}  ' + ' '.join(f'{k}={v:.3f}' for k, v in rec['x'].items()) + f'  ({rec["seconds"]:.0f}s)', flush=True)
    return score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='外观与其它共同设置（handoff_variants 变体）')
    ap.add_argument('--out', default=str(HERE / 'analysis/match-cache/g1-motion-fit.json'))
    ap.add_argument('--stride', type=int, default=4, help='原片独立帧的抽取间隔')
    ap.add_argument('--maxfev', type=int, default=45)
    ap.add_argument('--lp-weight', type=float, default=0., help='低频亮度差（相对起点）的权重；0 为只看几何')
    a = ap.parse_args()
    refs = Refs(SCENES, a.stride)
    ctx = moderngl.create_standalone_context(require=430); log = []; lp_base = {}
    to_u = lambda x: (np.asarray(x) - LO) / (HI - LO)
    from_u = lambda u: LO + (HI - LO) * np.clip(u, 0, 1)
    def f(u):
        val = evaluate(from_u(u), a.base, refs, ctx, log, a.lp_weight, lp_base)
        Path(a.out).write_text(json.dumps(dict(base=a.base, log=log), ensure_ascii=False, indent=1), 'utf-8')
        return val + 5. * float(np.sum(np.clip(-u, 0, None) + np.clip(u - 1, 0, None)))
    # 初始单纯形：每个参数在单位区间上走 0.2（默认 5% 相对步长太小，会在原点附近爬行）；朝区间内侧取向。
    u0 = to_u(X0); simplex = [u0]
    for k in range(len(u0)):
        u = u0.copy(); u[k] = u[k] + .2 if u[k] + .2 <= 1 else u[k] - .2; simplex.append(u)
    res = minimize(f, u0, method='Nelder-Mead', options=dict(maxfev=a.maxfev, xatol=.02, fatol=.002, initial_simplex=np.array(simplex)))
    best = min(log, key=lambda r: r['score'])
    print('最好:', best['score'], best['x'])
    Path(a.out).write_text(json.dumps(dict(base=a.base, log=log, best=best), ensure_ascii=False, indent=1), 'utf-8')
    ctx.release()


if __name__ == '__main__':
    main()
