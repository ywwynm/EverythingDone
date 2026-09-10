from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from export_videos import Reference

font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
ctx=moderngl.create_standalone_context(require=430)
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    r=Renderer(name,ctx=ctx);ref=Reference(r.meta)
    old=np.load(HERE/'archive/r33'/f'{name}.npy',mmap_mode='r')
    x,y,x1,y1=r.meta['rect'];lo=max(0,y-180);hi=min(r.h,y1+130)
    h=round((hi-lo)/r.w*300)
    sheet=Image.new('RGB',(300*5,3*(h+34)),(15,20,29));d=ImageDraw.Draw(sheet)
    for j,t in enumerate([.15,.30,.45,.60,.78]):
        for row,(im,label) in enumerate([(ref.at(t),'参考 / 原图'),(old[round(t*120)],'逐参考拟合 / 旧通用'),(r.render(t),'统一规则')]):
            tile=Image.fromarray(im[lo:hi]).resize((300,h),Image.Resampling.LANCZOS)
            sheet.paste(tile,(j*300,row*(h+34)+34))
            d.text((j*300+8,row*(h+34)+5),f'{label} {t:.2f}',font=font,fill='white')
    sheet.save(HERE/'analysis'/f'unified-{name}.jpg',quality=93)
    r.close()
ctx.release()
print('七场景统一模型阶段对照已生成')
