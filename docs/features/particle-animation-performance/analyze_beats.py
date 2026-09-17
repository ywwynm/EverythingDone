"""第三批：节拍级取证的汇总（`result.json` 里的 `beats` 与 `clock`）。

回答的是「左滑为什么不是 60 fps」这一问的四个分支：
  1. 节拍本身跳没跳（TICK 之间隔了几个 vsync、目标时刻相对 vsync 漂了多少）；
  2. 出拍了却没画（SKIP 的原因分布）；
  3. 画了但提交晚了（COMPOSITE 的等栅栏 / 画 / swap 三段）；
  4. 提交了却没按目标上屏（PRESENT 的驱动呈现时刻 vs 目标）。

用法：`python analyze_beats.py <序列号> <run 前缀> [场景键…]`
"""
import json
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / 'tmp/particle-perf/per-frame'

TICK, PUMP, COMPOSITE, PRESENT, SKIP = 0, 1, 2, 3, 4
SKIP_NAMES = {0: '已取消', 1: '不可见', 2: '阶段未到', 3: '无渲染器', 4: '相位没变'}


def f32(bits):
    return struct.unpack('f', struct.pack('I', bits & 0xffffffff))[0]


def quant(values, unit):
    """把间隔按 unit 归成整数份数，返回直方图。"""
    out = {}
    for v in values:
        k = round(v / unit) if unit else 0
        out[k] = out.get(k, 0) + 1
    return dict(sorted(out.items()))


def pct(values, q):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(len(s) * q))], 2)


def report(path):
    d = json.loads(path.read_text(encoding='utf-8'))
    rows = d.get('beats') or []
    clock = d.get('clock') or {}
    if not rows:
        return None
    vsync = clock.get('vsyncNanos') or 0
    ticks = [r for r in rows if r[0] == TICK]
    beats = [r for r in ticks if r[3] != 0]
    comps = [r for r in rows if r[0] == COMPOSITE]
    skips = [r for r in rows if r[0] == SKIP]
    pres = [r for r in rows if r[0] == PRESENT]
    # 只看动画真的在跑的那一段：第一次合成到最后一次合成。
    span = (comps[0][1], comps[-1][5]) if comps else (0, 1 << 62)
    ticks = [r for r in ticks if span[0] <= r[1] <= span[1]]
    beats = [r for r in beats if span[0] <= r[1] <= span[1]]
    skips = [r for r in skips if span[0] <= r[1] <= span[1]]

    tick_gaps = [(b[1] - a[1]) / 1e6 for a, b in zip(ticks, ticks[1:])]
    beat_gaps = [(b[3] - a[3]) / 1e6 for a, b in zip(beats, beats[1:])]
    # 节拍回调实际执行时刻相对 vsync 的延迟。
    tick_lat = [(r[2] - r[1]) / 1e6 for r in ticks]
    # 目标时刻相对 vsync 的偏移：相位锁定之后应当恒为「一两个 vsync」，自由计时会漂。
    target_off = [(r[3] - r[1]) / 1e6 for r in beats]
    fence = [r[3] / 1e6 for r in comps]
    enter = [(r[2] - r[1]) / 1e6 for r in comps]          # 目标 → 进入合成
    draw = [(r[4] - r[2]) / 1e6 for r in comps]
    swap = [(r[5] - r[4]) / 1e6 for r in comps]
    submit = [(r[5] - r[1]) / 1e6 for r in comps]          # 目标 → swap 返回
    shown = [r for r in pres if r[3] > 0]
    show_gaps = [(b[3] - a[3]) / 1e6 for a, b in zip(shown, shown[1:])]
    # 真实呈现时刻 − 目标呈现时刻：>0 是晚于目标，<0 是抢在目标之前（目标写过去时就会这样）。
    show_err = [(r[3] - r[1]) / 1e6 for r in shown]
    bad = sum(1 for r in pres if r[3] < 0)
    reasons = {}
    for r in skips:
        reasons[SKIP_NAMES.get(r[3], r[3])] = reasons.get(SKIP_NAMES.get(r[3], r[3]), 0) + 1
    return dict(
        vsyncMs=round(vsync / 1e6, 3), stride=clock.get('stride'),
        phaseLock=clock.get('phaseLock'),
        ticks=len(ticks), beats=len(beats), composites=len(comps), skipped=len(skips),
        skipReasons=reasons,
        tickGapVsyncs=quant(tick_gaps, vsync / 1e6) if vsync else None,
        beatGapVsyncs=quant(beat_gaps, vsync / 1e6) if vsync else None,
        tickLatencyMs=[pct(tick_lat, .5), pct(tick_lat, .9), round(max(tick_lat), 2) if tick_lat else None],
        targetOffsetMs=[pct(target_off, .5), pct(target_off, .9),
                        round(min(target_off), 2) if target_off else None,
                        round(max(target_off), 2) if target_off else None],
        enterMs=[pct(enter, .5), pct(enter, .9), round(max(enter), 2) if enter else None],
        fenceWaitMs=[pct(fence, .5), pct(fence, .9), round(max(fence), 2) if fence else None],
        drawMs=[pct(draw, .5), pct(draw, .9)],
        swapMs=[pct(swap, .5), pct(swap, .9), round(max(swap), 2) if swap else None],
        submitMs=[pct(submit, .5), pct(submit, .9), round(max(submit), 2) if submit else None],
        presented=len(shown), presentBad=bad,
        presentGapVsyncs=quant(show_gaps, vsync / 1e6) if vsync else None,
        presentErrMs=[pct(show_err, .5), pct(show_err, .9),
                      round(min(show_err), 2) if show_err else None,
                      round(max(show_err), 2) if show_err else None])


if __name__ == '__main__':
    serial, prefix = sys.argv[1], sys.argv[2]
    only = sys.argv[3:]
    out = {}
    for run_dir in sorted((ROOT / serial / 'runs').glob(prefix + '*')):
        result = run_dir / 'result.json'
        if not result.is_file():
            continue
        if only and not any(k in run_dir.name for k in only):
            continue
        r = report(result)
        if r:
            out[run_dir.name] = r
    print(json.dumps(out, indent=1, ensure_ascii=False))
