"""分析逐一播放与真实键盘滚动，保留首次加载和已显示后空白的区别。"""
from pathlib import Path
from collections import Counter
import json
import sys
import statistics

ROOT=Path(__file__).resolve().parents[4]/'tmp/detail-gif-flicker'
failed=False
for name in [a for a in sys.argv[1:] if a!='--verify']:
    folder=ROOT/name
    data=json.loads((folder/'samples.json').read_text())
    result=json.loads((folder/'result.json').read_text())
    gestures=json.loads((folder/'gestures.json').read_text())
    log=(folder/'process.log').read_text(encoding='utf-8',errors='replace')
    runtime_failures={token:log.count(token) for token in (
        'FATAL EXCEPTION','OutOfMemoryError','Cannot draw recycled','Cannot start a recycled')}
    scenes=[]
    for stage in dict.fromkeys(r['stage'] for r in data):
        rows=[r for r in data if r['stage']==stage]
        scenes.append(dict(stage=stage,samples=len(rows),
            blank=sum(r['type']=='null' or r['opaque']==0 for r in rows),
            transparent=sum(r['alpha']<.99 for r in rows),
            unexpectedRunning=sum(r['running'] and not r['shouldPlay'] for r in rows),
            types=dict(Counter(r['type'] for r in rows))))
    blanks=[{k:r.get(k) for k in ('t','stage','pos','type','frame','running','shouldPlay','opaque','alpha','request','load')}
        for r in data if r['type']=='null' or r['opaque']==0 or r['alpha']<.99]
    running=Counter(r['draw'] for r in data if r.get('draw') is not None and r['running'])
    ime=[r for r in data if r['stage']=='sequential-ime-fling']
    coverage=bool(ime) and len(ime)>100 and len({r['pos'] for r in ime})>=2
    gesture_summary=dict(count=len(gestures),medianDurationMs=statistics.median([g['durationMs'] for g in gestures]) if gestures else None,
        moved=sum(g['scrollOffsetDelta']!=0 for g in gestures),ime=sum(g['ime'] for g in gestures),
        settling=sum(g['scrollState']==2 for g in gestures))
    summary=dict(run=name,ok=result.get('ok'),originalUnchanged=result.get('originalUnchanged'),
        samples=len(data),positions=sorted({r['pos'] for r in data}),imeCoverage=coverage,
        runtimeFailures=runtime_failures,
        unmappedRunning=sum(r['running'] and not r.get('mapped',False) for r in data) if any('mapped' in r for r in data) else None,
        maxSimultaneouslyRunning=max(running.values(),default=0),gestures=gesture_summary,
        events=result.get('events',[]),scenes=scenes,blanks=blanks)
    (folder/'sequential-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(name, json.dumps(dict(samples=len(data),blanks=len(blanks),imeBlanks=sum(r['type']=='null' or r['opaque']==0 for r in ime),
        imeCoverage=coverage,positions=summary['positions'],maxRunning=summary['maxSimultaneouslyRunning'],gestures=gesture_summary),ensure_ascii=False))
    failed |= not result.get('ok') or not result.get('originalUnchanged') or bool(blanks) or not coverage or summary['maxSimultaneouslyRunning']>1 or any(s['unexpectedRunning'] for s in scenes) or any(runtime_failures.values()) or bool(summary['unmappedRunning'])
sys.exit(1 if '--verify' in sys.argv and failed else 0)
