"""对齐 atrace 与粒子提交，列出长间隔内的应用线程耗时；不将提交数当显示帧数。"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('trace', type=Path)
p.add_argument('result', type=Path)
p.add_argument('--pid', type=int, required=True)
a = p.parse_args()
line_pattern = re.compile(r'^\s*(.+)-(\d+)\s+\(\s*([^)]*)\)\s+\[\d+\]\s+\S+\s+(\d+\.\d+):\s+(\w+):\s+(.*)$')
stacks = defaultdict(list)
spans = []
names = {}
offset = None
for line in a.trace.open(encoding='utf-8', errors='replace'):
    m = line_pattern.match(line)
    if not m:
        continue
    name, tid, group, timestamp, event, message = m.groups()
    timestamp = float(timestamp)
    if 'trace_event_clock_sync: parent_ts=' in message:
        offset = timestamp - float(message.split('parent_ts=')[1])
    if group.strip() != str(a.pid) or event != 'tracing_mark_write':
        continue
    tid = int(tid)
    names[tid] = name
    parts = message.split('|', 2)
    if parts[0] == 'B' and len(parts) == 3:
        stacks[tid].append((timestamp, parts[2]))
    elif parts[0] == 'E' and stacks[tid]:
        start, label = stacks[tid].pop()
        spans.append(dict(tid=tid, name=label, start=start, end=timestamp, ms=(timestamp-start)*1000))
assert offset is not None
d = json.loads(a.result.read_text(encoding='utf-8'))
output = []
for o in d['overlays']:
    frames = o['committed']
    for before, after in zip(frames, frames[1:]):
        gap = (after[1]-before[1])/1e6
        if gap < 40:
            continue
        lo = before[1]/1e9+offset
        hi = after[1]/1e9+offset
        row = dict(cycle=o['cycle'], reverse=o['reverse'], gap=round(gap, 2),
                   progress=round((before[0]-frames[0][0])/1e9, 3),
                   interval=[lo,hi], spans=[])
        relevant = [s for s in spans if s['start'] < hi and s['end'] > lo and s['ms'] >= 3]
        for s in sorted(relevant, key=lambda s:s['start']):
            row['spans'].append([names[s['tid']],s['tid'],s['name'],round((s['start']-lo)*1000,1),round(s['ms'],1)])
        output.append(row)
a.result.with_name('trace-gaps.json').write_text(json.dumps(output, indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps(output,ensure_ascii=False))
