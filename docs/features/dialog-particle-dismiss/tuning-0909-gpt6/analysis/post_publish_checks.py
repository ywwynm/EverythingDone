from pathlib import Path
import argparse,subprocess,sys,re,json,time,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);a=p.parse_args()
here=Path(__file__).resolve().parent;root=here.parent/'device-r33'/a.serial;base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace').strip()
def ui(*args):subprocess.run([sys.executable,'-X','utf8',str(here/'device_ui.py'),a.serial,*args],capture_output=True,check=True)
apk=adb('shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
assert re.fullmatch(r'/data/app/[A-Za-z0-9_\-/+=.~]+',apk),apk
digest=adb('shell','sha256sum',apk).split()[0]
meta=json.loads((here/'published-update.json').read_text('utf-8'))
assert digest==meta['sha256'],digest
ui('tap','--desc','Open Navigation Drawer' if a.serial=='9018f404' else '打开导航抽屉','--expect','Settings' if a.serial=='9018f404' else '设置','--name','published-drawer')
ui('tap','--text','Settings' if a.serial=='9018f404' else '设置','--expect','cb_follow_system_dark_mode','--name','published-settings')
tree=ET.fromstring((root/'published-settings.xml').read_bytes())
assert [n.get('checked') for n in tree.iter('node') if n.get('resource-id').endswith('/cb_follow_system_dark_mode')]==['true']
pid=adb('shell','pidof','com.ywwynm.everythingdone').split()[0]
ui('tap','--id','com.ywwynm.everythingdone:id/ll_app_language_as_bt','--expect','English','--name','published-language')
ui('back','--expect','ll_app_language_as_bt','--name','published-language-closed')
start=time.perf_counter()
while True:
    hierarchy=adb('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.SettingsActivity')
    if 'ParticleDismissOverlay{' not in hierarchy and not re.findall(r'^          android\.view\.View\{',hierarchy,re.M):break
    if time.perf_counter()-start>6:raise RuntimeError('发布版动画层未退出')
log=adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','ParticleDismiss:E','AndroidRuntime:E','*:S')
complete=[v for v in log.splitlines() if '完成 count=' in v]
assert complete,log
assert not any(v in log for v in ['FATAL EXCEPTION','GL 错误','微片消散失败']),log
scale=adb('shell','settings','get','global','animator_duration_scale');assert scale==('null' if a.serial=='9018f404' else '1.0'),scale
ui('back','--expect','act_search','--name','published-final-main')
report={'device':a.serial,'debugUpdateCode':meta['debugUpdateCode'],'installedSha256':digest,'launchAndDismissPassed':True,'systemAnimatorDurationScale':scale,'followSystemDarkModeRestored':True,'finalUi':'ThingsActivity 主列表','render':complete[-1]}
(root/'published-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(root/'published-log.txt').write_text(log,encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
