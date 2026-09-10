"""真实关闭路径：返回覆盖历史触点、按钮方向、八方向和连续开关。"""
from pathlib import Path
import argparse,subprocess,xml.etree.ElementTree as ET,json,re,time,math,sys
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--resume-editor',action='store_true');p.add_argument('--output',default='device-streams');a=p.parse_args()
here=Path(__file__).resolve().parent;out=here.parent/a.output/a.serial;out.mkdir(parents=True,exist_ok=True)
base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial];prefix='com.ywwynm.everythingdone:id/'
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace')
def dump(name):
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml');raw=adb('shell','cat','/sdcard/particle-ui.xml')
    (out/f'{name}.xml').write_text(raw,encoding='utf-8');return ET.fromstring(raw)
def find(tree,value):
    return [n for n in tree.iter('node') if n.get('resource-id')==prefix+value or n.get('text')==value or n.get('content-desc')==value]
def waitfor(value,name):
    for _ in range(8):
        tree=dump(name)
        if find(tree,value):return tree
        time.sleep(.12)
    raise RuntimeError('未到达 '+value)
def bounds(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def point(n):
    l,t,r,b=bounds(n);return [(l+r)//2,(t+b)//2]
def tap(value,expected,name):
    tree=dump(name+'-before');nodes=find(tree,value);assert len(nodes)==1,(value,len(nodes))
    adb('shell','input','tap',*[str(x) for x in point(nodes[0])]);return waitfor(expected,name)
pid=adb('shell','pidof','com.ywwynm.everythingdone').split()[0]
def completed():
    return [x for x in adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','*:S').splitlines() if '完成 count=' in x]
rows=json.loads((out/'ui-checks.json').read_text('utf-8')) if a.resume_editor else []
def finish(name,before,angle,elapsed=None):
    start=time.monotonic()
    while time.monotonic()-start<10:
        last=completed()
        if last and last[-1] not in before:break
        time.sleep(.10)
    else:raise RuntimeError('消散未完成 '+name)
    actual=float(re.search(r'direction=([-\d.]+)',last[-1]).group(1))
    error=abs((actual-angle+180)%360-180)
    row=dict(case=name,expectedDirection=angle,actualDirection=actual,error=error,render=last[-1])
    if elapsed is not None:row['touchToBackMs']=elapsed
    assert error<1,row
    rows.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    (out/'ui-checks.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
def button_direction(tree,id):
    node=find(tree,id);assert len(node)==1
    x,y=point(node[0]);l,t,r,b=bounds(tree.find('node'))
    return math.degrees(math.atan2(-(y-(t+b)/2),x-(l+r)/2))%360
def helper(file,*args):
    subprocess.run([sys.executable,'-X','utf8',str(here/file),a.serial,*args,'--output',a.output],check=True)

if not a.resume_editor:
    waitfor('fab_create','main')
    tap('Open Navigation Drawer' if a.serial=='9018f404' else '打开导航抽屉','Settings' if a.serial=='9018f404' else '设置','drawer')
    tap('Settings' if a.serial=='9018f404' else '设置','ll_app_language_as_bt','settings')
    tree=tap('ll_app_language_as_bt','tv_title_fragment_chooser','language')
    title=find(tree,'tv_title_fragment_chooser')[0]
    before=completed();adb('shell','input','tap',*[str(x) for x in point(title)])
    stamp=time.perf_counter();adb('shell','input','keyevent','4');elapsed=(time.perf_counter()-stamp)*1000
    finish('language-back-after-title-touch',before,135,elapsed)
    waitfor('ll_app_language_as_bt','language-back-closed')
    tree=tap('ll_app_language_as_bt','tv_title_fragment_chooser','language-cancel')
    angle=button_direction(tree,'tv_cancel_as_bt_fragment_chooser');before=completed()
    tap('tv_cancel_as_bt_fragment_chooser','ll_app_language_as_bt','language-cancel-closed')
    finish('language-cancel-touch',before,angle)
    adb('shell','input','keyevent','4');waitfor('fab_create','main-after-language')
    tap('fab_create','act_add_attachment','editor')
    tap('act_add_attachment','tv_take_photo_as_bt','attachment')
    before=completed()
    helper('record_dismiss.py' if a.serial=='R5CW20BLNKL' else 'capture_dismiss_stills.py','attachment-back','--back')
    finish('attachment-back',before,135);waitfor('act_add_attachment','attachment-closed')
else:
    waitfor('act_add_attachment','resumed-editor')
helper('device_direction_checks.py')
helper('device_rapid_checks.py')
tap('act_change_color','tab_tbe_gradient','color')
tree=tap('tab_tbe_gradient','tv_cancel_as_bt_tbe','color-gradient')
angle=button_direction(tree,'tv_cancel_as_bt_tbe');before=completed()
helper('record_dismiss.py' if a.serial=='R5CW20BLNKL' else 'capture_dismiss_stills.py','color-cancel','--id',prefix+'tv_cancel_as_bt_tbe')
finish('gradient-color-cancel-touch',before,angle);waitfor('act_add_attachment','color-closed')
tap('ib_back','fab_create','final-main')
log=adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','ParticleDismiss:E','AndroidRuntime:E','*:S')
assert not any(v in log for v in ['FATAL EXCEPTION','GL 错误','微片消散失败'])
(out/'real-ui-log.txt').write_text(log,encoding='utf-8')
print(a.serial,'真实返回、内容色、八方向和连续开关通过，已回到主列表。',flush=True)
