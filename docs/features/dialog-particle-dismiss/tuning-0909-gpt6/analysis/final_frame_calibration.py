"""绑定本轮视频、共同模型、双设备验证与发布包；不混用历史验收。"""
from pathlib import Path
import argparse,hashlib,json,sys,subprocess,zipfile
import xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/frame-difference'
SERIALS=['9018f404','R5CW20BLNKL']
def read(p):return json.loads(p.read_text('utf-8'))
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def adb(serial,*args):
    return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe','-s',serial,*args]).decode('utf-8','replace').strip()

def main():
    global OUT
    p=argparse.ArgumentParser();p.add_argument('--published',action='store_true')
    p.add_argument('--analysis-dir',default='frame-difference');p.add_argument('--device-dir',default='device-frame-difference');a=p.parse_args()
    OUT=HERE/'analysis'/a.analysis_dir
    device_dir=HERE/a.device_dir
    fingerprint=model_fingerprint();code=code_hash();resources=read(SHARED/'model.json')
    assert resources['model_hash']==fingerprint
    video=read(HERE/'analysis/video-qa.json');model=read(HERE/'analysis/model-qa.json')
    assert video['code_hash']==model['code_hash']==code and (video['count'],video['family_count'])==(128,36)
    assert len(model['model'])==18 and len(model['direction_checks'])==30
    family=read(HERE/'videos/family-videos.json')
    assert family['code_hash']==code and len(family['videos'])==36
    assert family['exporter_hash']==digest(HERE/'analysis/export_flow_family.py')
    assert family['focus_exporter_hash']==digest(HERE/'analysis/rim_review_media.py')
    assert family['pixel_exporter_hash']==digest(HERE/'analysis/export_pixel_difference.py')
    metrics=read(OUT/'metrics.json');regression=read(OUT/'regression.json')
    assert metrics['model_hash']==regression['model_hash']==fingerprint
    assert len(metrics['distance_cases'])==18 and len(regression['cases'])==234
    assert not any(c['stagnant_windows'] or c['reverse_steps'] for c in regression['cases'])
    for c in metrics['distance_cases']:
        m=read(HERE/f'assets/{c["scene"]}/scene.json');x,y=c['point'];l,t,r,b=m['touch_rect'];cl,ct,cr,cb=m['rect']
        assert l<=x<=r and t<=y<=b and not(cl<=x<=cr and ct<=y<=cb)
    report=read(OUT/'device/device-comparison.json')
    assert report['model_hash']==fingerprint and len(report['cases'])==12
    maximum=max(f.get('position_max_px',0) for c in report['cases'] for f in c['frames'])
    assert maximum<1
    ui=[]
    for serial in SERIALS:
        data=read(device_dir/serial/'touch-ui-checks.json')
        assert [c['case'] for c in data['cases']]==['near','far','back'] and data['final']=='ThingsActivity'
        assert data['cases'][1]['strength']>data['cases'][0]['strength']+.3
        assert data['cases'][2]['direction']==135 and data['cases'][2]['strength']==.5
        ui.append(data)
    tests=ET.parse(ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.ywwynm.everythingdone.views.particledismiss.ParticleMicroflakeModelTest.xml').getroot()
    assert int(tests.get('tests'))==14 and int(tests.get('failures'))==int(tests.get('errors'))==0
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';apk_hash=digest(apk)
    with zipfile.ZipFile(apk) as z:
        assert json.loads(z.read('assets/particle-dismiss/model.json'))==resources
        for name,expected in resources['files'].items():assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==expected
    for serial in SERIALS:
        path=adb(serial,'shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
        assert adb(serial,'shell','sha256sum',path).split()[0]==apk_hash
        if a.published:
            published=read(device_dir/serial/'published-checks.json')
            assert published['apk_sha256']==apk_hash and published['overlay_cleared']
            assert published['returned_to']=='ThingsActivity'
    result=dict(model_hash=fingerprint,code_hash=code,videos=164,scenes=18,state_cases=234,distance_inputs=18,
        independent_device_cases=12,devices=SERIALS,device_position_max_px=maximum,jvm_tests=14,
        apk_sha256=apk_hash,real_touch_near_far_back=True,published=a.published,
        scope='共同释放、输运与颗粒材质的本轮验证；完整帧误差和局部瑕疵分别核验，不据此声称单粒子像素一致。')
    if (OUT/'separation-check.json').exists():
        separation=read(OUT/'separation-check.json')
        assert separation['model_hash']==fingerprint and separation['actual_energy']<separation['limit']
        result['filament_regression']=separation
        result['frame_error_summary']=metrics['appearance']['summary']
    if (OUT/'release-regression.json').exists():
        release=read(OUT/'release-regression.json')
        assert release['passed'] and release['model_hash']==fingerprint
        extra=read(OUT/'device-reproductions/device-comparison.json')
        assert extra['model_hash']==fingerprint and len(extra['cases'])==6
        assert video['reproduction_count']==6
        all_devices=report['cases']+extra['cases']
        assert all(c['grid_jitter_max_cell']<1e-6 for c in all_devices)
        assert all(f['alpha_mae']<.25 and f['composited_rgb_mae']<.25 for c in all_devices for f in c['frames'])
        assert all({34,40}.issubset({f['frame'] for f in c['frames'] if 'position_max_px' in f}) for c in all_devices)
        clips=read(HERE/'videos/release-filament-videos.json')
        assert clips['code_hash']==code and len(clips['videos'])==6
        for item in clips['videos']:
            assert item['code_hash']==code and digest(HERE/'videos'/item['file'])==item['sha256']
        for name,expected in clips['inputs'].items():assert digest(OUT/name)==expected
        result.update(release_filament_regression=release,recording_input_device_cases=6,extra_reproduction_videos=6,
            total_current_videos=170,device_grid_jitter_max_cell=max(c['grid_jitter_max_cell'] for c in all_devices),
            device_composited_rgb_mae_max=max(f['composited_rgb_mae'] for c in all_devices for f in c['frames']))
    if a.published:
        import urllib.request
        local=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
        with urllib.request.urlopen('http://120.25.194.207/everythingdone-updates/debug/latest.json',timeout=45) as response:remote=json.load(response)
        assert local==remote and remote['sha256']==apk_hash
        url=remote.get('apkUrl',remote.get('downloadUrl'));assert url
        h=hashlib.sha256()
        with urllib.request.urlopen(url,timeout=45) as response:
            for chunk in iter(lambda:response.read(1024*1024),b''):h.update(chunk)
        assert h.hexdigest()==apk_hash
        result.update(debug_update_code=remote['debugUpdateCode'],apk_url=url,remote_apk_verified=True,published_return_checks=SERIALS)
    (OUT/('acceptance.json' if a.published else 'local-acceptance.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
