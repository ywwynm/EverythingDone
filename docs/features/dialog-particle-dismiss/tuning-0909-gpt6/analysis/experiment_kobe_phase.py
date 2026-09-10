import sys,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
src=(ROOT/'renderer.py').read_text(encoding='utf-8');ctx=moderngl.create_standalone_context(require=430);rr=[]
for dt in [0,-.035,-.060,-.085]:
    code=src.replace('gy,gx=np.gradient(gaussian_filter(T,3)',f'yy,xx=np.mgrid[:self.ny,:self.nx];T+={dt}*np.exp(-(((xx+.5)/self.nx-.53)/.28)**2-(((yy+.5)/self.ny-.45)/.32)**2)\n        gy,gx=np.gradient(gaussian_filter(T,3)')
    mod=types.ModuleType('phase');mod.__file__=str(ROOT/'renderer.py');exec(compile(code,mod.__file__,'exec'),mod.__dict__);rr.append((dt,mod.Renderer('kobe',ctx=ctx)))
W=360;H=400;out=Image.new('RGB',(W*5,H*4),(18,23,31));draw=ImageDraw.Draw(out);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for row,t in enumerate([.32,.44,.56,.68]):
    for col,(label,a) in enumerate([('华为',reference(rr[0][1].meta,t))]+[(str(dt),r.render(t)) for dt,r in rr]):
        im=Image.fromarray(crop(a,rr[0][1].meta));im.thumbnail((W-6,H-34));out.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
out.save(ROOT/'analysis/r32-kobe-phase.jpg',quality=96)
for _,r in rr:r.close()
ctx.release()
