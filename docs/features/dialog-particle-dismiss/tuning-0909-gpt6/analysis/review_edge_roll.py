"""并列查看参考、上一发布版与当前候选；保存真实密度及边缘控制图。"""
from pathlib import Path
import sys,argparse,json,time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from export_videos import Reference

p=argparse.ArgumentParser();p.add_argument('--tag',required=True);p.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe','language','color','attachment','attachment-image']);a=p.parse_args()
out=HERE/'analysis/edge-roll'/a.tag;out.mkdir(parents=True,exist_ok=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);ctx=moderngl.create_standalone_context(require=430)
for name in a.scenes:
    started=time.perf_counter();r=Renderer(name,ctx=ctx);ref=Reference(r.meta)
    path=HERE/'archive/streams-before-edge-roll'/f'{name}.npy';old=np.load(path,mmap_mode='r') if path.exists() else None
    x,y,x1,y1=r.meta['rect'];lo=max(0,y-180);hi=min(r.h,y1+130);h=round((hi-lo)/r.w*320)
    sheet=Image.new('RGB',(1600,3*(h+34)),(15,20,29));d=ImageDraw.Draw(sheet)
    for j,t in enumerate([.20,.35,.50,.65,.80]):
        now=r.render(t)
        for row,(im,label) in enumerate([(ref.at(t),'参考 / 原图'),(old[round(t*120)] if old is not None else ref.at(0),'上一发布版'),(now,'当前候选')]):
            sheet.paste(Image.fromarray(im[lo:hi]).resize((320,h),Image.Resampling.LANCZOS),(j*320,row*(h+34)+34))
            d.text((j*320+8,row*(h+34)+5),f'{label} {t:.2f}',font=font,fill='white')
        if j==2:
            field=np.frombuffer(r.cloud.read(),dtype='float32').reshape(128,128,8)
            density=np.clip(field[:,:,0]*150,0,255).astype('uint8')
            edge=np.clip(field[:,:,3]*255,0,255).astype('uint8')
            im=np.stack([edge,density,np.zeros_like(edge)],axis=2)
            Image.fromarray(im).resize((512,512)).save(out/f'{name}-cloud.png')
    sheet.save(out/f'{name}.jpg',quality=94);r.close()
    print(name,round(time.perf_counter()-started,2),'秒',flush=True)
ctx.release()
