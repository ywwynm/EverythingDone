"""在保留前后光流一致性筛选的条件下比较连续空间流场带宽。"""
import sys,json,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np,cv2
import moderngl
from PIL import Image,ImageDraw,ImageFont
import fit_flow
from review import reference,crop
from renderer import Renderer
OUT=ROOT/'analysis/flow-detail';OUT.mkdir(exist_ok=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
variants=[('r31',6,.21,.006),('detail8',8,.14,.003),('detail10',10,.115,.002),('detail12',12,.095,.0015)]
for name in ['thanos','kobe','ironman']:
    d=ROOT/'assets'/name;m=json.loads((d/'scene.json').read_text(encoding='utf-8'))
    bg=np.array(Image.open(d/'background.png')).astype('float32');src=np.array(Image.open(d/'source.png')).astype('float32')
    x,y,x1,y1=m['rect'];cw=x1-x;ch=y1-y;span=min(cw,ch)
    iy,ix=np.mgrid[:m['frame'][1],:m['frame'][0]].astype('float32');theta=np.deg2rad(m['direction']);wind=np.array([np.cos(theta),-np.sin(theta)])
    samples=[];knots=np.linspace(.07,.88,12);delta=.04
    for t in knots:
        a=reference(m,t);b=reference(m,t+delta)
        ag=cv2.cvtColor(a,cv2.COLOR_RGB2GRAY);bgra=cv2.cvtColor(b,cv2.COLOR_RGB2GRAY)
        flow=cv2.calcOpticalFlowFarneback(ag,bgra,None,.5,4,17,5,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        rev=cv2.calcOpticalFlowFarneback(bgra,ag,None,.5,4,17,5,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        backward=cv2.remap(rev,ix+flow[:,:,0],iy+flow[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        active=(np.sqrt(np.mean((a.astype('float32')-bg)**2,axis=-1))>14)&(np.sqrt(np.mean((a.astype('float32')-src)**2,axis=-1))>22)
        roi=(ix>x-.3*cw)&(ix<x1+.3*cw)&(iy>max(90,y-.4*ch))&(iy<y1+.22*ch)
        speed=np.linalg.norm(flow,axis=-1)/delta
        valid=active&roi&(np.linalg.norm(flow+backward,axis=-1)<1.5)&(speed>15)&(speed<1100)&(ix%3==0)&(iy%3==0)
        if name=='thanos' and t<.15:valid&=iy<y+.74*ch
        samples.append(((ix[valid]-x)/cw,(iy[valid]-y)/ch,flow[valid]/(delta*span)))
    ctx=moderngl.create_standalone_context(require=430)
    renderers=[]
    for label,n,radius,ridge_scale in variants:
        r=Renderer(name,ctx=ctx)
        if label!='r31':
            yy,xx=np.mgrid[:n,:n].astype(float);centers=np.stack((xx.ravel()/(n-1),yy.ravel()/(n-1)),axis=-1)
            coeff=[]
            for t,(vx,vy,vel) in zip(knots,samples):
                B=fit_flow.basis(vx,vy,centers,radius);ridge=max(1,len(B))*ridge_scale
                base=wind*(.24+.49*np.clip((t-.06)/.36,0,1))
                C=np.linalg.solve(B.T@B+np.eye(len(centers))*ridge,B.T@vel+np.ones((len(centers),1))*base*ridge)
                coeff.append(np.clip(C,-2.5,2.5))
            profile=dict(knots=knots.tolist(),centers=centers.tolist(),coefficients=np.array(coeff).tolist(),direction=m['direction'],basis_radius=radius)
            (OUT/f'{name}-{label}.json').write_text(json.dumps(profile),encoding='utf-8')
            r.flow_tex.write(fit_flow.texture(profile).tobytes())
        renderers.append((label,r))
    times=[.24,.40,.56,.72];W=350;H=420
    canvas=Image.new('RGB',(W*5,H*4),(18,23,31));draw=ImageDraw.Draw(canvas)
    for row,t in enumerate(times):
        frames=[('华为',reference(m,t))]+[(label,r.render(t)) for label,r in renderers]
        for col,(label,frame) in enumerate(frames):
            im=Image.fromarray(crop(frame,m));im.thumbnail((W-6,H-34));canvas.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
    canvas.save(OUT/f'{name}.jpg',quality=96)
    for _,r in renderers:r.close()
    ctx.release()
    print(name,flush=True)
