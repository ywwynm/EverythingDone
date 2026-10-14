"""按真实到底部和设备端急停时刻分析，排除静止期及准备手势。"""
import argparse,json,pathlib,re,statistics


def distribution(values):
    if not values:return {}
    values=sorted(values)
    return {'count':len(values),'median':round(statistics.median(values),3),
            'max':round(max(values),3)}


def analyze(folder):
    report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
    geometry=json.loads((folder/'geometry.json').read_text(encoding='utf-8'))
    rows=json.loads((folder/'beat-trace.json').read_text())
    events=report['events']
    submits=[e for e in events if e['event']=='particle_buffer_submitted']
    assert submits,'本分析要求整页 GL 的相同时间源记录'
    base=statistics.median(e['values']['submittedNanos']-e['elapsedMs']*1e6 for e in submits)
    commands={e['values']['action']:base+e['elapsedMs']*1e6
              for e in events if e['event']=='doing_probe_command'}
    observer=20 if any(r[0]==20 for r in rows) else 18
    states=[r for r in rows if r[0]==observer]
    edges=[];unavailable=[]
    for name,start in commands.items():
        if not name.startswith('measure_edge_bottom_'):continue
        label=name.removeprefix('measure_');end=commands['end_'+label]
        if (len(rows)>=48000 and states[0][1]>start) or states[-1][1]<end:
            unavailable.append(label)
            continue
        rr=[r for r in states if start<=r[1]<end]
        changes=[r for j,r in enumerate(rr) if not j or r[5]!=rr[j-1][5]]
        active=[r for r in rr if r[5]>0 or r[7]==0]
        scroll=[e for e in events if e['event']=='doing_probe_scroll' and start<=base+e['elapsedMs']*1e6<end]
        idle=[e['values'] for e in scroll if e['values']['state']==0]
        reached=bool(idle) and any(v['offset']>=v['range']-v['extent']-1 for v in idle)
        assert reached,(folder,label,'没有到达底部',idle)
        assert active,(folder,label,'没有触发底部效果')
        capture_times=sorted(r[2] for r in rows if r[0]==5 and active[0][1]-2e6<=r[2]<end)
        # 第一次边界绘制及其下一次绘制，旧版和新版都使用同一 PAGE_CAPTURE 时刻。
        first_capture_gap=(capture_times[1]-capture_times[0])/1e6 if len(capture_times)>1 else None
        first=next(r for r in active if r[5]>0)
        onset=(first[1]-active[0][1])/1e6
        active_changes=[r for j,r in enumerate(active) if not j or r[5]!=active[j-1][5]]
        edges.append({'label':label,'reachedBottom':reached,'bottomOffset':max(v['offset'] for v in idle),
            'absorbToFirstDistanceMs':round(onset,3),
            'firstCaptureGapAfterBottomMs':round(first_capture_gap,3) if first_capture_gap is not None else None,
            'activeTraversalGapsMs':distribution([(b[1]-a[1])/1e6 for a,b in zip(active,active[1:])]),
            'activeDistanceChangeGapsMs':distribution([(b[1]-a[1])/1e6 for a,b in zip(active_changes,active_changes[1:])]),
            'animationClockAheadMs':distribution([(r[8]-r[1])/1e6 for r in active])})
    stops=[]
    for path in sorted(folder.glob('stop_*-input.txt')):
        text=path.read_text();down=int(re.search(r'HLS_STOP_DOWN (\d+)',text)[1])
        up=int(re.search(r'HLS_UP (\d+)',text)[1])
        cancel=int(re.search(r'HLS_STOP_CANCEL (\d+)',text)[1])
        at=[e for e in events if e['event']=='doing_probe_scroll' and up-2e7<=base+e['elapsedMs']*1e6<cancel]
        settling=[e for e in at if e['values']['state']==2 and base+e['elapsedMs']*1e6<down]
        # 按下打断惯性时 RecyclerView 立即转 DRAGGING，随后 CANCEL 才转 IDLE。
        stopped=[e for e in at if e['values']['state'] in (0,1) and base+e['elapsedMs']*1e6>=down]
        before=[e for e in events if e['event']=='doing_probe_scroll' and base+e['elapsedMs']*1e6<=down]
        assert settling and before[-1]['values']['state']==2,(folder,path.name,'按下前已不在惯性滚动')
        assert stopped,(folder,path.name,'按下后未停止惯性')
        stop=base+stopped[0]['elapsedMs']*1e6
        moves=[r for r in rows if r[0]==17 and down<=r[1]<cancel]
        stops.append({'label':path.stem,'upToDownMs':round((down-up)/1e6,3),
            'downToStopMs':round((stop-down)/1e6,3),'contentMovesAfterDown':len(moves),
            'lastMoveAfterDownMs':round(max([(r[1]-down)/1e6 for r in moves],default=0),3)})
    assert not any(any(w in e['event'] for w in ('failed','timeout','aborted')) for e in events)
    return {'device':folder.parent.name,'run':folder.name,'fixture':geometry.get('fixture','real'),
            'edges':edges,'stops':stops,'rows':len(rows),'stateObserver':'actual_draw' if observer==20 else 'pre_draw','unavailableWindows':unavailable,
            'schedulerContractsPassed':any(e['event']=='doing_frame_callbacks_verified' for e in events)}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=pathlib.Path);args=p.parse_args()
    results=[analyze(p.parent) for p in sorted(args.root.glob('*/*/report.json'))]
    (args.root/'edge-analysis.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    for r in results:
        print(json.dumps({'device':r['device'],'fixture':r['fixture'],'impacts':len(r['edges']),
            'bounceOnsetMs':[e['absorbToFirstDistanceMs'] for e in r['edges']],
            'firstCaptureGapsMs':[e['firstCaptureGapAfterBottomMs'] for e in r['edges']],
            'stopResponseMs':[s['downToStopMs'] for s in r['stops']]},ensure_ascii=False))
