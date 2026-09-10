"""比较连续材料保留与运输下限，检查长曲束是否被短寿命提前抹去。"""
import sys,types,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np
import moderngl
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
source=(ROOT/'renderer.py').read_text(encoding='utf-8')
variants={
 'r31':{},
 'hold':{'life_gain=.70':'life_gain=1.2'},
 'fast':{'u*.20-dot(target,wind)':'u*.60-dot(target,wind)'},
 'hold_fast':{'life_gain=.70':'life_gain=1.1','u*.20-dot(target,wind)':'u*.50-dot(target,wind)'},
 'hold_compact':{'life_gain=.70':'life_gain=1.1','.48*exp(-age/.11)+.10*smoothstep':'.16*exp(-age/.11)+.035*smoothstep','(.9+.2*m.random.x)':'(.98+.04*m.random.x)'}
}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19);times=[.24,.40,.56,.72]
for name in ['thanos','kobe','ironman','attachment']:
    ctx=moderngl.create_standalone_context(require=430)
    rr=[]
    for label,changes in variants.items():
        code=source
        for old,new in changes.items():assert old in code,old;code=code.replace(old,new)
        mod=types.ModuleType(label);mod.__file__=str(ROOT/'renderer.py');exec(compile(code,mod.__file__,'exec'),mod.__dict__);rr.append((label,mod.Renderer(name,ctx=ctx)))
    W=340;H=420;ref=name!='attachment';out=Image.new('RGB',(W*(len(rr)+ref),H*len(times)),(18,23,31));draw=ImageDraw.Draw(out)
    for row,t in enumerate(times):
        frames=([('华为',reference(rr[0][1].meta,t))] if ref else [])+[(label,r.render(t)) for label,r in rr]
        for col,(label,frame) in enumerate(frames):
            im=Image.fromarray(crop(frame,rr[0][1].meta));im.thumbnail((W-6,H-34));out.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(ROOT/'analysis'/f'r32-roll-{name}.jpg',quality=96)
    for _,r in rr:r.close()
    ctx.release()
    print(name,flush=True)
