"""可见图案速度随「所在位置释放后经过的时间 τ」的变化：检验原片是否「释放后先慢后快」。

对原片与模型分别做 PIV（同 handoff_piv），每个有效块取块中心处的解除时刻 T（原片、模型各用自己的图），
按 τ = p − T 分组给出速度大小的中位数（像素 / 0.01 进度，按卡片短边折算为 短边 / 单位进度）。
只统计控件内的块；τ 较小的块主要是刚释放、仍在原处附近的材料。只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release
from handoff_piv import hp, piv

BINS = [(0., .03), (.03, .06), (.06, .1), (.1, .15), (.15, .22), (.22, .32), (.32, .5)]


def collect(probe, T, frames_at, pairs, args):
    s = probe.scene; x0, y0, x1, y1 = probe.rect
    roi = s.roi.astype(np.uint8)
    rows = []
    for (pa, pb, fa, fb) in pairs:
        R = piv(hp(fa), hp(fb), roi, args.block, args.step, args.radius, args.min_corr, args.min_energy)
        if not len(R): continue
        v = R[:, 2:4] / (pb - pa) * .01
        for (cx, cy, *_), (vx, vy) in zip(R, v):
            if not (x0 <= cx < x1 and y0 <= cy < y1): continue
            bi = int((cy - y0) // B); bj = int((cx - x0) // B)
            if bi >= probe.bh or bj >= probe.bw or not probe.valid[bi, bj] or not np.isfinite(T[bi, bj]): continue
            rows.append((pa - T[bi, bj], np.hypot(vx, vy), T[bi, bj]))
    return np.array(rows).reshape(-1, 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'thanos', 'reference-0926-7'])
    ap.add_argument('--spec', default='')
    ap.add_argument('--label', default='speed-age')
    ap.add_argument('--gap-progress', type=float, default=.017, help='原片与模型帧对的进度间隔')
    ap.add_argument('--block', type=int, default=24)
    ap.add_argument('--step', type=int, default=16)
    ap.add_argument('--radius', type=int, default=16)
    ap.add_argument('--min-corr', type=float, default=.45)
    ap.add_argument('--min-energy', type=float, default=6.)
    ap.add_argument('--two-d', action='store_true', help='同时按释放时刻 T 三分位分组输出')
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        probe = Probe(name); c = probe.comp; s = probe.scene
        span = min(s.rect[2] - s.rect[0], s.rect[3] - s.rect[1])
        prog = np.asarray(c.ref.progress, float)
        gap = max(1, int(round(a.gap_progress / float(np.median(np.diff(prog))))))
        idx = [i for i in range(len(prog) - gap) if .12 <= prog[i] <= .8]
        pairs_ref = [(float(prog[i]), float(prog[i + gap]), np.asarray(c.ref.frames[i]), np.asarray(c.ref.frames[i + gap])) for i in idx[::max(1, len(idx) // 40)]]
        frames = render_all(name, HERE, a.label, spec=a.spec)
        step = max(1, round(a.gap_progress * 120))
        pairs_mod = [(k / 120, (k + step) / 120, np.asarray(frames[k]), np.asarray(frames[k + step])) for k in range(15, 96, 2)]
        out = {}
        for label, T, pairs in [('原片', probe.huawei(), pairs_ref), ('模型', model_release(probe, name, frames), pairs_mod)]:
            d = collect(probe, T, None, pairs, a)
            vals = []
            for lo, hi in BINS:
                m = (d[:, 0] >= lo) & (d[:, 0] < hi) & (d[:, 1] > .15)
                vals.append((float(np.median(d[m, 1]) * 100 / span) if m.sum() >= 8 else np.nan, int(m.sum())))
            out[label] = vals
            print(f'{name} {label}: ' + '  '.join(f'τ{lo:.2f}-{hi:.2f}:{v:.2f}({n})' for (lo, hi), (v, n) in zip(BINS, vals)), flush=True)
            if a.two_d:
                # 同一 τ 下按释放时刻 T 分组：速度随 T 上升 ⇒ 全局时钟；不随 T 变 ⇒ 颗粒年龄。
                tq = np.quantile(d[:, 2], [0, 1 / 3, 2 / 3, 1]) if len(d) else []
                for k in range(3):
                    mT = (d[:, 2] >= tq[k]) & (d[:, 2] <= tq[k + 1])
                    cells = []
                    for lo, hi in BINS[:6]:
                        m = mT & (d[:, 0] >= lo) & (d[:, 0] < hi) & (d[:, 1] > .15)
                        cells.append(f'{np.median(d[m, 1]) * 100 / span:.2f}({m.sum()})' if m.sum() >= 8 else '-')
                    print(f'    T∈[{tq[k]:.2f},{tq[k + 1]:.2f}] ' + '  '.join(f'τ{lo:.2f}:{c}' for (lo, _), c in zip(BINS[:6], cells)))
        report[name] = out
    (OUT / f'speed-by-age-{a.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
