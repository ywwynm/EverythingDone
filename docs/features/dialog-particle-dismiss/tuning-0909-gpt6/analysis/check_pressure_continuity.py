"""检查缺陷前后的连续相位，以及尾段是否重新出现窄流。"""
from pathlib import Path
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/edge-flow-support'
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for number,scene,roi in [(1,'ironman',(30,150,690,920)),(2,'attachment',(50,480,690,1150)),(3,'attachment',(50,420,690,1120)),(4,'attachment',(50,450,690,1120))]:
    cap=cv2.VideoCapture(str(HERE/f'videos/pressure-flow-{number:02d}-{scene}-1x.mp4'))
    for phase in [.35,.425,.55,.70,.83]:
        cap.set(cv2.CAP_PROP_POS_MSEC,(.35+phase)*1000);ok,raw=cap.read();assert ok
        raw=cv2.cvtColor(raw,cv2.COLOR_BGR2RGB)
        # 导出左右画面均为 480 像素宽，顶部标题 80 像素。
        scale=480/720;l,t,r,b=[round(x*scale) for x in roi]
        out=Image.new('RGB',(1280,round((b-t)/(r-l)*640)+36),'#101620');d=ImageDraw.Draw(out)
        for col in [0,1]:
            crop=Image.fromarray(raw[t+80:b+80,col*480+l:col*480+r])
            out.paste(crop.resize((640,out.height-36),Image.Resampling.LANCZOS),(col*640,36))
            d.text((col*640+5,3),f'{"华为原片" if number==1 and col==0 else "缺陷复现" if col==0 else "当前共同模型"} · {phase:.3f}',font=font,fill='white')
        out.save(OUT/f'continuity-{number:02d}-{round(phase*1000):03d}.jpg',quality=96)
    cap.release()
