"""分析 2026-10-14 的低开销报告，只把驱动呈现时间戳作为实际出帧证据。"""
import collections
import argparse
import json
import math
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[4]
OUT = ROOT / 'tmp/doing-refresh-rate-20261014'


def percentile(values, ratio):
    values = sorted(values)
    if not values:
        return None
    position = (len(values) - 1) * ratio
    lower = math.floor(position)
    upper = math.ceil(position)
    return round(values[lower] + (values[upper] - values[lower]) * (position - lower), 3)


def distribution(values):
    return {'count': len(values), 'p50': percentile(values, .5),
            'p90': percentile(values, .9), 'p95': percentile(values, .95),
            'max': round(max(values), 3) if values else None}


def analyze(folder):
    instrument = (folder / 'instrument.txt').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_CODE: 0' in instrument and 'result=完成' in instrument
    data = json.loads((folder / 'report.json').read_text(encoding='utf-8'))
    events = data['events']
    assert data['droppedFrameEvents'] == data['droppedLifecycleEvents'] == 0
    assert not any('failed' in e['event'] for e in events)
    submits = [e for e in events if e['event'] == 'particle_buffer_submitted']
    presents = {e['values']['frameId']: e['values'] for e in events
                if e['event'] == 'particle_presentation_decision'}

    def segment(start, end, particles):
        selected = [e for e in submits if start <= e['elapsedMs'] < end
                    and bool(e['values']['sessions']) == particles]
        ids = [e['values']['frameId'] for e in selected]
        verified = [presents[i] for i in ids if i in presents
                    and presents[i]['basis'] == 'driver_timestamp'
                    and presents[i]['driverPresentNanos'] > 0]
        stamps = sorted({v['driverPresentNanos'] for v in verified})
        gaps = [(b - a) / 1e6 for a, b in zip(stamps, stamps[1:])]
        # 120 Hz 是此次应用请求及滑动时的参考节拍，不能把间隔超标比称为系统 jank 指标。
        late = [v for v in gaps if v > 12.5]
        cpu = [(e['values']['submittedNanos'] - e['values']['cpuStartNanos']) / 1e6
               for e in selected]
        capture_samples = [e['values']['captureToCallbackMs'] for e in events
                           if e['event'] == 'doing_scene_frame'
                           and start <= e['elapsedMs'] < end and e['values']['stage'] == 'live']
        capture_points = [e for e in events if e['event'] == 'doing_scene_frame'
                          and start <= e['elapsedMs'] < end and e['values']['stage'] == 'live']
        capture_rate = None
        if len(capture_points) >= 2:
            a, b = capture_points[0], capture_points[-1]
            capture_rate = round((b['values']['frames'] - a['values']['frames']) * 1000
                                 / (b['elapsedMs'] - a['elapsedMs']), 2)
        return {'startMs': round(start, 3), 'endMs': round(end, 3),
                'submittedFrames': len(selected), 'driverVerifiedFrames': len(verified),
                'uniquePresentedFrames': len(stamps),
                'fps': round((len(stamps) - 1) * 1e9 / (stamps[-1] - stamps[0]), 2) if len(stamps) > 1 else None,
                'intervalMs': distribution(gaps),
                'intervalOver12_5msPct': round(len(late) * 100 / len(gaps), 2) if gaps else None,
                'compositeWallMs': distribution(cpu),
                'sampledCaptureToCallbackMs': distribution(capture_samples),
                'captureRateBetweenSparseCounters': capture_rate}

    activations = [e for e in events if e['event'] == 'swipe_activated']
    horizontal = []
    for i, activation in enumerate(activations):
        stop = activations[i + 1]['elapsedMs'] if i + 1 < len(activations) else math.inf
        swipe_events = [e for e in events if activation['elapsedMs'] <= e['elapsedMs'] < stop]
        visible = next(e for e in swipe_events if e['event'] == 'swipe_pixels_visible')
        disposed = next(e for e in swipe_events if e['event'] == 'swipe_dispose' and e['values']['active'])
        result = segment(visible['elapsedMs'] + 150, disposed['elapsedMs'], True)
        result['firstVisibleLatencyMs'] = round(visible['elapsedMs'] - activation['elapsedMs'], 3)
        horizontal.append(result)
    assert len(horizontal) == 3

    vertical = []
    scrolls = [e for e in events if e['event'] == 'doing_probe_scroll']
    for i, event in enumerate(scrolls):
        if event['values']['state'] != 1:
            continue
        # 仅统计手指拖动区间，排除起步 150 ms 和松手后的减速／停止阶段。
        end = next(e for e in scrolls[i + 1:] if e['values']['state'] != 1)
        result = segment(event['elapsedMs'] + 150, end['elapsedMs'], False)
        result['offsetStart'] = event['values']['offset']
        result['offsetEnd'] = end['values']['offset']
        vertical.append(result)
    assert len(vertical) == 2

    display = (folder / 'display-active.txt').read_text(encoding='utf-8')
    window = (folder / 'window-active.txt').read_text(encoding='utf-8')
    window = window[window.index('ParticleDoingProbeActivity}:'):]
    display_fields = {}
    for key in ('mActiveModeId', 'mActiveSfDisplayMode', 'mActiveRenderFrameRate', 'mFrameRateOverrides'):
        display_fields[key] = next((line.strip().split('=', 1)[1] for line in display.splitlines()
                                    if line.strip().startswith(key + '=')), None)
    display_fields['preferredDisplayMode'] = re.search(r'preferredDisplayMode=(\d+)', window).group(1)
    fixture = next((e['values']['kind'] for e in events if e['event'] == 'doing_probe_fixture'), 'text')
    return {'device': folder.parent.name, 'style': data['metadata']['particleStyle'], 'fixture': fixture,
            'run': folder.name, 'build': data['metadata']['build'],
            'displayBeforeGestures': display_fields,
            'presentationBasisCounts': dict(collections.Counter(v['basis'] for v in presents.values())),
            'droppedFrameEvents': data['droppedFrameEvents'],
            'droppedLifecycleEvents': data['droppedLifecycleEvents'],
            'horizontal': horizontal, 'vertical': vertical}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, default=OUT)
    parser.add_argument('--expected-runs', type=int, default=6)
    args = parser.parse_args()
    OUT = args.root.resolve()
    results = [analyze(p.parent) for p in sorted(OUT.glob('*/*/report.json'))
               if (p.parent / 'instrument.txt').exists() and (p.parent / 'geometry.json').exists()
               and not (p.parent / 'error.json').exists()]
    assert len(results) == args.expected_runs, '设备与粒子样式的报告数量不符'
    (OUT / 'analysis.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    for result in results:
        print(result['device'], result['fixture'], result['style'], result['presentationBasisCounts'])
        for kind in ('horizontal', 'vertical'):
            for item in result[kind]:
                print(kind, json.dumps(item, ensure_ascii=False))
