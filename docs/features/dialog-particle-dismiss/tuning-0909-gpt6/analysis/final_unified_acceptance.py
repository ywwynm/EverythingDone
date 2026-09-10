"""汇总已实际完成的验证，拒绝混用旧模型、旧视频或旧设备证据。"""
from pathlib import Path
import sys,json,hashlib,zipfile,xml.etree.ElementTree as ET,urllib.request
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash
def read(p):return json.loads(p.read_text('utf-8'))
out=HERE/'analysis/unified-validation';frozen=read(out/'freeze.json');fingerprint=model_fingerprint()
assert fingerprint==frozen['model_hash']
contract=read(out/'contract.json');assert contract['frozen_model_hash']==fingerprint and contract['renamed_and_poisoned_profiles_pixel_identical']
qa=read(HERE/'analysis/model-qa.json');assert qa['code_hash']==code_hash() and len(qa['model'])==13
trace=read(HERE/'analysis/trajectory-qa.json');assert trace['model_hash']==fingerprint and len(trace['cases'])==130
assert all(r['reverse_steps_over_002px']==0 for r in trace['cases'])
videos=read(HERE/'analysis/video-qa.json');assert videos['code_hash']==code_hash() and videos['count']==78
gpu=read(out/'device-comparison.json');assert gpu['model_hash']==fingerprint and len(gpu['cases'])==26
unit=[]
for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*Particle*.xml'):
    suite=ET.fromstring(path.read_bytes());row={k:suite.get(k) for k in ['name','tests','failures','errors','skipped']}
    assert all(row[k]=='0' for k in ['failures','errors','skipped']),row
    unit.append(row)
assert sum(int(x['tests']) for x in unit)==10
meta=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
apk=ROOT/'app/build/outputs/update-debug-apk'/meta['apkUrl'].rsplit('/',1)[1]
assert hashlib.sha256(apk.read_bytes()).hexdigest()==meta['sha256']
resources=read(SHARED/'model.json');assert resources['model_hash']==fingerprint
with zipfile.ZipFile(apk) as z:
    assert read(SHARED/'model.json')==json.loads(z.read('assets/particle-dismiss/model.json'))
    for name,digest in resources['files'].items():
        assert hashlib.sha256((SHARED/name).read_bytes()).hexdigest()==digest
        assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==digest
devices=[]
for serial in ['9018f404','R5CW20BLNKL']:
    d=HERE/'device-unified'/serial
    published=read(d/'published-checks.json');assert published['installedSha256']==meta['sha256'] and published['launchAndDismissPassed']
    directions=read(d/'direction-checks.json');assert len(directions)==8 and max(v['error'] for v in directions)<.06
    rapid=read(d/'rapid-checks.json');assert len(rapid['iterations'])==10 and rapid['particleLayersAfter']==rapid['dimLayersAfter']==0
    devices.append({'published':published,'directions':directions,'rapid':rapid})
url='http://127.0.0.1:13070/'
with urllib.request.urlopen(url+'index.html') as response:
    html=response.read().decode('utf-8');assert '统一参数验证' in html and 'holdout-alpha' in html
with urllib.request.urlopen(urllib.request.Request(url+'ironman-compare-versions-0.5x.mp4',headers={'Range':'bytes=0-1023'})) as response:
    assert response.status==206 and len(response.read())==1024
report={'model_hash':fingerprint,'development_scenes':7,'strictly_new_holdouts':5,'previously_reviewed_but_untuned_holdouts':1,
        'scenes':13,'unit_tests':unit,'trajectory_cases':130,'visible_material_frame_pairs':trace['total_visible_pairs'],
        'videos':78,'video_mib':sum(v['bytes'] for v in read(HERE/'videos/manifest.json')['videos'])/1024**2,
        'independent_device_cases':26,'resources_verified':list(resources['files']),'published':meta,'devices':devices,
        'limits':['综合引导场来自既有参考学习，但所有素材只用这一份参数。','留出通知背景是重建估计，不能证明隐藏内容真实。',
        '两端半透明解码存在预乘量化；不透明材料严格对照，半透明寿命误差低于一个 120 Hz 样本。',
        '轨迹和离屏验证不能代替视觉判断或实际显示帧率，华为局部侵蚀形态仍有差异。',
        '颜色弹窗关闭后编辑器输入法恢复可能遮挡动画尾段。']}
(out/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['scenes','strictly_new_holdouts','trajectory_cases','visible_material_frame_pairs','videos','video_mib','independent_device_cases']},ensure_ascii=False))
print('发布包、双设备安装身份及全部统一规则证据通过，更新',meta['debugUpdateCode'])
