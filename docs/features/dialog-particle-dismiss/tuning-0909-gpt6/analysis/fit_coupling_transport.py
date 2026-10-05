"""直接用真实材料散布校准共同可逆耦合；输出仍是既有输运配置。

所有序列共用耦合层，场景名字仅用于读取离线目标及等权统计。
"""
from pathlib import Path
import argparse, json, math, pickle, sys, time
import numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from fit_particle_transport import prepare,N,FIELD
from check_particle_proxy import Splat,LO,SIZE
from shared_transport import load_transport,correction
from coupling_transport import geometry,compose
from fit_particle_optics import NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def tensor_features(uv,p,extent,origins,valid):
    import torch
    u,v=uv[...,0],uv[...,1]
    one=torch.ones_like(u)
    tt=p[:,None,None]*one
    values=[u,v,u*u,v*v,u*v,tt,tt*tt,one*extent[:,None,None,0],one*extent[:,None,None,1],one*valid.sum(-1)[:,None,None]/4]
    a=u[...,None]-origins[:,None,None,:,0]
    b=v[...,None]-origins[:,None,None,:,1]
    st=origins[:,None,None,:,2]+torch.zeros_like(a)
    distance=(a*a+b*b+.0001).sqrt()
    for rate in [2.,6.,16.]:
        cost=distance+st+(1-valid[:,None,None,:])*1e4
        weight=torch.softmax(-rate*cost,dim=-1)
        for f in [a,b,distance,st,a*a,b*b,a*b]:values.append((weight*f).sum(-1))
    coordinates=[u,v,tt,values[24],values[25],values[26],tt-values[27]]
    for frequency in [2*math.pi,4*math.pi]:
        for q in coordinates:values.extend([torch.sin(frequency*q),torch.cos(frequency*q)])
    return torch.stack(values,-1)


def make_model(width=64,count=6):
    import torch
    def mlp(output):
        return torch.nn.Sequential(torch.nn.Linear(59,width),torch.nn.Tanh(),torch.nn.Linear(width,width),torch.nn.Tanh(),torch.nn.Linear(width,output))
    class Coupling(torch.nn.Module):
        def __init__(self):
            super().__init__();self.parts=torch.nn.ModuleList([mlp(2) for _ in range(count)]);self.density=mlp(1)
            self.log_scale=.22;self.shift_scale=.12;self.density_scale=.8
            with torch.no_grad():
                for net in [*self.parts,self.density]:net[-1].weight.zero_();net[-1].bias.zero_()
        def forward(self,start,p,extent,origins,valid):
            uv=start;logdet=torch.zeros_like(start[...,0])
            for i,net in enumerate(self.parts):
                axis=i%2;fixed=uv[...,1-axis];zero=torch.zeros_like(fixed)
                f=torch.stack([zero,fixed] if axis==0 else [fixed,zero],-1)
                raw=net(tensor_features(f,p,extent,origins,valid)).tanh()
                scale=raw[...,0]*self.log_scale*p[:,None,None]
                shift=raw[...,1]*self.shift_scale*p[:,None,None]
                changed=uv[...,axis]*scale.exp()+shift
                uv=torch.stack([changed,fixed] if axis==0 else [fixed,changed],-1)
                logdet=logdet+scale
            density=self.density(tensor_features(start,p,extent,origins,valid)).tanh()*self.density_scale
            return uv-start,density,logdet
        def specification(self):
            def save(net):
                return [dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in net if isinstance(q,torch.nn.Linear)]
            return dict(log_scale=self.log_scale,shift_scale=self.shift_scale,density_scale=self.density_scale,
                        couplings=[save(q) for q in self.parts],density=save(self.density))
        def restore(self,spec):
            for key in ['log_scale','shift_scale','density_scale']:setattr(self,key,spec[key])
            with torch.no_grad():
                for net,layers in zip([*self.parts,self.density],[*spec['couplings'],spec['density']]):
                    for q,v in zip([q for q in net if isinstance(q,torch.nn.Linear)],layers):
                        q.weight.copy_(q.weight.new_tensor(v['weight']));q.bias.copy_(q.bias.new_tensor(v['bias']))
    return Coupling()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=8000);ap.add_argument('--label',default='continuous')
    ap.add_argument('--initial',type=Path,default=HERE/'conditional-transport-weights.json');ap.add_argument('--cache')
    ap.add_argument('--resume');ap.add_argument('--width',type=int,default=64);ap.add_argument('--layers',type=int,default=6)
    ap.add_argument('--learning-rate',type=float,default=.0004);ap.add_argument('--batch',type=int,default=3)
    ap.add_argument('--cloud',type=float,default=0.,help='同口径云团占用的联合约束权重')
    ap.add_argument('--coarse-to-fine',action='store_true',help='先约束较大尺度云团，再收紧到局部结构')
    ap.add_argument('--joint-scatter',action='store_true',help='共同调整已有细粒材质，避免旧反射场限制材料移动')
    a=ap.parse_args();out=HERE/'analysis/match-cache/coupling-transport'/a.label;out.mkdir(parents=True,exist_ok=True)
    data=pickle.loads(Path(a.cache).read_bytes()) if a.cache else prepare(a.initial,fine=True)
    assert all('scatter' in d for d in data),'必须使用完整细粒展开点'
    config=json.loads(a.initial.read_text('utf-8'));assert 'coupling' not in config
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(929);torch.backends.cudnn.benchmark=True
    s=Splat(n=N);t=s.tensor;model=make_model(a.width,a.layers).cuda()
    if a.resume:model.restore(json.loads(Path(a.resume).read_text('utf-8'))['coupling'])
    rows=[{k:t(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in d.items()} for d in data]
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx=LO+(xx+.5)/FIELD*SIZE;yy=LO+(yy+.5)/FIELD*SIZE
    maxsites=max(len(v) for v in OBSERVED_SITES.values());base=load_transport(a.initial)
    scene_geometry={}
    for name,points in OBSERVED_SITES.items():
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x0,y0,x1,y1=meta['rect'];w,h=x1-x0,y1-y0
        sites=[Site(*p) for p in points];rot,ori,extent=geometry(w,h,meta['direction'],sites)
        padded=np.zeros((maxsites,3),np.float32);padded[:len(ori)]=ori;valid=np.zeros(maxsites,np.float32);valid[:len(ori)]=1
        scene_geometry[name]=dict(w=w,h=h,sites=sites,direction=meta['direction'],rotation=rot,origins=padded,valid=valid,extent=extent)
    inputs=[dict(name=d['name'],phase=float(d['phase'])) for d in data]
    inputs.extend(dict(name=name,phase=float(p)) for name in OBSERVED_SITES for p in np.linspace(0,1,41))
    fields=[];starts=[];rotations=[];origins=[];valids=[];extents=[];scales=[]
    for d in inputs:
        g=scene_geometry[d['name']];w,h=g['w'],g['h'];span=min(w,h)
        field=correction(xx,yy,d['phase'],w,h,g['direction'],g['sites'],base)
        start=(np.stack([(xx-.5)*w/span,(yy-.5)*h/span],-1)+field[...,:2])@g['rotation'].T
        fields.append(field);starts.append(start);rotations.append(g['rotation']);origins.append(g['origins']);valids.append(g['valid']);extents.append(g['extent']);scales.append([span/w,span/h])
    anchor=t(np.asarray(fields));start=t(np.asarray(starts));rot=t(np.asarray(rotations));origin=t(np.asarray(origins));valid=t(np.asarray(valids));extent=t(np.asarray(extents));scale=t(np.asarray(scales));phase=t([d['phase'] for d in inputs])
    scatter_model=None
    if a.joint_scatter:
        saved_scatter=json.loads((HERE/'particle-scatter-weights.json').read_text('utf-8'))
        scatter_model=torch.nn.Sequential(torch.nn.Linear(59,128),torch.nn.Tanh(),torch.nn.Linear(128,128),torch.nn.Tanh(),torch.nn.Linear(128,3)).cuda()
        with torch.no_grad():
            for layer,v in zip([q for q in scatter_model if isinstance(q,torch.nn.Linear)],saved_scatter['layers']):
                layer.weight.copy_(t(v['weight']));layer.bias.copy_(t(v['bias']))
        scatter_scales=t(saved_scatter['scales'])
        sy,sx=np.mgrid[:64,:64].astype(np.float32);sx=LO+(sx+.5)/64*SIZE;sy=LO+(sy+.5)/64*SIZE
        from shared_transport import transport_features
        sf=[]
        for d in data:
            g=scene_geometry[d['name']]
            sf.append(transport_features(sx,sy,d['phase'],g['w'],g['h'],g['direction'],g['sites'],True))
        scatter_features=t(np.asarray(sf))
        with torch.no_grad():scatter_prior=scatter_model(scatter_features).tanh()
    def field(ids,p=None):
        # 时间微扰只作局部连续性约束；正式输出使用准确的既有场与进度。
        move,density,logdet=model(start[ids],phase[ids] if p is None else p,extent[ids],origin[ids],valid[ids])
        delta=torch.einsum('bxyc,bcd->bxyd',move,rot[ids])
        return anchor[ids]+torch.cat([delta,density],-1),logdet
    def lookup(f,d):return F.grid_sample(f.permute(2,0,1)[None],d['query'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
    current_scale=1
    filter_scales=[1,2,4] if a.coarse_to_fine else [1]
    def filters(image,d,factor=1):
        z=image.permute(2,0,1)[None]
        return torch.stack([s.gaussian(z,max(.55,factor*q*N/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]])
    bands=t([1.,.7,.4])[:,None,None,None]
    def photo(image,d):
        difference=filters(image,d,current_scale)-d['target_filtered'][current_scale];weight=d['mask'][None,:,:,None]*bands
        return (torch.sqrt(difference.square()+.003**2)*weight).sum()/weight.sum().clamp(min=1)/3
    def cloud_filters(cloud,d):
        z=cloud[None,None]
        return torch.stack([s.gaussian(z,max(.55,q*N/(SIZE*float(d['span']))))[0,0] for q in [8.,20.]])
    def objective(image,opacity,d):
        value=photo(image,d)
        if a.cloud:
            estimated=d['actual_cloud']+opacity[...,0]-d['opacity_anchor'][...,0]
            difference=cloud_filters(estimated,d)-d['cloud_target_filtered']
            value=value+a.cloud*(torch.sqrt(difference.square()+.002**2)*d['mask'][None]).sum()/d['mask'].sum().clamp(min=1)/2
        return value
    with torch.no_grad():
        for i,d in enumerate(rows):
            d['anchor']=lookup(anchor[i],d);d['cohort_anchor']=s.cohort(d,d['carrier'] if 'unit_alpha' in d else d['pos']);d['scatter_anchor']=s.scatter(d,d['carrier'])
            d['mass']=s.material_mass(d,torch.zeros_like(d['density']));d['render_anchor'],d['opacity_anchor']=s.render(d['pos'],d['mass'],d['color'],d['base'],return_opacity=True)
            d['target_filtered']={factor:filters(d['target'],d,factor) for factor in filter_scales}
            if a.cloud:
                assert 'target_cloud' in d
                d['cloud_target_filtered']=cloud_filters(d['target_cloud'],d)
        priors={}
        for current_scale in filter_scales:
            priors[current_scale]=torch.stack([objective(d['actual'],d['opacity_anchor'],d) for d in rows])
        current_scale=1
        prior=priors[1]
    ids_scene={name:[i for i,d in enumerate(data) if d['name']==name] for name in NAMES}
    before={name:float(prior[ids_scene[name]].mean()) for name in NAMES}
    def jacobian(f,ids):
        move=f[...,:2]*scale[ids,None,None,:]
        dx=(move[:,:-1,1:]-move[:,:-1,:-1])*FIELD/SIZE;dy=(move[:,1:,:-1]-move[:,:-1,:-1])*FIELD/SIZE
        return (1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
    def loss(ids,regular=True):
        f,logdet=field(ids);errors=[]
        scatter_values=scatter_model(scatter_features[ids]).tanh() if scatter_model is not None else None
        for j,i in enumerate(ids):
            d=rows[i];delta=(lookup(f[j],d)-d['anchor'])*d['warm'][:,None]
            pos=d['pos']+delta[:,:2]*d['scale']/SIZE
            carrier=d['carrier']+pos-d['pos']
            density=delta[:,2]+s.cohort(d,carrier if 'unit_alpha' in d else pos)-d['cohort_anchor']
            if scatter_values is None:new=s.scatter(d,d['carrier']+pos-d['pos'])
            else:
                changed=dict(d,scatter=scatter_values[j]*scatter_scales)
                new=s.scatter(changed,d['carrier']+pos-d['pos'])
            mass=s.material_mass(d,density,new[:,0]) if 'unit_alpha' in d else s.material_mass(d,density)*(new[:,0]-d['scatter_anchor'][:,0]).exp()
            color=s.shade(d['color'],d['scatter_anchor'],new)
            pos=s.fission_position(d,pos,density)
            image,opacity=s.render(pos,mass,color,d['base'],return_opacity=True)
            image=d['actual']+image-d['render_anchor']
            errors.append(objective(image,opacity,d))
        errors=torch.stack(errors)
        if not regular:return errors
        movement=f[...,:2]-anchor[ids,...,:2]
        dx=torch.diff(movement,dim=2)*FIELD/SIZE;dy=torch.diff(movement,dim=1)*FIELD/SIZE
        ids2=torch.randint(len(rows),len(inputs),(3,),device='cuda')
        g,ld=field(ids2);det=jacobian(g,ids2)
        # 连续耦合不翻转，但粗网格插值仍需要检查最差局部。
        penalty=F.relu(.03-det).square().flatten(1)
        limit=20*penalty.mean()+10*penalty.topk(16,dim=1).values.mean()
        smooth=.008*(dx.square().mean()+dy.square().mean())
        trust=1.5*F.relu(abs(movement)-.18).square().mean()
        density=.004*(f[...,2]-anchor[ids,...,2]).square().mean()
        material=0.
        if scatter_values is not None:
            material=.02*(scatter_values-scatter_prior[ids]).square().mean()+.7*(torch.diff(scatter_values,dim=1).square().mean()+torch.diff(scatter_values,dim=2).square().mean())
        return (errors/prior[ids].clamp(min=.015)).mean()+2*F.relu(errors-prior[ids]).mean()+smooth+trust+density+limit+.005*logdet.square().mean()+material
    parameters=list(model.parameters())+(list(scatter_model.parameters()) if scatter_model is not None else [])
    opt=torch.optim.AdamW(parameters,lr=a.learning_rate,weight_decay=.0001);best=1.3;history=[];started=time.monotonic()
    print('共同可逆耦合起点',before,flush=True)
    for step in range(a.steps):
        current_scale=4 if a.coarse_to_fine and step<a.steps*.25 else 2 if a.coarse_to_fine and step<a.steps*.55 else 1
        prior=priors[current_scale]
        for group in opt.param_groups:group['lr']=a.learning_rate*(.08+.92*.5*(1+math.cos(math.pi*step/a.steps)))
        choices=torch.randint(len(NAMES),(a.batch,)).tolist();ids=[ids_scene[NAMES[k]][torch.randint(len(ids_scene[NAMES[k]]),(1,)).item()] for k in choices]
        opt.zero_grad();loss(ids).backward();torch.nn.utils.clip_grad_norm_(parameters,3.);opt.step()
        if (step+1)%250==0:
            current_scale=1;prior=priors[1]
            with torch.no_grad():
                errors=torch.cat([loss(list(range(i,min(i+4,len(rows)))),False) for i in range(0,len(rows),4)])
                ratios={name:float(errors[ids_scene[name]].mean())/before[name] for name in NAMES}
                minimum=1.
                for i in range(len(rows),len(inputs),12):
                    ids=list(range(i,min(i+12,len(inputs))));f,_=field(ids);minimum=min(minimum,float(jacobian(f,ids).min()))
            score=float(np.mean(list(ratios.values()))+.3*max(ratios.values()))
            row=dict(step=step+1,score=score,ratios=ratios,minimum_jacobian=minimum);history.append(row)
            print('共同可逆耦合',step+1,round(time.monotonic()-started,1),round(score,4),'最小雅可比',round(minimum,5),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best and minimum>.015:
                best=score;result=dict(config,coupling=model.specification(),selection=row,method='全参考共用的可逆耦合；真实 GPU 另相位复核')
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
                if scatter_model is not None:
                    material_result=dict(saved_scatter,layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in scatter_model if isinstance(q,torch.nn.Linear)],selection=row,method='与共同耦合输运联合优化，实际渲染另行复核')
                    (out/'candidate-scatter.json').write_text(json.dumps(material_result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    # 用独立 NumPy 路径检查训练／桌面单位及实现一致性。
    checks={}
    with torch.no_grad():
        for name in OBSERVED_SITES:
            i=next(i for i,d in enumerate(inputs) if d['name']==name and abs(d['phase']-.5)<1e-5)
            g=scene_geometry[name];actual=compose(xx,yy,.5,g['w'],g['h'],g['direction'],g['sites'],fields[i],model.specification())
            predicted=field([i])[0][0].cpu().numpy();error=float(abs(actual-predicted).max());checks[name]=error
            assert error<4e-5,(name,error)
    (out/'implementation-check.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
