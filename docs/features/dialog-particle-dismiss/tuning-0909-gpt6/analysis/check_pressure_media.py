"""读取最终编码视频生成全画面检查表；不把局部裁切作为最终验收。"""
from pathlib import Path
import json,sys
import cv2,numpy as np
from PIL import Image
HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/edge-flow-support'
items=json.loads((HERE/'videos/pressure-flow-videos.json').read_text('utf-8'))['videos']
for item in items:
    if item['rate']!=1:continue
    cap=cv2.VideoCapture(str(HERE/'videos'/item['file']))
    shots=[]
    for phase in [.30,.40,.50,.65]:
        cap.set(cv2.CAP_PROP_POS_MSEC,(.35+phase)*1000)
        ok,frame=cap.read();assert ok,item
        shots.append(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB))
    cap.release()
    sheet=np.concatenate([np.concatenate(shots[:2],axis=1),np.concatenate(shots[2:],axis=1)],axis=0)
    path=OUT/f'full-process-{item["case"]:02d}.jpg'
    Image.fromarray(sheet).save(path,quality=94)
    print(path.name)
