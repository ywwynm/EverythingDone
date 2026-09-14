"""按下后立即开始滑动；短促移动后保持再松手恢复，采集真实首个非零画面。"""
import argparse
import json
import re
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'thing-creation-animation'))
import device_ui as ui
import device_performance as perf

p=argparse.ArgumentParser()
p.add_argument('--title',required=True)
p.add_argument('--run',required=True)
p.add_argument('--rounds',type=int,default=3)
p.add_argument('--continuous',action='store_true')
a=p.parse_args()
assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]+',a.title)
if a.continuous:
    driver=Path(__file__).resolve().parents[3]/'tmp/thing-animation-device/input-driver/input.jar'
    ui.adb('push',driver,'/data/local/tmp/thing-animation-input.jar')
for i in range(a.rounds):
    tree=ui.dump()
    before=ui.find(tree,'resource-id','tv_header_subtitle').get('text')
    node=ui.find(tree,'text',a.title)
    parents={c:n for n in tree.iter() for c in n}
    while not node.get('resource-id','').endswith('/cv_thing'): node=parents[node]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    start,y=x2-80,(y1+y2)//2
    end=round(max(12,start-(x2-x1)*.36))
    run=f'{a.run}-swipe-{i}'
    perf.begin(run,'swipe',a.title,1)
    # 一次 shell 会话连续注入系统 MotionEvent；无预先停顿、无应用内部入口。
    commands=[f'input touchscreen motionevent DOWN {start} {y}']
    commands += [f'input touchscreen motionevent MOVE {round(start+(end-start)*n/3)} {y}' for n in range(1,4)]
    commands += ['sleep 0.7',f'input touchscreen motionevent UP {end} {y}']
    if a.continuous:
        ui.adb('shell','CLASSPATH=/data/local/tmp/thing-animation-input.jar','app_process','/system/bin','ParticleGestureInput',
               start,y,'UP',end,y,180,end,y,600)
    else:
        ui.adb('shell','; '.join(commands))
    data=perf.end(run)
    tree=ui.dump()
    ui.find(tree,'text',a.title)
    assert ui.find(tree,'resource-id','tv_header_subtitle').get('text')==before
    print(json.dumps({'run':run,'serial':ui.SERIAL,'inputs':len(data['inputs']),'overlays':len(data['overlays'])}),flush=True)
