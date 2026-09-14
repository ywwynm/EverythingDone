"""分析真实系统输入采样；GPU 等待和纹理回调分别报告，不混作物理显示帧率。"""
import json
import math
import struct
from pathlib import Path
import device_ui as ui

ROOT=ui.ROOT/'performance'

def stats(values):
    a=sorted(float(x) for x in values)
    if not a: return None
    def p(q): return round(a[max(0,math.ceil(len(a)*q)-1)],3)
    return dict(n=len(a),p50=p(.5),p95=p(.95),p99=p(.99),max=round(a[-1],3))

def intervals(values):
    return [(b-a)/1e6 for a,b in zip(values,values[1:]) if b>a]


def save_order(d):
    if d['config'].get('kind') not in ('save-button','save-system-back'): return None
    results=[]
    for o in d['overlays']:
        if not o['reverse'] or o['stage']=='open': continue
        committed=o.get('committed',[]); submitted=o.get('submitted',[])
        if not committed or not submitted:
            results.append({'passed':False,'reason':'missing particle frames'}); continue
        observed=[s for s in d['states'] if o['requestAt']<=s[0]<committed[-1][1]]
        early=sum(s[4]>.01 and s[5]==0 for s in observed)
        overlap=sum(bool(s[2]) for s in observed)
        last=committed[-1][0]==submitted[-1][0]
        hidden=sum(s[0]<o['requestAt'] and s[2] and s[5]==4 for s in d['states'])
        results.append(dict(earlyVisible=early,overlapping=overlap,lastFrameCommitted=last,
                            hiddenDuringSpace=hidden,passed=early==0 and overlap==0 and last and hidden>0))
    return results or None

def phase(d):
    inputs=d['inputs']; overlays=d['overlays']
    start=inputs[0][0] if inputs else d['startedAt']
    end=max([o.get('removedAt',start) for o in overlays]+[
        e['at'] for e in d['events'] if e['event']=='editor-ready']+[start])
    if end<=start:
        # 普通滑动和非粒子保存：结束点由恢复到静止状态的最后一帧界定。
        busy=[r[0] for r in d['states'] if r[1] or r[2] or r[3] or r[6] != 0]
        end=max(busy+[r[0] for r in inputs]+[start])+20_000_000
    wf=[r for r in d['windowFrames'] if start<=r[0]<=end]
    deadlines=[r for r in wf if r[9]>0]
    main=[t for t in d['mainFrames'] if start<=t<=end]
    gpu=[]
    for o in overlays:
        submitted=o.get('submitted',[]); committed=o.get('committed',[]); draws=o.get('draw',[])
        activated=o.get('gestureActivatedAt',0)
        moving={r[0] for r in submitted if len(r)>2 and struct.unpack('f',struct.pack('I',r[2]&0xffffffff))[0]>0}
        first_moving=next((r[1] for r in committed if r[0] in moving),None)
        active_submitted=[r for r in submitted if r[1]>=activated]
        active_committed=[r for r in committed if r[1]>=activated]
        submit_set={r[0] for r in submitted}
        seen=set(); textures=[]
        for r in o.get('textures',[]):
            if r[0]==0 and r[1]>=activated and r[2] in submit_set and r[2] not in seen:
                seen.add(r[2]); textures.append(r[1])
        playback=[r for r in d['windowFrames'] if committed and
                  committed[0][1]<=r[0]<=committed[-1][1] and
                  r[12]==(1 if o['stage']=='open' else 0)]
        gpu.append(dict(stage=o['stage'],size=[o['width'],o['height']],
            firstCommitMs=round((committed[0][1]-o['requestAt'])/1e6,3) if committed else None,
            firstMovementAfterActivationMs=round((first_moving-activated)/1e6,3) if first_moving and activated else None,
            firstMovementAfterDownMs=round((first_moving-start)/1e6,3) if first_moving and activated else None,
            requestAfterInputMs=round((o['requestAt']-start)/1e6,3),
            submitted=len(submitted),committed=len(committed),
            playbackWindowMs=stats(r[1]/1e6 for r in playback),
            playbackDeadlineMissed=sum(r[9]>0 and r[1]>r[9] for r in playback),
            playbackDeadlineFrames=sum(r[9]>0 for r in playback),
            submitGapMs=stats(intervals([r[0] for r in active_submitted])),
            textureGapMs=stats(intervals(textures)),commitGapMs=stats(intervals([r[1] for r in active_committed])),
            glSubmitMs=stats([(r[2]-r[1])/1e6 for r in draws]),
            glWaitMs=stats([(r[3]-r[2])/1e6 for r in draws]),
            glReadyMs=stats([(r[3]-r[1])/1e6 for r in draws]),
            swapMs=stats([(r[4]-r[3])/1e6 for r in draws])))
    return dict(config=d['config'],durationMs=round((end-start)/1e6,2),
        events=d['events'],inputEvents=len(inputs),keyEvents=sum(r[1]==1 for r in inputs),
        windowTotalMs=stats(r[1]/1e6 for r in wf),windowGpuMs=stats(r[8]/1e6 for r in wf if r[8]>=0),
        deadlineMissed=sum(r[1]>r[9] for r in deadlines),deadlineFrames=len(deadlines),
        mainGapMs=stats(intervals(main)),overlays=gpu,
        cpuObservedMs=d['cpuEndMs']-d['cpuStartMs'],memoryStart=d['memoryStart'],memoryEnd=d['memoryEnd'],
        saveOrder=save_order(d))

reports={}
for p in sorted(ROOT.glob('*/result.json')):
    d=json.loads(p.read_text())
    if d.get('schema')!=2 or d.get('driver')!='system-input-observed' or d.get('config',{}).get('memoryOnly') or d.get('config',{}).get('diagnosticOnly'): continue
    reports[p.parent.name]=phase(d)
(ROOT/'summary.json').write_text(json.dumps(reports,indent=2),encoding='utf-8')
for name,d in reports.items():
    print(name,json.dumps({k:d[k] for k in ['durationMs','inputEvents','keyEvents','windowTotalMs','deadlineMissed','deadlineFrames','mainGapMs','overlays']}))
