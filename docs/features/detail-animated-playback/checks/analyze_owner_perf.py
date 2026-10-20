"""按输入区间统计窗口帧成本，区分进程 PSS 与解码资源字节；不换算 FPS。"""
from pathlib import Path
from collections import defaultdict
import csv
import json
import statistics
import sys
import io
import subprocess

sys.stdout.reconfigure(encoding='utf-8')
ROOT = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').exists())
BASE = ROOT / 'tmp/gif-owner-perf-20261020'

def pct(a, p):
    a = sorted(a)
    if not a:
        return None
    k = (len(a)-1)*p/100
    lo = int(k)
    return round(a[lo] + (a[min(lo+1, len(a)-1)]-a[lo])*(k-lo), 3)

def load(p):
    return json.loads(p.read_text(encoding='utf-8'))

def memory(rows):
    return {name: {'medianMiB': pct([r[key] / unit for r in rows], 50), 'peakMiB': max((round(r[key]/unit, 3) for r in rows), default=None)}
        for name, key, unit in [('pss', 'pssKb', 1024), ('java', 'javaBytes', 1024**2), ('native', 'nativeBytes', 1024**2)]}

def frames(rows):
    eligible = [r for r in rows if r['deadline'] > 0]
    return {'n': len(rows), 'p50ms': pct([r['total']/1e6 for r in rows], 50), 'p95ms': pct([r['total']/1e6 for r in rows], 95),
        'p99ms': pct([r['total']/1e6 for r in rows], 99), 'overDeadlinePct': round(100*sum(r['total']>r['deadline'] for r in eligible)/len(eligible), 3) if eligible else None,
        'droppedCallbacks': sum(r['dropped'] for r in rows)}

def phase(d, home):
    prefix = 'perf-' if home else ''
    fr = [{k: int(v) for k, v in r.items()} for r in csv.DictReader((d / (prefix+'frames.csv')).open(encoding='utf-8'))]
    mem = load(d / (prefix+'memory.json'))
    samples = load(d / (prefix+'samples.json'))
    result = load(d / 'perf-result.json') if home else {'spans': load(d / 'spans.json')}
    spans = result['spans']
    def group(s):
        if home:
            return ('fling-input' if s['part'] == 'finger' else 'fling') if 'fling' in s['phase'] else 'finger'
        return 'scroll' if s['phase'].startswith('scroll') else s['phase']
    groups = {}
    timeline = []
    trace = d.parent/'trace.perfetto-trace'
    if home and trace.exists():
        cache = d.parent/'home-timeline.json'
        if cache.exists():
            timeline = load(cache)
        else:
            processor = ROOT/'tmp/home-list-scroll-20261009/trace_processor_shell.exe'
            def query(sql):
                r = subprocess.run([str(processor), 'query', str(trace), sql], capture_output=True, check=True)
                return list(csv.DictReader(io.StringIO(r.stdout.decode())))
            clocks = query("SELECT ts-clock_value offset FROM clock_snapshot WHERE clock_name='MONOTONIC' LIMIT 1")
            layers = query("SELECT layer_name,COUNT(*) n FROM actual_frame_timeline_slice WHERE layer_name LIKE '%gifownervalidation%ThingsActivity%' AND layer_name NOT LIKE '%SurfaceView%' AND layer_name NOT LIKE '%Bounds for%' AND layer_name NOT LIKE '%Background for%' GROUP BY layer_name ORDER BY n DESC LIMIT 1")
            if clocks and layers:
                name = layers[0]['layer_name'].replace("'", "''")
                offset = int(clocks[0]['offset'])
                timeline = query("SELECT ts,dur,present_type,on_time_finish,jank_type,surface_frame_token FROM actual_frame_timeline_slice WHERE layer_name='" + name + "' ORDER BY ts")
                for r in timeline:
                    r['ns'] = int(r['ts']) - offset
            cache.write_text(json.dumps(timeline), encoding='utf-8')
    for name in sorted(set(group(s) for s in spans)):
        intervals = [s for s in spans if group(s) == name]
        def inside(ns): return any(s['start'] <= ns <= s['end'] for s in intervals)
        g = frames([r for r in fr if inside(r['vsync'])])
        g['seconds'] = round(sum((s['end']-s['start'])/1e9 for s in intervals), 3)
        g['memory'] = memory([r for r in mem if inside(r['ns'])])
        g['distancePx'] = sum(p.get('distancePx', p.get('distance', 0)) for p in result.get('phases', []) if ('fling' if 'fling' in p['name'] else 'finger') == name) if home else sum(s.get('distancePx', 0) for s in intervals)
        changes = defaultdict(set); seen = set(); running = 0; direct = set(); progression = defaultdict(list)
        for s in samples:
            if not inside(s['ns']): continue
            for c in s['cards']:
                identity = c.get('id', c.get('position'))
                seen.add(identity)
                if 'gif' in c:
                    changes[identity, c['gif']].add(c.get('frame'))
                    running += bool(c.get('playing', c.get('running')))
                    if 'direct' in c: direct.add(c['direct'])
                    if not home and c['position'] < 6 and c.get('running') and c.get('frame', -1) >= 0:
                        progression[c['position'], c['gif']].append((s['ns'], c['frame']))
        g.update(seen=len(seen), gifInstances=len(changes), advancingGifInstances=sum(len(v)>1 for v in changes.values()), runningSamples=running, mappedInputValues=sorted(direct))
        rates = []
        if not home and name == 'play':
            # 75 帧压力 GIF，100ms 采样短于一个完整循环；按同一实例连续播放段计算帧推进速度。
            for rows in progression.values():
                if len(rows) >= 10 and rows[-1][0]-rows[0][0] >= 1e9:
                    advanced = sum((b[1]-a[1]) % 75 for a, b in zip(rows, rows[1:]))
                    rates.append(advanced/((rows[-1][0]-rows[0][0])/1e9))
            g['largeGifAdvancePerSecond'] = {'n': len(rates), 'median': pct(rates, 50), 'min': min(rates, default=None)}
        if trace.exists() and home:
            ft = [r for r in timeline if inside(r['ns'])]
            g['frameTimeline'] = {'n': len(ft), 'latePct': round(100*sum(r['present_type']=='Late Present' for r in ft)/len(ft), 3) if ft else None,
                'jankPct': round(100*sum(r['jank_type'] not in ('None', 'None - None') for r in ft)/len(ft), 3) if ft else None}
        groups[name] = g
    return {'memory': memory(mem), 'groups': groups, 'coverage': {k: result.get(k) for k in ['seen', 'mediaReady', 'phases'] if k in result}}

all_results = {}
for path in sorted(BASE.glob('*/*/result.json')):
    raw = load(path)
    if not path.parent.name in ('shared', 'isolated'): continue
    item = {k: raw.get(k) for k in ['ok', 'stage', 'error', 'apkSha256', 'originalUnchanged', 'runtimeFailures', 'runningAfterFinish', 'duplicateSource'] if k in raw}
    if raw.get('ok') or raw.get('scenarioCompleted'):
        item['phases'] = {p: phase(path.parent/p, p.startswith('home')) for p in ['home-before', 'sequential', 'all-once', 'all-loop', 'home-after'] if (path.parent/p).is_dir()}
        item['cache'] = [e for e in raw['events'] if 'cacheBytes' in e]
        snapshots = [json.loads(line) for line in (path.parent/'media-memory.jsonl').read_text(encoding='utf-8').splitlines()]
        item['snapshots'] = [{k: s.get(k) for k in ['phase', 'pssKb', 'javaUsedBytes', 'nativeAllocatedBytes', 'knownGifFrameBitmapBytes', 'pool', 'trackedLoadersTotal']} |
            {'alive': len(s['aliveLoaders']), 'running': sum(r.get('running', False) for r in s['aliveLoaders']), 'uncleared': sum(not r.get('cleared', False) for r in s['aliveLoaders'])} for s in snapshots]
    all_results[str(path.parent.relative_to(BASE))] = item
(BASE/'analysis.json').write_text(json.dumps(all_results, ensure_ascii=False, indent=2), encoding='utf-8')
for name, item in all_results.items():
    print(name, 'ok', item.get('ok'), 'unchanged', item.get('originalUnchanged'), 'runningAfterFinish', item.get('runningAfterFinish'), 'error', item.get('error'))
    for p, data in item.get('phases', {}).items():
        print(p, 'PSS', data['memory']['pss'], 'windows', {n: {k: g[k] for k in ['n','p95ms','overDeadlinePct','droppedCallbacks','distancePx','advancingGifInstances']} for n,g in data['groups'].items()})
