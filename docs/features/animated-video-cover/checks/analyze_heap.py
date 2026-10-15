"""汇总 heapprofd 的分配路径；累计分配不能当作同时驻留内存。"""
from pathlib import Path
import csv
import io
import json
import subprocess
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
RUN = sys.argv[1]
OUT = ROOT / "tmp/media-memory-20261015" / RUN
TRACE = OUT / "trace.perfetto-trace"
TP = ROOT / "tmp/home-list-scroll-20261009/trace_processor_shell.exe"

def query(sql):
    result = subprocess.run([str(TP), "query", str(TRACE), sql], capture_output=True, check=True)
    return list(csv.DictReader(io.StringIO(result.stdout.decode())))

frames = {r['id']: r for r in query('SELECT f.*,m.name mapping_name FROM stack_profile_frame f JOIN stack_profile_mapping m ON f.mapping=m.id')}
calls = {r['id']: r for r in query('SELECT * FROM stack_profile_callsite')}
allocs = query('SELECT * FROM heap_profile_allocation')
clock = query("SELECT ts-clock_value offset FROM clock_snapshot WHERE clock_name='MONOTONIC' LIMIT 1")
offset = int(clock[0]['offset'])
snapshots = [json.loads(l) for l in (ROOT / 'tmp/selection-transition-20261013' / RUN / 'media-memory.jsonl').read_text(encoding='utf-8').splitlines()]
drag_start = snapshots[0]['time'] * 1000000 + offset
drag_end = next(r['time'] * 1000000 + offset for r in snapshots if r['phase']=='after-drag')

stacks = {}
for cid in {a['callsite_id'] for a in allocs}:
    names = []; cur = cid
    while cur in calls:
        f = frames[calls[cur]['frame_id']]
        names.append(f['name'] if f['name'] != '[NULL]' else f['mapping_name'] + ':' + f['rel_pc'])
        cur = calls[cur]['parent_id']
    stacks[cid] = names

totals = defaultdict(lambda: [0, 0, 0])
top = defaultdict(lambda: [0, 0])
for a in allocs:
    size = int(a['size']); ts = int(a['ts']); cid = a['callsite_id']; stack = stacks[cid]
    key = next((n for n in stack if n.startswith('com.ywwynm.') or n.startswith('com.bumptech.')), None)
    key = key or next((n for n in stack if 'libGLES' in n or 'libhwui' in n or 'Sk' in n), None) or '其它原生分配'
    totals[key][0] += max(size, 0); totals[key][1] += size
    if drag_start <= ts <= drag_end: totals[key][2] += max(size, 0)
    top[cid][0] += max(size, 0); top[cid][1] += size

report = {'run': RUN, 'dragDurationSeconds': (drag_end-drag_start)/1e9,
    'traceHealth': query("SELECT name,severity,value FROM stats WHERE value>0 AND (severity IN ('error','data_loss') OR name GLOB 'heapprofd*')"),
    'paths': [{'path': k, 'allocatedMiB': v[0]/1048576, 'liveAtTraceEndMiB': v[1]/1048576, 'dragAllocatedMiB': v[2]/1048576}
        for k,v in sorted(totals.items(), key=lambda kv: -kv[1][0])],
    'topStacks': [{'allocatedMiB': v[0]/1048576, 'liveAtTraceEndMiB': v[1]/1048576, 'stack': stacks[k]}
        for k,v in sorted(top.items(), key=lambda kv: -kv[1][0])[:25]]}
(OUT / 'analysis.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k:v[:16] if k=='paths' else v for k,v in report.items() if k!='topStacks'}, ensure_ascii=False, indent=2))
