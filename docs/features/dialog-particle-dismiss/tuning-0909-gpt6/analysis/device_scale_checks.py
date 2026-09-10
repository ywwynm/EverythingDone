import subprocess,re,json,sys
from pathlib import Path
here=Path(__file__).resolve().parent;root=here.parent/'device-r33'/'R5CW20BLNKL';base=['E:/AndroidSDK/platform-tools/adb.exe','-s','R5CW20BLNKL']
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace').strip()
def run(script,*args):subprocess.run([sys.executable,'-X','utf8',str(here/script),*args],capture_output=True,check=True)
def log():return adb('logcat','-d','-s','ParticleMicroflake:I','*:S')
original=adb('shell','settings','get','global','animator_duration_scale');assert original=='1.0',original
rows=[]
try:
    for scale in [2.,.5,0.,1.]:
        adb('shell','settings','put','global','animator_duration_scale',str(scale))
        assert float(adb('shell','settings','get','global','animator_duration_scale'))==scale
        run('device_ui.py','R5CW20BLNKL','tap','--id','com.ywwynm.everythingdone:id/act_add_attachment','--expect','tv_take_photo_as_bt','--name',f'scale-{scale}')
        old=log();run('record_dismiss.py','R5CW20BLNKL',f'attachment-scale-{scale}','--back');after=log()
        new=[line for line in after.splitlines() if '完成 count=' in line and line not in old]
        row={'scale':scale,'render':new}
        if scale:
            assert len(new)==1,new
            elapsed=float(re.search(r'elapsedMs=([\d.]+)',new[-1]).group(1));assert abs(elapsed-scale*1000)<70,(scale,elapsed)
            row['elapsedMs']=elapsed
        else:assert not new,new
        run('device_ui.py','R5CW20BLNKL','dump','--expect','act_add_attachment','--name',f'scale-{scale}-after')
        hierarchy=adb('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.DetailActivity')
        assert 'ParticleDismissOverlay{' not in hierarchy
        assert not re.findall(r'^          android\.view\.View\{',hierarchy,re.M)
        row['mainUiRestored']=True;row['noResidualLayers']=True
        rows.append(row);print(json.dumps(row),flush=True)
finally:
    adb('shell','settings','put','global','animator_duration_scale',original)
    restored=adb('shell','settings','get','global','animator_duration_scale')
    (root/'scale-checks.json').write_text(json.dumps({'original':original,'restored':restored,'tests':rows},indent=2),encoding='utf-8')
