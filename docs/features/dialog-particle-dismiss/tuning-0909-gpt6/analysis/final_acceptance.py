from pathlib import Path
import json,re,xml.etree.ElementTree as ET,subprocess
root=Path(__file__).resolve().parents[1];repo=next(p for p in root.parents if (p/'gradlew.bat').is_file())
def read(path):return json.loads(path.read_text('utf-8'))
published=read(root/'analysis/published-update.json')
devices=[]
for serial in ['9018f404','R5CW20BLNKL']:
    d=root/'device-r33'/serial
    release=read(d/'published-checks.json');directions=read(d/'direction-checks.json');rapid=read(d/'rapid-checks.json')
    assert release['installedSha256']==published['sha256'] and release['launchAndDismissPassed']
    assert len(directions)==8 and max(v['error'] for v in directions)<.1
    assert len(rapid['iterations'])==10 and rapid['mainUiRestored']
    hierarchy=(d/'rapid-after-hierarchy.txt').read_text('utf-8')
    assert 'ParticleDismissOverlay{' not in hierarchy and not re.findall(r'^          android\.view\.View\{',hierarchy,re.M)
    devices.append({'device':serial,'published':release,'directions':8,'maximumDirectionError':max(v['error'] for v in directions),'rapidCycles':10})
scale=read(root/'device-r33/R5CW20BLNKL/scale-checks.json');assert scale['restored']==scale['original']=='1.0';assert {v['scale'] for v in scale['tests']}=={0,.5,1,2}
tests=[]
for path in (repo/'app/build/test-results/testDebugUnitTest').glob('*Particle*.xml'):
    t=ET.fromstring(path.read_bytes());assert t.get('failures')=='0' and t.get('errors')=='0'
    tests.append({'class':t.get('name'),'tests':int(t.get('tests'))})
assert sum(v['tests'] for v in tests)==9
trajectory=read(root/'analysis/trajectory-qa.json');assert len(trajectory['cases'])==70
assert sum(v['reverse_steps_over_002px'] for v in trajectory['cases'])==0
parity=read(root/'analysis/android-gpu-comparison.json');maximum=max(v.get('position_max',0) for v in parity);assert maximum<.13
upload=read(root/'analysis/android-upload-qa.json');assert upload['images']==84 and upload['uploadPixelsIdentical'] and upload['terminalAlphaZero']
video_reports=[]
for p in (root/'device-r33/videos').glob('*.mp4'):
    result=subprocess.run(['C:/ffmpeg/bin/ffprobe.exe','-v','error','-select_streams','v:0','-show_entries','stream=width,height,duration,avg_frame_rate,nb_frames','-of','json',str(p)],capture_output=True,check=True)
    v=json.loads(result.stdout)['streams'][0]
    subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe','-v','error','-i',str(p),'-map','0:v:0','-f','null','-'],capture_output=True,check=True)
    video_reports.append({'file':p.name,**v})
assert len(video_reports)==10
report={'version':'r33','debugUpdateCode':published['debugUpdateCode'],'sha256':published['sha256'],'tests':tests,'devices':devices,'systemScale':scale,'desktopVideos':54,'androidRecordingVideos':video_reports,'visibleTrajectoryPairs':sum(v['visible_material_frame_pairs'] for v in trajectory['cases']),'reverseTrajectorySteps':0,'maximumGpuCenterDifferenceLogicalPx':maximum,'uploadImagesIdentical':84,'limitations':['OPD2515 无系统录屏，保留真实阶段截图及 GPU 固定输入输出。','渲染线程提交间隔不是实际显示帧率。','局部细束与曲率仍有参考差异，r33 为本轮视觉取舍。']}
(root/'analysis/final-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['tests','devices','systemScale','androidRecordingVideos']},ensure_ascii=False))
