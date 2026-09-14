"""真实手指保持按下并跨过原点；旧版会在 DecorView 绘制中删除粒子层而崩溃。"""
import argparse
import json
import re
import sys
import subprocess
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'thing-creation-animation'))
import device_ui as ui

p=argparse.ArgumentParser()
p.add_argument('--title', required=True)
p.add_argument('--run', required=True)
p.add_argument('--rounds', type=int, default=3)
p.add_argument('--overshoot', type=int, default=24)
a=p.parse_args()
assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]+',a.title)
assert re.fullmatch(r'[a-zA-Z0-9_-]+',a.run)
results=[]
for i in range(a.rounds):
    tree=ui.dump()
    count=ui.find(tree,'resource-id','tv_header_subtitle').get('text')
    node=ui.find(tree,'text',a.title)
    parents={c:n for n in tree.iter() for c in n}
    while not node.get('resource-id','').endswith('/cv_thing'): node=parents[node]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    x,y=x2-80,(y1+y2)//2
    left=round(max(x1+20,x-(x2-x1)*.6))
    pid=ui.adb('shell','pidof',ui.PACKAGE).decode().strip()
    stamp=ui.adb('shell','date','+%Y%m%d%H%M%S').decode().strip()
    events=[]
    def send(kind,px):
        ui.adb('shell','input','touchscreen','motionevent',kind,px,y)
        events.append([kind,px,y,time.monotonic_ns()])
    send('DOWN',x)
    for n in range(1,7): send('MOVE',round(x+(left-x)*n/6))
    time.sleep(.35)
    for n in range(1,7): send('MOVE',round(left+(x+a.overshoot-left)*n/6))
    time.sleep(.25)
    # 手指仍按下时即检查进程，区分回拖与 UP 后的回弹。
    after=subprocess.run([ui.ADB,'-s',ui.SERIAL,'shell','pidof',ui.PACKAGE],capture_output=True).stdout.decode().strip()
    send('UP',x+a.overshoot)
    time.sleep(.3)
    logs=ui.adb('logcat','-d','-b','crash','-t','120').decode(errors='replace')
    (ui.ROOT/f'{a.run}-{i}-crash.txt').write_text(logs,encoding='utf-8')
    row={'serial':ui.SERIAL,'beforePid':pid,'heldPid':after,'start':stamp,'events':events,'overshootPx':a.overshoot}
    results.append(row)
    (ui.ROOT/f'{a.run}.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    assert after==pid and pid, 'App crashed while the finger was held across the origin'
    tree=ui.dump()
    assert ui.find(tree,'resource-id','tv_header_subtitle').get('text')==count
    ui.find(tree,'text',a.title)
    print(json.dumps({'serial':ui.SERIAL,'round':i,'pid':pid,'crossedOrigin':True,'retained':True}),flush=True)
