"""共同的有限起裂区与持续侵蚀；种子仅由启动段选择，后续帧只校准一份规则。"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import numpy as np
from scipy.optimize import least_squares
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import random_values,smooth
from front_transport import ReleaseSite
from export_videos import load_meta
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'
TRAIN=[f'reference-0926-{i}' for i in [1,4,5,6,7]]+['spiderman-original']


@dataclasses.dataclass(frozen=True)
class Stages:
    burst:float=.16
    linear:float=.3
    fatigue:float=1.5
    rate_variation:float=.45
    burst_variation:float=.7
    anisotropy:float=.25


@dataclasses.dataclass(frozen=True)
class Activation:
    ceiling:float=.62
    reach:float=.44
    exponent:float=2.
    variation:float=.55
    anisotropy:float=.2


@dataclasses.dataclass(frozen=True)
class Elliptic:
    duration:float=1.20
    exponent:float=1.70
    rate_variation:float=.70
    shape_variation:float=1.35
    anisotropy:float=0.


@dataclasses.dataclass(frozen=True)
class EllipticPulse(Stages):
    shape_variation:float=1.2


def sampled(x,y,w,h,seed,sites,rules):
    span=min(w,h);local=random_values(len(sites),seed^0x3c6ef372)
    age=np.linspace(0,1.5,451);result=np.full_like(x,4.,dtype='float64')
    for j,s in enumerate(sites):
        dx=x-s.x*w/span;dy=y-s.y*h/span
        inward=np.array([(.5-s.x)*w,(.5-s.y)*h]);inward/=max(np.linalg.norm(inward),1e-5)
        a=dx*inward[0]+dy*inward[1];b=-dx*inward[1]+dy*inward[0]
        edge=max(min(s.x,s.y,1-s.x,1-s.y),0.)
        anisotropy=rules.anisotropy*(1-float(smooth(edge/.28)))
        d=np.sqrt(a*a+(1-anisotropy)**2*b*b)
        if isinstance(rules,EllipticPulse):
            angle=float(local[j,3])*np.pi
            axis=np.exp(rules.shape_variation*(float(local[j,2])-.5))
            d=np.hypot((dx*np.cos(angle)+dy*np.sin(angle))/axis,(-dx*np.sin(angle)+dy*np.cos(angle))*axis)
        if isinstance(rules,Elliptic):
            angle=float(local[j,2])*np.pi
            axis=np.exp(rules.shape_variation*(float(local[j,1])-.5))
            aa=(dx*np.cos(angle)+dy*np.sin(angle))/axis
            bb=(-dx*np.sin(angle)+dy*np.cos(angle))*axis
            d=np.hypot(aa,bb)
            rate=np.exp(2*rules.rate_variation*(float(local[j,0])-.5))
            arrival=s.start+rules.duration*np.power(d/rate,rules.exponent)
        elif isinstance(rules,Activation):
            reach=rules.reach*np.exp(2*rules.variation*(float(local[j,0])-.5))
            arrival=s.start+rules.ceiling*(-np.expm1(-np.power(d/reach,rules.exponent)))
        else:
            rate=np.exp(2*rules.rate_variation*(float(local[j,0])-.5))
            burst=rules.burst*np.exp(2*rules.burst_variation*(float(local[j,1])-.5))
            radius=burst*(1-np.exp(-age/.035))+rate*(rules.linear*age+rules.fatigue*age**3)
            arrival=np.interp(d,radius,age)+s.start
        blend=np.maximum(.025-np.abs(result-arrival),0)/.025
        result=np.minimum(result,arrival)-blend*blend*.025*.25
    return .86-.025*np.logaddexp(0,(.86-result)/.025)


def field(nx,ny,w,h,direction,seed,sites,rules):
    y,x=np.mgrid[:ny,:nx].astype('float64');span=min(w,h)
    return sampled((x+.5)/nx*w/span,(y+.5)/ny*h/span,w,h,seed,sites,rules).astype('float32')


def cases():
    result=[]
    for name,origins in OBSERVED_SITES.items():
        m=load_meta(name);xx,yy,x1,y1=m['rect'];w,h=x1-xx,y1-yy;span=min(w,h)
        data=np.load(OUT/f'{name}-coverage-measurements.npz');target=data['arrival'];valid=data['initial_valid']
        y,x=np.mgrid[:target.shape[0],:target.shape[1]].astype('float64');x=(x+.5)/target.shape[1]*w/span;y=(y+.5)/target.shape[0]*h/span
        # 只取初始可见且覆盖投影可信的点，每段等权；包括尚未解除的点。
        indices=np.flatnonzero(valid & (target>.02))[::3]
        result.append(dict(name=name,w=w,h=h,sites=[ReleaseSite(*q) for q in origins],
                           x=x.ravel()[indices],y=y.ravel()[indices],target=target.ravel()[indices]))
    return result


def choose_seeds(data,rules,count=4096):
    chosen={}
    for c in data:
        target=c['target'];early=(target<=.20);late=~early
        # 限制在 <= .20 的观测，只判断后续点此时仍应保留。
        ei=np.flatnonzero(early)[::max(1,int(early.sum())//120)]
        li=np.flatnonzero(late)[::max(1,int(late.sum())//120)]
        index=np.r_[ei,li];x=c['x'][index];y=c['y'][index];t=target[index]
        def loss(seed):
            p=sampled(x,y,c['w'],c['h'],seed,c['sites'],rules)
            return np.mean(abs(p[:len(ei)]-t[:len(ei)]))+np.mean(np.maximum(.20-p[len(ei):],0))
        best=min((loss(seed),seed) for seed in range(count))
        chosen[c['name']]=best[1]
        print('启动输入',c['name'],best[1],round(float(best[0]),4),flush=True)
    return chosen


def main():
    p=argparse.ArgumentParser();p.add_argument('--rounds',type=int,default=3);p.add_argument('--law',default='pulse',choices=['pulse','activation','elliptic','elliptic-pulse']);a=p.parse_args()
    data=cases();kind={'activation':Activation,'pulse':Stages,'elliptic':Elliptic,'elliptic-pulse':EllipticPulse}[a.law];rules=kind();history=[]
    keys=list(dataclasses.asdict(rules));bounds=np.array([[.04,.26],[.02,.9],[.2,3.8],[.05,.8],[.2,1.1],[0,.65]]) if a.law in ['pulse','elliptic-pulse'] else np.array([[.45,.85],[.20,.8],[1.,3.5],[.1,.9],[0.,.55]]) if a.law=='activation' else np.array([[.45,2.5],[1.,3.],[.1,1.2],[.25,1.7],[-.001,.001]])
    if a.law=='elliptic-pulse':bounds=np.vstack([bounds,[[.35,1.7]]])
    for i in range(a.rounds):
        chosen=choose_seeds(data,rules)
        def residual(values):
            r=kind(**dict(zip(keys,values)));result=[]
            for c in data:
                if c['name'] not in TRAIN:continue
                p=sampled(c['x'],c['y'],c['w'],c['h'],chosen[c['name']],c['sites'],r)
                e=(p-c['target']);result.extend(e[np.linspace(0,len(e)-1,900).astype(int)])
            return np.array(result)
        fit=least_squares(residual,[getattr(rules,k) for k in keys],bounds=bounds.T,loss='soft_l1',f_scale=.04,diff_step=1e-3,max_nfev=100)
        rules=kind(**dict(zip(keys,map(float,fit.x))))
        scores={c['name']:float(np.mean(abs(sampled(c['x'],c['y'],c['w'],c['h'],chosen[c['name']],c['sites'],rules)-c['target']))) for c in data}
        history.append(dict(rules=dataclasses.asdict(rules),seeds=chosen,scores=scores))
        print('一份全局规则',history[-1],flush=True)
    chosen=choose_seeds(data,rules)
    (OUT/f'front-{a.law}-study.json').write_text(json.dumps(dict(rules=dataclasses.asdict(rules),seeds=chosen,history=history,seed_prefix=.20,training=TRAIN),indent=2),'utf-8')


if __name__=='__main__':main()
