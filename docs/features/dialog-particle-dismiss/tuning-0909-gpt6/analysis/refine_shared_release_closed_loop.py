"""同口径测量原片和真实 GPU 输出，闭环校准一份共同释放函数。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter,map_coordinates
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from conditional_material import ConditionalRenderer,ConditionalRules
from learned_release import load_weights,predict,features,canonical_sites
from release_integrity import Probe
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

NAMES=['ironman','kobe','thanos','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in range(1,8)]
OUT=HERE/'analysis/match-cache/closed-loop-release'


def main():
    global OUT
    ap=argparse.ArgumentParser();ap.add_argument('--initial',required=True);ap.add_argument('--rounds',type=int,default=2);ap.add_argument('--steps',type=int,default=4000);ap.add_argument('--transfer');ap.add_argument('--transport');ap.add_argument('--label');a=ap.parse_args()
    if a.label:OUT=OUT/a.label
    OUT.mkdir(parents=True,exist_ok=True)
    import torch
    torch.set_num_threads(4);torch.manual_seed(19)
    def tensor(a):
        v=np.ascontiguousarray(a,np.float32)
        return torch.frombuffer(bytearray(v.tobytes()),dtype=torch.float32).reshape(v.shape)
    probes={n:Probe(n) for n in NAMES}
    cfg=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    renderer=ConditionalRenderer;extra={}
    if a.transport:
        from shared_transport import SharedTransportRenderer,load_transport
        renderer=SharedTransportRenderer;extra['transport_weights']=load_transport(Path(a.transport))
    if a.transfer:
        from surface_transfer import TransferRules,TransferRenderer
        renderer=TransferRenderer;config=json.loads(Path(a.transfer).read_text('utf-8'))
        extra['transfer']=TransferRules(**config['transfer']);cfg=ConditionalRules(**config['material'])
        if 'transport_weights' not in extra:
            from shared_transport import load_transport
            extra['transport_weights']=load_transport(HERE/'conditional-transport-weights.json')
    weights=load_weights(Path(a.initial));ctx=moderngl.create_standalone_context(require=430)
    model=torch.nn.Sequential(torch.nn.Linear(33,48),torch.nn.Tanh(),torch.nn.Linear(48,48),torch.nn.Tanh(),torch.nn.Linear(48,1))
    with torch.no_grad():
        for layer,(w,b) in zip([q for q in model if isinstance(q,torch.nn.Linear)],weights):layer.weight.copy_(tensor(w));layer.bias.copy_(tensor(b))
    initial=json.loads(Path(a.initial).read_text('utf-8'));history=[]
    for iteration in range(a.rounds):
        data={};measurements={};start=time.monotonic()
        for name,p in probes.items():
            m=p.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
            f,native=features(64,64,w,h,m['direction'],m['seed'],sites,rotation_aligned=weights.rotation_aligned)
            canonical,_=features(64,64,w,h,m['direction'],m['seed'],canonical_sites(w,h,m['direction']),rotation_aligned=weights.rotation_aligned)
            current=predict(64,64,w,h,m['direction'],m['seed'],sites,weights)
            if name=='ironman':target=native;mask=np.ones((64,64),np.float32)
            else:
                r=renderer(name,sites=sites,weights=weights,rules=cfg,ctx=ctx,quality=2,**extra)
                observed=p.model(r);r.close();reference=p.huawei()
                good=p.valid&np.isfinite(reference)&np.isfinite(observed)
                delta=np.where(reference<.99,reference-observed,np.maximum(.85-observed,0))
                delta=np.where(good,np.clip(delta,-.15,.15),0)
                smooth_delta=gaussian_filter(delta,.8)/np.maximum(gaussian_filter(good.astype('float32'),.8),1e-5)
                yy,xx=np.mgrid[:64,:64];coords=[(yy+.5)/64*h/10-.5,(xx+.5)/64*w/10-.5]
                correction=map_coordinates(smooth_delta,coords,order=1,mode='nearest')
                mask=map_coordinates(good.astype('float32'),coords,order=0,mode='nearest')
                target=np.clip(current+.60*correction,.002,.84)
                measurements[name]=dict(mean_abs_error=float(np.abs(delta)[good].mean()) if good.any() else None,
                                        mean_signed_error=float(delta[good].mean()) if good.any() else None,valid_blocks=int(good.sum()))
                np.savez(OUT/f'{iteration+1}-{name}.npz',reference=reference,observed=observed,valid=good,current=current,target=target)
                print('测量',iteration+1,name,measurements[name],flush=True)
            data[name]=[tensor(v) for v in [f.reshape(-1,33),canonical.reshape(-1,33),native.ravel(),target.ravel(),mask.ravel()]]
        opt=torch.optim.AdamW(model.parameters(),lr=.0007,weight_decay=.003)
        for step in range(a.steps):
            opt.zero_grad();loss=0.
            for name,(f,c,b,t,mask) in data.items():
                ids=torch.randint(len(f),(512,));prediction=b[ids]+.65*(torch.tanh(model(f[ids])[:,0])-torch.tanh(model(c[ids])[:,0]))
                e=prediction-t[ids];loss+=((torch.sqrt(e*e+.003**2)-.003)*mask[ids]).sum()/mask[ids].sum().clamp(min=1)
            loss.backward();opt.step()
            if (step+1)%1000==0:print('共同更新',iteration+1,step+1,round(float(loss.detach()),5),round(time.monotonic()-start,1),flush=True)
        result={k:v for k,v in initial.items() if k not in ['layers','history']}
        result.update(label='closed-loop-shared-release',train=NAMES,holdout=[],minimum_sites=1,round=iteration+1,
                      renderer=renderer.__name__,transfer=a.transfer,transport=a.transport,
                      layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
        path=OUT/f'round-{iteration+1}.json';path.write_text(json.dumps(result,ensure_ascii=False),'utf-8')
        history.append(dict(round=iteration+1,measurements=measurements));(OUT/'measurements.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
        weights=load_weights(path);print('已保存',path,flush=True)
    ctx.release()


if __name__=='__main__':main()
