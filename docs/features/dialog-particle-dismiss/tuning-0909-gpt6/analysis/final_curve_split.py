"""把本轮视频、状态回归、跨端输入、实际关闭和安装包绑定为同一结果。"""
from pathlib import Path
import argparse,hashlib,json,sys,subprocess,zipfile
import xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/curve-split'
def read(p):return json.loads(p.read_text('utf-8'))
def adb(*args):return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe','-s','9018f404',*args]).decode('utf-8','replace').strip()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--published',action='store_true');args=parser.parse_args()
    fingerprint=model_fingerprint();code=code_hash();resources=read(SHARED/'model.json')
    assert resources['model_hash']==fingerprint
    qa=read(HERE/'analysis/video-qa.json');model=read(HERE/'analysis/model-qa.json')
    assert qa['code_hash']==model['code_hash']==code and (qa['count'],qa['family_count'])==(128,16)
    assert len(model['model'])==18 and len(model['direction_checks'])==30
    family=read(HERE/'videos/family-videos.json')
    assert family['code_hash']==code and len(family['videos'])==16
    assert family['exporter_hash']==hashlib.sha256((HERE/'analysis/export_flow_family.py').read_bytes()).hexdigest()
    metrics=read(OUT/'metrics.json');regression=read(OUT/'regression.json')
    assert metrics['model_hash']==regression['model_hash']==fingerprint
    assert len(metrics['distance_cases'])==18 and len(regression['cases'])==234
    assert not any(c['stagnant_windows'] or c['reverse_steps'] for c in regression['cases'])
    for c in metrics['distance_cases']:
        meta=read(HERE/f'assets/{c["scene"]}/scene.json');x,y=c['point'];l,t,r,b=meta['touch_rect'];cl,ct,cr,cb=meta['rect']
        assert l<=x<=r and t<=y<=b and not(cl<=x<=cr and ct<=y<=cb)
    device=[]
    for suffix,n in [('',6),('-local',2),('-multiple',2),('-side',1),('-near',1),('-far',1)]:
        report=read(OUT/f'device{suffix}/device-comparison.json')
        assert report['model_hash']==fingerprint and len(report['cases'])==n
        device.extend(report['cases'])
    maximum=max(f.get('position_max_px',0) for r in device for f in r['frames'])
    assert maximum<1
    ui=read(HERE/'device-curve-split/9018f404/touch-ui-checks.json')
    assert [r['case'] for r in ui['cases']]==['near','far','back'] and ui['final']=='ThingsActivity'
    assert ui['cases'][1]['strength']>ui['cases'][0]['strength']+.3
    assert ui['cases'][2]['direction']==135 and ui['cases'][2]['strength']==.5
    tests=ET.parse(ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.ywwynm.everythingdone.views.particledismiss.ParticleMicroflakeModelTest.xml').getroot()
    assert int(tests.get('tests'))==13 and int(tests.get('failures'))==int(tests.get('errors'))==0
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(apk) as z:
        assert json.loads(z.read('assets/particle-dismiss/model.json'))==resources
        for name,expected in resources['files'].items():assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==expected
    installed=adb('shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
    assert adb('shell','sha256sum',installed).split()[0]==digest
    result=dict(model_hash=fingerprint,code_hash=code,videos=144,scenes=18,state_cases=234,distance_inputs=18,
        independent_device_cases=13,device_position_max_px=maximum,jvm_tests=13,apk_sha256=digest,
        real_touch_near_far_back=True,published=args.published,unavailable_device='R5CW20BLNKL：USB 调试未授权',
        scope='恢复横向弧边和下方拖尾的可见分离；亮度、时序仍有参考差异，数值检查不替代审美验收。')
    if args.published:
        import urllib.request
        local=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
        with urllib.request.urlopen('http://120.25.194.207/everythingdone-updates/debug/latest.json',timeout=60) as response:remote=json.load(response)
        assert local==remote and remote['sha256']==digest
        url=remote.get('apkUrl',remote.get('downloadUrl'))
        assert url,'发布元数据缺少 APK 地址'
        h=hashlib.sha256()
        with urllib.request.urlopen(url,timeout=60) as response:
            for chunk in iter(lambda:response.read(1024*1024),b''):h.update(chunk)
        assert h.hexdigest()==digest
        result.update(debug_update_code=remote['debugUpdateCode'],apk_url=url,remote_apk_verified=True)
    (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
