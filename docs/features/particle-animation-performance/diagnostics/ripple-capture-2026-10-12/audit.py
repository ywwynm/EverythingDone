import json,pathlib,sys
import numpy as np
from PIL import Image
import metrics
ROOT=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path(__file__).parent
rows=[]
for name in ['samsung','meizu','oppo']:
    for folder in sorted((ROOT/name).glob('*')):
        path=folder/'result.json'
        if not path.exists():continue
        if not (folder.name.startswith(('candidate3-', 'final-', 'published-', 'drain-')) or folder.name.startswith('candidate-custom-dismiss') or
                folder.name.startswith('candidate-custom-transition') or folder.name.startswith('baseline-custom-transition') or
                folder.name.startswith('baseline2-native-doing-control')):continue
        data=json.loads(path.read_text(encoding='utf8'));ev=data['report']['events']
        material=[]
        for path in folder.glob('material-*-false.png'):
            a=np.array(Image.open(path).convert('RGBA'),dtype=np.int16)
            delta=np.maximum(a[:,:,2]-a[:,:,0],0)
            material.append({'file':path.name,'bluePixels':int(((delta>5)&(a[:,:,3]>200)).sum())})
        pixels=[]
        for r in data['samples']:
            path=folder/f'button-{r["frame"]}.png'
            if not path.exists():continue
            a=np.array(Image.open(path).convert('RGB'),dtype=np.int16)
            pixels.append((r['ms'],float(np.maximum(a[:,:,2]-a[:,:,0],0).mean())))
        tail=[v for t,v in pixels if t>=1500]
        peak=max((v for t,v in pixels),default=0)
        tail_max=max(tail,default=None)
        zero_after=next((t for i,(t,v) in enumerate(pixels) if t>=300 and v<.1 and all(w<.1 for _,w in pixels[i:])),None)
        click=next((e['elapsedMs'] for e in ev if e['event']=='ripple_probe_click'),0)
        late_page_frames=[e for e in ev if e['event']=='doing_scene_frame' and e['elapsedMs']>click+1800]
        first=[{'reverse':e['values']['reverse'],'delayMs':round(e['elapsedMs']-click,2)}
               for e in ev if e['event']=='particle_first_frame_callback' and e['elapsedMs']>=click]
        bad=[e for e in ev if any(w in e['event'] for w in ['failed','timeout','aborted'])]
        passed=(bool(material) and all(m['bluePixels']==0 for m in material)) if 'doing-control' not in folder.name else (peak>2 and tail_max is not None and tail_max<.1)
        row={'device':name,'run':folder.name,'materials':material,'peakBlue':round(peak,3),'tailMaxBlue':tail_max,
             'latePageFrameEvents':len(late_page_frames),
             'zeroAfterMs':zero_after,'firstParticleCallback':first,'bad':bad,'passed':passed}
        rows.append(row)
        if folder.name.startswith('baseline') or folder.name=='published-native-doing-control-drift-unified-1791812876':assert not passed,row
        else:assert passed and not bad,row
        if folder.name.startswith('drain-') and 'doing-control' in folder.name:assert not late_page_frames,row
(ROOT/'ripple-audit.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf8')
print('ripple expected baseline failures',sum(not r['passed'] for r in rows),'candidate passes',sum(r['passed'] for r in rows))
perf=[]
for stage in [p.name.removeprefix('perf-') for p in sorted(ROOT.glob('perf-*')) if p.is_dir()]:
    for path in sorted((ROOT/('perf-'+stage)).glob('*/*/harness/report.json')):
        row=metrics.audit(path);row['stage']=stage;perf.append(row)
        print(stage,row['device'],row['style'],'fps',round(row['driverFps'] or 0,1),'p90',round(row['intervalP90Ms'] or 0,1),'latency',round(row['gestureToParticleCallbackMs'] or 0,1),'bad',len(row['bad']))
        assert not row['bad'],row
(ROOT/'perf-audit.json').write_text(json.dumps(perf,ensure_ascii=False,indent=2),encoding='utf8')
