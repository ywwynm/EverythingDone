"""逐帧成本专项的汇总：呈现帧数/应有帧数、呈现间隔、首帧延迟、左滑画面数、涟漪期窗口帧。

口径按 per-frame-plan-2026-09-17.md 第 1 节：不用「帧耗时百分位」，用「一次动画里真正
呈现的帧数 / 应有帧数」与「呈现间隔的 p90 与最大值」。呈现时刻优先取驱动报的真实呈现
时刻（`committed` 行的第 4 位），拿不到才退回观察器收到回调的时刻。
"""
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_particle_perf import stats, intervals, appearance_report  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / 'tmp/particle-perf/per-frame'


def progress_of(row):
    return struct.unpack('f', struct.pack('I', row[2] & 0xffffffff))[0]


def presented_times(overlay):
    """真实呈现时刻；驱动没报时退回观察器收到回调的时刻，并记下有多少帧是退回来的。"""
    rows = overlay.get('committed', [])
    times = [(r[3] if len(r) > 3 and r[3] > 0 else r[1]) for r in rows]
    fallback = sum(1 for r in rows if not (len(r) > 3 and r[3] > 0))
    return times, fallback


def bits_to_float(value):
    return struct.unpack('f', struct.pack('I', value & 0xffffffff))[0]


def gesture_report(overlay, window=None):
    """手势的滞后曲线：每帧「手指要的相位 − 实际画出的相位」，单位是历史采样档。

    行的形状是 [目标呈现时刻, wanted 位, drawn 位, 可画档位, 已提交档位, 目标档位]。
    反向瞬间的峰值与「几拍之内追回来」是判「跟不跟手」的两个数；`clampedFrames` 是画面
    被**还没备好的档位**卡住的帧数，它大就说明瓶颈在预读而不是出帧。
    """
    rows = overlay.get('gesture') or []
    if window:
        rows = [r for r in rows if window[0] <= r[0] <= window[1]]
    if not rows:
        return None
    lag = [max(0.0, bits_to_float(r[1]) - bits_to_float(r[2])) for r in rows]
    wanted = [bits_to_float(r[1]) for r in rows]
    clamped = sum(1 for value in lag if value > 1e-4)
    # 反向：手指要的相位由增转减或由减转增的那一帧；记峰值滞后与恢复到 0.5 档以内用了几帧。
    peaks = []
    for i in range(1, len(rows) - 1):
        before = wanted[i] - wanted[i - 1]
        after = wanted[i + 1] - wanted[i]
        if before * after >= 0 or abs(before) < 1e-4 or abs(after) < 1e-4:
            continue
        tail = lag[i:i + 20]
        recover = next((k for k, value in enumerate(tail) if value <= .5), None)
        peaks.append(dict(frame=i, peakSamples=round(max(tail), 3),
                          recoverFrames=recover))
    return dict(frames=len(rows), lagSamples=stats(lag),
                clampedFrames=clamped,
                clampedRatio=round(clamped / len(rows), 3),
                preparedEnd=rows[-1][3], submittedEnd=rows[-1][4], goalEnd=rows[-1][5],
                reversals=peaks)


def gesture_window(d):
    """手指真正在移动的那一段：首个 MOVE 到末个 MOVE（观察器的 inputs 第 2 位是 actionMasked）。"""
    moves = [r[0] for r in d['inputs'] if r[1] == 0 and r[2] == 2]
    return (moves[0], moves[-1]) if len(moves) > 1 else None


def overlay_report(overlay, window=None):
    submitted = overlay.get('submitted', [])
    committed = overlay.get('committed', [])
    draws = overlay.get('draw', [])
    request = overlay['requestAt']
    times, fallback = presented_times(overlay)
    gaps = intervals(times)
    span = (times[-1] - times[0]) / 1e6 if len(times) > 1 else 0.0
    expected = round(span / 1000 * 60, 1)
    activated = overlay.get('gestureActivatedAt', 0)
    moving = [r for r in submitted if len(r) > 2 and progress_of(r) > 0]
    distinct = len({round(progress_of(r), 6) for r in moving})
    first_moving = next((r[1] for r in committed if r[0] in {m[0] for m in moving}), None)
    # 左滑：只看手指真正在动的那一段里出了几个不同画面（末尾的停住不算掉帧）。
    in_move = None
    if window:
        rows = [r for r in submitted if window[0] <= r[0] <= window[1] and len(r) > 2]
        presented_in = [t for r, t in zip(committed, times) if window[0] <= r[0] <= window[1]]
        in_move = dict(
            pictures=len({round(progress_of(r), 6) for r in rows}),
            presented=len(presented_in),
            windowMs=round((window[1] - window[0]) / 1e6, 1),
            gapMs=stats(intervals(presented_in)))
    return dict(
        stage=overlay['stage'], reverse=overlay['reverse'],
        size=[overlay['width'], overlay['height']],
        count=overlay.get('particleCount'),
        presented=len(times), expected=expected,
        ratio=round(len(times) / expected, 3) if expected > 0 else None,
        spanMs=round(span, 1), presentFallback=fallback,
        presentGapMs=stats(gaps),
        firstSubmitMs=round((submitted[0][1] - request) / 1e6, 2) if submitted else None,
        firstPresentMs=round((times[0] - request) / 1e6, 2) if times else None,
        # 左滑：识别（手势激活）到首个非零画面上屏。
        firstMovementMs=round((first_moving - activated) / 1e6, 2)
        if first_moving and activated else None,
        distinctPictures=distinct,
        # CPU 提交：一帧里本会话发 GL 命令用掉的 CPU 时间（不含 swap）；
        # submitLagMs 是「发完 → 窗口 eglSwapBuffers 返回」。
        cpuIssueMs=stats([(r[2] - r[1]) / 1e6 for r in draws]),
        submitLagMs=stats([(r[4] - r[2]) / 1e6 for r in draws]),
        inMove=in_move, prewarm=overlay.get('prewarm'),
        gestureLag=gesture_report(overlay, window),
        appearance=appearance_report(overlay))


def run_report(d):
    inputs = d['inputs']
    overlays = d['overlays']
    start = inputs[0][0] if inputs else d['startedAt']
    labels = {r['type']: r['label'] for r in d.get('windowLabels', [])}

    def window_rows(kind_filter, since, span_ns):
        rows = [r for r in d['windowFrames'] if since <= r[0] <= since + span_ns
                and (kind_filter is None or r[12] == kind_filter)]
        deadlines = [r for r in rows if r[9] > 0]
        return dict(frames=len(rows), totalMs=stats(r[1] / 1e6 for r in rows),
                    deadlineMs=stats(r[9] / 1e6 for r in deadlines),
                    missed=sum(r[1] > r[9] for r in deadlines))

    downs = [r for r in inputs if r[1] == 0 and r[2] == 0]
    ripple = None
    ripple_host = None
    if downs:
        tap_at, tap_type = downs[0][0], downs[0][5]
        ripple = dict(window=labels.get(tap_type), **window_rows(tap_type, tap_at, 600_000_000))
        ripple_host = dict(window='all', **window_rows(None, tap_at, 600_000_000))
    idle = ('nativePollOnce', 'epollWait', 'Object.wait', 'nativeWait')
    busy = [s for s in d.get('stalls', []) if s['gapMs'] > 20 and (s.get('stack') or [''])[0]
            and not any(m in s['stack'][0] for m in idle)]
    return dict(kind=d['config'].get('kind'),
                # 左滑：这一格用的是哪张卡、它在列表里多高（卡高直接改片数与压力网格）。
                card=dict(title=d['config'].get('cardTitle'),
                          heightPx=d['config'].get('cardHeight'),
                          widthPx=d['config'].get('cardWidth'))
                if d['config'].get('cardTitle') else None,
                clock=d.get('clock'),
                mainGapMs=stats(intervals([t for t in d['mainFrames'] if t >= start])),
                # F14：两个上下文实际读回的优先级档位；拿不到两档就没有可归因的对照。
                glContext=d.get('glContext'),
                ripple=ripple, rippleAllWindows=ripple_host,
                overlays=[overlay_report(o, gesture_window(d)) for o in overlays],
                busyStalls=len(busy),
                busyStallGapMs=stats(s['gapMs'] for s in busy),
                busyStallTops=[s['stack'][0].split('(')[0] for s in busy][:6])


if __name__ == '__main__':
    serial = sys.argv[1]
    only = sys.argv[2] if len(sys.argv) > 2 else ''
    out = {}
    for run_dir in sorted((ROOT / serial / 'runs').glob('*')):
        result = run_dir / 'result.json'
        if not result.is_file() or (only and not run_dir.name.startswith(only)):
            continue
        d = json.loads(result.read_text(encoding='utf-8'))
        if d.get('schema') != 2:
            continue
        out[run_dir.name] = run_report(d)
    target = ROOT / serial / ('summary-%s.json' % (only or 'all'))
    target.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({k: len(v['overlays']) for k, v in out.items()}))
    print('写入', target)
