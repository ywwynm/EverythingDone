"""绑定本轮视频、共同模型、双设备验证与发布包；不混用历史验收。"""
from pathlib import Path
import argparse,hashlib,json,sys,subprocess,zipfile
import xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/density-timing'
SERIALS=['9018f404','R5CW20BLNKL']
def read(p):return json.loads(p.read_text('utf-8'))
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def adb(serial,*args):
    return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe','-s',serial,*args]).decode('utf-8','replace').strip()

def main():
    p=argparse.ArgumentParser();p.add_argument('--published',action='store_true');a=p.parse_args()
    fingerprint=model_fingerprint();code=code_hash();resources=read(SHARED/'model.json')
    assert resources['model_hash']==fingerprint
    video=read(HERE/'analysis/video-qa.json');model=read(HERE/'analysis/model-qa.json')
    assert video['code_hash']==model['code_hash']==code and (video['count'],video['family_count'])==(128,34)
    assert len(model['model'])==18 and len(model['direction_checks'])==30
    family=read(HERE/'videos/family-videos.json')
    assert family['code_hash']==code and len(family['videos'])==34
    assert family['exporter_hash']==digest(HERE/'analysis/export_flow_family.py')
    assert family['focus_exporter_hash']==digest(HERE/'analysis/rim_review_media.py')
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
        data=read(HERE/'device-density-timing'/serial/'touch-ui-checks.json')
        assert [c['case'] for c in data['cases']]==['near','far','back'] and data['final']=='ThingsActivity'
        assert data['cases'][1]['strength']>data['cases'][0]['strength']+.3
        assert data['cases'][2]['direction']==135 and data['cases'][2]['strength']==.5
        ui.append(data)
    tests=ET.parse(ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.ywwynm.everythingdone.views.particledismiss.ParticleMicroflakeModelTest.xml').getroot()
    assert int(tests.get('tests'))==13 and int(tests.get('failures'))==int(tests.get('errors'))==0
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';apk_hash=digest(apk)
    with zipfile.ZipFile(apk) as z:
        assert json.loads(z.read('assets/particle-dismiss/model.json'))==resources
        for name,expected in resources['files'].items():assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==expected
    for serial in SERIALS:
        path=adb(serial,'shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
        assert adb(serial,'shell','sha256sum',path).split()[0]==apk_hash
        if a.published:
            published=read(HERE/'device-density-timing'/serial/'published-checks.json')
            assert published['apk_sha256']==apk_hash and published['overlay_cleared']
            assert published['returned_to']=='ThingsActivity'
    result=dict(model_hash=fingerprint,code_hash=code,videos=162,scenes=18,state_cases=234,distance_inputs=18,
        independent_device_cases=12,devices=SERIALS,device_position_max_px=maximum,jvm_tests=13,
        apk_sha256=apk_hash,real_touch_near_far_back=True,published=a.published,
        scope='实际渲染确认迎风覆盖、连续渐隐和单片展开；共同场、建材与触点规则保持一致，整帧外观误差用于消融，不等于视觉相似度。')
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
