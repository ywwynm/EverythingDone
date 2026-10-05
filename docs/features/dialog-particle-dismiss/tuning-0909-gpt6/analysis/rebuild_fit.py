"""连续薄膜模型（rebuild_cf.py）的自动拟合：CMA-ES，共用参数 + 逐段终点输入（加速度 A、方向偏差 dθ），7 段拟合、3 段留出。

判据：原片独立帧（0.1～0.85，每隔 --stride 帧取一帧）上可见颗粒密度 D 三档（0.22 / 0.45 / 0.65）的 (1 − 交并比) 均值，七段等权。
留出段只拟合自己的终点输入（A、dθ），共用参数固定，然后与现行候选同口径比较（原始画面，不加录像模糊）。
输出 analysis/match-cache/rebuild/fit-<标签>.json（逐代最优、最终参数、逐段结果）。
用法：rebuild_fit.py 标签 [--evals 2400] [--stride 2] [--resume]
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
# 名称、下界、上界、初值
SHARED = [('V0', 0., .5, .15), ('Vn', 0., .6, .15), ('tn', .02, .4, .08), ('sv', 0., .15, .03), ('curl', 0., .4, .05),
          ('curl_scale', .08, .8, .3), ('jitter', 0., .1, .01), ('life', .15, .9, .45), ('life_var', 0., .9, .4),
          ('size0', .4, 1.6, 1.), ('size_pow', .3, 8., 3.), ('fade_in', .001, .1, .01), ('gain', .6, 1.6, 1.),
          ('glint', 0., 1., .1), ('sb', 0., 1.5, .6), ('tw', 0., 60., 10.)]
A_LO, A_HI = .3, 4.
D_LO, D_HI = -25., 25.
# 初值只用于起步（原片逐颗跟踪、年龄 ≤ 0.25 的加速度；旧蜘蛛侠轨迹不足取 1.6），拟合时与其它参数一起放开
A0 = {'ironman': 1.55, 'kobe': 3.23, 'spiderman-original': 1.6, 'ironman-up-reference': 1.08, 'reference-0926-1': 1.08,
      'reference-0926-6': 1.48, 'reference-0926-7': .80, 'thanos': 2.36, 'reference-0926-5': 2.05, 'reference-0926-4': 1.83}
OUT = HERE / 'analysis/match-cache/rebuild'


def to_unit(v, lo, hi): return (v - lo) / (hi - lo)
def from_unit(u, lo, hi): return lo + float(np.clip(u, 0, 1)) * (hi - lo)


def decode(x, scenes):
    P = replace(Params(), **{n: from_unit(x[i], lo, hi) for i, (n, lo, hi, _) in enumerate(SHARED)})
    k = len(SHARED); inp = {}
    for j, s in enumerate(scenes):
        inp[s] = (from_unit(x[k + 2 * j], A_LO, A_HI), from_unit(x[k + 2 * j + 1], D_LO, D_HI))
    return P, inp


def encode(P, inp, scenes):
    x = [to_unit(getattr(P, n), lo, hi) for n, lo, hi, _ in SHARED]
    for s in scenes:
        a, d = inp[s]; x += [to_unit(a, A_LO, A_HI), to_unit(d, D_LO, D_HI)]
    return np.array(x)


def fit_inputs(sc, P, a0, d0, stride, evals=80):
    """共用参数固定，只拟合该段的终点输入（A、dθ）。"""
    frames = list(range(0, len(sc.idx), stride))
    es = cma.CMAEvolutionStrategy([to_unit(a0, A_LO, A_HI), to_unit(d0, D_LO, D_HI)], .15,
                                  {'bounds': [0, 1], 'popsize': 8, 'maxfevals': evals, 'verbose': -9, 'seed': 1})
    while not es.stop():
        xs = es.ask()
        es.tell(xs, [sc.loss(P, from_unit(x[0], A_LO, A_HI), from_unit(x[1], D_LO, D_HI), frames)[0] for x in xs])
    x = es.result.xbest
    return from_unit(x[0], A_LO, A_HI), from_unit(x[1], D_LO, D_HI)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('tag'); ap.add_argument('--evals', type=int, default=2400); ap.add_argument('--stride', type=int, default=2)
    ap.add_argument('--resume', action='store_true'); ap.add_argument('--hold-only', action='store_true')
    a = ap.parse_args()
    log_path = OUT / f'fit-{a.tag}.json'
    log = json.loads(log_path.read_text('utf-8')) if (a.resume or a.hold_only) and log_path.exists() else {'history': []}
    scenes = {s: SceneCF(s) for s in FIT}
    frames = {s: list(range(0, len(scenes[s].idx), a.stride)) for s in FIT}
    P0 = replace(Params(), **{n: v for n, _, _, v in SHARED}); inp0 = {s: (A0[s], 0.) for s in FIT}
    x0 = encode(P0, inp0, FIT) if 'best_x' not in log else np.array(log['best_x'])
    t0 = time.time()
    l0 = [scenes[s].loss(P0, *inp0[s], frames[s])[0] for s in FIT]
    print(f'起点损失 {np.mean(l0):.4f}（{time.time() - t0:.1f} 秒/次）', flush=True)
    if not a.hold_only:
        es = cma.CMAEvolutionStrategy(x0, .2, {'bounds': [0, 1], 'maxfevals': a.evals, 'verbose': -9, 'seed': 7})
        best = (float('inf'), None); gen = 0
        while not es.stop():
            xs = es.ask(); fs = []
            for x in xs:
                P, inp = decode(x, FIT)
                fs.append(float(np.mean([scenes[s].loss(P, *inp[s], frames[s])[0] for s in FIT])))
            es.tell(xs, fs); gen += 1
            i = int(np.argmin(fs))
            if fs[i] < best[0]: best = (fs[i], np.array(xs[i]))
            P, inp = decode(best[1], FIT)
            log['history'].append(dict(gen=gen, evals=es.countevals, best=best[0], gen_best=fs[i], sigma=es.sigma, t=time.time() - t0))
            log.update(best_x=best[1].tolist(), best_loss=best[0], params=asdict(P), inputs={s: list(v) for s, v in inp.items()})
            log_path.write_text(json.dumps(log, ensure_ascii=False, indent=1), 'utf-8')
            if gen % 5 == 0:
                print(f'第 {gen} 代 {es.countevals} 次：最优 {best[0]:.4f}，σ {es.sigma:.3f}，{(time.time() - t0) / 60:.1f} 分钟', flush=True)
    P, inp = decode(np.array(log['best_x']), FIT)
    print('共用参数', json.dumps({k: round(v, 4) for k, v in asdict(P).items()}, ensure_ascii=False))
    res = {}
    for s in FIT:
        med, _ = scenes[s].evaluate(P, inp[s][0], dth=inp[s][1]); res[s] = med
        print(f'{s:22s} A {inp[s][0]:.2f} dθ {inp[s][1]:+5.1f}  {fmt(med)}', flush=True)
    del scenes; torch.cuda.empty_cache()
    for s in HOLD:
        sc = SceneCF(s)
        aa, dd = fit_inputs(sc, P, A0[s], 0., a.stride)
        med, _ = sc.evaluate(P, aa, dth=dd); res[s] = med; inp[s] = (aa, dd)
        print(f'{s:22s}（留出）A {aa:.2f} dθ {dd:+5.1f}  {fmt(med)}', flush=True)
        del sc; torch.cuda.empty_cache()
    log.update(final={s: np.asarray(m).tolist() for s, m in res.items()}, inputs={s: list(v) for s, v in inp.items()})
    log_path.write_text(json.dumps(log, ensure_ascii=False, indent=1), 'utf-8')
    print('拟合段平均', fmt(np.nanmean(np.stack([res[s] for s in FIT]), 0)))
    print('留出段平均', fmt(np.nanmean(np.stack([res[s] for s in HOLD]), 0)))
