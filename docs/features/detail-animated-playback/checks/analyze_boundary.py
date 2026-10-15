"""区分真实窗口露底、控件空白与循环回绕；不把初次加载或注入对照当产品复现。"""
from collections import Counter, defaultdict
from pathlib import Path
import json
import statistics
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[4] / 'tmp/detail-gif-flicker'
failed = False
for name in [arg for arg in sys.argv[1:] if not arg.startswith('--')]:
    folder = root / name
    result = json.loads((folder / 'result.json').read_text(encoding='utf-8'))
    samples = json.loads((folder / 'samples.json').read_text(encoding='utf-8'))
    windows = json.loads((folder / 'window-pixels.json').read_text(encoding='utf-8'))
    by_stage = defaultdict(list)
    for sample in samples:
        by_stage[sample['stage']].append(sample)
    stage_windows = defaultdict(list)
    for row in windows:
        stage_windows[row['stage']].append(row)
    stages = {}
    for stage, rows in by_stage.items():
        captures = stage_windows[stage]
        previous = {}
        wraps = []
        completions = []
        for row in rows:
            pos = row['pos']
            old = previous.get(pos)
            if old and row['type'] == old['type'] == 'GifDrawable' and row['drawable'] == old['drawable'] and 0 <= row['frame'] < old['frame']:
                wraps.append((row['t'], pos))
            if old and old['type'] == 'GifDrawable' and row['type'] == 'BitmapDrawable' and not row['shouldPlay']:
                completions.append((row['t'], pos))
            previous[pos] = row
        fractions = [(row, media, media['background'] / media['pixels'])
                     for row in captures for media in row['media']]
        near = [(row, media, fraction) for row, media, fraction in fractions
                if any(pos == media['pos'] and abs(row['requested'] - stamp) <= 160 for stamp, pos in wraps)]
        completion_near = [(row, media, fraction) for row, media, fraction in fractions
                if any(pos == media['pos'] and abs(row['requested'] - stamp) <= 160 for stamp, pos in completions)]
        def uncovered(boundaries):
            return sum(not any(pos == media['pos'] and abs(row['requested'] - stamp) <= 160
                               for row, media, _ in fractions) for stamp, pos in boundaries)
        gaps = [b['requested'] - a['requested'] for a, b in zip(captures, captures[1:])]
        stages[stage] = dict(
            durationMs=max(r['t'] for r in rows) - min(r['t'] for r in rows),
            samples=len(rows), blank=sum(r['opaque'] == 0 for r in rows),
            windowCaptures=len(captures), copyStatus=dict(Counter(r['status'] for r in captures)),
            successfulWindowCaptures=sum(r['status'] == 0 for r in captures),
            missedWindowCaptures=sum(r['status'] != 0 for r in captures),
            uncoveredWraps=uncovered(wraps), uncoveredCompletions=uncovered(completions),
            medianCaptureGapMs=statistics.median(gaps) if gaps else None,
            maxCaptureGapMs=max(gaps, default=0),
            medianCopyLatencyMs=statistics.median([r['completed'] - r['requested'] for r in captures]) if captures else None,
            wraps=dict(Counter(pos for _, pos in wraps)),
            completions=dict(Counter(pos for _, pos in completions)),
            windowMediaSamples=len(fractions), boundaryWindowMediaSamples=len(near),
            completionWindowMediaSamples=len(completion_near),
            maxBackgroundFraction=max((fraction for _, _, fraction in fractions), default=0),
            boundaryMaxBackgroundFraction=max((fraction for _, _, fraction in near), default=0),
            completionMaxBackgroundFraction=max((fraction for _, _, fraction in completion_near), default=0),
            suspectCaptures=sum(r['suspect'] for r in captures),
            backgroundOver10Pct=[dict(seq=r['seq'], pos=m['pos'], frame=m['frame'], fraction=f)
                                 for r, m, f in fractions if f > .1][:40],
        )
    checked = {k: v for k, v in stages.items() if k.startswith('boundary-loop-') or
               k in ('boundary-sequential-completion', 'boundary-ime-fast-return', 'boundary-ime-fast-fling')}
    passed = bool(result.get('ok') and result.get('originalUnchanged') and checked and
                  all(v['blank'] == 0 and v['suspectCaptures'] == 0 and v['windowCaptures'] > 0 and
                      v['successfulWindowCaptures'] > 0 and set(v['copyStatus']).issubset({0, 3}) and
                      v['uncoveredWraps'] == 0 and v['uncoveredCompletions'] == 0 and
                      v['boundaryMaxBackgroundFraction'] <= .1 and v['completionMaxBackgroundFraction'] <= .1
                      for v in checked.values()) and
                  all(sum(v['wraps'].values()) > 0 for k, v in checked.items() if k.startswith('boundary-loop-')) and
                  sum(stages.get('boundary-sequential-completion', {}).get('completions', {}).values()) > 0)
    failed |= not passed
    summary = dict(run=name, passed=passed,
                   captureComplete=all(v['missedWindowCaptures'] == 0 for v in checked.values()),
                   result=result, stages=stages)
    (folder / 'boundary-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(name, json.dumps(dict(passed=passed, ok=result.get('ok'), unchanged=result.get('originalUnchanged'), runtime=result.get('runtimeFailures'),
          stages={k: v for k, v in stages.items() if k.startswith('boundary-loop-') or k == 'boundary-sequential-completion'}), ensure_ascii=False))
if '--verify' in sys.argv:
    sys.exit(1 if failed else 0)
