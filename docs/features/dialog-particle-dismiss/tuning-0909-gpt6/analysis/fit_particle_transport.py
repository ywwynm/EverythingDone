"""携带真实微片颜色及面积的非线性散布拟合，只更新既有共同输运权重。"""
from pathlib import Path
import sys,json,argparse,math,time,hashlib,pickle
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_proxy import ParticleProxy
from check_particle_proxy import capture,Splat,LO,SIZE
from check_cloud_evolution import configuration
from shared_transport import transport_features,load_transport
from match_compare import Comparison
from fit_particle_optics import NAMES
from fit_current_transport import review
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=160;FIELD=56;PHASES=[.12,.22,.32,.42,.52,.62,.72,.82]
OUT=HERE/'analysis/match-cache/particle-transport'


def preserve_density(motion, original):
    """把新运动与原密度合并为同一个普通网络，运行时不增加分支。"""
    first,second,last=motion
    p0,p1,p2=original
    a=np.asarray(first['weight']);b=np.asarray(p0['weight']);n0=len(a);m0=len(b)
    c=np.asarray(second['weight']);d=np.asarray(p1['weight']);n1=len(c);m1=len(d)
    middle=np.zeros((n1+m1,n0+m0));middle[:n1,:n0]=c;middle[n1:,n0:]=d
    output=np.zeros((3,n1+m1));output[:2,:n1]=np.asarray(last['weight'])[:2];output[2,n1:]=np.asarray(p2['weight'])[2]
    return [dict(weight=np.concatenate([a,b]).tolist(),bias=first['bias']+p0['bias']),
            dict(weight=middle.tolist(),bias=second['bias']+p1['bias']),
            dict(weight=output.tolist(),bias=last['bias'][:2]+p2['bias'][2:3])]

def prepare(initial,dense_stride=0,fine=False,scenes=None,phases=None,root=HERE):
    scenes=NAMES if scenes is None else list(scenes)
    sample_phases=PHASES if phases is None else list(phases)
    names=['renderer.py','surface_coherence.py','surface-coherence-weights.json','particle_cohorts.py',
        'particle-cohort-weights.json','conditional-release-weights.json','conditional-material-rules.json',
        'surface-transfer-rules.json','particle-coverage-rules.json','shared_transport.py','conditional_material.py',
        'surface_transfer.py','particle_coverage.py','analysis/particle_proxy.py','analysis/check_particle_proxy.py']
    if fine:names+=['particle_fission.py','scatter_material.py','particle-scatter-weights.json','analysis/measure_particle_footprint.py']
    key=hashlib.sha256(initial.read_bytes()+str((N,sample_phases,dense_stride,fine,'float32',scenes)).encode()+b''.join(((root if k.endswith('.json') else HERE)/k).read_bytes() for k in names)).hexdigest()[:16]
    path=OUT/f'particles-{key}.pkl'
    if path.exists():return pickle.loads(path.read_bytes())
    cls,kw=configuration(root,scatter=fine);kw['transport_weights']=load_transport(initial)
    ctx=moderngl.create_standalone_context(require=430);data=[]
    fy,fx=np.mgrid[:FIELD,:FIELD].astype(np.float32);fx=LO+(fx+.5)/FIELD*SIZE;fy=LO+(fy+.5)/FIELD*SIZE
    yy,xx=np.mgrid[:N,:N].astype(np.float32);xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    for name in scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=cls(name,ctx=ctx,quality=2,sites=sites,**kw);reader=ParticleProxy(r)
        qx=x+xx*w;qy=y+yy*h
        sample=lambda f:cv2.remap(cv2.GaussianBlur(f,(0,0),max(.5,.45*w*SIZE/N),sigmaY=max(.5,.45*h*SIZE/N)),qx,qy,cv2.INTER_LINEAR)
        angle=math.radians(m['direction']);cs,ss=math.cos(angle),-math.sin(angle)
        requested_phases=sample_phases if not dense_stride else [float(c.ref.progress[i]) for i in c.index[::dense_stride] if .06<=c.ref.progress[i]<=.94]
        for requested in requested_phases:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);d=capture(r,p,reader,N)
            d.update(features=transport_features(fx,fy,d['phase'],w,h,m['direction'],sites,True),
                target=sample(ref.astype(np.float32)/255),mask=sample(c.scene.roi.astype(np.float32)),
                rotation=np.array([[cs,ss],[-ss,cs]],np.float32),scale=np.array([min(w,h)/w,min(w,h)/h],np.float32),
                name=name,progress=p,gain=r.layout_change+(1-r.layout_change)*kw['transport_weights'].canonical_gain)
            data.append({key:value.astype(np.float32) if isinstance(value,np.ndarray) and np.issubdtype(value.dtype,np.floating) else value for key,value in d.items()})
        reader.close();r.close();print('逐材料缓存',name,len(requested_phases),flush=True)
    ctx.release();path.write_bytes(pickle.dumps(data,protocol=5));print(path,flush=True);return data

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--initial',default=str(HERE/'conditional-transport-weights.json'));ap.add_argument('--steps',type=int,default=3000)
    ap.add_argument('--label',default='first');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES)
    ap.add_argument('--learning-rate',type=float,default=.0004);ap.add_argument('--trust',type=float,default=.10);ap.add_argument('--smoothness',type=float,default=.003)
    ap.add_argument('--origin',action='store_true');ap.add_argument('--width',type=int,default=48)
    ap.add_argument('--cache',help='仅复用明确相同初始渲染器的逐材料缓存');ap.add_argument('--resume',help='在缓存展开点不变的情况下继续优化候选')
    ap.add_argument('--anneal',action='store_true');ap.add_argument('--batch',type=int,default=4)
    ap.add_argument('--correspondence',help='仅离线的大尺度 RGBXY 对应，不写入运行时权重')
    ap.add_argument('--matching-weight',type=float,default=.20);ap.add_argument('--jitter',type=float,default=0.)
    ap.add_argument('--residual',action='store_true',help='冻结既有函数，拟合并复合一份共同残差速度场')
    ap.add_argument('--dense-stride',type=int,default=0,help='按独立画面顺序加密观测，0 保留八相位缓存')
    ap.add_argument('--fine',action='store_true',help='使用已采用的完整细粒模型及逐子片近似')
    ap.add_argument('--motion-only',action='store_true',help='固定原来的密度函数，只校准实际位移；导出时合并为普通共同网络')
    ap.add_argument('--affine-time',action='store_true',help='运动只采用共同起止速度函数的平滑变化，移除周期时间位置摆动')
    ap.add_argument('--temporal-weight',type=float,default=0.,help='固定 GPU 材料路径上的修正加速度及逆风速度约束')
    ap.add_argument('--photo-floor',type=float,default=.015,help='按各参考误差归一化的下限，控制小误差参考的权重')
    ap.add_argument('--trace-root',type=Path,default=HERE/'analysis/match-cache/motion-continuity/current')
    a=ap.parse_args();out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:
        if a.fine:
            from fit_fine_transport_response import review as fine_review
            return fine_review(Path(a.review),out,a.scenes)
        if a.origin:
            import fit_current_transport as original
            from material_origin_flow import OriginFlowRenderer,load_origin_flow
            origin=load_origin_flow(Path(a.review));cls,options=configuration(HERE,scatter=False)
            def create(name,**kwargs):
                if getattr(kwargs['transport_weights'],'is_origin_review',False):kwargs['transport_weights']=options['transport_weights'];kwargs['origin_weights']=origin
                return OriginFlowRenderer(name,**kwargs)
            def marker(path):
                value=options['transport_weights'];value=type(value)(value);value.is_origin_review=True;return value
            original.CoherenceRenderer=create;original.load_transport=marker
        return review(Path(a.review),out,a.scenes)
    if a.fine and a.origin:raise ValueError('细粒校准使用当前同一采样坐标')
    if a.motion_only and (a.residual or a.origin):raise ValueError('运动独立校准需以原始共同函数的密度为基准')
    if a.affine_time and (not a.motion_only or a.residual or a.origin):raise ValueError('低阶时间实验固定密度、直接校准共同运动')
    initial=Path(a.initial);data=pickle.loads(Path(a.cache).read_bytes()) if a.cache else prepare(initial,a.dense_stride,a.fine);config=json.loads(initial.read_text('utf-8'))
    if a.fine and not all('scatter' in d for d in data):raise ValueError('细粒校准不能复用粗片缓存')
    scene_ids={name:[i for i,d in enumerate(data) if d['name']==name] for name in NAMES}
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(928);torch.backends.cudnn.benchmark=True
    s=Splat(n=N);t=s.tensor
    initial_residual=config.get('residual') if a.residual else None
    if config.get('residual') is not None and not a.residual:raise ValueError('已有复合场应使用 --residual 继续细化')
    source=initial_residual or config
    width=a.width if a.origin else max(a.width,len(source['layers'][0]['bias']))
    model=torch.nn.Sequential(torch.nn.Linear(59,width),torch.nn.Tanh(),torch.nn.Linear(width,width),torch.nn.Tanh(),torch.nn.Linear(width,3)).cuda()
    with torch.no_grad():
        if a.origin or (a.residual and initial_residual is None):
            model[-1].weight.zero_();model[-1].bias.zero_();config.update(displacement_scale=.30,density_scale=1.,flow_steps=0)
        else:
            # 重复隐单元并均分其出边，扩宽前后的函数严格相同，避免破坏已认可运动。
            widths=[len(source['layers'][0]['bias']),len(source['layers'][1]['bias'])]
            ix=[torch.arange(width,device='cuda')%v for v in widths]
            count=[torch.bincount(idx,minlength=v) for idx,v in zip(ix,widths)]
            p=model[0];p.weight.copy_(t(source['layers'][0]['weight'])[ix[0]]);p.bias.copy_(t(source['layers'][0]['bias'])[ix[0]])
            p=model[2];p.weight.copy_(t(source['layers'][1]['weight'])[ix[1]][:,ix[0]]/count[0][ix[0]]);p.bias.copy_(t(source['layers'][1]['bias'])[ix[1]])
            p=model[4];p.weight.copy_(t(source['layers'][2]['weight'])[:,ix[1]]/count[1][ix[1]]);p.bias.copy_(t(source['layers'][2]['bias']))
    yy,xx=torch.meshgrid(torch.arange(FIELD,device='cuda'),torch.arange(FIELD,device='cuda'),indexing='ij')
    grid=(torch.stack([xx,yy],-1).float()+.5)/FIELD*2-1
    rows=[{k:t(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in d.items()} for d in data]
    if a.origin:
        for d in rows:
            d['query']=d['origin'];age=((d['age']-.008)/(.06-.008)).clamp(0,1);d['warm']=age*age*(3-2*age)
    geometry=[]
    if a.fine:
        gy,gx=np.mgrid[:FIELD,:FIELD].astype(np.float32);gx=LO+(gx+.5)/FIELD*SIZE;gy=LO+(gy+.5)/FIELD*SIZE
        for name,origins in OBSERVED_SITES.items():
            m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
            sites=[Site(*v) for v in origins];ang=math.radians(m['direction']);cs,ss=math.cos(ang),-math.sin(ang)
            for p in np.linspace(0,1,41):
                geometry.append(dict(name=name,phase=p,features=transport_features(gx,gy,p,w,h,m['direction'],sites,True),
                    card=np.array([w,h]),rotation=np.array([[cs,ss],[-ss,cs]]),scale=np.array([min(w,h)/w,min(w,h)/h]),gain=1.))
    inputs=data+geometry
    features=t(np.stack([d['features'] for d in inputs]));phase=t([d['phase'] for d in inputs]);rot=t(np.stack([d['rotation'] for d in inputs]));scale=t(np.stack([d['scale'] for d in inputs]));gain=t([d['gain'] for d in inputs])
    endpoint_features=None
    if a.affine_time:
        end_by_name={}
        for name in set(d['name'] for d in inputs):
            meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x0,y0,x1,y1=meta['rect'];w,h=x1-x0,y1-y0
            end_by_name[name]=np.stack([transport_features(gx,gy,p,w,h,meta['direction'],[Site(*q) for q in OBSERVED_SITES[name]],True) for p in [0.,1.]])
        endpoint_features=t(np.stack([end_by_name[d['name']] for d in inputs]))
    fixed_density=None
    if a.motion_only:
        with torch.no_grad():fixed_density=torch.cat([model(features[i:i+8]).tanh()[...,2:3] for i in range(0,len(inputs),8)])
    base_fields=None
    if a.residual:
        config.update(displacement_scale=.45,density_scale=1.4,flow_steps=5)
        if initial_residual is not None:
            for key in ['displacement_scale','density_scale','flow_steps']:config[key]=initial_residual[key]
        from shared_transport import correction
        yy0,xx0=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx0=LO+(xx0+.5)/FIELD*SIZE;yy0=LO+(yy0+.5)/FIELD*SIZE
        old=load_transport(initial);old.residual=None;values=[]
        for d in inputs:
            meta=json.loads((HERE/'assets'/d['name']/'scene.json').read_text('utf-8'))
            values.append(correction(xx0,yy0,float(d['phase']),*d['card'],meta['direction'],[Site(*q) for q in OBSERVED_SITES[d['name']]],old)*d['gain'])
        base_fields=t(np.asarray(values))
    def field(ids,original=False):
        z=model(features[ids])
        if endpoint_features is not None and not original:
            endpoints=model(endpoint_features[ids]);pt=phase[ids,None,None,None]
            z=torch.cat([endpoints[:,0,:,:,:2]*(1-pt)+endpoints[:,1,:,:,:2]*pt,z[...,2:]],-1)
        z=z.tanh()
        if fixed_density is not None:z=torch.cat([z[...,:2],fixed_density[ids]],-1)
        v=config['displacement_scale']*phase[ids,None,None,None]*z[...,:2]
        v=torch.einsum('bxyc,bcd->bxyd',v,rot[ids])*scale[ids,None,None,:]/(2**config['flow_steps'])
        for _ in range(config['flow_steps']):v=v+F.grid_sample(v.permute(0,3,1,2),grid[None]+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        result=torch.cat([v/scale[ids,None,None,:],config['density_scale']*z[...,2:]],-1)*gain[ids,None,None,None]
        if base_fields is not None:
            shifted=grid[None]+base_fields[ids,...,:2]*scale[ids,None,None,:]*2/SIZE
            result=base_fields[ids]+F.grid_sample(result.permute(0,3,1,2),shifted,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return result
    def lookup(f,d):return F.grid_sample(f.permute(2,0,1)[None],d['query'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
    def jacobian(f,ids):
        full=f[...,:2]*scale[ids,None,None,:]
        jx=(full[:,:-1,1:]-full[:,:-1,:-1])*FIELD/SIZE;jy=(full[:,1:,:-1]-full[:,:-1,:-1])*FIELD/SIZE
        return (1+jx[...,0])*(1+jy[...,1])-jx[...,1]*jy[...,0]
    paths=[]
    if a.temporal_weight:
        if not a.fine:raise ValueError('连续轨迹约束需全部布局的几何采样')
        lookup_geometry={(d['name'],round(float(d['phase'])*40)):i for i,d in enumerate(inputs) if i>=len(data)}
        rng=np.random.default_rng(928)
        for path in sorted(a.trace_root.glob('*.npz')):
            saved=np.load(path);tr=saved['trace'];span=float(saved['span']);rect=saved['rect'];card=np.array(rect[2:])-rect[:2]
            # 显示进度经过共同的时钟校准；GPU 输出的 age_out 保留负年龄，
            # 同一材料的年龄差正好是内部求解时间，不能混用页面时间。
            ts=tr[:,0,4]-tr[0,0,4]
            for center in range(6,31,2):
                phases3=np.array([center-2,center,center+2])/40
                ids=[lookup_geometry[(path.stem,round(p*40))] for p in phases3]
                hi=np.clip(np.searchsorted(ts,phases3),1,len(ts)-1);lo=hi-1
                fraction=((phases3-ts[lo])/(ts[hi]-ts[lo]))[:,None,None]
                q=tr[lo]*(1-fraction)+tr[hi]*fraction
                live=((q[:,:,4]>.06)&(q[:,:,4]<q[:,:,5]-.06)&(q[:,:,6]<.6)).all(0)&saved['mask']
                options=np.flatnonzero(live)
                if not len(options):continue
                take=rng.choice(options,min(384,len(options)),replace=False);q=q[:,take]
                paths.append(dict(ids=ids,query=t((q[:,:,:2]/card+.38)/1.76),
                    base=t(q[:,:,:2]/span),wind=t(saved['wind']),age=t(q[:,:,4])))
        if not paths:raise ValueError('找不到有效 GPU 固定材料轨迹')
    def continuous_loss(sample):
        fields=field(sample['ids'])[...,:2]
        move=F.grid_sample(fields.permute(0,3,1,2),sample['query'][:,None]*2-1,padding_mode='border',align_corners=False)[:,:,0].permute(0,2,1)
        # 原始积分中的涡动保留；仅限制后置映射增加的加速和回退。
        acceleration=(move[2]-2*move[1]+move[0])/.05**2
        velocity=(sample['base'][2]+move[2]-sample['base'][0]-move[0])/.10
        reverse=F.relu(.025-velocity@sample['wind'])
        return acceleration.square().sum(-1).mean()/16.+4*reverse.square().mean()
    def cohort(f,pos,d):
        value=F.grid_sample(f.permute(2,0,1)[None],pos[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
        def ease(lo,hi):
            q=((d['age']-lo)/(hi-lo)).clamp(0,1);return q*q*(3-2*q)
        mature= ease(.06,.16);elder=ease(.22,.38)
        ages=torch.stack([1-mature,mature*(1-elder),mature*elder],-1)
        return (value*ages).sum(-1)*ease(.005,.035)
    def filters(image,d):
        # 场景的实际像素尺度用于滤波，不按素材名称切换。
        sigmas=[max(.55,q*N/(SIZE*float(d['span']))) for q in [4.,12.,24.]]
        z=image.permute(2,0,1)[None]
        return torch.stack([s.gaussian(z,q)[0].permute(1,2,0) for q in sigmas])
    with torch.no_grad():
        anchors=torch.cat([field(list(range(i,min(i+8,len(rows)))),original=a.affine_time) for i in range(0,len(rows),8)])
        for i,d in enumerate(rows):
            d['anchor']=lookup(anchors[i],d)
            d['cohort_anchor']=cohort(d['cohort'],d['carrier'] if 'unit_alpha' in d else d['pos'],d)
            if a.fine:d['scatter_anchor']=s.scatter(d,d['carrier'])
            d['mass']=s.material_mass(d,torch.zeros_like(d['density']))
            d['render_anchor']=s.render(d['pos'],d['mass'],d['color'],d['base'])
            d['target_filtered']=filters(d['target'],d);d['actual_filtered']=filters(d['actual'],d)
    bands=t([1.,.7,.4])[:,None,None,None]
    def photo(estimate,d):
        difference=filters(estimate,d)-d['target_filtered'];w=d['mask'][None,:,:,None]*bands
        return (torch.sqrt(difference.square()+.003**2)*w).sum()/w.sum().clamp(min=1)/3
    with torch.no_grad():prior=torch.stack([photo(d['actual'],d) for d in rows])
    if a.correspondence:
        matches=pickle.loads(Path(a.correspondence).read_bytes())
        for d,m in zip(rows,matches):
            if d['name']!=m['name'] or abs(d['progress']-m['phase'])>1e-6:raise ValueError('对应缓存与材料帧不一致')
            samples=t(np.concatenate([m['flow'],m['weight'][...,None]],-1))
            values=F.grid_sample(samples.permute(2,0,1)[None],d['pos'][None,None]*2-1,padding_mode='zeros',align_corners=False)[0,:,0].T
            d['match_delta']=values[:,:2];d['match_weight']=values[:,2]*d['mass'].clamp(max=1)*d['warm']
    if a.resume:
        saved=json.loads(Path(a.resume).read_text('utf-8'))
        if a.residual:
            saved=saved['residual']
            for key in ['displacement_scale','density_scale','flow_steps']:config[key]=saved[key]
        with torch.no_grad():
            for q,v in zip([q for q in model if isinstance(q,torch.nn.Linear)],saved['layers']):
                # 运动候选可能已经与冻结密度合并；仅取前面的运动隐单元。
                q.weight.copy_(t(v['weight'])[:q.out_features,:q.in_features]);q.bias.copy_(t(v['bias'])[:q.out_features])
    if a.jitter:
        # 精确扩宽后打破重复单元的对称；锚点仍是扰动前的实际渲染。
        with torch.no_grad():
            for q in model.parameters():q.add_(torch.randn_like(q)*a.jitter)
    def losses(ids,regular=True):
        f=field(ids);errs=[];matching=[]
        for j,i in enumerate(ids):
            d=rows[i];delta=(lookup(f[j],d)-d['anchor'])*d['warm'][:,None]
            pos=d['pos']+delta[:,:2]*d['scale']/SIZE
            density=delta[:,2]
            if not a.origin:
                cohort_query=d['carrier']+pos-d['pos'] if 'unit_alpha' in d else pos
                density=density+cohort(d['cohort'],cohort_query,d)-d['cohort_anchor']
            mass=s.material_mass(d,density)
            color=d['color']
            if a.fine:
                changed=s.scatter(d,d['carrier']+pos-d['pos'])
                mass=s.material_mass(d,density,changed[:,0]) if 'unit_alpha' in d else mass*(changed[:,0]-d['scatter_anchor'][:,0]).exp()
                color=s.shade(color,d['scatter_anchor'],changed)
            pos=s.fission_position(d,pos,density)
            estimate=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
            errs.append(photo(estimate,d))
            if a.correspondence:
                diff=(pos-d['pos']-d['match_delta'])*SIZE/d['scale']
                matching.append(((diff.square().sum(-1)+.0001).sqrt()*d['match_weight']).sum()/d['match_weight'].sum().clamp(min=1)/.10)
        errors=torch.stack(errs)
        if not regular:return errors
        delta=f-anchors[ids];move=delta[...,:2]
        trust=F.relu(abs(move)-a.trust).square().mean()+.02*F.relu(abs(delta[...,2])-.8).square().mean()
        dx=(move[:,:-1,1:]-move[:,:-1,:-1])*FIELD/SIZE;dy=(move[:,1:,:-1]-move[:,:-1,:-1])*FIELD/SIZE
        full=f[...,:2]*scale[ids,None,None,:]
        jx=(full[:,:-1,1:]-full[:,:-1,:-1])*FIELD/SIZE;jy=(full[:,1:,:-1]-full[:,:-1,:-1])*FIELD/SIZE
        det=(1+jx[...,0])*(1+jy[...,1])-jx[...,1]*jy[...,0]
        extra=a.matching_weight*torch.stack(matching).mean() if matching else 0.
        topology=.08*F.relu(.2-det).square().mean()
        if a.fine:
            penalty=F.relu(.12-det).square().flatten(1)
            topology=20*penalty.mean()+10*penalty.topk(16,dim=1).values.mean()
            gi=torch.randint(len(rows),len(inputs),(4,),device='cuda')
            additional=F.relu(.10-jacobian(field(gi),gi)).square().flatten(1)
            topology+=20*additional.mean()+10*additional.topk(16,dim=1).values.mean()
        temporal=a.temporal_weight*continuous_loss(paths[int(torch.randint(len(paths),(1,)).item())]) if paths else 0.
        return (errors/prior[ids].clamp(min=a.photo_floor)).mean()+2*F.relu(errors-prior[ids]).mean()+3*trust+a.smoothness*(dx.square().mean()+dy.square().mean())+topology+extra+temporal
    def scores():
        with torch.no_grad():
            values=torch.cat([losses(list(range(i,min(i+4,len(rows)))),False) for i in range(0,len(rows),4)]).cpu().numpy()
        return {name:float(np.mean(values[scene_ids[name]])) for name in NAMES}
    before={name:float(prior[scene_ids[name]].mean()) for name in NAMES};print('真实材料近似起点',before,flush=True)
    # 保留新运动形式的最佳候选供连续动画验收；是否替换默认值单独决定。
    optimizer=torch.optim.AdamW(model.parameters(),lr=a.learning_rate,weight_decay=.0001);history=[];best=float('inf');start=time.monotonic()
    for step in range(a.steps):
        if a.anneal:
            rate=a.learning_rate*(.08+.92*.5*(1+math.cos(math.pi*step/a.steps)))
            for group in optimizer.param_groups:group['lr']=rate
        scenes=torch.randint(len(NAMES),(a.batch,)).tolist()
        ids=[scene_ids[NAMES[k]][torch.randint(len(scene_ids[NAMES[k]]),(1,)).item()] for k in scenes]
        optimizer.zero_grad();losses(ids).backward();torch.nn.utils.clip_grad_norm_(model.parameters(),3.);optimizer.step()
        if (step+1)%250==0:
            current=scores();ratios={n:current[n]/before[n] for n in NAMES};score=float(np.mean(list(ratios.values()))+.3*max(ratios.values()))
            minimum=1.
            if a.fine:
                with torch.no_grad():
                    for start_id in range(len(rows),len(inputs),12):
                        ids=list(range(start_id,min(start_id+12,len(inputs))));minimum=min(minimum,float(jacobian(field(ids),ids).min()))
            temporal_score=0.
            if paths:
                with torch.no_grad():temporal_score=float(torch.stack([continuous_loss(paths[i]) for i in range(0,len(paths),4)]).mean())
                score+=a.temporal_weight*temporal_score
            row=dict(step=step+1,score=score,ratios=ratios,minimum_jacobian=minimum,temporal_cost=temporal_score);history.append(row);print('逐材料共同输运',step+1,round(time.monotonic()-start,1),round(score,4),'轨迹代价',round(temporal_score,3),'最小雅可比',round(minimum,5),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best and minimum>.025:
                best=score;result={k:config[k] for k in ['displacement_scale','density_scale','flow_steps','canonical_gain','multiscale_features']}
                result.update(train=NAMES,holdout=[],selection=row,method='共同材料散布；实际渲染另行复核',layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                if a.motion_only:
                    result['layers']=preserve_density(result['layers'],source['layers'])
                    result['method']='固定共同密度的运动校准；两个输出部分合并成同一普通网络，无运行时分支'
                if a.affine_time:
                    result['motion_time_basis']='affine_velocity'
                    result['method']='共同起止速度函数，平滑单程变化；移除周期时间位置修正，密度固定'
                if a.residual:
                    residual={k:result[k] for k in ['displacement_scale','density_scale','flow_steps','multiscale_features','layers']}
                    result=json.loads(initial.read_text('utf-8'));result.update(residual=residual,selection=row,method='既有共同场与共同残差速度场连续复合，无逐参考运行时资源')
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
