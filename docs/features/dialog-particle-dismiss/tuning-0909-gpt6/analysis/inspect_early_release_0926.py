"""按原片检查起裂的前十二个相位，避免用后段粒子云反推起点。"""
from pathlib import Path
import sys,argparse
import numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
from match_refs import RefFrames
OUT=HERE/'analysis/new-references-0926'

ap=argparse.ArgumentParser()
ap.add_argument('scenes',nargs='+')
ap.add_argument('--video-time',action='store_true',help='单独检查原文件的线性时间；不与共同模型页面进度混用')
a=ap.parse_args()
for name in a.scenes:
    m=load_meta(name);ref=Reference(m) if a.video_time else RefFrames(name)
    x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.04)
    sheet=Image.new('RGB',(300*6,324*2),'#0e141e');draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for i,t in enumerate(np.arange(12)*.02):
        if a.video_time:f=ref.at(float(t));phase=float(t)
        else:
            index,f=ref.nearest(float(t));phase=float(ref.progress[index])
        im=Image.fromarray(f[max(0,y-pad):y1+pad,max(0,x-pad):x1+pad]);im.thumbnail((300,300))
        tx=(i%6)*300;ty=(i//6)*324
        clock='原文件时间' if a.video_time else '画面序号进度'
        sheet.paste(im,(tx+(300-im.width)//2,ty+24));draw.text((tx+5,ty),f'{clock} · {phase:.3f}',font=font,fill='white')
    suffix='-video-time' if a.video_time else ''
    sheet.save(OUT/f'{name}-early-contact{suffix}.jpg',quality=96)
