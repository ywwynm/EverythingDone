"""将局部起裂与后续扩张分开检验；仅选择启动段种子，后段校准共同规则。"""
from pathlib import Path
import argparse, dataclasses, json, sys
import numpy as np
from scipy.optimize import least_squares
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import ReleaseSite
from unified_model import random_values
from export_videos import load_meta
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'
TRAIN=[f'reference-0926-{i}' for i in [1,4,5,6,7]]+['spiderman-original']


@dataclasses.dataclass(frozen=True)
class Propagation:
    burst: float=.13
    linear: float=.22
    fatigue: float=1.4
    rate_variation: float=.50
    burst_variation: float=.30
    union: float=.025


def field(nx,ny,width,height,direction,seed,sites,rules=Propagation()):
    yy,xx=np.mgrid[:ny,:nx].astype('float64');span=min(width,height)
    xx=(xx+.5)/nx*width/span;yy=(yy+.5)/ny*height/span
    local=random_values(len(sites),int(seed)^0x3c6ef372).astype('float64')
    age=np.linspace(0,1.4,561)
    result=np.full((ny,nx),10.,dtype='float64')
    for j,p in enumerate(sites):
        d=np.hypot(xx-p.x*width/span,yy-p.y*height/span)
        rate=np.exp(2*rules.rate_variation*(local[j,0]-.5))
        burst=rules.burst*np.exp(2*rules.burst_variation*(local[j,1]-.5))
        # 单调半径：首次起裂的有限脉冲，随后持续侵蚀与材料疲劳。
        radius=burst*(1-np.exp(-age/.045))+rate*(rules.linear*age+rules.fatigue*age**3)
        arrival=np.interp(d,radius,age)+p.start
        h=np.maximum(rules.union-np.abs(result-arrival),0)/max(rules.union,1e-6)
        result=np.minimum(result,arrival)-h*h*rules.union*.25
    return (.86-.025*np.logaddexp(0,(.86-result)/.025)).astype('float32')


def main():
    p=argparse.ArgumentParser();p.add_argument('--rounds',type=int,default=3);a=p.parse_args()
    rules=Propagation();cases=[]
    for name,origins in OBSERVED_SITES.items():
        m=load_meta(name);x,y,x1,y1=m['rect']
        d=np.load(OUT/f'{name}-surface-measurements.npz')
        measured=d['arrival'];valid=d['initial_valid']
        cases.append((name,m,x1-x,y1-y,[ReleaseSite(*q) for q in origins],measured,valid,d['weight']))
    chosen={};history=[]
    def predict(c,seed,r):
        name,m,w,h,sites,measured,valid,weight=c
        return field(measured.shape[1],measured.shape[0],w,h,m['direction'],seed,sites,r)
    def seeds(r):
        for c in cases:
            name,m,w,h,sites,measured,valid,weight=c
            early=valid&(measured>.025)&(measured<=.20);later=valid&(measured>.20)
            def loss(seed):
                guess=predict(c,seed,r)
                return float(np.abs(guess-measured)[early].mean()+np.maximum(.20-guess[later],0).mean())
            choices=[(loss(seed),seed) for seed in range(2048)]
            chosen[name]=min(choices)[1]
            print(name,'启动种子',chosen[name],round(min(choices)[0],4),flush=True)
    keys=['burst','linear','fatigue','rate_variation','burst_variation']
    bounds=np.array([[.02,.24],[.01,.8],[.2,3.],[0.,.9],[0.,.9]])
    for iteration in range(a.rounds):
        seeds(rules)
        def residual(v):
            r=Propagation(**(dataclasses.asdict(rules)|dict(zip(keys,v))))
            errors=[]
            for c in cases:
                if c[0] not in TRAIN:continue
                delta=(predict(c,chosen[c[0]],r)-c[5])[c[7]]
                # 各素材等权，不用已释放云团亮度决定原表面何时解除。
                sample=delta[np.linspace(0,len(delta)-1,700).astype(int)]
                errors.extend(sample)
            return np.asarray(errors)
        fit=least_squares(residual,[getattr(rules,k) for k in keys],bounds=bounds.T,loss='soft_l1',f_scale=.045,diff_step=1e-3)
        rules=Propagation(**(dataclasses.asdict(rules)|dict(zip(keys,map(float,fit.x)))))
        scores={c[0]:float(np.abs(predict(c,chosen[c[0]],rules)-c[5])[c[7]].mean()) for c in cases}
        history.append(dict(iteration=iteration,rules=dataclasses.asdict(rules),seeds=chosen.copy(),phase_errors=scores))
        print('共同规则',dataclasses.asdict(rules),'时序误差',scores,flush=True)
    seeds(rules)
    result=dict(rules=dataclasses.asdict(rules),seeds=chosen,history=history,seed_prefix=.20,training=TRAIN)
    (OUT/'propagation-study.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
