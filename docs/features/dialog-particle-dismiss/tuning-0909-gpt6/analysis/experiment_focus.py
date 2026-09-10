"""用有限宽度的连续应变场比较弯曲粒束；全程速度积分，不附加位置回收。"""
import sys,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer
from fit_flow import texture
from review import reference,crop
regions={'thanos':(.72,.65,.42,.45,-.65,.30),'kobe':(.78,.55,.43,.55,-.15,.45),'ironman':(.5,.6,.45,.50,-.4,.28),'attachment':(.58,.5,.5,.6,-.4,.28)}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
for name in regions:
    ctx=moderngl.create_standalone_context(require=430)
    rr=[];cx,cy,sx,sy,theta,bend=regions[name]
    yy,xx=np.mgrid[:36,:36];x=-.45+(xx+.5)/36*1.9;y=-.45+(yy+.5)/36*1.9
    nx,ny=np.cos(theta),np.sin(theta);tx,ty=-ny,nx
    dx=x-cx;dy=y-cy;along=dx*tx+dy*ty;across=dx*nx+dy*ny-bend*along*along
    weight=np.exp(-(dx/sx)**2-(dy/sy)**2)
    normal=np.stack((np.ones_like(x)*nx-2*bend*along*tx,np.ones_like(y)*ny-2*bend*along*ty),axis=-1)
    ts=np.linspace(0,1,32);attack=np.clip((ts-.08)/.16,0,1);attack=attack*attack*(3-2*attack);decay=1-np.clip((ts-.7)/.26,0,1)
    delta=-weight[...,None]*across[...,None]*normal
    for strength in [0,4,8,12]:
        r=Renderer(name,ctx=ctx);data=np.frombuffer(r.flow_tex.read(),dtype='float32').reshape(32,36,36,2).copy()
        data+=delta[None]*(attack*decay)[:,None,None,None]*strength
        r.flow_tex.write(data.astype('float32').tobytes());rr.append((str(strength),r))
    W=350;H=420;ref=name!='attachment';out=Image.new('RGB',(W*(len(rr)+ref),H*4),(18,23,31));d=ImageDraw.Draw(out)
    for row,t in enumerate([.24,.40,.56,.72]):
        frames=([('华为',reference(rr[0][1].meta,t))] if ref else [])+[(label,r.render(t)) for label,r in rr]
        for col,(label,a) in enumerate(frames):
            im=Image.fromarray(crop(a,rr[0][1].meta));im.thumbnail((W-6,H-34));out.paste(im,(col*W+(W-im.width)//2,row*H+32));d.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(ROOT/'analysis'/f'r32-focus-{name}.jpg',quality=96)
    for _,r in rr:r.close()
    ctx.release()
    print(name,flush=True)
