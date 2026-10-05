"""以完整 GPU 共同校准交接宽度、初始覆盖和粒子寿命，不保存逐素材参数。"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import numpy as np,cv2,moderngl
from scipy.optimize import minimize
from scipy.stats import qmc
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_transfer import TransferRules,TransferRenderer
from conditional_material import ConditionalRules
from shared_transport import load_transport
from learned_release import load_weights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','kobe','thanos','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in [1,4,5,6,7]]);ap.add_argument('--label',default='joint');ap.add_argument('--samples',type=int,default=16);ap.add_argument('--refine',type=int,default=80);ap.add_argument('--initial');a=ap.parse_args()
    out=HERE/'analysis/match-cache/material-transfer'/a.label;out.mkdir(parents=True,exist_ok=True)
    rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules']);weights=load_weights(HERE/'conditional-release-weights.json');flow=load_transport(HERE/'conditional-transport-weights.json')
    ctx=moderngl.create_standalone_context(require=430);scenes={}
    def features(f,mask):
        f=f.astype('float32');low=cv2.GaussianBlur(f,(0,0),1.5)
        return [cv2.GaussianBlur(f,(0,0),s)[mask] for s in [2.,7.]]+[cv2.GaussianBlur(np.abs(f-low),(0,0),7.)[mask]]
    def difference(f,g):return float(.5*np.mean(np.abs(f[0]-g[0]))+.5*np.mean(np.abs(f[1]-g[1]))+.6*np.mean(np.abs(f[2]-g[2])))
    for name in a.scenes:
        c=Comparison(name);mask=c.scene.roi
        refs=[(float(c.ref.progress[i]),features(np.asarray(f),mask)) for i,f in [c.ref.nearest(t) for t in [.12,.20,.28,.40,.52,.64,.76,.84]]]
        r=TransferRenderer(name,ctx=ctx,quality=2,weights=weights,transport_weights=flow,rules=rules,sites=[Site(*s) for s in OBSERVED_SITES[name]],transfer=TransferRules(stochastic=0.,pairing=0.,broaden=0.))
        den=float(np.mean([difference(features(r.render(p),mask),f) for p,f in refs]));scenes[name]=(r,refs,mask,den)
        print('基准',name,round(den,4),flush=True)
    keys=['spatial','temporal','pairing','coverage','origin_lead','life','grain','light']
    bounds=np.array([[.006,.045],[.006,.045],[0.,.30],[0.,1.],[0.,.075],[.95,1.7],[1.,1.5],[.74,1.10]])
    history=[];best=None;started=time.monotonic()
    def evaluate(x):
        nonlocal best
        d=dict(zip(keys,map(float,x)));cfg=TransferRules(**{k:d[k] for k in keys[:5]},stochastic=.25,broaden=1.)
        material=dataclasses.replace(rules,**{k:d[k] for k in keys[5:]});scores={}
        for name,(r,refs,mask,den) in scenes.items():
            r.apply_rules(material);r.apply_transfer(cfg)
            score=float(np.mean([difference(features(r.render(p),mask),f) for p,f in refs]));scores[name]=score/den
        loss=float(np.mean(list(scores.values()))+.25*max(scores.values()))
        row=dict(loss=loss,transfer=dataclasses.asdict(cfg),material=dataclasses.asdict(material),scores=scores)
        history.append(row)
        if best is None or loss<best['loss']:best=row;(out/'best.json').write_text(json.dumps(best,ensure_ascii=False,indent=2),'utf-8')
        (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
        print(len(history),round(time.monotonic()-started,1),round(loss,4),'最优',round(best['loss'],4),{k:round(v,3) for k,v in d.items()},flush=True)
        return loss
    if a.initial:
        d=json.loads(Path(a.initial).read_text('utf-8'));evaluate([d['transfer'].get(k,d['material'].get(k)) for k in keys])
    else:evaluate([.018,.015,0.,.75,.0,rules.life,rules.grain,rules.light])
    if a.samples:
        points=qmc.Sobol(len(keys),scramble=True,seed=27).random_base2(int(np.ceil(np.log2(a.samples))))[:a.samples]
        for x in points:evaluate(bounds[:,0]+x*(bounds[:,1]-bounds[:,0]))
    if a.refine:
        x=[best['transfer'].get(k,best['material'].get(k)) for k in keys]
        lo,hi=bounds[:,0],bounds[:,1]
        minimize(lambda z:evaluate(lo+z*(hi-lo)),(np.array(x)-lo)/(hi-lo),method='Powell',bounds=[(0.,1.)]*len(x),options=dict(maxfev=a.refine,xtol=.02,ftol=.003))
    for r,*_ in scenes.values():r.close()
    ctx.release()

if __name__=='__main__':main()
