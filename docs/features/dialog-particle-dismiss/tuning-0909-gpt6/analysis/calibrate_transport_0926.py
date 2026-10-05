"""以可见颗粒的参考光流校准一组共同速度系数，避免 RGB 损失偏爱静止／淡出。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import cv2
import numpy as np
import moderngl
from scipy.optimize import minimize
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRenderer,ReleaseSite,FrontRules
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'
NAMES=[f'reference-0926-{i}' for i in [1,4,5,6,7]]+['spiderman-original']


def main():
    ctx=moderngl.create_standalone_context(require=430);cases=[];history=[]
    for name in NAMES:
        r=FrontRenderer(name,sites=[ReleaseSite(*q) for q in OBSERVED_SITES[name]],ctx=ctx,quality=1,cell_px=2.1)
        d=np.load(OUT/f'{name}-flow-observed.npz');rect=d['rect'];left,top,right,bottom=rect
        w=64;h=round(w*(bottom-top)/(right-left));scale=float(d['scale']);truth=[]
        for f,e in zip(d['flow'],d['error']):
            flow=cv2.resize(f,(w,h),interpolation=cv2.INTER_AREA)/scale/.04/r.span
            norm=np.linalg.norm(f,axis=2);mask=(norm>1.)&(norm<12)&(e<.6)
            valid=cv2.resize(mask.astype('float32'),(w,h),interpolation=cv2.INTER_AREA)>.75
            truth.append((flow,valid))
        cases.append((name,r,rect,(w,h),d['phases'],truth))
    def evaluate(values):
        losses=[]
        for name,r,rect,size,phases,truth in cases:
            r.compute['front_curl']=values[0];r.compute['front_carry']=values[1];r.compute['front_template']=values[2];r.reset()
            left,top,right,bottom=rect;w,h=size;x,y,_,_=r.meta['rect'];items=[]
            for t,(target,valid) in zip(phases,truth):
                r.render(float(t));state=np.frombuffer(r.state.read(),dtype='float32').reshape(-1,8)
                age=t-r.base[:,2];life=r.base[:,6]
                alive=(age>.03)&(age<life-.04)
                ix=np.floor((state[:,0]+x-left)/(right-left)*w).astype('int32');iy=np.floor((state[:,1]+y-top)/(bottom-top)*h).astype('int32')
                alive&=(ix>=0)&(ix<w)&(iy>=0)&(iy<h)
                index=(iy[alive]*w+ix[alive]);counts=np.bincount(index,minlength=w*h)
                vector=np.stack([np.bincount(index,weights=state[alive,4+c]/r.span,minlength=w*h)/np.maximum(counts,1) for c in [0,1]],axis=1).reshape(h,w,2)
                mask=valid&(counts.reshape(h,w)>3)
                if mask.sum()>10:items.append(float(np.minimum(np.linalg.norm(vector-target,axis=2)[mask],2).mean()))
            losses.append(float(np.mean(items)) if items else 1.)
        result=dict(parameters=dict(zip(['curl','carry','template'],map(float,values))),loss=float(np.mean(losses)),cases=dict(zip(NAMES,losses)))
        history.append(result)
        if len(history)==1 or result['loss']<min(q['loss'] for q in history[:-1]):
            print('共同速度误差',round(result['loss'],5),result['parameters'],flush=True)
        return result['loss']
    initial=[-.15,.2,.10];bounds=[(-.7,.7),(-.2,1.0),(0,.6)]
    minimize(evaluate,initial,method='Nelder-Mead',bounds=bounds,options=dict(maxiter=42,xatol=.01,fatol=.002))
    best=min(history,key=lambda q:q['loss']);(OUT/'transport-joint-fit.json').write_text(json.dumps(dict(best=best,history=history),ensure_ascii=False,indent=2),'utf-8')
    for _,r,*_ in cases:r.close()


if __name__=='__main__':main()
