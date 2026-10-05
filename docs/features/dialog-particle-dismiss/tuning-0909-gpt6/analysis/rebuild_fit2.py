"""连续薄膜模型（rebuild_cf.py）第二轮拟合：逐段终点输入固定为原片实测值，只用 CMA-ES 拟合十二个共用参数。

逐段终点输入（触点决定的加速度大小与方向，原片未知时按逐颗跟踪推断，scratchpad kin_within.py）：
A = 年龄 ≤ 0.25 的样本拟合的加速度大小；方向 = 加速度方向（旧蜘蛛侠轨迹不足，取 scene.json 方向与 1.6）。
判据：可见密度 D 的连续交并比（Σmin/Σmax）与三档阈值交并比各占一半，7 段拟合、3 段留出（留出段同样用实测输入，不再拟合）。
用法：rebuild_fit2.py 标签 [--evals 1200] [--stride 2]
"""
from pathlib import Path
from dataclasses import asdict, replace
import argparse, json, math, sys, time
import numpy as np, torch, cma

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from rebuild_cf import SceneCF, Params, fmt

FIT = ['ironman', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1', 'reference-0926-6', 'reference-0926-7']
HOLD = ['thanos', 'reference-0926-5', 'reference-0926-4']
SHARED = [('V0', 0., .4, .1), ('Vn', 0., .5, .1), ('tn', .02, .4, .08), ('sv', 0., .12, .04), ('curl', 0., .3, .06),
          ('curl_scale', .08, .8, .25), ('life', .15, .9, .4), ('life_var', 0., .9, .4), ('size0', .5, 1.5, 1.),
          ('size_pow', .5, 8., 3.), ('sb', 0., 1.4, .6), ('glint', 0., 1., .2)]
A_MEAS = {'ironman': 1.55, 'kobe': 3.23, 'spiderman-original': 1.6, 'ironman-up-reference': 1.08, 'reference-0926-1': 1.08,
          'reference-0926-6': 1.48, 'reference-0926-7': .80, 'thanos': 2.36, 'reference-0926-5': 2.05, 'reference-0926-4': 1.83}
DIR_MEAS = {'ironman': 123, 'kobe': 139, 'spiderman-original': None, 'ironman-up-reference': 120, 'reference-0926-1': 95,
            'reference-0926-6': 133, 'reference-0926-7': 101, 'thanos': 131, 'reference-0926-5': 55, 'reference-0926-4': 75}
OUT = HERE / 'analysis/match-cache/rebuild'


def to_unit(v, lo, hi): return (v - lo) / (hi - lo)
def from_unit(u, lo, hi): return lo + float(np.clip(u, 0, 1)) * (hi - lo)


def decode(x):
    return replace(Params(), **{n: from_unit(x[i], lo, hi) for i, (n, lo, hi, _) in enumerate(SHARED)})


def inputs(sc):
    d = DIR_MEAS[sc.name]
    return A_MEAS[sc.name], (0. if d is None else d - sc.direction)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('tag'); ap.add_argument('--evals', type=int, default=1200); ap.add_argument('--stride', type=int, default=2)
    a = ap.parse_args()
    log_path = OUT / f'fit-{a.tag}.json'; log = {'history': []}
    scenes = {s: SceneCF(s, fuzzy=True) for s in FIT}
    frames = {s: list(range(0, len(scenes[s].idx), a.stride)) for s in FIT}
    x0 = np.array([to_unit(v, lo, hi) for _, lo, hi, v in SHARED])
    t0 = time.time()
    l0 = [scenes[s].loss(decode(x0), *inputs(scenes[s]), frames[s])[0] for s in FIT]
    print(f'起点损失 {np.mean(l0):.4f}（{time.time() - t0:.1f} 秒/次）', flush=True)
    es = cma.CMAEvolutionStrategy(x0, .2, {'bounds': [0, 1], 'maxfevals': a.evals, 'verbose': -9, 'seed': 11})
    best = (float('inf'), None); gen = 0
    while not es.stop():
        xs = es.ask(); fs = []
        for x in xs:
            P = decode(x)
            fs.append(float(np.mean([scenes[s].loss(P, *inputs(scenes[s]), frames[s])[0] for s in FIT])))
        es.tell(xs, fs); gen += 1
        i = int(np.argmin(fs))
        if fs[i] < best[0]: best = (fs[i], np.array(xs[i]))
        log['history'].append(dict(gen=gen, evals=es.countevals, best=best[0], gen_best=fs[i], sigma=es.sigma, t=time.time() - t0))
        log.update(best_x=best[1].tolist(), best_loss=best[0], params=asdict(decode(best[1])))
        log_path.write_text(json.dumps(log, ensure_ascii=False, indent=1), 'utf-8')
        if gen % 5 == 0:
            print(f'第 {gen} 代 {es.countevals} 次：最优 {best[0]:.4f}，σ {es.sigma:.3f}，{(time.time() - t0) / 60:.1f} 分钟', flush=True)
    P = decode(best[1])
    print('共用参数', json.dumps({k: round(v, 4) for k, v in asdict(P).items()}, ensure_ascii=False))
    res = {}
    for s in FIT:
        med, _ = scenes[s].evaluate(P, inputs(scenes[s])[0], dth=inputs(scenes[s])[1]); res[s] = med
        print(f'{s:22s} {fmt(med)}', flush=True)
    del scenes; torch.cuda.empty_cache()
    for s in HOLD:
        sc = SceneCF(s)
        med, _ = sc.evaluate(P, inputs(sc)[0], dth=inputs(sc)[1]); res[s] = med
        print(f'{s:22s}（留出） {fmt(med)}', flush=True)
        del sc; torch.cuda.empty_cache()
    log.update(final={s: np.asarray(m).tolist() for s, m in res.items()})
    log_path.write_text(json.dumps(log, ensure_ascii=False, indent=1), 'utf-8')
    print('拟合段平均', fmt(np.nanmean(np.stack([res[s] for s in FIT]), 0)))
    print('留出段平均', fmt(np.nanmean(np.stack([res[s] for s in HOLD]), 0)))
