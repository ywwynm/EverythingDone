"""安装发布包并复核真实返回关闭；只操作指定设备中的本应用。"""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,time,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parents[1];ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);args=p.parse_args()
out=HERE/'device-surface-transfer'/args.serial;out.mkdir(parents=True,exist_ok=True)
def adb(*params):return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe','-s',args.serial,*params]).decode('utf-8','replace').strip()
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml')
    raw=adb('shell','cat','/sdcard/particle-ui.xml');(out/'published-current.xml').write_text(raw,'utf-8')
    return ET.fromstring(raw)
def wait_node(predicate):
    for _ in range(8):
        found=[n for n in dump().iter('node') if predicate(n)]
        if len(found)==1:return found[0]
        time.sleep(.15)
    raise RuntimeError('未找到唯一的界面目标')
def tap(node):
    x,y,x1,y1=map(int,re.findall(r'-?\d+',node.get('bounds')))
    adb('shell','input','tap',str((x+x1)//2),str((y+y1)//2))
def rid(name):return lambda n:n.get('resource-id')=='com.ywwynm.everythingdone:id/'+name
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';expected=hashlib.sha256(apk.read_bytes()).hexdigest()
assert 'Success' in adb('install','-r',str(apk))
adb('shell','am','start','-W','-n','com.ywwynm.everythingdone/.activities.ThingsActivity')
path=adb('shell','pm','path','com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
assert adb('shell','sha256sum',path).split()[0]==expected
tap(wait_node(lambda n:n.get('content-desc') in ['Open Navigation Drawer','打开导航抽屉']))
tap(wait_node(lambda n:n.get('text') in ['Settings','设置']))
tap(wait_node(rid('ll_app_language_as_bt')))
wait_node(lambda n:n.get('resource-id')=='android:id/content' and int(re.findall(r'-?\d+',n.get('bounds'))[0])>0)
pid=adb('shell','pidof','com.ywwynm.everythingdone').split()[0]
def complete():return [s for s in adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','*:S').splitlines() if '完成 count=' in s]
before=len(complete());adb('shell','input','keyevent','4');started=time.monotonic()
while time.monotonic()-started<8:
    lines=complete()
    if len(lines)>before:break
    time.sleep(.12)
else:raise RuntimeError('发布包关闭没有完成日志')
log=lines[-1];assert 'direction=135.0' in log and 'strength=0.5' in log
wait_node(rid('ll_app_language_as_bt'))
hierarchy=adb('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.SettingsActivity')
assert 'ParticleDismissOverlay{' not in hierarchy
adb('shell','input','keyevent','4');wait_node(rid('act_search'))
result=dict(serial=args.serial,apk_sha256=expected,returned_to='ThingsActivity',overlay_cleared=True,log=log)
(out/'published-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
print(args.serial,'发布包身份、真实返回关闭和动画层清理通过',flush=True)
