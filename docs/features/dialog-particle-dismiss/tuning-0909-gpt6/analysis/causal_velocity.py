"""未采用的运动实验：以出生位置决定低阶速度，按各材料年龄积分。

保留基础积分和全部材质，用这一函数替换后置时变位置修正。原始参考仍是
最终验收目标；已有轨迹只用于初始化，不能作为相似度真值。
"""
from pathlib import Path
import argparse,json,math,pickle,sys,time
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from shared_transport import transport_features
from learned_release import canonical_sites
from material_motion_constraints import trace_path,motion_penalty
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

FIELD=56
HORIZON=.65
AXIAL_FLOOR=-.10
LATERAL=1.8
BIRTH_RAMP=.08
BIRTH_SCALE=.14
CORRECTION_SCALE=4.


class VelocityWeights(list):
    control_kind='forward_correction'
    birth_conditioned=False


def coefficients(x,y,w,h,direction,sites,weights,birth=0.):
    value=transport_features(x,y,birth,w,h,direction,sites,True)
    for i,(matrix,bias) in enumerate(weights):
        value=value@matrix.T+bias
        if i<len(weights)-1:value=np.tanh(value)
    if value.shape[-1]==6:value=np.concatenate([np.zeros((*x.shape,2),np.float32),value],-1)
    value=value.reshape(*x.shape,4,2)
    value[...,0,:]=BIRTH_SCALE*np.tanh(value[...,0,:])
    if getattr(weights,'control_kind','forward_correction')=='symmetric_correction':
        value[...,1:,:]=CORRECTION_SCALE*np.tanh(value[...,1:,:]/CORRECTION_SCALE)
    else:
        value[...,1:,0]=AXIAL_FLOOR+np.logaddexp(0.,value[...,1:,0])
        value[...,1:,1]=LATERAL*np.tanh(value[...,1:,1]/LATERAL)
    angle=math.radians(direction);c,s=math.cos(angle),-math.sin(angle)
    return (value@np.array([[c,s],[-s,c]],np.float32)).astype(np.float32)


def load(path):
    data=json.loads(Path(path).read_text('utf-8'))
    if data['horizon']!=HORIZON or data['axial_floor']!=AXIAL_FLOOR or data['lateral_scale']!=LATERAL:
        raise ValueError('共同速度参数与当前实现不一致')
    if data.get('birth_ramp',BIRTH_RAMP)!=BIRTH_RAMP or data.get('birth_scale',BIRTH_SCALE)!=BIRTH_SCALE:
        raise ValueError('出生响应参数与当前实现不一致')
    weights=VelocityWeights((np.asarray(v['weight'],np.float32),np.asarray(v['bias'],np.float32)) for v in data['layers'])
    weights.control_kind=data.get('control_kind','forward_correction')
    weights.birth_conditioned=bool(data.get('birth_conditioned',False))
    if weights.control_kind not in ['forward_correction','symmetric_correction']:
        raise ValueError('未知的速度修正表示')
    if data.get('correction_scale',CORRECTION_SCALE)!=CORRECTION_SCALE:
        raise ValueError('速度修正范围与实现不一致')
    return weights


class CausalVelocityRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){','''
uniform sampler3D causal_velocity;
uniform float causal_strength;
uniform int causal_birth_conditioned;
layout(std430,binding=7) readonly buffer CausalControls{vec4 causal_controls[];};
void main(){''',1)
    vertex_source=vertex_source.replace('move+=transport.xy*span*smoothstep(0.,.03,age);','''
    vec2 causal_q=(m.src.xy/card+vec2(.38))/1.76;
    vec2 birth=texture(causal_velocity,vec3(causal_q,1./8.)).rg;
    vec2 v0=texture(causal_velocity,vec3(causal_q,3./8.)).rg;
    vec2 v1=texture(causal_velocity,vec3(causal_q,5./8.)).rg;
    vec2 v2=texture(causal_velocity,vec3(causal_q,7./8.)).rg;
    if(causal_birth_conditioned==1){
        vec4 first=causal_controls[parent_instance*2],second=causal_controls[parent_instance*2+1];
        birth=first.xy;v0=first.zw;v1=second.xy;v2=second.zw;
    }
    float a=min(max(age,0.),.65),h=.65;
    vec3 integral=vec3(a-a*a/h+a*a*a/(3.*h*h),a*a/h-2.*a*a*a/(3.*h*h),a*a*a/(3.*h*h));
    integral.z+=max(age-h,0.);
    vec2 delta=birth*smoothstep(0.,.08,age)+v0*integral.x+v1*integral.y+v2*integral.z;
    if(causal_strength>.5)move+=delta*span;
    else move+=transport.xy*span*smoothstep(0.,.03,age);
''',1)

    def __init__(self,*args,causal=None,**kw):
        super().__init__(*args,**kw)
        yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32)
        xx=-.38+(xx+.5)/FIELD*1.76;yy=-.38+(yy+.5)/FIELD*1.76
        sites=self.origin_sites or canonical_sites(self.cw,self.ch,self.direction)
        birth_conditioned=causal is not None and causal.birth_conditioned
        value=np.zeros((FIELD,FIELD,4,2),np.float32) if causal is None or birth_conditioned else coefficients(xx,yy,self.cw,self.ch,self.direction,sites,causal)
        self.causal_tex=self.ctx.texture3d((FIELD,FIELD,4),2,value.transpose(2,0,1,3).copy().tobytes(),dtype='f4')
        self.causal_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.causal_tex.repeat_x=self.causal_tex.repeat_y=self.causal_tex.repeat_z=False
        self.program['causal_velocity']=12;self.program['causal_strength']=float(causal is not None)
        source=self.base[:,:2]/[self.cw,self.ch]
        control=coefficients(source[:,0],source[:,1],self.cw,self.ch,self.direction,sites,causal,self.base[:,2]) if birth_conditioned else np.zeros((1,4,2),np.float32)
        self.causal_controls=self.ctx.buffer(control.astype(np.float32).tobytes())
        self.program['causal_birth_conditioned']=int(birth_conditioned)

    def render(self,*args,**kw):
        self.causal_tex.use(12)
        self.causal_controls.bind_to_storage_buffer(7)
        return super().render(*args,**kw)

    def close(self):
        super().close();self.causal_tex.release();self.causal_controls.release()


def review(path,out):
    from PIL import Image,ImageDraw,ImageFont
    from check_cloud_evolution import configuration
    from match_compare import Comparison
    from fit_particle_optics import measure
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);weights=load(path)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in OBSERVED_SITES:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        old=cls(name,**args);new=CausalVelocityRenderer(name,causal=weights,**args)
        tw=285;th=round(old.h*tw/old.w);sheet=Image.new('RGB',(tw*5,(th+26)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);before=old.render(p);after=new.render(p);row=dict(phase=p)
            if c.scene.roi.any():
                target=measure(ref,c.scene.roi)
                for stage,frame in [('before',before),('after',after)]:row[stage]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            for k,(title,frame) in enumerate([('华为原片',ref),('当前默认',before),('共同年龄速度实验',after)]):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26))
                draw.text((j*tw+3,k*(th+26)+2),f'{title} {p:.3f}',font=font,fill='white')
            rows.append(row)
        sheet.save(out/f'{name}.jpg',quality=94);old.close();new.close();report[name]=rows
        print(name,{s:{k:float(np.mean([r[s][k] for r in rows])) for k in ['rgb_4','grain']} for s in ['before','after']} if c.scene.roi.any() else '未知背景，只作目视',flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def check(path,out):
    from scipy.ndimage import map_coordinates
    from check_cloud_evolution import configuration
    from diagnose_motion_continuity import trajectory_reader
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);weights=load(path);report={}
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx=-.38+(xx+.5)/FIELD*1.76;yy=-.38+(yy+.5)/FIELD*1.76
    for name in ['ironman-up-reference','reference-0926-2','ironman']:
        args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        old=cls(name,**args);new=CausalVelocityRenderer(name,causal=weights,**args);read,close=trajectory_reader(new)
        field=coefficients(xx,yy,new.cw,new.ch,new.direction,new.origin_sites,weights)
        q=(new.base[:,:2]/[new.cw,new.ch]+.38)/1.76*FIELD-.5
        if weights.birth_conditioned:
            source=new.base[:,:2]/[new.cw,new.ch]
            sampled=coefficients(source[:,0],source[:,1],new.cw,new.ch,new.direction,new.origin_sites,weights,new.base[:,2])
        else:sampled=np.stack([map_coordinates(field[:,:,k,c],[q[:,1],q[:,0]],order=1,mode='nearest') for k in range(4) for c in range(2)],-1).reshape(-1,4,2)
        result=dict(zero_max_pixel_delta=0,endpoint_max_pixel_delta=[],state_equal=True,maximum_position_equation_error=0.)
        for p in [0.,.18,.41,.58,.77,1.]:
            before=old.render(p);new.program['causal_strength']=0.;zero=new.render(p)
            result['zero_max_pixel_delta']=max(result['zero_max_pixel_delta'],int(abs(zero.astype(np.int16)-before.astype(np.int16)).max()))
            new.program['causal_strength']=1.;actual=new.render(p);tr=read()
            result['state_equal']&=old.state.read()==new.state.read()
            age=np.clip(tr[:,4],0,HORIZON);h=HORIZON
            young=np.clip(tr[:,4]/BIRTH_RAMP,0,1)
            basis=np.stack([young**2*(3-2*young),age-age**2/h+age**3/(3*h*h),age**2/h-2*age**3/(3*h*h),age**3/(3*h*h)+np.maximum(tr[:,4]-h,0)],-1)
            predicted=tr[:,:2]+(sampled*basis[...,None]).sum(-2)*new.span
            result['maximum_position_equation_error']=max(result['maximum_position_equation_error'],float(abs(predicted-tr[:,2:4]).max()/new.span))
            if p in [0.,1.]:result['endpoint_max_pixel_delta'].append(int(abs(actual.astype(np.int16)-before.astype(np.int16)).max()))
        first=new.render(.43);new.render(.72);result['seek_equal']=bool(np.array_equal(first,new.render(.43)));result['gl_error']=ctx.error
        close();old.close();new.close();report[name]=result;print(name,result,flush=True)
        (out/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=3500);ap.add_argument('--width',type=int,default=192)
    ap.add_argument('--label',default='source-velocity-initialization');ap.add_argument('--review',type=Path)
    ap.add_argument('--check',type=Path)
    ap.add_argument('--symmetric-correction',action='store_true',help='约束最终运动，允许修正项连续减速')
    ap.add_argument('--birth-conditioned',action='store_true',help='以材料实际释放时刻作为静态初始条件')
    ap.add_argument('--initial',type=Path);ap.add_argument('--photo-cache',type=Path);ap.add_argument('--grain-cache',type=Path)
    ap.add_argument('--trust-radius',type=float,default=0.,help='将单轮近似限制在真实采样位置附近，以控件短边归一化')
    ap.add_argument('--trace-root',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    a=ap.parse_args();out=HERE/'analysis/match-cache/causal-velocity'/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(a.review,out)
    if a.check:return check(a.check,out)
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(280928)
    to=lambda v:torch.as_tensor(np.asarray(v,np.float32),device='cuda')
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx=-.38+(xx+.5)/FIELD*1.76;yy=-.38+(yy+.5)/FIELD*1.76
    paths=[];features=[];rotations=[];names=list(OBSERVED_SITES);geometries={}
    for name in names:
        saved=np.load(a.trace_root/f'{name}.npz');p=trace_path(saved,seed=2810,maximum=2048)
        p={k:to(v) if isinstance(v,np.ndarray) else v for k,v in p.items()}
        p['physical']=(p['query']*1.76-.38)/p['scale']
        visible=F.pad(p['pair'],(0,0,1,0))+F.pad(p['pair'],(0,0,0,1));p['visible']=visible.clamp(max=1)
        card=saved['rect'][2:]-saved['rect'][:2];meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
        sites=[Site(*v) for v in OBSERVED_SITES[name]];geometries[name]=(card,meta['direction'],sites)
        if a.birth_conditioned:
            raw_source=trace_path(saved,seed=2810,maximum=2048)
            source=raw_source['origin']*1.76-.38
            p['features']=to(transport_features(source[:,0],source[:,1],-raw_source['age'][0],*card,meta['direction'],sites,True))
        features.append(transport_features(xx,yy,0.,*card,meta['direction'],[Site(*v) for v in OBSERVED_SITES[name]],True))
        angle=math.radians(meta['direction']);c,s=math.cos(angle),-math.sin(angle);rotations.append([[c,s],[-s,c]])
        age=p['age'].clamp(0,HORIZON);h=HORIZON
        young=(p['age']/BIRTH_RAMP).clamp(0,1)
        p['basis']=torch.stack([young.square()*(3-2*young),age-age.square()/h+age**3/(3*h*h),age.square()/h-2*age**3/(3*h*h),age**3/(3*h*h)+(p['age']-h).clamp(min=0)],-1)
        paths.append(p)
    features=to(features);rotations=to(rotations)
    net=torch.nn.Sequential(torch.nn.Linear(59,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,8)).cuda()
    with torch.no_grad():
        net[-1].weight.zero_();net[-1].bias.zero_()
        if not a.symmetric_correction:net[-1].bias[2::2]=math.log(math.expm1(-AXIAL_FLOOR))
        if a.initial:
            initial=load(a.initial)
            expected='symmetric_correction' if a.symmetric_correction else 'forward_correction'
            if initial.control_kind!=expected:raise ValueError('初始化与速度表示不一致')
            if initial.birth_conditioned!=a.birth_conditioned:raise ValueError('初始化的释放时刻输入不一致')
            for layer,(weight,bias) in zip([v for v in net if isinstance(v,torch.nn.Linear)],initial):
                if layer.out_features==8 and len(bias)==6:
                    layer.weight[2:].copy_(to(weight));layer.bias[2:].copy_(to(bias))
                else:layer.weight.copy_(to(weight));layer.bias.copy_(to(bias))

    def transform(raw):
        if a.symmetric_correction:flow=CORRECTION_SCALE*(raw[...,1:,:]/CORRECTION_SCALE).tanh()
        else:flow=torch.stack([AXIAL_FLOOR+F.softplus(raw[...,1:,0]),LATERAL*(raw[...,1:,1]/LATERAL).tanh()],-1)
        return torch.cat([BIRTH_SCALE*raw[...,:1,:].tanh(),flow],-2)

    def field(ids):
        if a.birth_conditioned:
            return [transform(net(paths[i]['features']).reshape(-1,4,2))@rotations[i] for i in ids]
        raw=net(features[ids]).reshape(len(ids),FIELD,FIELD,4,2)
        value=transform(raw)
        return torch.einsum('bxykc,bcd->bxykd',value,rotations[ids])

    def positions(value,p):
        sampled=value if a.birth_conditioned else F.grid_sample(value.reshape(FIELD,FIELD,8).permute(2,0,1)[None],p['origin'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T.reshape(-1,4,2)
        return p['physical']+(sampled[None]*p['basis'][...,None]).sum(-2)

    def fitting(value,p):
        pos=positions(value,p);error=(pos-p['base'])/.05
        image=((error.square()+.04).sqrt()*p['visible'][...,None]).sum()/p['visible'].sum().clamp(min=1)/2
        return image+.15*motion_penalty(torch,pos,p)

    rows=[]
    if a.photo_cache:
        if not a.grain_cache:raise ValueError('真实细粒校准需要相同缓存的颗粒目标')
        from check_particle_proxy import Splat,SIZE
        from particle_grain_proxy import alpha_moments,variance
        s=Splat(n=160);data=pickle.loads(a.photo_cache.read_bytes());grain=np.load(a.grain_cache)
        if list(grain['names'])!=[d['name'] for d in data]:raise ValueError('细粒目标顺序不一致')
        if np.max(abs(grain['phases']-[d['progress'] for d in data]))>1e-6:raise ValueError('细粒目标相位不一致')
        if 'identity' in grain and any(d.get('causal_cache_identity')!=str(grain['identity']) for d in data):raise ValueError('颗粒目标与候选缓存不属于同一次采样')

        def sampled(value,query):
            return F.grid_sample(value.permute(2,0,1)[None],query[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T

        def filters(image,d):
            return torch.stack([s.gaussian(image.permute(2,0,1)[None],max(.55,q*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]])

        def photo(image,d):
            delta=filters(image,d)-d['target_filtered'];w=d['mask'][None,:,:,None]*to([1.,.7,.4])[:,None,None,None]
            return ((delta.square()+.003**2).sqrt()*w).sum()/w.sum().clamp(min=1)/3

        with torch.no_grad():
            for i,source in enumerate(data):
                d={key:to(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for key,v in source.items()};d['sid']=names.index(d['name'])
                if a.birth_conditioned:
                    original=np.concatenate([source['origin'],(source['phase']-source['age'])[:,None]],-1)
                    unique,indices=np.unique(original,axis=0,return_inverse=True);q=unique[:,:2]*1.76-.38
                    card,direction,sites=geometries[d['name']]
                    d['source_features']=to(transport_features(q[:,0],q[:,1],unique[:,2],*card,direction,sites,True))
                    d['source_indices']=torch.as_tensor(indices,device='cuda')
                d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
                mass=s.material_mass(d,torch.zeros_like(d['density']));d['render_anchor']=s.render(d['pos'],mass,d['color'],d['base'])
                d['target_filtered']=filters(d['target'],d);d['prior']=photo(d['actual'],d)
                d['actual_grain']=to(grain['actual'][i]);d['target_grain']=to(grain['target'][i])
                m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0]);d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base'])
                d['grain_prior']=((abs(d['actual_grain']-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3
                age=d['age'].clamp(0,HORIZON);h=HORIZON
                young=(d['age']/BIRTH_RAMP).clamp(0,1)
                d['basis']=torch.stack([young.square()*(3-2*young),age-age.square()/h+age**3/(3*h*h),age.square()/h-2*age**3/(3*h*h),age**3/(3*h*h)+(d['age']-h).clamp(min=0)],-1)
                rows.append(d)

        def photo_loss(value,d):
            if a.birth_conditioned:
                controls=(transform(net(d['source_features']).reshape(-1,4,2))@rotations[d['sid']])[d['source_indices']]
            else:controls=sampled(value.reshape(FIELD,FIELD,8),d['origin']).reshape(-1,4,2)
            move=(controls*d['basis'][...,None]).sum(-2)
            carrier=d['query']+move*d['scale']/SIZE;pos=d['pos']+carrier-d['carrier']
            density=s.cohort(d,carrier)-d['cohort_anchor'];scatter=s.scatter(d,carrier)
            mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
            pos=s.fission_position(d,pos,density)
            image=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
            # 保留真实相对退步阈值；不能用较大的分母下限默许低误差序列退步。
            relative_photo=photo(image,d)/d['prior'].clamp(min=1e-5)
            ratio=photo(image,d)/d['prior'].clamp(min=.003)
            m1,m2=alpha_moments(s,d,density,scatter[:,0])
            detail=(d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])).clamp(min=0)
            absolute_grain=((abs(detail-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3
            relative_grain=absolute_grain/d['grain_prior'].clamp(min=1e-5)
            grain_error=absolute_grain/d['grain_prior'].clamp(min=.002)
            loss=ratio+.6*grain_error+2*F.relu(relative_photo-1)+2*F.relu(relative_grain-1)
            if a.trust_radius:
                travel=torch.linalg.vector_norm((carrier-d['carrier'])*SIZE/d['scale'],dim=-1)
                loss=loss+2*F.relu(travel/a.trust_radius-1).square().mean()
            return loss

        target_ids={r['sid'] for r in rows}

    optimizer=torch.optim.AdamW(net.parameters(),lr=.0008,weight_decay=.001);history=[];best=float('inf');started=time.monotonic()
    for step in range(a.steps):
        for group in optimizer.param_groups:group['lr']=(.0003 if rows else .0008)*(.10+.90*.5*(1+math.cos(math.pi*step/a.steps)))
        optimizer.zero_grad()
        if rows:
            value=field(list(range(len(paths))));indices=torch.randint(len(rows),(2,)).tolist()
            loss=torch.stack([photo_loss(value[rows[i]['sid']],rows[i]) for i in indices]).mean()
            k=int(torch.randint(len(paths),(1,)));p=paths[k];pos=positions(value[k],p)
            pose=((pos-p['base']).square()*p['visible'][...,None]).sum()/p['visible'].sum().clamp(min=1)/.05**2
            loss=loss+.30*motion_penalty(torch,pos,p)+(.02 if k in target_ids else .30)*pose
        else:
            ids=torch.randperm(len(paths))[:4].tolist();value=field(ids)
            loss=torch.stack([fitting(value[i],paths[k]) for i,k in enumerate(ids)]).mean()
        if a.birth_conditioned:
            loss=loss+.00005*torch.stack([v.square().mean() for v in value]).mean()
        else:
            smooth=(value[:,1:]-value[:,:-1]).square().mean()+(value[:,:,1:]-value[:,:,:-1]).square().mean()
            loss=loss+.005*(FIELD/1.76)**2*smooth
        loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),2.);optimizer.step()
        if (step+1)%500==0:
            with torch.no_grad():
                values=field(list(range(len(paths))));errors={}
                motion=float(torch.stack([motion_penalty(torch,positions(v,p),p) for v,p in zip(values,paths)]).mean())
                score=float(torch.stack([photo_loss(values[d['sid']],d) for d in rows]).mean())+.30*motion if rows else float(torch.stack([fitting(v,p) for v,p in zip(values,paths)]).mean())
                for name,v,p in zip(names,values,paths):
                    error=torch.linalg.vector_norm(positions(v,p)-p['base'],dim=-1)[p['visible']>.5]
                    errors[name]=dict(median=float(error.median()),q90=float(error.quantile(.9)))
            row=dict(step=step+1,score=score,path_cost=motion,stage='真实画面及颗粒校准' if rows else '已有轨迹初始化',elapsed=time.monotonic()-started,position_error_in_short_side=errors)
            history.append(row);print('共同年龄速度初始化',step+1,round(row['elapsed'],1),score,errors,flush=True)
            if score<best:
                best=score;config=dict(method='源位置共同短时展开和低阶速度按材料年龄积分，替换后置位置修正；现有轨迹仅作初始化',horizon=HORIZON,axial_floor=AXIAL_FLOOR,lateral_scale=LATERAL,birth_ramp=BIRTH_RAMP,birth_scale=BIRTH_SCALE,
                    control_kind='symmetric_correction' if a.symmetric_correction else 'forward_correction',correction_scale=CORRECTION_SCALE,
                    birth_conditioned=a.birth_conditioned,
                    trust_radius=a.trust_radius,
                    selection=row,layers=[dict(weight=v.weight.detach().cpu().tolist(),bias=v.bias.detach().cpu().tolist()) for v in net if isinstance(v,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(config,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
