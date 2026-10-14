"""核对长 fling 的实际清单位移与整页捕获，排除仅计时文字在刷新的误判。"""
import argparse
import bisect
import json
import pathlib
import statistics

from analyze_doing_fling import analyze_fling
from analyze_doing_refresh_rate import distribution


def analyze(folder):
    result = analyze_fling(folder, 4)
    report = json.loads((folder / 'report.json').read_text(encoding='utf-8'))
    rows = json.loads((folder / 'beat-trace.json').read_text(encoding='utf-8'))
    assert len(rows) < 48000, '详细时序记录溢出'
    events = report['events']
    for name in ('doing_scene_closed', 'doing_scene_capture_resources_released', 'doing_scene_window_detached'):
        assert any(e['event'] == name for e in events), (folder, name)
    submits = [e for e in events if e['event'] == 'particle_buffer_submitted']
    base = statistics.median(e['values']['submittedNanos'] - e['elapsedMs'] * 1e6 for e in submits)
    motion = sorted((r[1], r[2]) for r in rows if r[0] == 17)
    assert motion, '缺少实际滚动位移记录'
    motion_times = [r[0] for r in motion]
    segments = []
    for s in result['intervals']:
        if s['state'] != 2:
            continue
        # 只统计松手后 100–600ms：尾端低速时每帧不足一像素，不能要求位移事件仍为 120Hz。
        start, end = s['startMs'] + 100, min(s['startMs'] + 600, s['endMs'])
        assert end > start
        lo, hi = base + start * 1e6, base + end * 1e6
        updates = [r for r in motion if lo <= r[0] < hi]
        assert len(updates) > 5, (folder, s)
        sampled = []
        for r in rows:
            if r[0] != 5 or not lo <= r[2] < hi:
                continue
            index = bisect.bisect_right(motion_times, r[2]) - 1
            if index >= 0:
                sampled.append((r[2], motion[index][1]))
        changed = [r for i, r in enumerate(sampled) if not i or r[1] != sampled[i - 1][1]]
        segments.append({
            'durationMs': end - start,
            'settlingDurationMs': s['endMs'] - s['startMs'],
            'motionEvents': len(updates),
            'captureCount': len(sampled),
            'capturesWithChangedOffset': len(changed),
            'motionIntervalsMs': [(b[0] - a[0]) / 1e6 for a, b in zip(updates, updates[1:])],
            'changedCaptureIntervalsMs': [(b[0] - a[0]) / 1e6 for a, b in zip(changed, changed[1:])],
        })
    duration = sum(s['durationMs'] for s in segments)
    result['contentMotion'] = {
        'scope': 'SETTLING 开始后 100–600ms；详细探针样本，不并入低开销 FPS 表',
        'traceRows': len(rows),
        'motionEventsPerSecond': round(sum(s['motionEvents'] for s in segments) * 1000 / duration, 2),
        'changedCapturesPerSecond': round(sum(s['capturesWithChangedOffset'] for s in segments) * 1000 / duration, 2),
        'motionIntervalsMs': distribution([v for s in segments for v in s['motionIntervalsMs']]),
        'changedCaptureIntervalsMs': distribution([v for s in segments for v in s['changedCaptureIntervalsMs']]),
        'exitResourcesReleased': True,
        'segments': segments,
    }
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, required=True)
    args = parser.parse_args()
    results = [analyze(p.parent) for p in sorted(args.root.glob('*/*/report.json'))]
    assert len(results) == 3
    (args.root / 'content-analysis.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    for r in results:
        print(json.dumps({'device': r['device'], 'fling': r['fling'], 'validation': r['flingValidation'],
                         'motion': {k: v for k, v in r['contentMotion'].items() if k != 'segments'}}, ensure_ascii=False))
