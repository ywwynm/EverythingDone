"""撤回复测的离线汇总；同一口径比较三个版本，并保留逐手势峰值。"""
import json
import re
from pathlib import Path

from analyze_framestats import pct

ROOT = Path(__file__).resolve().parents[4]
OLD = ROOT / 'tmp/home-list-scroll-20261009-optimized'
OUT = ROOT / 'tmp/home-list-scroll-20261009-rollback'


def summarise(device, style, phase, directory):
    samples = json.loads((directory / 'summary.json').read_text())
    if len(samples) != 6:
        raise RuntimeError(f'采集尚未完整：{directory}，当前 {len(samples)} 次')
    log = (directory / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
    groups = {'moving': [], 'hold': [], 'tail': []}
    gestures = []
    for sample in samples:
        g = sample['gesture']
        parts = {'moving': [], 'hold': [], 'tail': []}
        for frame in sample['rows']:
            if not 0 < frame.get('total', 0) < 1000:
                continue
            t = frame['t']
            if g['hold'] - 8.4 <= t <= (g['up'] - g['down']) / 1e6:
                key = 'moving'
            elif 0 <= t < g['hold'] - 8.4:
                key = 'hold'
            elif t > (g['up'] - g['down']) / 1e6:
                key = 'tail'
            else:
                continue
            groups[key].append(frame)
            parts[key].append(frame)
        captures = []
        for line in log.splitlines():
            if '抓图路径' not in line:
                continue
            stamp = re.match(r'\s*(\d+\.\d+)', line)
            if stamp and g['down'] / 1e9 <= float(stamp[1]) <= g['up'] / 1e9:
                captures.append(line)
        gestures.append(dict(index=g['index'], card=g['card'], x=g['x'], y1=g['y1'], y2=g['y2'],
                             hold=g['hold'], capture_count=len(captures),
                             moving_max=round(max((f['total'] for f in parts['moving']), default=0), 2)))
    row = dict(device=device, phase=phase, style=style, gestures=gestures,
               captured_gestures=sum(g['capture_count'] > 0 for g in gestures))
    for key, frames in groups.items():
        totals = [f['total'] for f in frames]
        row[key] = dict(n=len(frames), p90=round(pct(totals, .9), 2) if totals else 0,
                        max=round(max(totals, default=0), 2),
                        deadline_miss=sum(f['overdue'] for f in frames))
    return row


def main():
    rows = []
    for device in ['samsung', 'meizu', 'oppo']:
        for style in ['flow', 'drift']:
            for root, phase in [(OLD, 'before'), (OLD, 'after'), (OUT, 'rollback'), (OUT, 'repeat')]:
                path = root / device / (phase + '-' + style)
                if not (path / 'summary.json').exists():
                    continue
                row = summarise(device, style, phase, path)
                rows.append(row)
                print(json.dumps({k: v for k, v in row.items() if k != 'gestures'}))
    (OUT / 'comparison.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
    for device in ['samsung', 'meizu', 'oppo']:
        for style in ['flow', 'drift']:
            selected = [r for r in rows if r['device'] == device and r['style'] == style]
            if len(selected) < 3:
                continue
            shapes = [[{k: g[k] for k in ['card', 'x', 'y1', 'y2', 'hold']} for g in r['gestures']] for r in selected]
            print(json.dumps(dict(device=device, style=style,
                                  first_geometry_equal=all(s[0] == shapes[0][0] for s in shapes),
                                  moving_peaks={r['phase']: [g['moving_max'] for g in r['gestures']] for r in selected})))


if __name__ == '__main__':
    main()
