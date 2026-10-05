"""将可观测的局部材质残差拟合为一份共同几何函数；不保存逐素材控制场。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--response',type=Path,required=True);ap.add_argument('--controls',type=Path,required=True)
    ap.add_argument('--initial',type=Path,default=HERE/'particle-scatter-weights.json');ap.add_argument('--label',default='capacity-common');ap.add_argument('--steps',type=int,default=6500)
    ap.add_argument('--single-pulse',action='store_true',help='允许一次持续的材料变化，限制重复明暗往返，而非统一压低变化速度')
    ap.add_argument('--strict-repeat',action='store_true',help='加密完整可见路径并限制局部高分位的重复变化')
    ap.add_argument('--trace-root',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    a=ap.parse_args();out=HERE/'analysis/match-cache/scatter-material'/a.label;out.mkdir(parents=True,exist_ok=True)
    from fit_particle_optics import NAMES
    from shared_transport import transport_features
    from dissolve_v2 import Site
    from v2_inputs import OBSERVED_SITES
    import torch
    torch.set_num_threads(4);torch.manual_seed(929)
    t=lambda v:torch.as_tensor(np.ascontiguousarray(v,np.float32),device='cuda')
    z=np.load(a.response);initial=json.loads(a.initial.read_text('utf-8'))
    f,actual,target,slope,curve,prior,mask=[t(z[k]) for k in ['features','actual','target','slope','curve','prior','mask']]
    optimal=t(np.load(a.controls)['delta']);scales=t(initial['scales'])
    # 并联增加容量，保留原函数的精确初值，运行仍只有原来的三个材质输出。
    width=256;model=torch.nn.Sequential(torch.nn.Linear(59,width),torch.nn.Tanh(),torch.nn.Linear(width,width),torch.nn.Tanh(),torch.nn.Linear(width,3)).cuda()
    layers=[v for v in model if isinstance(v,torch.nn.Linear)]
    with torch.no_grad():
        for i,(l,s) in enumerate(zip(layers,initial['layers'])):
            w=t(s['weight']);b=t(s['bias'])
            if i<2:
                l.weight[:len(w)].zero_();l.weight[:len(w),:w.shape[1]]=w;l.bias[:len(b)]=b
            else:
                l.weight.zero_();l.weight[:,:w.shape[1]]=w;l.bias.copy_(b)
        # 隐藏扩展支路的随机特征不反向污染原来的第二层。
        layers[1].weight[128:,:128].zero_()
    channels=t([.8]*3+[1.]*3+[.7]*3+[1.2]*3+[1.2]*3+[.8]*3+[.12]*8)
    def observed(estimate,ids):
        return ((((estimate-target[ids]).square()+.004**2).sqrt()*channels)*mask[ids,...,None]).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/channels.sum()
    with torch.no_grad():baseline=observed(actual,list(range(len(f))))
    paths=[];life_paths=[];rng=np.random.default_rng(929)
    for path in sorted(a.trace_root.glob('*.npz')):
        s=np.load(path);tr=s['trace'];card=s['rect'][2:]-s['rect'][:2];clock=tr[:,0,4]-tr[0,0,4]
        meta=json.loads((HERE/'assets'/f'{path.stem}'/'scene.json').read_text('utf-8'));sites=[Site(*q) for q in OBSERVED_SITES[path.stem]]
        if a.single_pulse:
            live=(tr[:,:,4]>.04)&(tr[:,:,4]<tr[:,:,5]-.025)&(tr[:,:,6]<.6)&s['mask'][None]
            first=live.argmax(0);last=len(live)-1-live[::-1].argmax(0)
            good=np.flatnonzero((last-first>12)&live.any(0))
            ids=rng.choice(good,min(768,len(good)),replace=False)
            ts=clock[first[ids],None]+(clock[last[ids]]-clock[first[ids]])[:,None]*np.linspace(0,1,25 if a.strict_repeat else 9)
            hi=np.clip(np.searchsorted(clock,ts),1,len(clock)-1);lo=hi-1
            xy=(tr[lo,ids[:,None],2:4]+(tr[hi,ids[:,None],2:4]-tr[lo,ids[:,None],2:4])*((ts-clock[lo])/(clock[hi]-clock[lo]))[...,None])/card
            life_paths.append(transport_features(xy[...,0],xy[...,1],ts,*card,meta['direction'],sites,True))
        for center in np.arange(.16,.78,.07):
            ts=np.array([center-.04,center,center+.04]);hi=np.clip(np.searchsorted(clock,ts),1,len(clock)-1);lo=hi-1
            q=tr[lo]+(tr[hi]-tr[lo])*((ts-clock[lo])/(clock[hi]-clock[lo]))[:,None,None]
            live=np.flatnonzero(((q[:,:,4]>.04)&(q[:,:,4]<q[:,:,5]-.03)&(q[:,:,6]<.6)).all(0)&s['mask'])
            if len(live)<12:continue
            ids=rng.choice(live,96,replace=len(live)<96);xy=q[:,ids,2:4]/card
            paths.append(np.stack([transport_features(xy[k,:,0],xy[k,:,1],ts[k],*card,meta['direction'],sites,True) for k in range(3)]))
    paths=t(np.stack(paths))
    if a.single_pulse:
        life_paths=t(np.concatenate(life_paths))
        with torch.no_grad():
            life_anchor=model(life_paths).tanh()
            repeat_anchor=torch.relu(abs(life_anchor[:,1:]-life_anchor[:,:-1]).sum(1)-2*(life_anchor.max(1).values-life_anchor.min(1).values))
    with torch.no_grad():path_anchor=model(paths).tanh()*scales
    opt=torch.optim.AdamW(model.parameters(),lr=.0007,weight_decay=.0001);best=float('inf');history=[];started=time.monotonic()
    for step in range(a.steps):
        ids=torch.randint(len(f),(8,),device='cuda');v=model(f[ids]).tanh()*scales;delta=v-prior[ids]
        estimate=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        e=observed(estimate,ids)
        # 局部解只提供优化方向，真实有限差分目标仍决定共同函数。
        confidence=(slope[ids,:,:,:9].square().mean(-2)*scales.square()).detach()
        guide=(((delta-optimal[ids])/scales).square()*confidence*mask[ids,...,None]).sum()/confidence.sum().clamp(min=1)
        pids=torch.randint(len(paths),(8,),device='cuda');pd=model(paths[pids]).tanh()*scales-path_anchor[pids]
        velocity=(pd[:,2]-pd[:,0])/.08
        curvature=(pd[:,2]-2*pd[:,1]+pd[:,0])/.04**2
        if a.single_pulse:
            li=torch.randint(len(life_paths),(256,),device='cuda');value=model(life_paths[li]).tanh()
            travel=abs(value[:,1:]-value[:,:-1]).sum(1);span=value.max(1).values-value.min(1).values
            if a.strict_repeat:
                repeated=torch.relu((travel-2*span-repeat_anchor[li]*.5-.012)/.10).square()
                time_guard=.75*repeated.mean()+.10*repeated.flatten().topk(96).values.mean()+.0000002*(curvature/scales).square().mean()
            else:time_guard=.18*torch.relu(travel-2*span-.04).square().mean()+.0000002*(curvature/scales).square().mean()
        else:time_guard=.0002*(curvature/scales).square().mean()+.004*(velocity/scales).square().mean()
        smooth=(delta[:,1:]-delta[:,:-1]).square().mean()+(delta[:,:,1:]-delta[:,:,:-1]).square().mean()
        loss=(e/baseline[ids].clamp(min=.008)).mean()+.16*guide+.05*smooth+time_guard+.05*torch.relu(abs(delta/scales)-.6).square().mean()
        for g in opt.param_groups:g['lr']=.0007*(.12+.88*.5*(1+np.cos(np.pi*step/a.steps)))
        opt.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),3.);opt.step()
        if (step+1)%500==0:
            errs=[]
            with torch.no_grad():
                for i in range(0,len(f),8):
                    js=list(range(i,i+8));de=model(f[js]).tanh()*scales-prior[js]
                    errs.append(observed(actual[js]+(slope[js]*de[...,None,:]+curve[js]*de[...,None,:].square()).sum(-1),js))
                er=torch.cat(errs)
            ratios={name:float(er[i*8:(i+1)*8].mean()/baseline[i*8:(i+1)*8].mean()) for i,name in enumerate(NAMES)}
            score=float(np.mean(list(ratios.values()))+.4*max(ratios.values()));row=dict(step=step+1,score=score,ratios=ratios);history.append(row)
            print(step+1,round(time.monotonic()-started,1),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scales=initial['scales'],train=NAMES,selection=row,single_pulse=a.single_pulse,strict_repeat=a.strict_repeat,method='共同几何函数、实际材料路径约束与真实渲染响应',layers=[dict(weight=l.weight.detach().cpu().tolist(),bias=l.bias.detach().cpu().tolist()) for l in layers])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
