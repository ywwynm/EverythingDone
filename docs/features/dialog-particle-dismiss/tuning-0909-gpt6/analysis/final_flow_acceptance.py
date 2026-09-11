"""检查本轮修复、视频、设备与发布证据一致，避免混入被否决候选或旧发布记录。"""
from pathlib import Path
import json,sys,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,model_fingerprint
from export_videos import code_hash
OUT=HERE/'analysis/motion-field-extension'

def read(path):return json.loads(path.read_text('utf-8'))

def main():
    fingerprint=model_fingerprint();render_hash=code_hash()
    regression=read(OUT/'regression.json');assert regression['model_hash']==fingerprint
    assert regression['annotated_cases']==226 and regression['gallery_inputs']==7
    assert regression['original_long_marks']==853 and len(regression['extra_inputs'])==4
    assert regression['new_short_windows']==0 and regression['old_short_windows']>10000
    video=read(HERE/'analysis/video-qa.json');assert video['code_hash']==render_hash and video['count']==110
    model=read(HERE/'analysis/model-qa.json');assert model['code_hash']==render_hash and len(model['model'])==17
    gpu=read(OUT/'device-fast-clock/device-comparison.json');assert gpu['model_hash']==fingerprint and len(gpu['cases'])==6
    units=[]
    for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*Particle*.xml'):
        suite=ET.fromstring(path.read_bytes());assert all(suite.get(k)=='0' for k in ['failures','errors','skipped'])
        units.append(dict(name=suite.get('name'),tests=int(suite.get('tests'))))
    assert sum(s['tests'] for s in units)==15
    remote=read(OUT/'remote-publication.json');assert remote['model_hash']==fingerprint
    assert remote['remoteMetadataExact'] and remote['remoteApkExact'] and len(remote['resources_verified'])==8
    meta=read(ROOT/'app/build/outputs/update-debug-apk/latest.json');assert remote['sha256']==meta['sha256']
    devices=[]
    for serial in ['9018f404','R5CW20BLNKL']:
        ui=read(HERE/'device-flow-fast-clock'/serial/'variation-ui.json')
        assert ui['model_hash']==fingerprint and ui['unique_seeds']==3
        published=read(HERE/'device-flow-published'/serial/'published-checks.json')
        assert published['installedSha256']==remote['sha256'] and published['debugUpdateCode']==remote['debugUpdateCode']
        assert published['launchAndDismissPassed'] and published['particleLayersAfter']==published['dimLayersAfter']==0
        devices.append(published)
    result=dict(model_hash=fingerprint,render_hash=render_hash,debugUpdateCode=remote['debugUpdateCode'],
        apkUrl=remote['apkUrl'],sha256=remote['sha256'],state_cases=233,extra_cases=4,long_marks=853,
        normal_videos=110,focused_videos=9,unit_tests=units,independent_android_cases=6,published_devices=devices,
        scope='数值回归、重点画面、实机独立生成和发布身份一致；不宣称所有可能输入或主观美感已获得用户验收。')
    (OUT/'final-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['unit_tests','published_devices']},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
