"""拆分指定真实清单的整页捕获与驱动完成耗时；详细取证不并入基线 FPS。"""
import argparse,collections,json,pathlib,statistics
from analyze_doing_refresh_rate import distribution


def analyze(folder,phase='all'):
    rows=json.loads((folder/'beat-trace.json').read_text(encoding='utf-8'))
    report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
    assert len(rows)<48000, '详细取证可能已覆盖最早记录'
    assert report['droppedFrameEvents']==report['droppedLifecycleEvents']==0
    events=report['events']
    submits=[e for e in events if e['event']=='particle_buffer_submitted']
    base=statistics.median(e['values']['submittedNanos']-e['elapsedMs']*1e6 for e in submits)
    markers={e['values']['action']:e['elapsedMs'] for e in events if e['event']=='doing_probe_command'}
    windows=[(start,markers['end_'+key.removeprefix('measure_')])
        for key,start in markers.items() if key.startswith('measure_')]
    if phase!='all':
        states=[e for e in events if e['event']=='doing_probe_scroll']
        ranges=[]
        for s,n in zip(states,states[1:]):
            if s['values']['state']!=2 or s['values']['offset']==n['values']['offset']:continue
            a,b=s['elapsedMs'],n['elapsedMs']
            if phase=='fling-start':b=min(b,a+100)
            elif phase=='fling-later':a+=100
            ranges.extend((max(a,lo),min(b,hi)) for lo,hi in windows if max(a,lo)<min(b,hi))
        windows=ranges
    def inside(t):return any(a<=(t-base)/1e6<=b for a,b in windows)
    captures=[r for r in rows if r[0]==5 and inside(r[1])]
    images=[r for r in rows if r[0]==8 and inside(r[1])]
    requests=[r for r in rows if r[0]==9 and inside(r[1])]
    acquires=[r for r in rows if r[0]==14 and inside(r[1])]
    uploads=[r for r in rows if r[0]==15 and inside(r[1]) and r[3]>0]
    details={name:distribution([(r[i]-r[i-1])/1e6 for r in selected])
        for selected,fields in [(acquires,[(2,'acquireMs'),(3,'producerFenceMs'),(4,'wrapBitmapMs')]),
                                (uploads,[(2,'glQueueMs'),(3,'importMs'),(4,'recycleMs')])]
        for i,name in fields}
    chain={}
    for i,label in [(2,'imageAvailableMs'),(3,'fenceMs'),(4,'mainDispatchMs'),(5,'uploadMs'),(6,'releaseGateMs')]:
        chain[label]=distribution([(r[i]-r[i-1])/1e6 for r in images])
    chain['totalMs']=distribution([(r[6]-r[1])/1e6 for r in images])
    frame_events={e['values']['frameId']:e['values'] for e in submits}
    complete=[r for r in rows if r[0]==7 and r[1] in frame_events and inside(r[3]) and r[4]>0]
    # 驱动完成墙钟包含排队／GPU，不能标成纯 GPU 执行时长。
    gpu_wall=[(r[4]-frame_events[r[1]]['cpuStartNanos'])/1e6 for r in complete]
    assert all(t>=0 for t in gpu_wall)
    def cadence(kind,index):
        gaps=[]
        for a,b in windows:
            stamps=sorted({r[index] for r in rows if r[0]==kind and a<=(r[index]-base)/1e6<b})
            gaps.extend((y-x)/1e6 for x,y in zip(stamps,stamps[1:]))
        return {'fps':round(1000/statistics.mean(gaps),2) if gaps else None,'intervalMs':distribution(gaps)}
    return {'device':folder.parent.name,'run':folder.name,'traceRows':len(rows),
        'scope':phase,'windows':len(windows),
        'imageDetails':details,
        'recordMs':distribution([(r[3]-r[2])/1e6 for r in captures]),
        'syncMs':distribution([(r[4]-r[3])/1e6 for r in captures]),
        'chain':chain,'requests':len(requests),'requestsWhileBusy':sum(r[2] for r in requests),
        'hardwareSyncStatuses':dict(collections.Counter(r[5] for r in captures)),
        'recordOver8ms':sum((r[3]-r[2])>8e6 for r in captures),
        'syncOver8ms':sum((r[4]-r[3])>8e6 for r in captures),
        'compositeStartToDriverRenderCompleteMs':distribution(gpu_wall),
        # 回调可能多于实际输出节拍，不能用原始回调次数宣称物理面板刷新率。
        'clockCallbackCadence':cadence(0,1),'outputBeatCadence':cadence(0,3),
        'captureCadence':cadence(5,1),'requestCadence':cadence(9,1),
        'snapshotRetiredDuringWindows':sum(e['event']=='hardware_snapshot_retired'
            and any(a<=e['elapsedMs']<b for a,b in windows) for e in events)}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=pathlib.Path,required=True)
    parser.add_argument('--expected-runs',type=int,default=2);args=parser.parse_args()
    runs=[]
    for p in sorted(args.root.glob('*/*/beat-trace.json')):
        r=analyze(p.parent)
        if any(e['event']=='doing_probe_command' and str(e['values'].get('action','')).startswith('measure_fling')
               for e in json.loads((p.parent/'report.json').read_text())['events']):
            r['flingPhases']={phase:analyze(p.parent,phase) for phase in ('fling','fling-start','fling-later')}
        runs.append(r)
    assert len(runs)==args.expected_runs
    (args.root/'analysis.json').write_text(json.dumps(runs,ensure_ascii=False,indent=2),encoding='utf-8')
    for r in runs:print(json.dumps(r,ensure_ascii=False))
