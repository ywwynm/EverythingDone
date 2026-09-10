"""本轮共用流束模型与实际发布基线的阶段表；不覆盖此前验收证据。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from export_videos import Reference

font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
ctx=moderngl.create_standalone_context(require=430)
out=HERE/'analysis/streams-content';out.mkdir(exist_ok=True)
stats=[]
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    r=Renderer(name,ctx=ctx);ref=Reference(r.meta)
    old=np.load(HERE/'archive/unified-before-streams'/f'{name}.npy',mmap_mode='r')
    x,y,x1,y1=r.meta['rect'];lo=max(0,y-180);hi=min(r.h,y1+130)
    h=round((hi-lo)/r.w*300)
    sheet=Image.new('RGB',(300*5,3*(h+34)),(15,20,29));d=ImageDraw.Draw(sheet)
    for j,t in enumerate([.18,.32,.46,.60,.76]):
        for row,(im,label) in enumerate([(ref.at(t),'参考 / 原图'),(old[round(t*120)],'已发布统一模型'),(r.render(t),'卷曲与内容色')]):
            tile=Image.fromarray(im[lo:hi]).resize((300,h),Image.Resampling.LANCZOS)
            sheet.paste(tile,(j*300,row*(h+34)+34))
            d.text((j*300+8,row*(h+34)+5),f'{label} {t:.2f}',font=font,fill='white')
    sheet.save(out/f'{name}.jpg',quality=94)
    row={'scene':name,'particles':r.n,'base':r.nx*r.ny,**r.material_info}
    stats.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    r.close()
ctx.release()
(out/'material-statistics.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
