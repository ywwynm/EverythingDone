"""用源图—背景投影检查内部消逝起点的径向覆盖，不把它等同于粒子真值。"""
from pathlib import Path
import json,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from v2_inputs import OBSERVED_SITES

def main():
    out=HERE/'analysis/match-cache/material-transfer/edge-profiles';out.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);records={}
    for name,col in [('reference-0926-4',1)]:
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;span=min(w,h)
        src=np.asarray(Image.open(HERE/'assets'/name/'foreground.png').convert('RGB')).astype('float32')
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'))[y:y1,x:x1].astype('float32')
        src=cv2.GaussianBlur(src,(0,0),2);bg=cv2.GaussianBlur(bg,(0,0),2);vector=src-bg;norm=np.sum(vector*vector,2)
        pad=round(span*.18);x0=max(0,x-pad);y0=max(0,y-pad)
        yy,xx=np.mgrid[:h,:w];sx,sy,_=OBSERVED_SITES[name][0];dist=np.hypot((xx-sx*w)/span,(yy-sy*h)/span)
        canvas=Image.new('RGB',(820,430),(14,20,30));d=ImageDraw.Draw(canvas)
        d.text((20,8),'内部起点径向覆盖：仅作源图—背景投影诊断',font=font,fill='white')
        colors=['#eeeeee','#f78973','#53ded2'];records[name]={}
        for i,(folder,row,label) in enumerate([('shared-soft-final',0,'华为原片'),('shared-soft-final',1,'调整前'),('shared-soft-final',2,'本轮共同模型')]):
            a=np.asarray(Image.open(HERE/f'analysis/match-cache/material-transfer/{folder}/{name}-{row}-{col}.png')).astype('float32')[y-y0:y-y0+h,x-x0:x-x0+w]
            a=cv2.GaussianBlur(a,(0,0),2)
            alpha=np.sum((a-bg)*vector,2)/np.maximum(norm,100.)
            pts=[];values=[]
            for r in np.arange(.02,.34,.01):
                ok=(norm>3600)&(dist>=r)&(dist<r+.01)
                v=float(np.clip(alpha[ok],0,1).mean()) if ok.sum()>12 else float('nan')
                values.append([float(r),v]);pts.append((60+r/.34*710,370-v*280))
            for q,z in zip(pts[:-1],pts[1:]):
                if np.isfinite(q+z).all():d.line((q,z),fill=colors[i],width=3)
            d.text((30+i*190,48),label,font=font,fill=colors[i]);records[name][label]=values
        d.line((60,90,60,370,780,370),fill='#81919f',width=1)
        d.text((160,395),'横轴：距起点 / 控件短边；纵轴：材料覆盖投影',font=font,fill='#a9b9cd')
        canvas.save(out/f'{name}.png')
    (out/'profiles.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
