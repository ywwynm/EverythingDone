"""前后对照汇总：分别报告手指移动、按住和尾段，避免把管线延迟称为掉帧率。"""
import json
import re
from verify_optimization_20261009 import r

all_rows = []
for name in r.DEVICES:
    for phase in ['before', 'after']:
        for style in ['flow', 'drift']:
            d = r.OUT / name / (phase + '-' + style)
            if not (d / 'summary.json').exists():
                continue
            data = json.loads((d / 'summary.json').read_text())
            grouped = {'moving': [], 'hold': [], 'tail': []}
            maximums = []
            captures = []
            log = (d / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
            for sample in data:
                g = sample['gesture']
                rows = [x for x in sample['rows'] if 0 < x.get('total', 0) < 1000]
                moving = [x for x in rows if g['hold'] - 8.4 <= x['t'] <= (g['up'] - g['down']) / 1e6]
                hold = [x for x in rows if 0 <= x['t'] < g['hold'] - 8.4]
                tail = [x for x in rows if (g['up'] - g['down']) / 1e6 < x['t']]
                for key, part in [('moving', moving), ('hold', hold), ('tail', tail)]:
                    grouped[key] += part
                maximums.append(round(max((x['total'] for x in moving), default=0), 2))
                for line in log.splitlines():
                    if '抓图路径' not in line:
                        continue
                    stamp = re.match(r'\s*(\d+\.\d+)', line)
                    if stamp and g['down'] / 1e9 <= float(stamp[1]) <= g['up'] / 1e9:
                        captures.append(g['index'])
            row = dict(device=name, phase=phase, style=style, moving_max_by_gesture=maximums,
                       captured_gestures=captures)
            for key, part in grouped.items():
                totals = [x['total'] for x in part]
                row[key] = dict(n=len(totals), p90=r.pct(totals, .9), max=max(totals, default=0),
                                deadline_miss=sum(x['overdue'] for x in part))
            all_rows.append(row)
            print(json.dumps(row, ensure_ascii=False))
r.save(r.OUT / 'comparison.json', json.dumps(all_rows, ensure_ascii=False, indent=2))
