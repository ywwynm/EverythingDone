"""真实点击已有记事、返回及放弃手势，检查预备资源不会累积。"""
import importlib.util
import json
import re
import time
import argparse
from pathlib import Path

spec=importlib.util.spec_from_file_location('device_ui',Path(__file__).resolve().parents[1]/'thing-creation-animation/device_ui.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)
p=argparse.ArgumentParser(); p.add_argument('--title',required=True)
a=p.parse_args(); title=a.title
assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]+',title)
out=ui.ROOT/'startup-warm-paths';out.mkdir(exist_ok=True)
rows=[]
def measure(label):
    data=ui.adb('shell','dumpsys','meminfo',ui.PACKAGE).decode(errors='replace')
    (out/(label+'-memory.txt')).write_text(data,encoding='utf-8')
    pss=int(re.search(r'TOTAL PSS:\s*(\d+)',data).group(1))
    pid=ui.adb('shell','pidof',ui.PACKAGE).strip().decode()
    threads=ui.adb('shell','ps','-T','-p',pid).decode(errors='replace')
    row=dict(label=label,pssKiB=pss,pid=pid,particleThreads=threads.count('ParticleDismiss'))
    rows.append(row);print(json.dumps(row),flush=True)
    assert row['particleThreads']==0,row
initial=ui.find(ui.dump(),'resource-id','tv_header_subtitle').get('text')
measure('before')
for i in range(6):
    tree=ui.dump();x,y=ui.center(ui.find(tree,'text',title))
    ui.adb('shell','input','touchscreen','motionevent','DOWN',x,y)
    time.sleep(.08)
    ui.adb('shell','input','touchscreen','motionevent','UP',x,y)
    tree=ui.wait_for('ib_back')
    assert ui.find(tree,'resource-id','et_title').get('text')==title
    ui.adb('shell','input','tap',*ui.center(ui.find(tree,'resource-id','ib_back')))
    ui.wait_for(title)
    tree=ui.dump();x,y=ui.center(ui.find(tree,'text',title))
    ui.adb('shell','input','touchscreen','motionevent','DOWN',x,y)
    time.sleep(.10)
    ui.adb('shell','input','touchscreen','motionevent','CANCEL',x,y)
    time.sleep(.6)
    assert ui.find(ui.dump(),'resource-id','tv_header_subtitle').get('text')==initial
    measure('cycle-'+str(i))
(out/'result.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
assert len({r['pid'] for r in rows})==1,'应用在循环中重启'
