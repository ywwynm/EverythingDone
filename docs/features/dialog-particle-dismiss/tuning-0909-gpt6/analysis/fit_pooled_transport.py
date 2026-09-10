"""合并三段原片的可靠运动观测，直接拟合唯一的方向归一化输运场。

只在离线研究时读取场景；空区域的回退速度不进入观测平均。
"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,cv2
from PIL import Image
from export_videos import Reference
from renderer import HERE
from fit_flow import basis

out=HERE/'analysis/edge-roll/pooled-transport';out.mkdir(parents=True,exist_ok=True)
knots=np.array([.08,.18,.28,.38,.48,.58,.70,.84,.96])
yy,xx=np.mgrid[0:7,0:7];centers=np.stack((xx.ravel()/6,yy.ravel()/6),axis=-1)
data=[[] for _ in knots];stats=[]
for name in ['ironman','thanos','kobe']:
    meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));ref=Reference(meta)
    bg=np.array(Image.open(HERE/'assets'/name/'background.png').convert('RGB')).astype('float32')
    src=ref.original.astype('float32');x,y,x1,y1=meta['rect'];cw=x1-x;ch=y1-y;span=min(cw,ch)
    iy,ix=np.mgrid[:meta['frame'][1],:meta['frame'][0]].astype('float32')
    c,s=np.cos(np.deg2rad(meta['direction']-90)),np.sin(np.deg2rad(meta['direction']-90))
    for j,t in enumerate(knots):
        lo=int(np.argmin(abs(ref.times-(meta['reference']['start']+(t-.018)*(meta['reference']['end']-meta['reference']['start'])))))
        hi=int(np.argmin(abs(ref.times-(meta['reference']['start']+(t+.018)*(meta['reference']['end']-meta['reference']['start'])))))
        hi=max(lo+1,min(hi,len(ref.times)-1));dt=(ref.times[hi]-ref.times[lo])/(meta['reference']['end']-meta['reference']['start'])
        a,b=np.asarray(ref.frames[lo]),np.asarray(ref.frames[hi]);ag,bggray=cv2.cvtColor(a,cv2.COLOR_RGB2GRAY),cv2.cvtColor(b,cv2.COLOR_RGB2GRAY)
        f=cv2.calcOpticalFlowFarneback(ag,bggray,None,.5,4,25,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        r=cv2.calcOpticalFlowFarneback(bggray,ag,None,.5,4,25,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        reverse=cv2.remap(r,ix+f[:,:,0],iy+f[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        error=np.linalg.norm(f+reverse,axis=-1);speed=np.linalg.norm(f,axis=-1)/dt
        active=(np.sqrt(np.mean((a.astype('float32')-bg)**2,axis=-1))>20)&(np.sqrt(np.mean((a.astype('float32')-src)**2,axis=-1))>22)
        valid=active&(error<1.25)&(speed>30)&(speed<950)&(ix>x-.28*cw)&(ix<x1+.22*cw)&(iy>max(95,y-.35*ch))&(iy<y1+.18*ch)
        valid&=(ix%4==0)&(iy%4==0)
        px=(ix[valid]-x)/cw-.5;py=(iy[valid]-y)/ch-.5
        positions=np.stack((c*px-s*py+.5,s*px+c*py+.5),axis=-1)
        v=f[valid]/(dt*span);vel=np.stack((c*v[:,0]-s*v[:,1],s*v[:,0]+c*v[:,1]),axis=-1)
        weight=np.exp(-error[valid]**2/.6)
        # 每段观测总权重相同，画面更大或颗粒更多的素材不支配整份模型。
        weight*=1500/max(weight.sum(),1)
        data[j].append((positions,vel,weight))
        stats.append({'scene':name,'phase':float(t),'samples':int(valid.sum()),'median_speed':float(np.median(speed[valid])) if valid.any() else None})
    print('观测完成',name,flush=True)

coefs=[]
for j,t in enumerate(knots):
    pos=np.concatenate([d[0] for d in data[j]]);vel=np.concatenate([d[1] for d in data[j]]);w=np.concatenate([d[2] for d in data[j]])
    B=basis(pos[:,0],pos[:,1],centers,.19);base=np.array([0.,-(.24+.49*np.clip((t-.06)/.36,0,1))])
    # 相邻空间系数的平滑约束，而非每一份无观测区域先独立回退再平均。
    lap=np.zeros((49,49))
    for z in range(49):
        for n in [z+1 if z%7<6 else -1,z+7 if z<42 else -1]:
            if n<0:continue
            lap[z,z]+=1;lap[n,n]+=1;lap[z,n]-=1;lap[n,z]-=1
    ridge=7.;A=B.T@(w[:,None]*B)+np.eye(49)*ridge+lap*5.
    C=np.linalg.solve(A,B.T@(w[:,None]*vel)+np.ones((49,1))*base*ridge)
    coefs.append(np.clip(C,-1.8,1.8))
coefs=np.array(coefs)
n=36;yy,xx=np.mgrid[:n,:n].astype(float);x=-.45+(xx+.5)/n*1.9;y=-.45+(yy+.5)/n*1.9;B=basis(x,y,centers,.19)
result=[]
for t in np.linspace(0,1,32):
    hi=int(np.clip(np.searchsorted(knots,t),1,len(knots)-1));lo=hi-1;f=float(np.clip((t-knots[lo])/(knots[hi]-knots[lo]),0,1));f=f*f*(3-2*f)
    result.append(B@(coefs[lo]*(1-f)+coefs[hi]*f))
result=np.array(result,dtype='float32');np.save(out/'common-transport.npy',result)
(out/'observations.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'fit.json').write_text(json.dumps({'knots':knots.tolist(),'centers':centers.tolist(),'coefficients':coefs.tolist(),'basis_radius':.19,'runtime_source_count':1,'scope':'三段原片共同训练一份场；未读取人物的拟合配置或局部修正。'},ensure_ascii=False,indent=2),encoding='utf-8')
print('共用输运场完成',sum(s['samples'] for s in stats),'观测点',flush=True)
