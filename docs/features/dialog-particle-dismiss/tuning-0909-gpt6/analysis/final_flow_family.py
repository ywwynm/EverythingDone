"""核对当前共同形态候选的桌面、实际设备及本地 APK；不把旧发布包计为当前。"""
from pathlib import Path
import hashlib, json, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/flow-family'


def read(path):
    return json.loads(path.read_text('utf-8'))


def adb(serial,*args):
    return subprocess.run(['E:/AndroidSDK/platform-tools/adb.exe','-s',serial,*args],capture_output=True,check=True).stdout.decode('utf-8','replace').strip()


def main():
    fingerprint=model_fingerprint();code=code_hash();resources=read(SHARED/'model.json')
    assert resources['model_hash']==fingerprint
    qa=read(HERE/'analysis/video-qa.json');model=read(HERE/'analysis/model-qa.json')
    assert qa['code_hash']==code and qa['count']==128 and qa['family_count']==14
    assert model['code_hash']==code and len(model['model'])==18 and len(model['direction_checks'])==30
    family=read(HERE/'videos/family-videos.json')
    assert family['code_hash']==code and len(family['videos'])==14
    assert family['exporter_hash']==hashlib.sha256((HERE/'analysis/export_flow_family.py').read_bytes()).hexdigest()
    metrics=read(OUT/'metrics.json');regression=read(OUT/'regression.json')
    assert metrics['model_hash']==regression['model_hash']==fingerprint
    assert len(metrics['distance_cases'])==18 and len(metrics['preservation'])==3
    assert len(regression['cases'])==234
    assert not any(c['stagnant_windows'] or c['reverse_steps'] for c in regression['cases'])
    ratios=[]
    for scene in ['ironman','attachment','color']:
        for direction in [135,90]:
            rows=sorted([r for r in metrics['distance_cases'] if r['scene']==scene and r['requested']==direction],key=lambda r:r['gap'])
            assert len(rows)==3
            meta=read(HERE/f'assets/{scene}/scene.json');x0,y0,x1,y1=meta['touch_rect'];cx0,cy0,cx1,cy1=meta['rect']
            for row in rows:
                x,y=row['point'];assert x0<=x<=x1 and y0<=y<=y1
                assert not(cx0<=x<=cx1 and cy0<=y<=cy1)
            item=dict(scene=scene,direction=direction)
            for key in ['speed_median','displacement_median']:
                assert 0<rows[0][key]<rows[1][key]<rows[2][key]<rows[0][key]*1.6
                item[key+'_far_near_ratio']=rows[2][key]/rows[0][key]
            ratios.append(item)
    independent=[]
    for suffix,count in [('',6),('-local',2),('-multiple',2),('-side',1),('-near',1),('-far',1)]:
        report=read(OUT/f'device{suffix}/device-comparison.json')
        assert report['model_hash']==fingerprint and len(report['cases'])==count
        for case in report['cases']:
            assert case['device']=='9018f404'
            middle=next(f for f in case['frames'] if f['frame']==34)
            assert middle['position_p99_px']<.25 and middle['position_max_px']<1
        independent.extend(report['cases'])
    ui=read(HERE/'device-flow-family/9018f404/touch-ui-checks.json')
    assert [x['case'] for x in ui['cases']]==['near','far','back'] and ui['final']=='ThingsActivity'
    assert ui['cases'][1]['gap']>ui['cases'][0]['gap']*1.7
    assert ui['cases'][2]['direction']==135 and ui['cases'][2]['gap']==.65
    test_file=ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.ywwynm.everythingdone.views.particledismiss.ParticleMicroflakeModelTest.xml'
    test=ET.parse(test_file).getroot()
    assert int(test.get('tests'))==12 and int(test.get('failures'))==int(test.get('errors'))==0
    assert test_file.stat().st_mtime>(ROOT/'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleReleaseTopology.kt').stat().st_mtime
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(apk) as archive:
        assert json.loads(archive.read('assets/particle-dismiss/model.json'))==resources
        for name,expected in resources['files'].items():
            assert hashlib.sha256(archive.read('assets/particle-dismiss/'+name)).hexdigest()==expected
    path=adb('9018f404','shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
    assert adb('9018f404','shell','sha256sum',path).split()[0]==digest
    unavailable=subprocess.run(['E:/AndroidSDK/platform-tools/adb.exe','-s','R5CW20BLNKL','get-state'],capture_output=True,text=True)
    assert unavailable.returncode!=0 and 'unauthorized' in unavailable.stdout+unavailable.stderr
    # 当前是本地审阅候选。旧远端元数据只记为历史，不请求或覆盖它。
    previous=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
    assert previous['sha256']!=digest
    result=dict(model_hash=fingerprint,code_hash=code,videos=142,scenes=18,state_cases=234,
        distance_inputs=18,distance_ratios=ratios,baseline_preservation=metrics['preservation'],
        independent_device_cases=len(independent),jvm_tests=12,local_apk_sha256=digest,
        device='9018f404',real_touch_and_back=True,unavailable_devices=[dict(serial='R5CW20BLNKL',reason='USB 调试未授权')],
        published=False,previous_publication=previous['debugUpdateCode'],
        status='桌面及 Android 本地候选已核验，三星设备待授权；尚未发布',
        scope='保留旧模型并扩展形态可能性；选过的示例种子不计为留出，数值检查不等于华为逐帧复刻或用户审美验收。')
    (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['distance_ratios','baseline_preservation']},ensure_ascii=False))


if __name__=='__main__':
    main()
