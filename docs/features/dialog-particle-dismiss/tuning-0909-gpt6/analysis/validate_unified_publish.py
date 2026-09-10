"""安装已发布 APK，核对字节身份并复测真实弹窗关闭与清理。"""
from pathlib import Path
import argparse,subprocess,sys,json,re,time,hashlib,urllib.request
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-unified');p.add_argument('--back-direction',type=float);a=p.parse_args()
here=Path(__file__).resolve().parent;base=here.parent
repo=next(p for p in base.parents if (p/'gradlew.bat').is_file())
meta=json.loads((repo/'app/build/outputs/update-debug-apk/latest.json').read_text('utf-8'))
url=meta['apkUrl'].split('/debug/apk/')[0]+'/debug/latest.json'
with urllib.request.urlopen(urllib.request.Request(url,headers={'Cache-Control':'no-cache'}),timeout=30) as response:
    remote=json.load(response)
assert remote['sha256']==meta['sha256'] and remote['debugUpdateCode']==meta['debugUpdateCode']
apk=repo/'app/build/outputs/update-debug-apk'/meta['apkUrl'].rsplit('/',1)[1]
assert hashlib.sha256(apk.read_bytes()).hexdigest()==meta['sha256']
out=base/a.output/a.serial;out.mkdir(parents=True,exist_ok=True)
adb_args=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(adb_args+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace').strip()
def ui(*args):
    r=subprocess.run([sys.executable,'-X','utf8',str(here/'device_ui.py'),a.serial,*args,'--output',a.output],capture_output=True)
    if r.returncode:raise RuntimeError(r.stdout.decode('utf-8','replace')+r.stderr.decode('utf-8','replace'))
(out/'published-install.txt').write_text(adb('install','-r',str(apk)),encoding='utf-8')
adb('shell','am','start','-W','-n','com.ywwynm.everythingdone/.activities.ThingsActivity')
path=adb('shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
assert re.fullmatch(r'/data/app/[A-Za-z0-9_\-/+=.~]+',path)
assert adb('shell','sha256sum',path).split()[0]==meta['sha256']
pid=adb('shell','pidof','com.ywwynm.everythingdone').split()[0]
ui('tap','--desc','Open Navigation Drawer' if a.serial=='9018f404' else '打开导航抽屉','--expect','Settings' if a.serial=='9018f404' else '设置','--name','published-drawer')
ui('tap','--text','Settings' if a.serial=='9018f404' else '设置','--expect','ll_app_language_as_bt','--name','published-settings')
ui('tap','--id','com.ywwynm.everythingdone:id/ll_app_language_as_bt','--expect','English','--name','published-language')
ui('back','--expect','ll_app_language_as_bt','--name','published-language-closed')
start=time.perf_counter()
while time.perf_counter()-start<8:
    hierarchy=adb('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.SettingsActivity')
    log=adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','ParticleDismiss:E','AndroidRuntime:E','*:S')
    complete=[l for l in log.splitlines() if '完成 count=' in l]
    if complete and 'ParticleDismissOverlay{' not in hierarchy and not re.findall(r'^          android\.view\.View\{',hierarchy,re.M):break
    time.sleep(.1)
else:raise RuntimeError('发布版消散未完成或存在残留')
assert not any(v in log for v in ['FATAL EXCEPTION','GL 错误','微片消散失败'])
if a.back_direction is not None:
    actual=float(re.search(r'direction=([-\d.]+)',complete[-1]).group(1))
    assert abs((actual-a.back_direction+180)%360-180)<.01,(actual,a.back_direction)
scale=adb('shell','settings','get','global','animator_duration_scale')
assert scale==('null' if a.serial=='9018f404' else '1.0'),scale
import xml.etree.ElementTree as ET
tree=ET.fromstring((out/'published-settings.xml').read_bytes())
assert [n.get('checked') for n in tree.iter('node') if n.get('resource-id').endswith('/cb_follow_system_dark_mode')]==['true']
ui('back','--expect','act_search','--name','published-final-main')
report={'device':a.serial,'debugUpdateCode':meta['debugUpdateCode'],'installedSha256':meta['sha256'],
        'launchAndDismissPassed':True,'particleLayersAfter':0,'dimLayersAfter':0,
        'systemAnimatorDurationScale':scale,'followSystemDarkModeUnchanged':True,'finalUi':'ThingsActivity 主列表','render':complete[-1]}
(out/'published-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'published-log.txt').write_text(log,encoding='utf-8')
(out/'published-metadata.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
