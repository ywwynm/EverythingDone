"""反复纵向滚动的驱动呈现统计；区分拖动、惯性及按下后的起步长帧。"""
import argparse,collections,json,pathlib,statistics
from analyze_doing_refresh_rate import distribution

def analyze(folder,min_gestures=20):
    data=json.loads((folder/'report.json').read_text(encoding='utf-8'));events=data['events']
    assert data['droppedFrameEvents']==data['droppedLifecycleEvents']==0
    assert not any('failed' in e['event'] for e in events)
    submits=[e for e in events if e['event']=='particle_buffer_submitted']
    decisions={e['values']['frameId']:e['values'] for e in events if e['event']=='particle_presentation_decision'}
    time_origin=statistics.median(e['values']['submittedNanos']-e['elapsedMs']*1e6 for e in submits)
    driver_stamps=sorted({v['driverPresentNanos'] for v in decisions.values()
        if v['basis']=='driver_timestamp' and v['driverPresentNanos']>0})
    states=[e for e in events if e['event']=='doing_probe_scroll']
    markers={e['values']['action']:e['elapsedMs'] for e in events if e['event']=='doing_probe_command'}
    intervals=[];gestures=[];blocks=[]
    def segment(a,b):
        selected=[e for e in submits if a<=e['elapsedMs']<b]
        p=[decisions[e['values']['frameId']] for e in selected if e['values']['frameId'] in decisions]
        stamps=sorted({d['driverPresentNanos'] for d in p if d['basis']=='driver_timestamp' and d['driverPresentNanos']>0})
        gaps=[(y-x)/1e6 for x,y in zip(stamps,stamps[1:])]
        cpu=[(e['values']['submittedNanos']-e['values']['cpuStartNanos'])/1e6 for e in selected]
        return {'startMs':a,'endMs':b,'gaps':gaps,'submitted':len(selected),'verified':len(stamps),
                'presentedInWindow':sum(time_origin+a*1e6<=t<time_origin+b*1e6 for t in driver_stamps),
                'fps':round(1000/statistics.mean(gaps),2) if gaps else None,
                'intervalMs':distribution(gaps),'compositeWallMs':distribution(cpu)}
    for block in (name.removeprefix('measure_') for name in markers if name.startswith('measure_')):
        start,end=markers['measure_'+block],markers['end_'+block]
        for i,s in enumerate(states[:-1]):
            a=max(start,s['elapsedMs']);b=min(end,states[i+1]['elapsedMs'])
            if a>=b or s['values']['state']==0:continue
            other=states[i+1]
            # contact 用一条持续 14 秒的折返输入，首尾都在顶端；净位移为零不代表中间没有滚动。
            if s['values']['offset']==other['values']['offset'] and block!='contact':continue
            result=segment(a,b)
            result.update({'block':block,'state':s['values']['state'],
                'offsetStart':s['values']['offset'],'offsetEnd':other['values']['offset']})
            intervals.append(result)
        for i,s in enumerate(states[:-1]):
            if s['values']['state']!=1 or not start<=s['elapsedMs']<end:continue
            after=next((v for v in states[i+1:] if v['values']['state']==0),states[-1])
            # 每次拖动和其惯性一起核对；下一次 DOWN 通常先使旧滚动进入 idle。
            b=min(end,after['elapsedMs']);a=s['elapsedMs']
            moved=[v for v in states[i+1:] if a<v['elapsedMs']<=b and v['values']['offset']!=s['values']['offset']]
            if not moved and block!='contact':continue
            result=segment(a,b);result.update({'block':block,'offsetStart':s['values']['offset'],
                'offsetEnd':after['values']['offset']})
            gestures.append(result)
        group=[r for r in intervals if r['block']==block]
        if block=='contact':
            assert len(group)==1 and group[0]['state']==1 and 13000<group[0]['endMs']-group[0]['startMs']<15000
        block_summary=aggregate(group,block)
        block_summary['drag']=aggregate([r for r in group if r['state']==1],'drag')
        block_summary['fling']=aggregate([r for r in group if r['state']==2],'fling')
        blocks.append(block_summary)
    assert len(gestures)>=min_gestures,(folder,len(gestures))
    captures=[e for e in events if e['event']=='swipe_capture_begin']
    ready=[e for e in events if e['event']=='swipe_capture_ready']
    assert len(captures)==len(ready),(folder,len(captures),len(ready))
    pairs=[b['elapsedMs']-a['elapsedMs'] for a,b in zip(captures,ready)]
    result={'device':folder.parent.name,'run':folder.name,'build':data['metadata']['build'],
        'basis':dict(collections.Counter(v['basis'] for v in decisions.values())),
        'aggregate':aggregate(intervals,'all'),'blocks':blocks,
        'drag':aggregate([r for r in intervals if r['state']==1],'drag'),
        'fling':aggregate([r for r in intervals if r['state']==2],'fling'),
        'gestures':gestures,'intervals':intervals,'swipeSnapshotLatencyMs':distribution(pairs),
        'horizontalActivationCount':sum(e['event']=='swipe_activated' for e in events),
        'clockTicks':sum(e['event']=='doing_probe_tick' for e in events),
        'scrollGeometry':states[-1]['values']}
    return result

def aggregate(rows,name):
    gaps=[g for r in rows for g in r['gaps']]
    duration=sum(r['endMs']-r['startMs'] for r in rows if 'endMs' in r)
    frames=sum(r.get('verified',0) for r in rows)
    presented=sum(r.get('presentedInWindow',0) for r in rows)
    return {'name':name,'segments':len(rows),'fps':round(1000/statistics.mean(gaps),2) if gaps else None,
        'activeDurationMs':round(duration,3) if duration else None,'matchedPresentedFrames':frames if duration else None,
        'matchedFramesPerActiveSecond':round(frames*1000/duration,2) if duration else None,
        # 用完整活动时段作分母，把开头、末尾以及完全没有新帧的时间也保留下来。
        'presentedFramesInWindow':presented if duration else None,
        'presentedFramesPerActiveSecond':round(presented*1000/duration,2) if duration else None,
        'intervalMs':distribution(gaps),'over12_5msPct':round(100*sum(g>12.5 for g in gaps)/len(gaps),2) if gaps else None,
        'over25msCount':sum(g>25 for g in gaps),'over50msCount':sum(g>50 for g in gaps)}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=pathlib.Path,required=True)
    parser.add_argument('--expected-runs',type=int,default=6)
    parser.add_argument('--min-gestures',type=int,default=20);args=parser.parse_args()
    runs=[analyze(p.parent,args.min_gestures) for p in sorted(args.root.glob('*/*/report.json')) if not (p.parent/'error.json').exists()]
    assert len(runs)==args.expected_runs,(len(runs),args.expected_runs)
    (args.root/'analysis.json').write_text(json.dumps(runs,ensure_ascii=False,indent=2),encoding='utf-8')
    for r in runs:
        print(r['device'],r['run'],json.dumps({'all':r['aggregate'],'blocks':r['blocks'],
            'gestureCount':len(r['gestures']),'snapshot':r['swipeSnapshotLatencyMs']},ensure_ascii=False))
