"""通过真实手势回到零进度并停住，验证粒子原图没有丢失媒体卡片圆角。"""
import argparse
import importlib.util
import json
import re
import time
from pathlib import Path
import numpy as np
from PIL import Image

spec=importlib.util.spec_from_file_location('device_ui',Path(__file__).resolve().parents[1]/'thing-creation-animation/device_ui.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)
p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--expect-failure',action='store_true')
p.add_argument('--title',required=True)
a=p.parse_args();title=a.title
assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]+',title)
r=ui.dump();node=ui.find(r,'text',title);parents={c:n for n in r.iter() for c in n}
while not node.get('resource-id','').endswith('/cv_thing'):node=parents[node]
x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
x,y=x2-60,(y1+y2)//2
count=ui.find(r,'resource-id','tv_header_subtitle').get('text')
ui.capture(a.name+'-before')
ui.adb('shell','input','touchscreen','motionevent','DOWN',x,y)
for i in range(1,9):ui.adb('shell','input','touchscreen','motionevent','MOVE',round(x-(x2-x1)*.36*i/8),y)
time.sleep(.4)
for i in range(1,9):ui.adb('shell','input','touchscreen','motionevent','MOVE',round(x-(x2-x1)*.36*(1-i/8)),y)
time.sleep(.5)
ui.capture(a.name+'-zero-held')
ui.adb('shell','input','touchscreen','motionevent','UP',x,y)
ui.wait_for(title);ui.capture(a.name+'-after')
r=ui.dump();assert ui.find(r,'resource-id','tv_header_subtitle').get('text')==count
def pixels(suffix):return np.asarray(Image.open(ui.ROOT/(a.name+'-'+suffix+'.png')).convert('RGB'),dtype=np.int16)
before,held,after=map(pixels,['before','zero-held','after'])
patches=[(x1+2,y1+2,x1+10,y1+10),(x2-10,y1+2,x2-2,y1+10)]
differences=[float(abs(before[b:d,c:e]-held[b:d,c:e]).mean()) for c,b,e,d in patches]
result={'bounds':[x1,y1,x2,y2],'cornerMeanDifferences':differences,'restoredMeanDifference':float(abs(before[y1:y2,x1:x2]-after[y1:y2,x1:x2]).mean()),'passed':max(differences)<20}
(ui.ROOT/(a.name+'.json')).write_text(json.dumps(result,indent=2));print(json.dumps(result))
assert result['passed']!=a.expect_failure,result
