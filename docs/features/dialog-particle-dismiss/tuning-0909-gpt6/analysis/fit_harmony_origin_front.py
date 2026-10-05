"""固定已复核的初始条件，只诊断共同传播标量；不生成逐段运行控制图。"""
from pathlib import Path
import argparse,json,sys
from dataclasses import asdict,replace
import numpy as np
from scipy.optimize import least_squares

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from release_integrity import Probe,SCENES,HOLDOUT
from reference_initial_conditions import SITES
from harmony_origins import OriginRules


def prediction(row,rules):
    sites=np.asarray(SITES[row['name']]);w,h=row['size'];span=min(w,h)
    uv=row['uv'];delta=(uv[:,None,:]-sites[None,:,:2])*[w/span,h/span]
    edges=np.stack([sites[:,1]*h,(1-sites[:,0])*w,(1-sites[:,1])*h,sites[:,0]*w],axis=1)/span
    edge=np.argmin(edges,axis=1);nearest=edges[np.arange(len(sites)),edge]
    horizontal=(edge==0)|(edge==2)
    along=np.where(horizontal[None,:],delta[:,:,0],delta[:,:,1])
    across=np.where(horizontal[None,:],delta[:,:,1],delta[:,:,0])
    along=along/(1+rules.edge_speed*np.exp(-np.maximum(nearest,0)/.06)[None,:]*np.exp(-np.abs(across)/rules.edge_depth))
    distance=np.hypot(along,across)
    travel=rules.duration*distance**rules.exponent
    travel=rules.travel_limit*np.tanh(travel/rules.travel_limit)
    return (sites[None,:,2]+travel).min(1)+rules.surface_tail*.5


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    data=[]
    for name in SCENES+HOLDOUT:
        p=Probe(name);target=p.huawei();v,u=np.meshgrid(p.v,p.u,indexing='ij')
        use=p.valid&np.isfinite(target)&(target>.04)&(target<.8)
        ids=np.flatnonzero(use.ravel());ids=ids[np.random.RandomState(129).permutation(len(ids))[:600]]
        x,y,x1,y1=p.rect
        data.append(dict(name=name,size=[x1-x,y1-y],uv=np.column_stack([u.ravel()[ids],v.ravel()[ids]]),
                         target=target.ravel()[ids]))
    base=OriginRules();keys=['duration','exponent','edge_speed','edge_depth','travel_limit']
    def rules(values):return replace(base,**dict(zip(keys,values)))
    def residual(values):
        q=rules(values)
        return np.concatenate([(prediction(r,q)-r['target'])/np.sqrt(len(r['target'])) for r in data if r['name'] in SCENES])
    fit=least_squares(residual,[getattr(base,k) for k in keys],bounds=([.5,.65,0.,.025,.55],[4.,2.2,1.5,.35,1.]),
                      max_nfev=160)
    result=dict(rules=asdict(rules(fit.x)),baseline=asdict(base),train=SCENES,holdout=HOLDOUT,scores={},
                limitation='原位纹理完整度时刻包含遮挡／材质滞后；只能用于诊断，必须实际渲染复核。')
    for row in data:
        result['scores'][row['name']]=dict(base_mae=float(np.abs(prediction(row,base)-row['target']).mean()),
                                           fit_mae=float(np.abs(prediction(row,rules(fit.x))-row['target']).mean()))
    (args.out/'front-fit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
