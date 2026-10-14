"""用设备端 ACTION_UP 时间核对惯性阶段，单独统计松手一秒后的持续滚动。"""
import argparse,json,pathlib,re,statistics
from analyze_doing_real_checklist import analyze,aggregate
from analyze_doing_refresh_rate import distribution


def analyze_fling(folder,min_gestures):
    result=analyze(folder,min_gestures)
    report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
    events=report['events']
    submits=[e for e in events if e['event']=='particle_buffer_submitted']
    base=statistics.median(e['values']['submittedNanos']-e['elapsedMs']*1e6 for e in submits)
    decisions={e['values']['frameId']:e['values'] for e in events if e['event']=='particle_presentation_decision'}
    ups=sorted((int(t)-base)/1e6 for p in folder.glob('fling_*-input.txt')
        for t in re.findall(r'HLS_UP (\d+)',p.read_text()))
    segments=[s for s in result['intervals'] if s['state']==2]
    assert len(segments)==len(ups),(folder,len(segments),len(ups))
    release_lags=[];tails=[];first100=[];after100=[]
    def selected(a,b):
        stamps=sorted({decisions[e['values']['frameId']]['driverPresentNanos'] for e in submits
            if a<=e['elapsedMs']<b and e['values']['frameId'] in decisions
            and decisions[e['values']['frameId']]['basis']=='driver_timestamp'
            and decisions[e['values']['frameId']]['driverPresentNanos']>0})
        return {'gaps':[(y-x)/1e6 for x,y in zip(stamps,stamps[1:])]}
    for s,up in zip(segments,ups):
        lag=s['startMs']-up
        assert -5<lag<100,(folder,lag)
        release_lags.append(lag)
        first100.append(selected(s['startMs'],min(up+100,s['endMs'])))
        after100.append(selected(max(s['startMs'],up+100),s['endMs']))
        if s['endMs']>up+1000:tails.append(selected(up+1000,s['endMs']))
    result['flingValidation']={
        'upEvents':len(ups),'movingSettlingSegments':len(segments),
        'upToSettlingMs':distribution(release_lags),
        'settlingDurationMs':distribution([s['endMs']-s['startMs'] for s in segments]),
        'settlingDistancePx':distribution([abs(s['offsetEnd']-s['offsetStart']) for s in segments]),
        'settlingOver1sCount':len(tails),
        'first100ms':aggregate(first100,'first100ms'),
        'after100ms':aggregate(after100,'after100ms'),
        'after1s':aggregate(tails,'after1s')}
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=pathlib.Path,required=True)
    parser.add_argument('--expected-runs',type=int,default=6)
    parser.add_argument('--min-gestures',type=int,default=20);args=parser.parse_args()
    runs=[analyze_fling(p.parent,args.min_gestures) for p in sorted(args.root.glob('*/*/report.json'))
          if not (p.parent/'error.json').exists()]
    assert len(runs)==args.expected_runs
    (args.root/'fling-analysis.json').write_text(json.dumps(runs,ensure_ascii=False,indent=2),encoding='utf-8')
    for r in runs:print(json.dumps({k:r[k] for k in ('device','run','drag','fling','flingValidation')},ensure_ascii=False))
