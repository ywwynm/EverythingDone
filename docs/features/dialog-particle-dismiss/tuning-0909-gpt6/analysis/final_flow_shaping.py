"""核对当前模型、视频、设备和实际发布 APK；不以主观指标宣称视觉一致。"""
from pathlib import Path
import sys,json,hashlib,zipfile,urllib.request
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/flow-shaping'
def read(p):return json.loads(p.read_text('utf-8'))
fingerprint=model_fingerprint();resources=read(SHARED/'model.json');assert resources['model_hash']==fingerprint
qa=read(HERE/'analysis/video-qa.json');assert qa['count']==118 and qa['code_hash']==code_hash()
model=read(HERE/'analysis/model-qa.json');assert model['code_hash']==code_hash() and len(model['model'])==17
before=read(OUT/'audit-before.json');after=read(OUT/'audit-after.json')
assert len(before)==len(after)==233
assert [(x['id'],x['scene'],x['angle'],x['seed']) for x in before]==[(x['id'],x['scene'],x['angle'],x['seed']) for x in after]
assert sum(x['stagnant_windows'] for x in after)==0 and sum(x['reverse_steps'] for x in after)==0
shape_before=read(OUT/'shape-before.json');shape_after=read(OUT/'shape-after.json');ratios=[]
for old,new in zip(shape_before,shape_after):
    target={(x['frame'],x['group']):x for x in new['cohorts']}
    for x in old['cohorts']:
        y=target.get((x['frame'],x['group']))
        if x['edge'] and x['ratio']<.08 and y:ratios.append(y['nonrigid']/max(x['nonrigid'],1e-8))
assert np.median(ratios)>2
distance=read(OUT/'shaped.json');assert distance['model_hash']==fingerprint
for name in ['ironman','attachment','color']:
    for angle in [135,90]:
        rows=sorted([r for r in distance['cases'] if r['scene']==name and r['angle']==angle and r['gap'] is not None],key=lambda r:r['gap'])
        assert len(rows)==3 and rows[0]['axial_median']<rows[1]['axial_median']<rows[2]['axial_median']
device=read(OUT/'device-comparison/device-comparison.json');far=read(OUT/'device-far-comparison/device-comparison.json')
assert device['model_hash']==far['model_hash']==fingerprint and len(device['cases'])+len(far['cases'])==14
published=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
apk=ROOT/'app/build/outputs/update-debug-apk'/published['apkUrl'].rsplit('/',1)[1]
assert hashlib.sha256(apk.read_bytes()).hexdigest()==published['sha256']
with zipfile.ZipFile(apk) as z:
    assert json.loads(z.read('assets/particle-dismiss/model.json'))==resources
    for name,digest in resources['files'].items():assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==digest
devices=[]
for serial in ['9018f404','R5CW20BLNKL']:
    ui=read(HERE/'device-flow-shaping'/serial/'touch-ui-checks.json')
    assert ui['cases'][1]['gap']>ui['cases'][0]['gap']*1.7 and ui['cases'][2]['direction']==135 and ui['cases'][2]['gap']==.65
    installed=read(HERE/'device-flow-shaping-published'/serial/'published-checks.json')
    assert installed['installedSha256']==published['sha256'] and installed['debugUpdateCode']==published['debugUpdateCode'] and installed['launchAndDismissPassed']
    assert installed['particleLayersAfter']==installed['dimLayersAfter']==0 and 'gap=0.65' in installed['render']
    devices.append(installed)
url=published['apkUrl'].split('/debug/apk/')[0]+'/debug/latest.json'
with urllib.request.urlopen(urllib.request.Request(url,headers={'Cache-Control':'no-cache'}),timeout=30) as response:remote=json.load(response)
assert remote['sha256']==published['sha256'] and remote['debugUpdateCode']==published['debugUpdateCode']
h=hashlib.sha256();size=0
with urllib.request.urlopen(urllib.request.Request(published['apkUrl'],headers={'Cache-Control':'no-cache'}),timeout=30) as response:
    while True:
        chunk=response.read(1024*1024)
        if not chunk:break
        h.update(chunk);size+=len(chunk)
assert h.hexdigest()==published['sha256'] and size==apk.stat().st_size
report=dict(model_hash=fingerprint,code_hash=code_hash(),videos=118,state_cases=233,rigid_cohorts=len(ratios),
    nonrigid_residual_median_ratio=float(np.median(ratios)),
    axial_distance_median_ratio=float(np.median([b['axial_median']/a['axial_median'] for a,b in zip(before,after)])),
    independent_device_cases=14,shared_resources=list(resources['files']),
    debugUpdateCode=published['debugUpdateCode'],apkUrl=published['apkUrl'],sha256=published['sha256'],remote_bytes=size,
    devices=devices,scope='状态指标不等同于华为运动真值或主观审美验收；设备离屏计时不等同于系统显示帧率。')
(OUT/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='devices'},ensure_ascii=False),flush=True)
