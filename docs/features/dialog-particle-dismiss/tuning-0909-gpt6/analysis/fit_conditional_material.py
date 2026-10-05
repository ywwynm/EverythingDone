"""用实际渲染约束少量共同材料参数；同时惩罚平均误差与最差素材，禁止逐素材参数。

不把拟合用的相位同时宣称为时序留出；最终验收需额外渲染完整动画。
"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import numpy as np,cv2,moderngl
from scipy.stats import qmc
from scipy.optimize import minimize
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from conditional_material import ConditionalRenderer,ConditionalRules
from learned_release import load_weights
from train_shared_release import TRAIN,HOLDOUT
from v2_inputs import OBSERVED_SITES
from dissolve_v2 import Site,V2Renderer,reference_inputs
from release_integrity import Probe


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--weights',default='conditional-full');ap.add_argument('--samples',type=int,default=24);ap.add_argument('--refine',type=int,default=50);ap.add_argument('--dense',action='store_true');ap.add_argument('--initial');ap.add_argument('--label',default='shared-material-fit');ap.add_argument('--transport');ap.add_argument('--grain-metric',action='store_true');a=ap.parse_args()
    out=HERE/'analysis/match-cache'/a.label;out.mkdir(parents=True,exist_ok=True)
    path=Path(a.weights)
    if not path.is_file():path=HERE/f'analysis/match-cache/shared-release/{a.weights}.json'
    ctx=moderngl.create_standalone_context(require=430);weights=load_weights(path)
    cls=ConditionalRenderer;options={}
    if a.transport:
        from shared_transport import SharedTransportRenderer,load_transport
        cls=SharedTransportRenderer;options['transport_weights']=load_transport(Path(a.transport))
    def measurements(frame,mask):
        frame=frame.astype('float32');out=[cv2.GaussianBlur(frame,(0,0),s)[mask] for s in [4.,12.]]
        if a.grain_metric:out.append(cv2.GaussianBlur(np.abs(frame-cv2.GaussianBlur(frame,(0,0),1.5)),(0,0),8.)[mask])
        return out
    def difference(model,reference):
        errors=[np.abs(a-b).mean() for a,b in zip(model,reference)]
        return float(np.mean(errors[:2])+(errors[2]*.9 if a.grain_metric else 0.))
    scenes={};names=[n for n in TRAIN+HOLDOUT if n!='ironman']
    for name in names:
        p=Probe(name);m=p.scene.meta;mask=p.scene.roi
        phases=[p.comp.ref.nearest(t) for t in ([.15,.25,.35,.45,.55,.65,.75,.85] if a.dense else [.25,.50,.74])]
        refs=[]
        for i,f in phases:refs.append((float(p.comp.ref.progress[i]),measurements(f,mask)))
        def score(r):
            rows=[]
            for t,fs in refs:
                frame=r.render(t).astype('float32')
                rows.append(difference(measurements(frame,mask),fs))
            return float(np.mean(rows))
        r=V2Renderer(name,ctx=ctx,quality=2 if a.dense else 1,**reference_inputs(name));den=score(r);r.close()
        r=cls(name,ctx=ctx,quality=2 if a.dense else 1,sites=[Site(*s) for s in OBSERVED_SITES[name]],weights=weights,rules=ConditionalRules(),**options)
        scenes[name]=(r,refs,mask,den)
        print('预备',name,round(den,3),'条件改变量',round(r.layout_change,3),flush=True)
    keys=['size','life','light','motion','wind','clock'];bounds=np.array([[1.,2.15],[.75,1.8],[.60,1.10],[.25,1.5],[.25,1.5],[-.075,.075]])
    if a.grain_metric:
        keys+=['grain','lift'];bounds=np.concatenate([bounds,[[1.,2.15],[0.,.14]]])
    history=[];best=None;started=time.monotonic()
    def evaluate(x):
        nonlocal best
        cfg=ConditionalRules(**dict(zip(keys,map(float,x))));scores={};ratios=[]
        for name,(r,refs,mask,den) in scenes.items():
            r.apply_rules(cfg);rows=[]
            for t,fs in refs:
                f=r.render(t).astype('float32');rows.append(difference(measurements(f,mask),fs))
            scores[name]=float(np.mean(rows));ratios.append(scores[name]/den)
        loss=float(np.mean(ratios)+.35*np.max(ratios));row=dict(loss=loss,rules=dataclasses.asdict(cfg),scores=scores,ratios=dict(zip(names,ratios)))
        history.append(row)
        if best is None or loss<best['loss']:
            best=row;(out/'best.json').write_text(json.dumps(row,ensure_ascii=False,indent=2),'utf-8')
        (out/'history.json').write_text(json.dumps(history,ensure_ascii=False),'utf-8')
        print(len(history),round(time.monotonic()-started,1),'loss',round(loss,4),'best',round(best['loss'],4),{k:round(v,3) for k,v in zip(keys,x)},flush=True)
        return loss
    if a.initial:
        previous=json.loads(Path(a.initial).read_text('utf-8'))['rules'];evaluate([previous.get(k,0. if k in ['clock','lift'] else 1.5 if k=='grain' else 1.) for k in keys])
    else:
        extra=[1.5,0.] if a.grain_metric else []
        evaluate([1,1,1,1,1,0]+extra);evaluate([1.6,1.4,1,1,1,0]+extra)
    if a.samples:
        sampler=qmc.Sobol(len(keys),scramble=True,seed=49);samples=sampler.random_base2(int(np.ceil(np.log2(a.samples))))[:a.samples]
        for x in samples:evaluate(bounds[:,0]+x*(bounds[:,1]-bounds[:,0]))
    if a.refine:
        x=[best['rules'][k] for k in keys]
        minimize(evaluate,x,method='Powell',bounds=bounds,options=dict(maxfev=a.refine,xtol=.045,ftol=.005))
    for r,_,_,_ in scenes.values():r.close()
    ctx.release();print('结果',best,flush=True)


if __name__=='__main__':main()
