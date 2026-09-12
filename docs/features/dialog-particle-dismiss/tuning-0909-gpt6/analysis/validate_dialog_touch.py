"""通过可访问性节点进入语言弹窗，再复用真实近远与返回关闭验收。"""
from pathlib import Path
import argparse,re,subprocess,sys,time,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',required=True);p.add_argument('--record',action='store_true');a=p.parse_args()
HERE=Path(__file__).resolve().parents[1];out=HERE/a.output/a.serial;out.mkdir(parents=True,exist_ok=True)

def adb(*args):return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial,*args],timeout=20).decode('utf-8','replace')

def dump(name):
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml')
    raw=adb('shell','cat','/sdcard/particle-ui.xml');(out/(name+'.xml')).write_text(raw,'utf-8');return ET.fromstring(raw)

def find(predicate):
    for _ in range(8):
        tree=dump('touch-navigation');nodes=[n for n in tree.iter('node') if predicate(n)]
        if len(nodes)==1:return nodes[0]
        time.sleep(.15)
    raise RuntimeError('未找到唯一界面目标')

def tap(n):
    l,t,r,b=map(int,re.findall(r'-?\d+',n.get('bounds')));adb('shell','input','tap',str((l+r)//2),str((t+b)//2))

adb('shell','am','start','-W','-n','com.ywwynm.everythingdone/.activities.ThingsActivity')
tap(find(lambda n:n.get('content-desc') in ['Open Navigation Drawer','打开导航抽屉']))
tap(find(lambda n:n.get('text') in ['Settings','设置']))
language=find(lambda n:n.get('resource-id')=='com.ywwynm.everythingdone:id/ll_app_language_as_bt')
dump('touch-settings');tap(language)
subprocess.run([sys.executable,'-X','utf8',str(HERE/'analysis/validate_touch_ui.py'),a.serial,'--output',a.output]+(['--record'] if a.record else []),check=True)
