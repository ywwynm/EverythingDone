"""两台指定设备的独立建材、实际 GPU 与固定材料来源回归。"""
from pathlib import Path
import subprocess,sys,json
import numpy as np
HERE=Path(__file__).resolve().parents[1]
OUT='analysis/recorded-filament-trace'
def call(script,*args):
    subprocess.run([sys.executable,'-X','utf8',str(HERE/'analysis'/script),*args],check=True)
scenes=['ironman','thanos','kobe','attachment','color','language','holdout-compact-dialog',
        'user-device-1','user-device-2','user-device-3']
for serial in ['9018f404','R5CW20BLNKL']:
    call('validate_unified_devices.py',serial,'--output',OUT+'/device-final','--scenes',*scenes)
    call('compare_unified_devices.py','--device-dir',OUT+'/device-final','--report-dir',OUT+'/checks-'+serial,
         '--serials',serial,'--scenes',*scenes[:7])
    call('compare_unified_devices.py','--device-dir',OUT+'/device-final','--report-dir',OUT+'/recordings-'+serial,
         '--serials',serial,'--input-directories',*[str(HERE/'analysis/device-filament-origins'/f'input-{j}') for j in [1,2,3]])
    call('validate_unified_devices.py',serial,'--output',OUT+'/device-down','--scenes','attachment','color',
         '--direction','270','--seed','909602')
    call('compare_unified_devices.py','--device-dir',OUT+'/device-down','--report-dir',OUT+'/down-'+serial,
         '--serials',serial,'--scenes','attachment','color','--direction','270','--seed','909602')
    fixture=json.loads((HERE/'analysis/filament-cohort.json').read_text('utf-8'))
    folder=HERE/OUT/'device-down'/serial/'generated'
    base=np.fromfile(folder/'attachment-materials.f32','<f4').reshape(-1,12)
    state=np.fromfile(folder/'attachment-state018.f32','<f4').reshape(-1,8)
    mask=np.isin(base[:,3].astype('int64'),fixture['ids']);assert mask.sum()==940
    width=float(np.diff(np.percentile(state[mask,:2]@fixture['transverse_axis'],[10,90]))[0])
    assert width>=fixture['source_width80']*.5,(serial,width)
    result=dict(serial=serial,count=int(mask.sum()),width80=width,baseline_width80=fixture['baseline_width80'])
    (folder/'filament-cohort.json').write_text(json.dumps(result,indent=2),'utf-8')
    print('真实 GPU 固定来源细缕回归通过',json.dumps(result),flush=True)
