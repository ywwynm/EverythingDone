"""将参考投影光流拟合为连续低频速度引导，不重放参考图像或逐帧流图。"""
import json
import numpy as np
import cv2
from PIL import Image
from pathlib import Path
HERE=Path(__file__).resolve().parent
from review import reference

KNOTS=np.array([.10,.22,.36,.50,.66,.82])
yy,xx=np.mgrid[0:6,0:6].astype(float)
CENTERS=np.stack((xx.ravel()/5,yy.ravel()/5),axis=-1)
RADIUS=.21

def basis(x,y,centers=CENTERS,radius=RADIUS):
    q=np.stack((x,y),axis=-1)
    b=np.exp(-np.sum((q[...,None,:]-centers)**2,axis=-1)/(radius**2))
    return b/np.maximum(np.sum(b,axis=-1,keepdims=True),1e-9)

def fit(name):
    p=HERE/'assets'/name;m=json.loads((p/'scene.json').read_text(encoding='utf-8'))
    bg=np.array(Image.open(p/'background.png')).astype('float32');src=np.array(Image.open(p/'source.png')).astype('float32')
    x,y,x1,y1=m['rect'];cw=x1-x;ch=y1-y;span=min(cw,ch)
    coeff=[];stats=[];theta=np.deg2rad(m['direction']);wind=np.array([np.cos(theta),-np.sin(theta)])
    iy,ix=np.mgrid[:m['frame'][1],:m['frame'][0]].astype('float32')
    for t in KNOTS:
        a=reference(m,t);b=reference(m,t+.06)
        ag=cv2.cvtColor(a,cv2.COLOR_RGB2GRAY);bgr=cv2.cvtColor(b,cv2.COLOR_RGB2GRAY)
        f=cv2.calcOpticalFlowFarneback(ag,bgr,None,.5,4,25,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        rev=cv2.calcOpticalFlowFarneback(bgr,ag,None,.5,4,25,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        backward=cv2.remap(rev,ix+f[:,:,0],iy+f[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        consistent=np.linalg.norm(f+backward,axis=-1)<1.7
        active=(np.sqrt(np.mean((a.astype('float32')-bg)**2,axis=-1))>18)&(np.sqrt(np.mean((a.astype('float32')-src)**2,axis=-1))>22)
        roi=(ix>x-.25*cw)&(ix<x1+.25*cw)&(iy>max(90,y-.3*ch))&(iy<y1+.22*ch)
        speed=np.linalg.norm(f,axis=-1)/.06
        valid=active&consistent&roi&(speed>25)&(speed<850)
        if name=='thanos' and t<.15:valid&=iy<y+.74*ch
        # 保留稀疏空间采样，不让高分辨率密集区重复计权。
        valid&=(np.mod(ix,3)==0)&(np.mod(iy,3)==0)
        vx=(ix[valid]-x)/cw;vy=(iy[valid]-y)/ch
        B=basis(vx,vy);vel=f[valid]/(.06*span)
        base=wind*(.24+.49*np.clip((t-.06)/.36,0,1))
        # 稀疏地区向整体风速回归，避免缺少证据时出现巨大外推。
        ridge=max(1,len(B))*.006
        C=np.linalg.solve(B.T@B+np.eye(len(CENTERS))*ridge,B.T@vel+np.ones((len(CENTERS),1))*base*ridge)
        C=np.clip(C,-1.8,1.8);coeff.append(C)
        residual=np.linalg.norm(B@C-vel,axis=-1)
        stats.append({'p':float(t),'samples':int(valid.sum()),'median_residual_px_per_normalized_second':float(np.median(residual)*span)})
    result={'knots':KNOTS.tolist(),'centers':CENTERS.tolist(),'coefficients':np.array(coeff).tolist(),'parameter_count':len(KNOTS)*len(CENTERS)*2,'direction':m['direction'],'basis_radius':RADIUS,'measurement':'前后光流一致性 < 1.7 px；显著变化的材料区域；每 3 px 采样','stats':stats}
    (p/'flow-profile.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name,result['parameter_count'],'个投影速度系数',sum(s['samples'] for s in stats),'有效样本',flush=True)

def evaluate(profile,x,y,t):
    knots=np.array(profile['knots']);coeff=np.array(profile['coefficients'])
    hi=int(np.clip(np.searchsorted(knots,t),1,len(knots)-1));lo=hi-1
    f=float(np.clip((t-knots[lo])/(knots[hi]-knots[lo]),0,1));f=f*f*(3-2*f)
    C=coeff[lo]*(1-f)+coeff[hi]*f
    out=basis(x,y,np.array(profile['centers']),profile['basis_radius'])@C
    if profile.get('motion_regions'):
        start,full,fall,end=profile.get('motion_envelope',[.08,.25,.72,.94])
        a=float(np.clip((t-start)/(full-start),0,1));a=a*a*(3-2*a)
        z=float(np.clip((t-fall)/(end-fall),0,1));z=z*z*(3-2*z)
        for ox,oy,sx,sy,vx,vy in profile['motion_regions']:
            out+=np.exp(-((x-ox)/sx)**2-((y-oy)/sy)**2)[...,None]*np.array([vx,vy])*a*(1-z)
    # 有限宽度的弯曲应变场：压缩横向散射、保留沿曲线的输运。
    # 修正进入速度积分，不把运动位置拉回初始位置，也不重放参考帧。
    for region in profile.get('focusing_regions',[]):
        cx,cy,sx,sy,angle,bend,strength,start,full=region
        dx=x-cx;dy=y-cy;nx,ny=np.cos(angle),np.sin(angle);tx,ty=-ny,nx
        along=dx*tx+dy*ty;across=dx*nx+dy*ny-bend*along*along
        normal=np.stack((np.full_like(x,nx)-2*bend*along*tx,np.full_like(y,ny)-2*bend*along*ty),axis=-1)
        attack=float(np.clip((t-start)/(full-start),0,1));attack=attack*attack*(3-2*attack)
        decay=float(1-np.clip((t-.70)/.26,0,1))
        out-=np.exp(-(dx/sx)**2-(dy/sy)**2)[...,None]*across[...,None]*normal*strength*attack*decay
    return out

def texture(profile):
    n=36;nt=32
    yy,xx=np.mgrid[:n,:n].astype(float);x=-.45+(xx+.5)/n*1.9;y=-.45+(yy+.5)/n*1.9
    array=np.array([evaluate(profile,x,y,t) for t in np.linspace(0,1,nt)],dtype='float32')
    return array

def common_texture():
    n=36;yy,xx=np.mgrid[:n,:n].astype(float);x=-.45+(xx+.5)/n*1.9;y=-.45+(yy+.5)/n*1.9
    fields=[]
    for name in ['ironman','thanos','kobe']:
        p=json.loads((HERE/'assets'/name/'flow-profile.json').read_text(encoding='utf-8'))
        d=np.deg2rad(p['direction']-90);c,s=np.cos(d),np.sin(d)
        # 把各参考的主方向统一为正上，再形成跨素材引导场。
        qx=.5+c*(x-.5)+s*(y-.5);qy=.5-s*(x-.5)+c*(y-.5)
        layers=[]
        for t in np.linspace(0,1,32):
            v=evaluate(p,qx,qy,t)
            layers.append(np.stack((c*v[...,0]-s*v[...,1],s*v[...,0]+c*v[...,1]),axis=-1))
        fields.append(np.array(layers))
    return np.mean(fields,axis=0).astype('float32')

if __name__=='__main__':
    for n in ['ironman','thanos','kobe']:fit(n)
    np.save(HERE/'assets/common-flow.npy',common_texture())
