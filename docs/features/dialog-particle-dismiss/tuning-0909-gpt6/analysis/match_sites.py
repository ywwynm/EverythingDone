"""按原片的原位表面解除，细化每段参考的起点位置与启动时刻（初始条件），全局前沿规则固定。

原位表面只由解除场决定，不需要渲染：像素颜色 = α·源图 + (1−α)·背景，当 (1−α)·|源图−背景| 不超过
测量阈值时仍被判为原位表面，与 match_observe 对原片的判定一致。只用表面，不用云团，
因此不会把运动误差吸收到起点里。位置限制在观测值 ±0.10、启动时刻 ±0.06 以内。
"""
from pathlib import Path
import argparse,dataclasses,json,math,sys
import cv2
import numpy as np
from scipy.optimize import minimize

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_compare import Comparison
from match_observe import OCC_T
from dissolve_v2 import release_field,Site,RULES,V2Rules
from v2_inputs import SITES,inputs

OUT=HERE/'analysis/match-cache'
GRID=4   # 预测与比较的像素步长


class SurfaceTarget:
    def __init__(self,name,max_progress=.6):
        comp=Comparison(name);s=comp.scene;self.name=name;self.scene=s
        x,y,x1,y1=s.rect;self.w,self.h=x1-x,y1-y
        src=s.source[y:y1,x:x1];bg=s.bg[y:y1,x:x1]
        self.contrast=np.abs(src-bg).max(2)[::GRID,::GRID]
        self.mask=(s.card&s.distinct&s.roi)[y:y1,x:x1][::GRID,::GRID]
        self.frames=[];self.obs=[]
        for i in comp.index:
            p=float(comp.ref.progress[i])
            if p>max_progress:continue
            o=s.observe(np.asarray(comp.ref.frames[i]))
            self.frames.append(p);self.obs.append(o['surface'][y:y1,x:x1][::GRID,::GRID].astype('float32'))
        q=inputs(name);self.seed=q['seed'];self.sites0=[(d['x'],d['y'],d['start']) for d in q['sites']]

    def predict(self,sites,rules):
        ny,nx=self.contrast.shape
        T=release_field(nx,ny,self.w,self.h,[Site(*q) for q in sites],self.seed,rules)
        out=[]
        for p in self.frames:
            alpha=1-np.clip((p-(T-rules.surface_lead))/max(rules.surface_lead+rules.surface_tail,1e-6),0,1)
            alpha=alpha*alpha*(3-2*alpha)
            out.append(((1-alpha)*self.contrast<=OCC_T*.5).astype('float32'))
        return out

    def loss(self,sites,rules):
        pred=self.predict(sites,rules);m=self.mask
        return float(np.mean([np.abs(a-b)[m].mean() for a,b in zip(self.obs,pred)]))


def refine(t,rules,span_xy=.10,span_t=.06):
    x0=np.array(t.sites0,dtype='float64').ravel()
    lo=x0.copy();hi=x0.copy()
    for k in range(len(t.sites0)):
        lo[3*k:3*k+2]-=span_xy;hi[3*k:3*k+2]+=span_xy;lo[3*k+2]=max(0.,x0[3*k+2]-span_t);hi[3*k+2]=x0[3*k+2]+span_t
    lo[0::3]=np.maximum(lo[0::3],-.05);hi[0::3]=np.minimum(hi[0::3],1.05);lo[1::3]=np.maximum(lo[1::3],-.05);hi[1::3]=np.minimum(hi[1::3],1.05)
    def f(v):
        v=np.clip(v,lo,hi);return t.loss(v.reshape(-1,3),rules)
    best=(f(x0),x0)
    rng=np.random.default_rng(11)
    for trial in range(6):
        start=x0 if trial==0 else np.clip(x0+rng.normal(0,1,len(x0))*(hi-lo)*.25,lo,hi)
        r=minimize(f,start,method='Nelder-Mead',options=dict(maxiter=500,xatol=1e-3,fatol=1e-5))
        v=np.clip(r.x,lo,hi);val=f(v)
        if val<best[0]:best=(val,v)
    return best[0],best[1].reshape(-1,3)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--scenes',nargs='+',default=list(SITES));ap.add_argument('--out',default='refined-sites.json')
    a=ap.parse_args()
    rules=RULES
    if a.rules:rules=dataclasses.replace(RULES,**json.loads(Path(a.rules).read_text('utf-8'))['best']['rules'])
    result={}
    for name in a.scenes:
        t=SurfaceTarget(name);before=t.loss(np.array(t.sites0),rules)
        val,sites=refine(t,rules)
        result[name]=dict(before=before,after=val,sites=[[round(float(v),4) for v in q] for q in sites],observed=t.sites0)
        print(name,'表面误差',round(before,4),'->',round(val,4),[tuple(round(float(v),3) for v in q) for q in sites],flush=True)
    (OUT/a.out).write_text(json.dumps(result,ensure_ascii=False,indent=1),'utf-8')


if __name__=='__main__':main()
