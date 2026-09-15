"""阶段 0 汇总：只接受真实系统输入观察器产物（schema=2）。

GL 等待、纹理回调与窗口帧分别报告，不混作物理扫描帧率。
"""
import json
import math
import re
import struct
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / 'tmp/particle-perf/phase0'


def stats(values):
    a = sorted(float(x) for x in values)
    if not a:
        return None

    def q(p):
        return round(a[max(0, math.ceil(len(a) * p) - 1)], 2)
    return dict(n=len(a), p50=q(.5), p90=q(.9), p95=q(.95), max=round(a[-1], 2))


def intervals(values):
    return [(b - a) / 1e6 for a, b in zip(values, values[1:]) if b > a]


VISIBLE_RATIO = .10


def appearance_report(o):
    """2a / 2c：倒放起点、每一帧的可见占比，以及首个「可见粒子占比 ≥ 10%」的帧何时上屏。

    判据只用材料自己的出生与寿命（观察器把每个采样的存活片数原样带了出来），
    与 material.vert 的裁剪一致；进度位取自逐帧提交记录，帧号按本次真实的起点还原。
    """
    if not o.get('reverse') or 'aliveCounts' not in o:
        return None
    counts = o['aliveCounts']
    count = o.get('particleCount') or 0
    start = o.get('appearanceStart', len(counts) - 1)
    if count <= 0 or start <= 0:
        return None
    progress_of = {}
    for r in o.get('submitted', []):
        if len(r) > 2:
            progress_of[r[0]] = struct.unpack('f', struct.pack('I', r[2] & 0xffffffff))[0]
    first_visible = None
    first_commit = None
    for r in o.get('committed', []):
        if first_commit is None:
            first_commit = r[1]
        progress = progress_of.get(r[0])
        if progress is None:
            continue
        frame = round((1 - min(max(progress, 0.), 1.)) * start)
        if 0 <= frame < len(counts) and counts[frame] >= count * VISIBLE_RATIO:
            first_visible = r[1]
            break
    request = o['requestAt']
    return dict(
        start=start, samples=o.get('appearanceSamples'), count=count,
        playbackMs=round(o.get('appearancePlaybackMs', 0), 1),
        skipped=(o.get('appearanceSamples') or start) - start,
        precomputeMs=round((o['precomputeDoneAt'] - request) / 1e6, 2) if o.get('precomputeDoneAt') else None,
        firstVisibleMs=round((first_visible - request) / 1e6, 2) if first_visible else None,
        visibleAfterFirstFrameMs=round((first_visible - first_commit) / 1e6, 2)
        if first_visible and first_commit else None,
        startRatio=round(counts[start] / count, 4) if start < len(counts) else None,
        tailRatios=[round(c / count, 4) for c in counts[-6:]],
        touchGap=o.get('touchGap'), touchStrength=o.get('touchStrength'),
        fromFab=o.get('fromFab'), skipTailEnabled=o.get('skipTailEnabled'))


def launch_report(d):
    """2a：FAB 抬手 → 首页快照 → startActivity → onCreate → 布局 → 出现请求 → 捕获。"""
    marks = d.get('launchTrace') or []
    if not marks:
        return None
    base = marks[0]['at']
    return dict(base=base, segments=[dict(label=m['label'], atMs=round((m['at'] - base) / 1e6, 2))
                                     for m in marks])


def run_report(d):
    inputs = d['inputs']
    overlays = d['overlays']
    start = inputs[0][0] if inputs else d['startedAt']
    end = max([o.get('removedAt', start) for o in overlays] +
              [e['at'] for e in d['events'] if e['event'] == 'editor-ready'] + [start])
    if end <= start:
        end = max([r[0] for r in inputs] + [start]) + 20_000_000
    labels = {r['type']: r['label'] for r in d.get('windowLabels', [])}
    frames = [r for r in d['windowFrames'] if start <= r[0] <= end]
    main = [t for t in d['mainFrames'] if start <= t <= end]
    per_window = {}
    for t, label in labels.items():
        rows = [r for r in frames if r[12] == t]
        if not rows:
            continue
        deadlines = [r for r in rows if r[9] > 0]
        per_window[label] = dict(frames=len(rows), totalMs=stats(r[1] / 1e6 for r in rows),
                                 deadlineFrames=len(deadlines),
                                 deadlineMissed=sum(r[1] > r[9] for r in deadlines),
                                 deadlineMs=stats(r[9] / 1e6 for r in deadlines))
    # 按下后 600 ms 视为涟漪期；只统计承载按钮的那个窗口。
    def ripple_for(taps):
        if not taps:
            return None
        tap_at, tap_type = taps[0][0], taps[0][5]
        rows = [r for r in d['windowFrames'] if tap_at <= r[0] <= tap_at + 600_000_000 and r[12] == tap_type]
        deadlines = [r for r in rows if r[9] > 0]
        return dict(window=labels.get(tap_type), frames=len(rows),
                    totalMs=stats(r[1] / 1e6 for r in rows),
                    deadlineMissed=sum(r[1] > r[9] for r in deadlines))

    downs = [r for r in inputs if r[1] == 0 and r[2] == 0]
    ripple = ripple_for([r for r in downs if r[5] >= 2])
    # 工具栏按钮的涟漪画在 Activity 窗口上（第二批补测项）：不筛窗口类型，取第一次按下。
    ripple_host = ripple_for(downs)
    particles = []
    for o in overlays:
        submitted = o.get('submitted', [])
        committed = o.get('committed', [])
        draws = o.get('draw', [])
        activated = o.get('gestureActivatedAt', 0)
        appearance = appearance_report(o)
        submit_set = {r[0] for r in submitted}
        seen = set()
        textures = []
        for r in o.get('textures', []):
            if r[0] == 0 and r[1] >= activated and r[2] in submit_set and r[2] not in seen:
                seen.add(r[2])
                textures.append(r[1])
        moving = {r[0] for r in submitted if len(r) > 2 and
                  struct.unpack('f', struct.pack('I', r[2] & 0xffffffff))[0] > 0}
        first_moving = next((r[1] for r in committed if r[0] in moving), None)
        particles.append(dict(
            stage=o['stage'], reverse=o['reverse'], size=[o['width'], o['height']],
            requestAfterInputMs=round((o['requestAt'] - start) / 1e6, 2),
            firstSubmitMs=round((submitted[0][1] - o['requestAt']) / 1e6, 2) if submitted else None,
            firstCommitMs=round((committed[0][1] - o['requestAt']) / 1e6, 2) if committed else None,
            firstMovementAfterActivationMs=round((first_moving - activated) / 1e6, 2)
            if first_moving and activated else None,
            submitted=len(submitted), committed=len(committed),
            submitGapMs=stats(intervals([r[0] for r in submitted])),
            textureGapMs=stats(intervals(textures)),
            glReadyMs=stats([(r[3] - r[1]) / 1e6 for r in draws]),
            startupMs=[round((v - o['requestAt']) / 1e6, 2) for v in o.get('startup', [])],
            appearance=appearance))
    # 60 Hz 下正常帧间隔就是 16.67 ms，>16 ms 会把每一帧都算成停顿；
    # 真正的停顿取 >20 ms 且栈顶不在 nativePollOnce（主线程空转等消息）的样本。
    idle_marks = ('nativePollOnce', 'epollWait', 'Object.wait', 'nativeWait')
    stalls = d.get('stalls', [])
    busy = [s for s in stalls if s['gapMs'] > 20 and (s.get('stack') or [''])[0]
            and not any(m in s['stack'][0] for m in idle_marks)]
    tops = Counter()
    for s in busy:
        stack = s.get('stack') or []
        top = next((f for f in stack if 'ywwynm' in f), stack[0] if stack else '?')
        tops[top] += 1
    cadence = stats(intervals(main))
    return dict(kind=d['config'].get('kind'), durationMs=round((end - start) / 1e6, 2),
                inputs=len(inputs), mainGapMs=cadence, windows=per_window, ripple=ripple,
                rippleHost=ripple_host, launch=launch_report(d),
                particles=particles, stallSamples=len(stalls), busyStalls=len(busy),
                stallGapMs=stats(s['gapMs'] for s in stalls),
                busyStallGapMs=stats(s['gapMs'] for s in busy),
                stallTops=tops.most_common(8))


def gpu_report(folder):
    rows = []
    for f in sorted(folder.glob('*.json')):
        d = json.loads(f.read_text())
        total = {}
        counts = {}
        per_frame = {}
        for name, frame, phase, ns in d['records']:
            total[name] = total.get(name, 0) + ns
            counts[name] = counts.get(name, 0) + 1
            per_frame.setdefault((name, phase), []).append(ns / 1e6)
        rows.append(dict(file=f.name, stage=d['stage'], mode=d['mode'],
                         noBarrier=d['noBarrier'], noPressure=d['noPressure'],
                         dvfsWarmMs=d['dvfsWarmMs'], disjoint=d['disjoint'],
                         extra={k: v for k, v in d['extra'].items() if k != 'renderer'},
                         sumMs={k: round(v / 1e6, 2) for k, v in total.items()}, counts=counts,
                         dist={'%s/%s' % k: stats(v) for k, v in sorted(per_frame.items())}))
    return rows


if __name__ == '__main__':
    only = sys.argv[1] if len(sys.argv) > 1 else ''
    out = {}
    for serial_dir in sorted(ROOT.glob('*')):
        if not serial_dir.is_dir():
            continue
        device = {}
        for run_dir in sorted((serial_dir / 'runs').glob('*')):
            result = run_dir / 'result.json'
            if not result.is_file() or (only and not run_dir.name.startswith(only)):
                continue
            d = json.loads(result.read_text())
            if d.get('schema') != 2 or d.get('driver') != 'system-input-observed':
                continue
            device[run_dir.name] = run_report(d)
        gpu = {}
        for probe_dir in sorted((serial_dir / 'gpu').glob('*')):
            if probe_dir.is_dir() and (not only or probe_dir.name.startswith(only)):
                gpu[probe_dir.name] = gpu_report(probe_dir)
        out[serial_dir.name] = dict(runs=device, gpu=gpu)
    (ROOT / 'summary.json').write_text(json.dumps(out, indent=1), encoding='utf-8')
    print(json.dumps({k: {'runs': len(v['runs']), 'gpu': sum(len(g) for g in v['gpu'].values())}
                      for k, v in out.items()}))
