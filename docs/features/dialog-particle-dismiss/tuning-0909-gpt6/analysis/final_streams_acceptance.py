"""汇总卷曲流束与内容色的已完成证据，不重跑实验或改动参数。"""
from pathlib import Path
import sys,json,hashlib,zipfile,re,urllib.request,xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,SHARED,model_fingerprint
from export_videos import code_hash

def read(path):return json.loads(path.read_text('utf-8'))

out=HERE/'analysis/streams-content';fingerprint=model_fingerprint();render_hash=code_hash()
scenes=read(HERE/'assets/scenes.json');count=len(scenes)
assert count==15 and sum(not m.get('holdout') for m in scenes)==7
assert read(out/'freeze.json')['model_hash']==fingerprint
contract=read(out/'contract.json')
assert contract['frozen_model_hash']==fingerprint and contract['renamed_and_poisoned_profiles_pixel_identical']
assert len(contract['cases'])==count and all(c['channel_permutation_identical_material'] for c in contract['cases'])
qa=read(HERE/'analysis/model-qa.json');assert qa['code_hash']==render_hash and len(qa['model'])==count
trace=read(HERE/'analysis/trajectory-qa.json')
assert trace['model_hash']==fingerprint and trace['code_hash']==render_hash and len(trace['cases'])==count*10
assert all(c['reverse_steps_over_002px']==0 for c in trace['cases'])
videos=read(HERE/'analysis/video-qa.json');assert videos['code_hash']==render_hash and videos['count']==videos['expected_final']==86
gpu=read(out/'device-comparison.json');assert gpu['model_hash']==fingerprint and len(gpu['cases'])==count*2
unit=[]
for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*Particle*.xml'):
    suite=ET.fromstring(path.read_bytes());row={k:suite.get(k) for k in ['name','tests','failures','errors','skipped']}
    assert all(row[k]=='0' for k in ['failures','errors','skipped']),row
    unit.append(row)
assert sum(int(row['tests']) for row in unit)==12
meta=read(ROOT/'app/build/outputs/update-debug-apk/latest.json')
apk=ROOT/'app/build/outputs/update-debug-apk'/meta['apkUrl'].rsplit('/',1)[1]
assert hashlib.sha256(apk.read_bytes()).hexdigest()==meta['sha256']
remote=read(out/'remote-publication.json')
assert remote['sha256']==meta['sha256'] and remote['debugUpdateCode']==meta['debugUpdateCode']
assert remote['remoteMetadataExact'] and remote['remoteApkExact'] and remote['model_hash']==fingerprint
resources=read(SHARED/'model.json');assert resources['model_hash']==fingerprint
with zipfile.ZipFile(apk) as z:
    assert resources==json.loads(z.read('assets/particle-dismiss/model.json'))
    for name,digest in resources['files'].items():
        assert hashlib.sha256((SHARED/name).read_bytes()).hexdigest()==digest
        assert hashlib.sha256(z.read('assets/particle-dismiss/'+name)).hexdigest()==digest
devices=[]
for serial in ['9018f404','R5CW20BLNKL']:
    d=HERE/'device-streams'/serial;published=read(d/'published-checks.json')
    assert published['installedSha256']==meta['sha256'] and published['debugUpdateCode']==meta['debugUpdateCode']
    assert published['launchAndDismissPassed'] and published['particleLayersAfter']==published['dimLayersAfter']==0
    assert float(re.search(r'direction=([-\d.]+)',published['render']).group(1))==135
    ui=read(d/'ui-checks.json');assert len(ui)==4 and all(c['error']<.001 for c in ui)
    title=next(c for c in ui if c['case']=='language-back-after-title-touch')
    assert title['actualDirection']==135 and title['touchToBackMs']<300
    directions=read(d/'direction-checks.json');assert len(directions)==8 and max(c['error'] for c in directions)<.06
    rapid=read(d/'rapid-checks.json')
    assert len(rapid['iterations'])==10 and rapid['dialogWindowsAfter']==rapid['particleLayersAfter']==rapid['dimLayersAfter']==0
    devices.append({'published':published,'ui':ui,'directions':directions,'rapid':rapid})
recordings=read(HERE/'device-streams/videos/manifest.json');assert len(recordings['videos'])==4
assert all((HERE/'device-streams/videos'/v['file']).is_file() for v in recordings['videos'])
url='http://127.0.0.1:13070/'
with urllib.request.urlopen(url+'index.html') as r:
    assert r.status==200
    html=r.read().decode('utf-8');assert '卷曲流束与内容色' in html and 'holdout-colored-panel' in html
with urllib.request.urlopen(urllib.request.Request(url+'ironman-compare-versions-0.5x.mp4',headers={'Range':'bytes=0-1023'})) as r:
    assert r.status==206 and len(r.read())==1024
assert '桌面预览已离屏渲染验证' in (out/'viewer-verification.log').read_text('utf-8-sig')
report={'model_hash':fingerprint,'code_hash':render_hash,'development_scenes':7,'regression_scenes':6,'new_untuned_scenes':2,
        'freeze_provenance':'初次冻结后创建两个新输入；随后仅依据语言开发场景修正低透明度量化并重新冻结，未根据新输入调参。',
        'scenes':count,'unit_tests':unit,'trajectory_cases':len(trace['cases']),'visible_material_frame_pairs':trace['total_visible_pairs'],
        'videos':videos['count'],'video_mib':sum(v['bytes'] for v in read(HERE/'videos/manifest.json')['videos'])/1024**2,
        'independent_device_cases':len(gpu['cases']),'resources_verified':list(resources['files']),'published':meta,'remote':remote,
        'devices':devices,'real_recording_videos':len(recordings['videos']),'gallery_http':200,'gallery_video_range_http':206,'qt_capture_passed':True,
        'limits':['华为局部侵蚀形态与时序仍有差异，数值一致不等于视觉完全一致。',
                  '实际操作验证返回键；预测性返回手势沿用同一分发，未单独注入测试。',
                  '输入法恢复可能遮挡动画尾段，离屏与提交耗时不代表系统显示帧率。',
                  'OPD2515 使用真实阶段截图；三星另有真实系统录屏。']}
(out/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['scenes','trajectory_cases','visible_material_frame_pairs','videos','video_mib','independent_device_cases']},ensure_ascii=False))
print('发布与双设备安装身份、统一规则及全部证据通过，更新',meta['debugUpdateCode'])
