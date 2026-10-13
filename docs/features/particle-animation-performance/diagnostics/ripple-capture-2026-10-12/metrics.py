import pathlib,json,numpy as np,zipfile
ROOT=pathlib.Path(__file__).parent
def audit(path):
    r=json.loads(path.read_text(encoding='utf8'));ev=r['events']; f=path.parents[1]
    event=lambda n:[e for e in ev if e['event']==n]
    active=event('swipe_activated'); visible=event('swipe_pixels_visible'); end=event('swipe_dispose')
    bad=[e for e in ev if any(w in e['event'] for w in ['failed','timeout','aborted'])]
    ids={e['values']['frameId'] for e in event('particle_buffer_submitted') if e['values']['sessions']>0 and visible and end and visible[0]['elapsedMs']+150 <= e['elapsedMs'] <=end[0]['elapsedMs']}
    pts=sorted(set(e['values']['driverPresentNanos'] for e in event('particle_presentation_decision') if e['values']['frameId'] in ids and e['values'].get('basis')=='driver_timestamp' and e['values'].get('driverPresentNanos',0)>0))
    intervals=np.diff(pts)/1e6
    fps=(len(pts)-1)*1e9/(pts[-1]-pts[0]) if len(pts)>5 else None
    scenes=event('doing_scene_frame'); frames=[(e['values']['frames'],e['elapsedMs']) for e in scenes]
    span=[(n,t) for n,t in frames if active and end and active[0]['elapsedMs']-1000<t<end[0]['elapsedMs']+1000]
    hz=(span[-1][0]-span[0][0])*1000/(span[-1][1]-span[0][1]) if len(span)>1 else None
    row={'device':f.parent.name,'run':f.name,'style':r['metadata']['particleStyle'],'scene':r['scene'],'bad':bad,
        'gestureToParticleCallbackMs':visible[0]['elapsedMs']-active[0]['elapsedMs'] if active and visible else None,
        'driverFps':fps,'intervalP90Ms':float(np.quantile(intervals,.9)) if len(intervals)>5 else None,
        'pageUpdateHz':hz,'final':event('doing_probe_final_state')[-1]['values'] if event('doing_probe_final_state') else None,
        'completions':len(event('doing_probe_swipe_finished')),'disposed':end[-1]['values'] if end else None,
        'backends':sorted(set(e['values']['backend'] for e in event('particle_backend'))),
        'firstPageMs':event('doing_scene_ready')[0]['elapsedMs'] if event('doing_scene_ready') else None,
        'geometrySwitches':len(event('surface_geometry_switch')),
        'droppedFrameEvents':r['droppedFrameEvents'],'droppedLifecycleEvents':r['droppedLifecycleEvents']}
    for p in (f/'harness').glob('diagnostics.zip'):
        with zipfile.ZipFile(p) as z: assert z.testzip() is None
    return row
if __name__=='__main__':
    rows=[]
    for p in sorted(ROOT.glob('*/glscene*/harness/report.json')):
        row=audit(p); rows.append(row)
        print(row['device'],row['run'],*[None if row[k] is None else round(row[k],1) for k in ['gestureToParticleCallbackMs','driverFps','pageUpdateHz']],row['bad'],row['completions'])
    (ROOT/'metrics.json').write_bytes(json.dumps(rows,ensure_ascii=False,indent=2).encode('utf8'))
