"""拟合一份沿材料位置积分的静态共同速度场；默认模型只读。"""
from pathlib import Path
import argparse,hashlib,json,math,pickle,shutil,sys,time
import numpy as np
from scipy.spatial import cKDTree
from scipy import sparse
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from carried_velocity import FIELD,SPEED,LO,SIZE,features
from carried_velocity_operator import operators,to_torch
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from material_motion_constraints import trace_path,motion_penalty


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--cache',type=Path,default=HERE/'analysis/match-cache/regional-structure/cache/particles.pkl')
    ap.add_argument('--grain',type=Path,default=HERE/'analysis/match-cache/regional-structure/cache/grain-targets.npz')
    ap.add_argument('--paths',type=Path,default=HERE/'analysis/match-cache/carried-velocity/paths')
    ap.add_argument('--traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/balanced-flow-refinement')
    ap.add_argument('--steps',type=int,default=3600);ap.add_argument('--motion-weight',type=float,default=.5)
    ap.add_argument('--age-slices',type=int,default=1,choices=[1,9,17])
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    import torch
    import torch.nn.functional as F
    from check_particle_proxy import Splat
    from particle_grain_proxy import alpha_moments,variance
    torch.set_num_threads(4);torch.manual_seed(2843)
    s=Splat(n=160);to=s.tensor
    configs=['conditional-release-weights.json','conditional-material-rules.json','conditional-transport-weights.json','surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','particle-scatter-weights.json']
    scope=json.loads((a.paths/'scope.json').read_text('utf-8'))
    assert scope['identity']==hashlib.sha256(b''.join((HERE/f).read_bytes() for f in configs)).hexdigest(),'基准路径与默认配置不同'
    for filename in configs:shutil.copy2(HERE/filename,a.out/filename)
    net=torch.nn.Sequential(torch.nn.Linear(59 if a.age_slices==1 else 61,128),torch.nn.Tanh(),torch.nn.Linear(128,128),torch.nn.Tanh(),torch.nn.Linear(128,2)).cuda()
    torch.nn.init.zeros_(net[-1].weight);torch.nn.init.zeros_(net[-1].bias)
    data=pickle.loads(a.cache.read_bytes());grain=np.load(a.grain)
    assert list(grain['names'])==[d['name'] for d in data]
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx=LO+(xx+.5)/FIELD*SIZE;yy=LO+(yy+.5)/FIELD*SIZE
    info={};rows=[];paths=[]
    for name in OBSERVED_SITES:
        saved=np.load(a.paths/f'{name}.npz');card=saved['card'];span=float(saved['span']);direction=float(saved['direction'])
        sites=[Site(*v) for v in OBSERVED_SITES[name]];angle=math.radians(direction);c,z=math.cos(angle),-math.sin(angle)
        info[name]=dict(features=to(features(*card,direction,sites,a.age_slices)),rotation=to([[c,z],[-z,c]]))
        indices=sorted([i for i,d in enumerate(data) if d['name']==name],key=lambda i:float(data[i]['phase']))
        ops=operators(saved,[data[i]['phase'] for i in indices],age_slices=a.age_slices);tree=cKDTree(saved['source'])
        for i,op in zip(indices,ops):
            d=data[i];distance,parent=tree.query((d['origin']*SIZE+LO)*card)
            assert distance.max()<.001,('材料源编号不能对应',name,distance.max())
            unique,inv=np.unique(parent,return_inverse=True)
            td={k:to(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in d.items()}
            td.update(operator=to_torch(op[unique]),parent=torch.as_tensor(inv,device='cuda',dtype=torch.long),
                actual_grain=to(grain['actual'][i]),target_grain=to(grain['target'][i]))
            rows.append(td)
        tr=np.load(a.traces/f'{name}.npz');raw=trace_path(tr,seed=823,maximum=1024,cohort_samples=512,native_clock=True)
        p={k:to(v) if isinstance(v,np.ndarray) else v for k,v in raw.items()};p['name']=name
        p['operator']=to_torch(sparse.vstack(operators(saved,raw['phase'],raw['material_indices'],age_slices=a.age_slices),format='csr'))
        p['n']=len(raw['material_indices']);p['length']=len(raw['phase']);paths.append(p)
        print('静态速度积分缓存',name,len(indices),'帧',p['n'],'固定材料',flush=True)
    del data

    def field(name):
        q=info[name]
        return (net(q['features']).tanh()*SPEED)@q['rotation']
    def filter_image(im,d):
        return torch.stack([s.gaussian(im.permute(2,0,1)[None],max(.55,k*160/(SIZE*float(d['span']))))[0].permute(1,2,0) for k in [4.,12.,24.]])
    bands=to([1.,.7,.4])[:,None,None,None]
    def photo(im,d):
        mask=d['mask'][None,:,:,None]*bands
        return ((filter_image(im,d)-d['target_filtered']).abs()*mask).sum()/mask.sum().clamp(min=1)/3
    def grain_error(im,d):
        mask=d.get('grain_mask',d['mask'])
        return ((im-d['target_grain']).abs()*mask[...,None]).sum()/mask.sum().clamp(min=1)/3
    with torch.no_grad():
        for d in rows:
            d['target_filtered']=filter_image(d['target'],d);d['prior']=photo(d['actual'],d).clamp(min=.003)
            d['grain_prior']=grain_error(d['actual_grain'],d).clamp(min=.002)
            d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
            mass=s.material_mass(d,torch.zeros_like(d['density']));d['render_anchor']=s.render(d['pos'],mass,d['color'],d['base'])
            m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0])
            d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base'])

    def output(d,v=None,images=False):
        v=field(d['name']) if v is None else v
        delta=torch.sparse.mm(d['operator'],v.reshape(-1,2))[d['parent']]
        shift=delta*d['scale']/SIZE
        carrier=d['carrier']+shift;density=s.cohort(d,carrier)-d['cohort_anchor']
        scatter=s.scatter(d,carrier);mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
        pos=s.fission_position(d,d['pos']+shift,density)
        estimated=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
        m1,m2=alpha_moments(s,d,density,scatter[:,0])
        observed=(d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])).clamp(min=0)
        if images:return estimated,observed
        return photo(estimated,d)/d['prior'],grain_error(observed,d)/d['grain_prior']

    def path_loss(p,v):
        delta=torch.sparse.mm(p['operator'],v.reshape(-1,2)).reshape(p['length'],p['n'],2)
        return motion_penalty(torch,p['base']+delta,p,acceleration_weight=.1)

    opt=torch.optim.Adam(net.parameters(),lr=.0004);history=[];best=float('inf');start=time.monotonic()
    scene_rows={name:[i for i,d in enumerate(rows) if d['name']==name] for name in OBSERVED_SITES}
    for step in range(a.steps):
        for group in opt.param_groups:group['lr']=.0004*(.15+.85*.5*(1+math.cos(math.pi*step/a.steps)))
        # 同一布局的两帧共享一份静态速度，保证训练目标实际约束同一个函数。
        name=list(OBSERVED_SITES)[step%len(OBSERVED_SITES)];ids=scene_rows[name]
        selected=torch.randint(len(ids),(2,)).tolist();v=field(name);opt.zero_grad();loss=0.
        for j in selected:
            d=rows[ids[j]];pe,ge=output(d,v)
            loss+=(d.get('photo_weight',1.)*pe+.6*ge)/len(selected)
        dx=(v[:,:,1:]-v[:,:,:-1])*FIELD/SIZE;dy=(v[:,1:]-v[:,:-1])*FIELD/SIZE
        smooth=.001*(dx.square().mean()+dy.square().mean())
        # 空间梯度上界只防止新增尖锐汇聚；不把当前轨迹当作视觉真值。
        limit=.03*(F.relu(abs(dx)-1.5).square().mean()+F.relu(abs(dy)-1.5).square().mean())
        ml=path_loss(paths[step%len(paths)],v)
        loss+=a.motion_weight*ml+smooth+limit
        loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),2.);opt.step()
        if step==0 or (step+1)%400==0:
            with torch.no_grad():
                ratios={};costs={}
                for name,ids in scene_rows.items():
                    fv=field(name);ratios[name]=np.mean([[float(x) for x in output(rows[i],fv)] for i in ids],0).tolist()
                    costs[name]=float(path_loss(paths[list(OBSERVED_SITES).index(name)],fv))
                score=np.mean([q[0]+.6*q[1] for q in ratios.values()])+a.motion_weight*np.mean(list(costs.values()))
            row=dict(step=step+1,score=float(score),ratios=ratios,path_costs=costs)
            history.append(row);print(step+1,round(time.monotonic()-start,1),round(score,4),{k:np.round(v,3).tolist() for k,v in ratios.items()},flush=True)
            if score<best:
                best=score
                config=dict(method='沿共同基准材料位置及年龄积分的速度场；未验收实验',speed=SPEED,age_slices=a.age_slices,selection=row,
                    layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in net if isinstance(q,torch.nn.Linear)])
                (a.out/'carried-velocity.json').write_text(json.dumps(config,ensure_ascii=False),'utf-8')
            (a.out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    (a.out/'inputs.json').write_text(json.dumps({k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
