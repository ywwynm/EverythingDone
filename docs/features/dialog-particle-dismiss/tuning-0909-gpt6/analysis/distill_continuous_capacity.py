"""将整段输运诊断提炼为共同几何函数，同时约束其它布局和实际材料路径。

离线控制格只提供训练监督。输出仅包含一套共同权重，运行时不读取素材名称、
参考画面或逐序列场。密度保持原样；原有渲染器负责积分及场的复合。
"""
from pathlib import Path
import argparse, json, math, pickle, sys, time
import cv2, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from fit_continuous_transport_capacity import time_basis, FIELD, MAGNITUDE, STEPS
from check_particle_proxy import LO, SIZE
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--controls',type=Path,required=True)
    ap.add_argument('--cache',type=Path,required=True)
    ap.add_argument('--label',default='continuous-grain-shared')
    ap.add_argument('--steps',type=int,default=5000)
    ap.add_argument('--width',type=int,default=192)
    ap.add_argument('--resume',type=Path)
    ap.add_argument('--grain',type=Path,help='用实际颗粒目标直接细化共同函数，而不只逼近离线场')
    ap.add_argument('--all-visible-paths',action='store_true',help='按每片材料的完整可见区间约束运动')
    ap.add_argument('--traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    a=ap.parse_args();out=HERE/'analysis/match-cache/transport-capacity'/a.label
    out.mkdir(parents=True,exist_ok=True)
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(931)
    def tensor(v):return torch.as_tensor(np.asarray(v,np.float32),device='cuda')
    config=json.loads((HERE/'conditional-transport-weights.json').read_text('utf-8'))
    assert not config.get('residual') and not config.get('coupling')
    names=list(OBSERVED_SITES);teacher=np.load(a.controls);teachers=list(teacher['names'])
    controls=tensor(teacher['controls']);data=pickle.loads(a.cache.read_bytes())
    n=FIELD;yy,xx=np.mgrid[:n,:n].astype(np.float32)
    px,py=LO+(xx+.5)/n*SIZE,LO+(yy+.5)/n*SIZE
    grid=tensor(np.stack([(xx+.5)/n*2-1,(yy+.5)/n*2-1],-1))[None]
    f=[];phase=[];rotation=[];scale=[];sid=[];importance=[]
    phases=np.linspace(0,1,41,dtype=np.float32)
    for ni,name in enumerate(names):
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
        card=np.asarray(m['rect'][2:])-m['rect'][:2];span=min(card)
        angle=math.radians(m['direction']);c,s=math.cos(angle),-math.sin(angle)
        sites=[Site(*v) for v in OBSERVED_SITES[name]]
        samples=[d for d in data if d['name']==name]
        weights=[]
        for d in samples:
            ix=np.floor(d['query']*n).astype(int);inside=(ix>=0).all(1)&(ix<n).all(1)
            mass=(d['mass']+.10*d['unit_mass'])*d['warm']
            w=np.bincount(ix[inside,1]*n+ix[inside,0],weights=mass[inside],minlength=n*n).reshape(n,n).astype(np.float32)
            w=cv2.GaussianBlur(w,(0,0),1.);w/=max(float(np.quantile(w,.9)),1e-6)
            weights.append(.08+np.minimum(w,4.))
        for p in phases:
            f.append(transport_features(px,py,float(p),*card,m['direction'],sites,True))
            phase.append(p);rotation.append([[c,s],[-s,c]]);scale.append(span/card);sid.append(ni)
            if weights:
                ts=np.array([d['phase'] for d in samples]);hi=int(np.clip(np.searchsorted(ts,p),1,len(ts)-1));lo=hi-1
                t=float(np.clip((p-ts[lo])/(ts[hi]-ts[lo]),0,1));w=weights[lo]*(1-t)+weights[hi]*t
            else:w=np.ones((n,n),np.float32)
            importance.append(w)
    features,phase,rotation,scale,importance=map(tensor,[f,phase,rotation,scale,importance])
    constants=[(tensor(q['weight']),tensor(q['bias'])) for q in config['layers']]
    net=torch.nn.Sequential(torch.nn.Linear(59,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,3)).cuda()
    with torch.no_grad():net[-1].weight.zero_();net[-1].bias.zero_()
    if a.resume:
        prior=json.loads(a.resume.read_text('utf-8'))['residual']
        with torch.no_grad():
            for layer,values in zip([q for q in net if isinstance(q,torch.nn.Linear)],prior['layers']):
                layer.weight.copy_(tensor(values['weight']));layer.bias.copy_(tensor(values['bias']))

    def integrate(raw,p,sc,magnitude,steps,rot=None):
        v=raw.tanh()[...,:2]
        if rot is not None:v=torch.einsum('bxyc,bcd->bxyd',v,rot)
        v=v*(magnitude*p[:,None,None,None])*sc[:,None,None,:]/2**steps
        for _ in range(steps):
            v=v+F.grid_sample(v.permute(0,3,1,2),grid+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return v/sc[:,None,None,:]

    with torch.no_grad():
        base=[]
        for lo in range(0,len(features),8):
            z=features[lo:lo+8]
            for j,(w,b) in enumerate(constants):
                z=z@w.T+b
                if j<len(constants)-1:z=z.tanh()
            base.append(integrate(z,phase[lo:lo+8],scale[lo:lo+8],config['displacement_scale'],config['flow_steps'],rotation[lo:lo+8]))
        base=torch.cat(base);target=torch.zeros_like(base)
        for name in teachers:
            lo=names.index(name)*41;ix=slice(lo,lo+41);control=controls[teachers.index(name)]
            raw=torch.einsum('tk,kxyc->txyc',tensor(time_basis(phases)),control)
            raw=F.interpolate(raw.permute(0,3,1,2),size=(n,n),mode='bilinear',align_corners=False).permute(0,2,3,1)
            extra=integrate(raw,phase[ix],scale[ix],MAGNITUDE,STEPS)
            target[ix]=F.grid_sample(extra.permute(0,3,1,2),grid+base[ix]*scale[ix,None,None,:]*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)

    def composed(features,p,sc,rot,base):
        extra=integrate(net(features),p,sc,MAGNITUDE,STEPS,rot)
        return F.grid_sample(extra.permute(0,3,1,2),grid+base*sc[:,None,None,:]*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)

    def prediction(ids):return composed(features[ids],phase[ids],scale[ids],rotation[ids],base[ids])

    rows=[]
    if a.grain:
        from check_particle_proxy import Splat
        from particle_grain_proxy import alpha_moments,variance
        s=Splat(n=160);grain=np.load(a.grain)
        assert list(grain['names'])==[d['name'] for d in data]
        assert np.max(abs(grain['phases']-[d['progress'] for d in data]))<1e-6

        def filters(im,d):
            return torch.stack([s.gaussian(im.permute(2,0,1)[None],max(.55,q*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]])

        def photo(im,d):
            delta=filters(im,d)-d['target_filtered'];w=d['mask'][None,:,:,None]*tensor([1.,.7,.4])[:,None,None,None]
            return ((delta.square()+.003**2).sqrt()*w).sum()/w.sum().clamp(min=1)/3

        with torch.no_grad():
            for i,source in enumerate(data):
                d={k:tensor(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in source.items()}
                z=d['features']
                for j,(w,b) in enumerate(constants):
                    z=z@w.T+b
                    if j<len(constants)-1:z=z.tanh()
                d['baseline_field']=integrate(z[None],d['phase'].reshape(1),d['scale'][None],config['displacement_scale'],config['flow_steps'],d['rotation'][None])[0]
                d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
                mass=s.material_mass(d,torch.zeros_like(d['density']))
                d['render_anchor']=s.render(d['pos'],mass,d['color'],d['base'])
                d['target_filtered']=filters(d['target'],d);d['prior']=photo(d['actual'],d)
                d['actual_grain']=tensor(grain['actual'][i]);d['target_grain']=tensor(grain['target'][i])
                m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0])
                d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base'])
                d['grain_prior']=((abs(d['actual_grain']-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3
                rows.append(d)

        def photo_loss(i):
            d=rows[i]
            delta=composed(d['features'][None],d['phase'].reshape(1),d['scale'][None],d['rotation'][None],d['baseline_field'][None])[0]
            move=F.grid_sample(delta.permute(2,0,1)[None],d['query'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T*d['warm'][:,None]
            dp=move*d['scale']/SIZE;pos=d['pos']+dp
            density=s.cohort(d,d['carrier']+dp)-d['cohort_anchor'];scatter=s.scatter(d,d['carrier']+dp)
            mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
            pos=s.fission_position(d,pos,density)
            estimate=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
            m1,m2=alpha_moments(s,d,density,scatter[:,0])
            observed=(d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])).clamp(min=0)
            grain_error=((abs(observed-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3/d['grain_prior'].clamp(min=.004)
            ratio=photo(estimate,d)/d['prior'].clamp(min=.012)
            return ratio+.6*grain_error+2*F.relu(ratio-1)+2*F.relu(grain_error-1)

    def jacobian(move,sc):
        v=move*sc[:,None,None,:]
        dx=(v[:,:-1,1:]-v[:,:-1,:-1])*n/SIZE;dy=(v[:,1:,:-1]-v[:,:-1,:-1])*n/SIZE
        return (1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]

    rng=np.random.default_rng(931);paths=[]
    for ni,name in enumerate(names):
        q=np.load(a.traces/f'{name}.npz');tr=q['trace'];ts=tr[:,0,4]-tr[0,0,4]
        card=q['rect'][2:]-q['rect'][:2];span=float(q['span'])
        if a.all_visible_paths:
            from material_motion_constraints import trace_path,motion_penalty
            source=trace_path(q,seed=1933)
            p={key:tensor(value) if isinstance(value,np.ndarray) else value for key,value in source.items()}
            p.update(ids=(ni*41+source['indices']).tolist(),pos=p['base'])
            paths.append(p);continue
        for start,stop in [(4,18),(10,28),(18,34)]:
            times=phases[start:stop];hi=np.clip(np.searchsorted(ts,times),1,len(ts)-1);lo=hi-1
            frac=((times-ts[lo])/(ts[hi]-ts[lo]))[:,None,None];tq=tr[lo]*(1-frac)+tr[hi]*frac
            visible=((tq[:,:,4]>.04)&(tq[:,:,4]<tq[:,:,5]-.055)&(tq[:,:,6]<.6)).all(0)&q['mask']
            take=np.flatnonzero(visible)
            if len(take)<16:continue
            take=rng.choice(take,min(len(take),256),replace=False);tq=tq[:,take]
            cross=np.array([-q['wind'][1],q['wind'][0]])
            pos=tq[:,:,2:4]/span;lat=pos@cross
            paths.append(dict(ids=list(range(ni*41+start,ni*41+stop)),query=tensor((tq[:,:,:2]/card-LO)/SIZE),
                pos=tensor(pos),cross=tensor(cross),wind=tensor(q['wind']),
                excess=tensor(abs(np.diff(lat,axis=0)).sum(0)-abs(lat[-1]-lat[0])),
                acceleration=tensor(np.linalg.norm(np.diff(pos,n=2,axis=0)/.025**2,axis=-1))))

    def path_loss(p,values=None):
        fields=prediction(p['ids']) if values is None else values[p['ids']]
        move=F.grid_sample(fields.permute(0,3,1,2),p['query'][:,None]*2-1,padding_mode='border',align_corners=False)[:,:,0].permute(0,2,1)
        pos=p['pos']+move;lat=pos@p['cross']
        if a.all_visible_paths:return motion_penalty(torch,pos,p)
        excess=abs(lat[1:]-lat[:-1]).sum(0)-abs(lat[-1]-lat[0])
        added=F.relu(excess-p['excess']*1.025-.0003)/.012
        velocity=(pos[1:]-pos[:-1])/.025
        accel=(pos[2:]-2*pos[1:-1]+pos[:-2])/.025**2
        acceleration=F.relu(torch.linalg.vector_norm(accel,dim=-1)-p['acceleration']*1.05-.10)
        return added.square().mean()+.5*added.topk(min(32,len(added))).values.square().mean()+.05*acceleration.square().mean()+.2*F.relu(-(velocity@p['wind'])-.04).square().mean()

    def error(delta,ids):
        w=importance[ids,...,None]
        return ((delta-target[ids]).square()*w).sum()/w.sum().clamp(min=1)/2/.035**2

    teacher_ids=torch.tensor([i for i,s in enumerate(sid) if names[s] in teachers],device='cuda')
    guard_ids=torch.tensor([i for i,s in enumerate(sid) if names[s] not in teachers],device='cuda')
    with torch.no_grad():
        path_audit={}
        for name in names:
            selected=[p for p in paths if p['ids'][0]//41==names.index(name)]
            path_audit[name]=dict(windows=len(selected),baseline=float(torch.stack([path_loss(p,torch.zeros_like(target)) for p in selected]).mean()),
                capacity=float(torch.stack([path_loss(p,target) for p in selected]).mean()))
        print('实际材料路径的容量审计',path_audit,flush=True)
        (out/'capacity-path-audit.json').write_text(json.dumps(path_audit,ensure_ascii=False,indent=2),'utf-8')
    rate=.00012 if a.grain else .0006
    optimizer=torch.optim.AdamW(net.parameters(),lr=rate,weight_decay=.0001)
    start=time.monotonic();history=[];best=float('inf')
    for step in range(a.steps):
        for group in optimizer.param_groups:group['lr']=rate*(.08+.92*.5*(1+math.cos(math.pi*step/a.steps)))
        ids=torch.cat([teacher_ids[torch.randint(len(teacher_ids),(6,),device='cuda')],guard_ids[torch.randint(len(guard_ids),(3,),device='cuda')]])
        optimizer.zero_grad();delta=prediction(ids);jac=jacobian(base[ids]+delta,scale[ids])
        penalty=F.relu(.13-jac).square().flatten(1)
        temporal=path_loss(paths[int(torch.randint(len(paths),(1,)))])
        if a.grain:
            observed=torch.stack([photo_loss(int(i)) for i in torch.randint(len(rows),(2,))]).mean()
            # 其余参考仅允许很小的变化；参考 2、3 不使用未知背景作像素真值。
            guard=(delta[-3:]/.006).square().mean()
            objective=observed+.015*error(delta,ids)+.3*guard
        else:objective=error(delta,ids)
        loss=objective+20*penalty.mean()+10*penalty.topk(24,dim=1).values.mean()+.6*temporal
        loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),2.);optimizer.step()
        if (step+1)%500==0:
            with torch.no_grad():
                scores=[];minima=[];guard_displacement=0.
                for lo in range(0,len(features),8):
                    ids=list(range(lo,min(lo+8,len(features))));delta=prediction(ids)
                    scores.append(float(error(delta,ids)));minima.append(float(jacobian(base[ids]+delta,scale[ids]).min()))
                temporal=float(torch.stack([path_loss(p) for p in paths]).mean())
                for lo in range(0,len(guard_ids),8):
                    delta=prediction(guard_ids[lo:lo+8]);guard_displacement=max(guard_displacement,float(torch.linalg.vector_norm(delta,dim=-1).max()))
                score=float(np.mean(scores))+.6*temporal
                photo_score=float(torch.stack([photo_loss(i) for i in range(len(rows))]).mean()) if a.grain else None
                if a.grain:score=photo_score+.015*float(np.mean(scores))+.6*temporal
            row=dict(step=step+1,score=score,minimum_jacobian=min(minima),path_cost=temporal,guard_maximum_displacement=guard_displacement)
            if a.grain:row['photo_score']=photo_score
            history.append(row);print('连续诊断提炼为共同规则',step+1,round(time.monotonic()-start,1),row,flush=True)
            if score<best and min(minima)>.025:
                best=score;result=dict(config)
                result['residual']=dict(displacement_scale=MAGNITUDE,density_scale=0.,flow_steps=STEPS,multiscale_features=True,
                    layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in net if isinstance(q,torch.nn.Linear)])
                result.update(method='整段连续诊断的共同几何函数；固定密度，十二段材料路径约束，无逐素材运行资源',selection=row)
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    (out/'inputs.json').write_text(json.dumps(dict(controls=str(a.controls),cache=str(a.cache),teacher_layouts=teachers,guard_layouts=[q for q in names if q not in teachers],all_visible_paths=a.all_visible_paths,scope='所有布局共用一份几何函数；不以训练损失代替实际 GPU 验收'),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
