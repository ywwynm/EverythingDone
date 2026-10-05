"""未采用实验：联合校准已有共同输运及材质，并约束完整可见材料路径。

不新增运行分支。位置、明暗与颗粒目标使用同一真实 GPU 缓存；默认权重只读。
"""
from pathlib import Path
import argparse,copy,hashlib,json,math,pickle,shutil,sys,time
import numpy as np
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from material_motion_constraints import trace_path,motion_penalty

OUT=HERE/'analysis/match-cache/joint-motion-material'
FIELD=56
MATERIAL_FIELD=64
LO,SIZE=-.38,1.76


def review(root,components='both',scenes=None,all_frames=False):
    import moderngl
    from PIL import Image,ImageDraw,ImageFont
    from check_cloud_evolution import configuration
    from shared_transport import load_transport
    from scatter_material import load_scatter
    from match_compare import Comparison
    from fit_particle_optics import measure
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    changed=dict(kw)
    if components=='both' and (root/'conditional-material-rules.json').is_file():
        candidate_cls,changed=configuration(root)
        if candidate_cls!=cls:raise ValueError('完整候选与基准必须使用同一渲染层级')
    if components in ['both','motion']:changed['transport_weights']=load_transport(root/'conditional-transport-weights.json')
    if components in ['both','material']:changed['scatter']=load_scatter(root/'particle-scatter-weights.json')
    out=root if components=='both' else root/f'review-{components}'
    if all_frames:out=out/'full-animation'
    out.mkdir(parents=True,exist_ok=True)
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in scenes or OBSERVED_SITES:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]])
        old=cls(name,**args,**kw);new=cls(name,**args,**changed)
        tw=285;th=round(old.h*tw/old.w);sheet=Image.new('RGB',(tw*5,(th+26)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        montage={c.ref.nearest(p)[0]:j for j,p in enumerate([.18,.29,.41,.58,.77])}
        indices=c.index if all_frames else list(montage)
        for i in indices:
            ref=np.asarray(c.ref.frames[i]);p=float(c.ref.progress[i]);before=old.render(p);after=new.render(p);row=dict(phase=p)
            if c.scene.roi.any():
                target=measure(ref,c.scene.roi)
                for stage,f in [('before',before),('after',after)]:row[stage]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            else:
                from prepare_uncertain_grain_cache import high_frequency,observed_mask
                mask=observed_mask(c.scene.meta,ref.shape)
                target=high_frequency(ref)
                row['observable_high_frequency']={stage:float((abs(high_frequency(f)-target)*mask[...,None]).sum()/mask.sum()/3*255) for stage,f in [('before',before),('after',after)]}
            if i in montage:
                j=montage[i]
                for k,(title,f) in enumerate([('华为原片',ref),('当前默认',before),('共同位置与材质实验',after)]):
                    sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26))
                    draw.text((j*tw+3,k*(th+26)+2),f'{title} {p:.3f}',font=font,fill='white')
            rows.append(row)
        sheet.save(out/f'{name}.jpg',quality=94);old.close();new.close();report[name]=rows
        print(name,{stage:{key:float(np.mean([row[stage][key] for row in rows])) for key in ['rgb_4','grain']} for stage in ['before','after']} if c.scene.roi.any() else {'仅局部高频，背景未知':{stage:float(np.mean([row['observable_high_frequency'][stage] for row in rows])) for stage in ['before','after']}},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='complete-paths');ap.add_argument('--steps',type=int,default=4500)
    ap.add_argument('--cache',type=Path,default=HERE/'analysis/match-cache/particle-transport/particles-fa3198331bd76d22.pkl')
    ap.add_argument('--grain',type=Path,default=HERE/'analysis/match-cache/transport-capacity/all-reference-grain/grain-targets.npz')
    ap.add_argument('--grain-responses',type=Path,help='用真实微扰导数校准材质的颗粒响应，位置项仍用逐材料散布')
    ap.add_argument('--traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    ap.add_argument('--review',type=Path);ap.add_argument('--resume',type=Path)
    ap.add_argument('--review-components',choices=['both','motion','material'],default='both');ap.add_argument('--scenes',nargs='+')
    ap.add_argument('--all-frames',action='store_true',help='对全部独立参考帧测量，而非只选五个相位')
    ap.add_argument('--motion-weight',type=float,default=.5);ap.add_argument('--trust',type=float,default=.025)
    ap.add_argument('--synchrony-guard',action='store_true',help='直接约束短时间内的共同换向及新增逆风速度')
    ap.add_argument('--cohort-synchrony-guard',action='store_true',help='同时检查几个持续可见的固定材料区间，避免出生与渐隐稀释共同换向')
    ap.add_argument('--synchrony-reduction',type=float,default=0.,help='共同换向的软诊断量相对原值的降低目标，不是视觉完成率')
    ap.add_argument('--native-path-clock',action='store_true',help='约束实际 120 Hz 材料路径，按运行时的 41 层纹理作时间插值')
    ap.add_argument('--freeze-material',action='store_true',help='固定当前默认材质，只导出共同运动的修改')
    ap.add_argument('--contrast-weight',type=float,default=0.,help='额外约束中尺度颜色差分，避免低频云团均值掩盖亮暗弧边；所有参考共用')
    ap.add_argument('--path-contrast',type=Path,help='按真实材料画面对比贡献同时约束共同换向；不替代按数量检查')
    ap.add_argument('--temporal-image-weight',type=float,default=0.,help='直接对齐原片连续画面变化；未知背景只比较高频变化')
    ap.add_argument('--base-root',type=Path,default=HERE,help='缓存、路径与材质导数共同使用的配置目录')
    ap.add_argument('--regional-weight',type=float,default=0.,help='另约束最差连续区域的结构与高频误差；不在运行模型中记录区域')
    ap.add_argument('--acceleration-weight',type=float,default=1.,help='基准逐材料加速度上界的实验权重；其它往返、逆风、同步约束不变')
    ap.add_argument('--image-capacity-only',action='store_true',help='仅作容量诊断：解除旧路径和逐帧不退步限制，保留负雅可比约束；禁止直接采用')
    a=ap.parse_args()
    if not np.isfinite(a.acceleration_weight) or a.acceleration_weight<0:ap.error('加速度权重必须是非负有限值')
    if a.review:return review(a.review,a.review_components,a.scenes,a.all_frames)
    if a.path_contrast and not (a.native_path_clock and a.synchrony_guard and a.cohort_synchrony_guard):
        ap.error('--path-contrast 需要实际时钟、共同换向和固定材料组检查')
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    import torch
    import torch.nn.functional as F
    from check_particle_proxy import Splat
    from particle_grain_proxy import alpha_moments,variance
    torch.set_num_threads(4);torch.manual_seed(930)
    s=Splat(n=160);to=lambda v:torch.as_tensor(np.asarray(v,np.float32),device='cuda')
    motion_config=json.loads((a.base_root/'conditional-transport-weights.json').read_text('utf-8'))
    material_config=json.loads((a.base_root/'particle-scatter-weights.json').read_text('utf-8'))
    for name in ['conditional-release-weights.json','conditional-material-rules.json','surface-transfer-rules.json',
                 'particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json']:
        shutil.copy2(a.base_root/name,out/name)
    if a.path_contrast:
        expected=hashlib.sha256((a.base_root/'conditional-transport-weights.json').read_bytes()+(a.base_root/'particle-scatter-weights.json').read_bytes()).hexdigest()
        assert json.loads((a.path_contrast/'report.json').read_text('utf-8'))['identity']==expected,'路径对比权重与当前默认不一致'
    assert not motion_config.get('residual') and not motion_config.get('coupling')
    def network(config):
        n0,n1=[len(v['bias']) for v in config['layers'][:2]]
        net=torch.nn.Sequential(torch.nn.Linear(59,n0),torch.nn.Tanh(),torch.nn.Linear(n0,n1),torch.nn.Tanh(),torch.nn.Linear(n1,3)).cuda()
        with torch.no_grad():
            for layer,vals in zip([v for v in net if isinstance(v,torch.nn.Linear)],config['layers']):
                layer.weight.copy_(to(vals['weight']));layer.bias.copy_(to(vals['bias']))
        return net
    motion=network(motion_config);material=network(material_config)
    density_original=copy.deepcopy(motion)
    for param in density_original.parameters():param.requires_grad_(False)
    # 密度所在支路保持逐权重不变；不需要导出额外的并联网络。
    layers=[v for v in motion if isinstance(v,torch.nn.Linear)]
    protect2=(layers[2].weight[2].detach()!=0)
    protect1=(layers[1].weight.detach()[protect2]!=0).any(0)
    frozen=[]
    for param,mask in [(layers[0].weight,protect1[:,None].expand_as(layers[0].weight)),(layers[0].bias,protect1),
                       (layers[1].weight,protect2[:,None].expand_as(layers[1].weight)),(layers[1].bias,protect2),
                       (layers[2].weight,to([[0],[0],[1]]).bool().expand_as(layers[2].weight)),(layers[2].bias,to([0,0,1]).bool())]:
        frozen.append((param,mask,param.detach().clone()))
        param.register_hook(lambda grad,mask=mask:grad.masked_fill(mask,0))
    scales=to(material_config['scales'])
    def grid(n):
        yy,xx=np.mgrid[:n,:n].astype(np.float32)
        return LO+(xx+.5)/n*SIZE,LO+(yy+.5)/n*SIZE
    xx,yy=grid(FIELD);mx,my=grid(MATERIAL_FIELD)
    basegrid=to(np.stack([(xx-LO)/SIZE*2-1,(yy-LO)/SIZE*2-1],-1))[None]
    names=list(OBSERVED_SITES);geometry={};paths=[]
    gf=[];gm=[];times=[];rots=[];ss=[]
    for ni,name in enumerate(names):
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
        card=np.asarray(meta['rect'][2:])-meta['rect'][:2];angle=math.radians(meta['direction']);c,z=math.cos(angle),-math.sin(angle)
        sites=[Site(*v) for v in OBSERVED_SITES[name]];rot=np.array([[c,z],[-z,c]],np.float32);scale=min(card)/card
        geometry[name]=(card,meta['direction'],sites,rot,scale)
        saved=np.load(a.traces/f'{name}.npz');raw=trace_path(saved,seed=2930,maximum=2048 if a.synchrony_guard else 768,cohort_samples=768 if a.cohort_synchrony_guard else 0,native_clock=a.native_path_clock)
        p={k:to(v) if isinstance(v,np.ndarray) else v for k,v in raw.items()}
        if a.path_contrast:
            observed=np.load(a.path_contrast/f'{name}.npz')
            assert np.array_equal(observed['source'],saved['source'])
            intervals=[(.20,.75),(.20,.45),(.30,.70),(.50,.75)]
            weights=[]
            for begin,end in intervals:
                take=(observed['phases']>=begin-1e-6)&(observed['phases']<=end+1e-6)
                weight=observed['weights'][take].mean(0)[raw['material_indices']]
                weights.append(weight/max(float(weight.mean()),1e-12))
            p['contrast_weights']=to(np.asarray(weights))
        p['ids']=[len(gf)+int(i) for i in (range(41) if a.native_path_clock else raw['indices'])]
        if a.native_path_clock:
            clock=(p['phase']*40).clamp(0,40);p['field_lo']=clock.floor().long();p['field_hi']=(p['field_lo']+1).clamp(max=40)
            p['field_mix']=(clock-p['field_lo'])[:,None,None,None]
        p['visible']=(F.pad(p['pair'],(0,0,1,0))+F.pad(p['pair'],(0,0,0,1))).clamp(max=1).bool()
        age=(p['age']/.03).clamp(0,1);p['warm']=age.square()*(3-2*age)
        paths.append(p)
        for phase in np.linspace(0,1,41,dtype=np.float32):
            gf.append(transport_features(xx,yy,phase,*card,meta['direction'],sites,True))
            gm.append(transport_features(mx,my,phase,*card,meta['direction'],sites,True))
            times.append(phase);rots.append(rot);ss.append(scale)
    gf,gm,gt,gr,gs=map(to,[gf,gm,times,rots,ss])
    data=pickle.loads(a.cache.read_bytes());grain=np.load(a.grain)
    assert list(grain['names'])==[d['name'] for d in data]
    assert np.max(abs(grain['phases']-[d['progress'] for d in data]))<1e-6
    response=np.load(a.grain_responses) if a.grain_responses else None
    if response is not None:
        assert list(response['names'])==[d['name'] for d in data]
        assert np.max(abs(response['phases']-[d['progress'] for d in data]))<1e-6
        assert np.max(abs(response['zero']-grain['actual']))<1e-6,'材质导数与当前缓存的展开点不同'
    rows=[]
    for i,source in enumerate(data):
        d={k:to(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in source.items()}
        card,direction,sites,_,_=geometry[d['name']]
        d['material_features']=to(transport_features(mx,my,float(d['phase']),*card,direction,sites,True))
        d['actual_grain']=to(grain['actual'][i]);d['target_grain']=to(grain['target'][i]);rows.append(d)
        if response is not None:
            d['grain_slope']=to(response['slope'][i]);d['grain_curve']=to(response['curve'][i]);d['grain_eps']=to(response['eps'])
    def field(features,phase,rotation,scale):
        raw=motion(features).tanh()
        v=torch.einsum('bxyc,bcd->bxyd',raw[...,:2],rotation)*(motion_config['displacement_scale']*phase[:,None,None,None])*scale[:,None,None,:]/2**motion_config['flow_steps']
        for _ in range(motion_config['flow_steps']):
            v=v+F.grid_sample(v.permute(0,3,1,2),basegrid+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return v/scale[:,None,None,:]
    def material_field(features):return material(features).tanh()*scales
    def sample(value,query):
        return F.grid_sample(value.permute(0,3,1,2),query[:,None]*2-1,padding_mode='border',align_corners=False)[:,:,0].permute(0,2,1)
    def at_path_clock(value,p):
        if not a.native_path_clock:return value
        return value[p['field_lo']]*(1-p['field_mix'])+value[p['field_hi']]*p['field_mix']
    def det(value,scale):
        v=value*scale[:,None,None,:]
        dx=(v[:,:-1,1:]-v[:,:-1,:-1])*FIELD/SIZE;dy=(v[:,1:,:-1]-v[:,:-1,:-1])*FIELD/SIZE
        return (1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
    def filters(image,d):
        return torch.stack([s.gaussian(image.permute(2,0,1)[None],max(.55,k*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for k in [4.,12.,24.]])
    bands=to([1.,.7,.4])[:,None,None,None]
    def photo(image,d):
        error=filters(image,d)-d['target_filtered'];weight=d['mask'][None,:,:,None]*bands
        return ((error.square()+.003**2).sqrt()*weight).sum()/weight.sum().clamp(min=1)/3
    def contrast(image,d):
        current=filters(image,d)
        value=current[:-1]-current[1:]
        wanted=d['target_filtered'][:-1]-d['target_filtered'][1:]
        error=(value-wanted).abs()*d['mask'][None,:,:,None]
        return error.sum()/d['mask'].sum().clamp(min=1)/6
    def grain_error(image,d):
        mask=d.get('grain_mask',d['mask'])
        return ((abs(image-d['target_grain'])+.0005)*mask[...,None]).sum()/mask.sum().clamp(min=1)/3
    def regional_error(image,grain_image,d):
        # 在完整有效区域内求两种尺度的最差五分之一区域，避免背景或已拟合区域
        # 稀释连贯弧边的误差。不选素材特有位置；排序仅用于离线损失。
        photo_map=(filters(image,d)[0]-d['target_filtered'][0]).abs().mean(-1)
        grain_map=(grain_image-d['target_grain']).abs().mean(-1)
        losses=[]
        for error,mask in [(photo_map,d['mask']),(grain_map,d.get('grain_mask',d['mask']))]:
            levels=[]
            for resolution in [8,16]:
                weight=F.adaptive_avg_pool2d(mask[None,None],resolution)[0,0]
                total=F.adaptive_avg_pool2d((error*mask)[None,None],resolution)[0,0]
                value=(total/weight.clamp(min=.01))[weight>.55]
                levels.append(value.topk(max(1,int(np.ceil(value.numel()*.20)))).values.mean() if value.numel() else error.sum()*0.)
            losses.append(torch.stack(levels).mean())
        return torch.stack(losses)
    def repeat(value,p):
        travel=(abs(value[1:]-value[:-1])*p['pair'][...,None]).sum(0)
        lo=value.masked_fill(~p['visible'][...,None],float('inf')).amin(0)
        hi=value.masked_fill(~p['visible'][...,None],-float('inf')).amax(0)
        return F.relu(travel-2*(hi-lo))
    def synchrony(pos,p):
        display=p['display_phase'];velocity=((pos[1:]-pos[:-1])@p['cross'])/(display[1:]-display[:-1])[:,None]
        if a.native_path_clock:
            # 与独立检查使用相同的约 17 ms 平滑和 50 ms 换向窗口；
            # 使用软方向的窗口跨度，既覆盖正向换反向，也覆盖反向换正向。
            axis=pos.new_tensor(np.arange(-8,9));kernel=torch.exp(-.5*(axis/2).square());kernel/=kernel.sum()
            smoothed=F.conv1d(F.pad(velocity.T[:,None],(8,8),mode='replicate'),kernel.reshape(1,1,-1))[:,0].T
            direction=(smoothed/.015).tanh()
            length=round(.05/float((display[1:]-display[:-1]).median()))+1
            d=direction.T[:,None]
            turn=.5*(F.max_pool1d(d,length,stride=1)+F.max_pool1d(-d,length,stride=1))[:,0].T
            valid=-F.max_pool1d(-p['pair'].T[:,None],length,stride=1)[:,0].T
            groups=[valid]
            if a.cohort_synchrony_guard:
                for cohort,(begin,end) in enumerate([(.20,.45),(.30,.70),(.50,.75)]):
                    window=(display[:len(turn)]>=begin)&(display[length:length+len(turn)]<=end)
                    groups.append(valid*p['cohort_masks'][cohort][None]*window[:,None])
            count=torch.stack([v.sum(1) for v in groups]);fraction=torch.stack([(turn*v).sum(1) for v in groups])/count.clamp(min=1)
            valid=count>=32
            if 'contrast_weights' in p:
                weighted=torch.stack(groups)*p['contrast_weights'][:len(groups),None,:]
                amount=weighted.sum(-1)
                effective=amount.square()/weighted.square().sum(-1).clamp(min=1e-12)
                extra=(weighted*turn[None]).sum(-1)/amount.clamp(min=1e-12)
                fraction=torch.cat([fraction,extra]);valid=torch.cat([valid,valid&(effective>=32)])
            return fraction.flatten(),valid.flatten()
        # 对齐实际路径检查约 17 ms 的低通尺度；去掉接近零的方向噪声。
        smoothed=F.conv1d(F.pad(velocity.T[:,None],(1,1),mode='replicate'),pos.new_tensor([.20,.60,.20]).reshape(1,1,3))[:,0].T
        direction=(smoothed/.025).tanh()
        turn=.5*abs(direction[2:]-direction[:-2])
        valid=p['pair'][2:]*p['pair'][1:-1]*p['pair'][:-2]
        groups=[valid]
        if a.cohort_synchrony_guard:
            times=display
            for cohort,(begin,end) in enumerate([(.20,.45),(.30,.70),(.50,.75)]):
                stable=p['cohort_masks'][cohort]
                window=(times[1:-2]>=begin+.025)&(times[1:-2]<=end-.025)
                groups.append(valid*stable[None]*window[:,None])
        fractions=[];checked=[]
        for group in groups:
            count=group.sum(1)
            fractions.append((turn*group).sum(1)/count.clamp(min=1));checked.append(count>=32)
        return torch.cat(fractions),torch.cat(checked)
    with torch.no_grad():
        anchors=torch.cat([field(gf[i:i+8],gt[i:i+8],gr[i:i+8],gs[i:i+8]) for i in range(0,len(gf),8)])
        oldmat=torch.cat([material_field(gm[i:i+8]) for i in range(0,len(gm),8)])
        determinant=det(anchors,gs)
        for p in paths:
            ids=p['ids'];p['anchor']=sample(at_path_clock(anchors[ids],p),p['query'])
            q=(p['base']*p['scale']+ .38)/SIZE
            p['material_anchor']=sample(at_path_clock(oldmat[ids],p),q)/scales
            p['repeat_anchor']=repeat(p['material_anchor'],p)
            p['synchrony_anchor'],p['synchrony_valid']=synchrony(p['base'],p)
        for d in rows:
            f=field(d['features'][None],d['phase'].reshape(1),d['rotation'][None],d['scale'][None])
            d['anchor']=sample(f,d['query'][None])[0];d['material_anchor']=material_field(d['material_features'])
            d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
            mass=s.material_mass(d,torch.zeros_like(d['density']));d['render_anchor']=s.render(d['pos'],mass,d['color'],d['base'])
            d['target_filtered']=filters(d['target'],d);d['prior']=photo(d['actual'],d)
            d['contrast_prior']=contrast(d['actual'],d)
            m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0])
            d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base']);d['grain_prior']=grain_error(d['actual_grain'],d)
            if a.regional_weight:d['regional_prior']=regional_error(d['actual'],d['actual_grain'],d)
    # 展开点始终是实际缓存的原默认，继续拟合不能把候选误当成原缓存。
    if a.resume:
        for model,filename in [(motion,'conditional-transport-weights.json'),(material,'particle-scatter-weights.json')]:
            if a.freeze_material and model is material:continue
            config=json.loads((a.resume/filename).read_text('utf-8'))
            with torch.no_grad():
                for layer,vals in zip([v for v in model if isinstance(v,torch.nn.Linear)],config['layers']):
                    layer.weight.copy_(to(vals['weight']));layer.bias.copy_(to(vals['bias']))
    def output(d,images=False):
        f=field(d['features'][None],d['phase'].reshape(1),d['rotation'][None],d['scale'][None])
        delta=(sample(f,d['query'][None])[0]-d['anchor'])*d['warm'][:,None]
        shift=delta*d['scale']/SIZE;pos=d['pos']+shift;carrier=d['carrier']+shift
        density=s.cohort(d,carrier)-d['cohort_anchor']
        material_delta=material_field(d['material_features'])-d['material_anchor']
        mf=d['scatter']+material_delta
        scatter=s.scatter(dict(d,scatter=mf),carrier)
        mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
        pos=s.fission_position(d,pos,density)
        estimated=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
        if response is None:
            m1,m2=alpha_moments(s,d,density,scatter[:,0])
            observed=d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])
        else:
            old_scatter=s.scatter(d,carrier)
            old_color=s.shade(d['color'],d['scatter_anchor'],old_scatter)
            m1,m2=alpha_moments(s,d,density,old_scatter[:,0])
            observed=d['actual_grain']+.40*(variance(s,pos,m1,m2,old_color,d['base'])-d['grain_anchor'])
            optical_delta=F.interpolate(material_delta.permute(2,0,1)[None],(s.n,s.n),mode='bilinear',align_corners=False)[0].permute(1,2,0)
            observed+=(d['grain_slope']*optical_delta[...,None,:]+d['grain_curve']*optical_delta[...,None,:].square()).sum(-1)
        observed=observed.clamp(min=0)
        if images:return estimated,observed
        pe,ge=photo(estimated,d),grain_error(observed,d)
        ce=contrast(estimated,d) if a.contrast_weight else pe*0
        re=regional_error(estimated,observed,d) if a.regional_weight else torch.stack([pe*0,ge*0])
        trust=F.relu(torch.linalg.vector_norm(delta,dim=-1)/a.trust-1).square().mean()
        if response is not None:trust+=.2*F.relu(abs(optical_delta)/(2*d['grain_eps'])-1).square().mean()
        return pe,ge,trust,ce,re
    # 时差只选择相邻的独立观测；不把近似光流或原模型轨迹当作原片真值。
    temporal_pairs=[]
    def temporal_error(left,right,first,second):
        d,e=rows[left],rows[right];image,grain_image=first;image_next,grain_next=second
        weight=torch.minimum(d['mask'],e['mask'])
        delta=(filters(image_next,e)-filters(image,d))-(e['target_filtered']-d['target_filtered'])
        pe=(abs(delta)*weight[None,:,:,None]*bands).sum()/(weight.sum()*bands.sum()).clamp(min=1)/3
        grain_mask=torch.minimum(d.get('grain_mask',d['mask']),e.get('grain_mask',e['mask']))
        gd=(grain_next-grain_image)-(e['target_grain']-d['target_grain'])
        ge=(abs(gd)*grain_mask[...,None]).sum()/grain_mask.sum().clamp(min=1)/3
        return pe,ge
    if a.temporal_image_weight:
        for name in names:
            ordered=sorted([i for i,d in enumerate(rows) if d['name']==name],key=lambda i:float(rows[i]['progress']))
            for left,right in zip(ordered,ordered[1:]):
                delta=float(rows[right]['progress']-rows[left]['progress'])
                if not .015<=delta<=.075:continue
                d,e=rows[left],rows[right]
                with torch.no_grad():
                    pe,ge=temporal_error(left,right,(d['actual'],d['actual_grain']),(e['actual'],e['actual_grain']))
                temporal_pairs.append((left,right,pe.clamp(min=.001),ge.clamp(min=.0005)))
        assert all(any(rows[p[0]]['name']==name for p in temporal_pairs) for name in names),'每段都需要独立相邻观测'
    def temporal_pair_loss(pair):
        left,right,prior_photo,prior_grain=pair
        pe,ge=temporal_error(left,right,output(rows[left],True),output(rows[right],True))
        photo_weight=rows[left].get('photo_weight',1.)
        return photo_weight*pe/prior_photo+.6*ge/prior_grain
    def path_loss(p):
        ids=p['ids'];value=field(gf[ids],gt[ids],gr[ids],gs[ids])
        delta=(sample(at_path_clock(value,p),p['query'])-p['anchor'])*p['warm'][...,None]
        pos=p['base']+delta
        motion_loss=motion_penalty(torch,pos,p,acceleration_weight=a.acceleration_weight)
        if a.synchrony_guard:
            turn_fraction,valid=synchrony(pos,p)
            increase=F.relu((turn_fraction-p['synchrony_anchor']*(1-a.synchrony_reduction)-.002)/.025)*valid
            motion_loss+=increase.square().sum()/valid.sum().clamp(min=1)+.20*increase.square().max()
            dt=p['delta_time'][:,None] if a.native_path_clock else .025
            reverse=F.relu(-((pos[1:]-pos[:-1])@p['wind'])/dt)
            added=F.relu((reverse-p['reverse']-.0001)/.05)*p['pair']
            motion_loss+=.5*added.square().sum()/p['pair'].sum().clamp(min=1)+.1*added.flatten().topk(64).values.square().mean()
        current=sample(at_path_clock(material_field(gm[ids]),p),(pos*p['scale']+.38)/SIZE)/scales
        excessive=F.relu((repeat(current,p)-p['repeat_anchor']-.003)/.08).square()
        material_loss=excessive.mean()+.15*excessive.flatten().topk(min(64,excessive.numel())).values.mean()
        diff=current-p['material_anchor'];trip=p['triple'][...,None]
        material_loss+=.02*((diff[2:]-2*diff[1:-1]+diff[:-2]).square()*trip).sum()/trip.sum().clamp(min=1)
        jac=det(value,gs[ids])
        floor=jac.new_tensor(0.) if a.image_capacity_only else torch.minimum(determinant[ids]*.9,jac.new_tensor(.12))
        penalty=F.relu(floor-jac).square()
        jacobian=10*penalty.mean()+3*penalty.flatten().topk(64).values.mean()
        return motion_loss,material_loss,jacobian
    ids_scene={name:[i for i,d in enumerate(rows) if d['name']==name] for name in dict.fromkeys(d['name'] for d in rows)}
    if a.freeze_material:
        for param in material.parameters():param.requires_grad_(False)
    parameters=list(motion.parameters())+([] if a.freeze_material else list(material.parameters()))
    opt=torch.optim.Adam(parameters,lr=.00018);history=[];best=float('inf');start=time.monotonic()
    regression_weight=0. if a.image_capacity_only else 2.
    def save(row):
        for net,config,filename in [(motion,motion_config,'conditional-transport-weights.json'),(material,material_config,'particle-scatter-weights.json')]:
            result=dict(config,selection=row,method='现有共同运动与材质联合校准；完整可见路径约束；未经过实际渲染验收')
            result['layers']=[dict(weight=l.weight.detach().tolist(),bias=l.bias.detach().tolist()) for l in net if isinstance(l,torch.nn.Linear)]
            (out/filename).write_text(json.dumps(result,ensure_ascii=False),'utf-8')
    print('共同联合拟合',len(rows),'帧',len(paths),'组完整材料路径',flush=True)
    if temporal_pairs:print('原片连续变化',len(temporal_pairs),'对独立画面；两段未知背景仅使用高频',flush=True)
    if a.cohort_synchrony_guard:print('三个固定区间的材料数',{name:[int(v) for v in p['cohort_masks'].sum(-1).tolist()] for name,p in zip(names,paths)},flush=True)
    for step in range(a.steps):
        for group in opt.param_groups:group['lr']=.00018*(.12+.88*.5*(1+np.cos(np.pi*step/a.steps)))
        ids=torch.randint(len(rows),(2,)).tolist();opt.zero_grad();loss=0.
        for i in ids:
            d=rows[i];pe,ge,trust,ce,re=output(d)
            pw=d.get('photo_weight',1.)
            loss+=(pw*(pe/d['prior'].clamp(min=.003)+regression_weight*F.relu(pe/d['prior'].clamp(min=1e-5)-1))
                   +.6*ge/d['grain_prior'].clamp(min=.002)+regression_weight*F.relu(ge/d['grain_prior'].clamp(min=1e-5)-1)+2*trust
                   +a.contrast_weight*pw*ce/d['contrast_prior'].clamp(min=.002))/len(ids)
            if a.regional_weight:
                ratio=re/d['regional_prior'].clamp(min=.002)
                loss+=a.regional_weight*(pw*ratio[0]+.6*ratio[1])/len(ids)
        p=paths[step%len(paths)];ml,al,jac=path_loss(p)
        loss+=jac if a.image_capacity_only else a.motion_weight*ml+.7*al+jac
        if temporal_pairs:
            pair=temporal_pairs[int(torch.randint(len(temporal_pairs),(1,)))]
            loss+=a.temporal_image_weight*temporal_pair_loss(pair)
        loss.backward();torch.nn.utils.clip_grad_norm_(parameters,2.);opt.step()
        with torch.no_grad():
            for param,mask,value in frozen:param[mask]=value[mask]
        if (step+1)%500==0 or step==0:
            with torch.no_grad():
                errs=[]
                for d in rows:
                    pe,ge,_,ce,re=output(d);errs.append([float(pe/d['prior'].clamp(min=1e-5)) if d.get('photo_weight',1.) else 1.,float(ge/d['grain_prior']),float(ce/d['contrast_prior'].clamp(min=1e-5)) if d.get('photo_weight',1.) and a.contrast_weight else 1.])
                costs=np.array([[float(v) for v in path_loss(p)] for p in paths])
                ratios={name:np.mean([errs[i] for i in ids],0).tolist() for name,ids in ids_scene.items()}
                means=np.array(list(ratios.values()));score=float(means[:,0].mean()+.6*means[:,1].mean()+regression_weight*np.maximum(means[:,:2]-1,0).mean()+a.contrast_weight*means[:,2].mean())
                score+=float(costs[:,2].mean()) if a.image_capacity_only else float(a.motion_weight*costs[:,0].mean()+.7*costs[:,1].mean())
                error=float(abs(motion(gf[::41])[...,2]-density_original(gf[::41])[...,2]).max())
                assert error==0.,('密度发生变化',error)
                regional_ratios={}
                if a.regional_weight:
                    for name,scene_indices in ids_scene.items():
                        values=[]
                        for i in scene_indices:
                            d=rows[i];im,grain_im=output(d,True)
                            ratio=regional_error(im,grain_im,d)/d['regional_prior'].clamp(min=.002)
                            values.append([float(ratio[0]) if d.get('photo_weight',1.) else None,float(ratio[1])])
                        regional_ratios[name]=[float(np.mean([v[k] for v in values if v[k] is not None])) if any(v[k] is not None for v in values) else None for k in [0,1]]
                    score+=a.regional_weight*(np.mean([v[0] for v in regional_ratios.values() if v[0] is not None])+.6*np.mean([v[1] for v in regional_ratios.values()]))
                temporal_ratios={}
                if temporal_pairs:
                    for name in names:
                        values=[float(temporal_pair_loss(pair)) for pair in temporal_pairs if rows[pair[0]]['name']==name]
                        temporal_ratios[name]=float(np.mean(values))
                    score+=a.temporal_image_weight*np.mean(list(temporal_ratios.values()))
            row=dict(step=step+1,score=score,ratios=ratios,path_costs=dict(zip(names,costs.tolist())),density_max_error=error)
            if temporal_pairs:row['temporal_ratios']=temporal_ratios
            if a.regional_weight:row['regional_ratios']=regional_ratios
            history.append(row);print(step+1,round(time.monotonic()-start,1),'score',round(score,4),'path',np.round(costs.mean(0),4).tolist(),{k:np.round(v,3).tolist() for k,v in ratios.items()},flush=True)
            if score<best:best=score;save(row)
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    (out/'inputs.json').write_text(json.dumps(dict(cache=str(a.cache),grain=str(a.grain),grain_responses=str(a.grain_responses) if a.grain_responses else None,motion_weight=a.motion_weight,trust=a.trust,synchrony_guard=a.synchrony_guard,synchrony_reduction=a.synchrony_reduction,cohort_synchrony_guard=a.cohort_synchrony_guard,native_path_clock=a.native_path_clock,freeze_material=a.freeze_material,resume=str(a.resume) if a.resume else None,
        contrast_weight=a.contrast_weight,regional_weight=a.regional_weight,acceleration_weight=a.acceleration_weight,image_capacity_only=a.image_capacity_only,path_contrast=str(a.path_contrast) if a.path_contrast else None,temporal_image_weight=a.temporal_image_weight,temporal_pairs=len(temporal_pairs),base_root=str(a.base_root.resolve()),common_inputs='现有几何与进度，无素材身份',source_identity=hashlib.sha256((a.base_root/'conditional-transport-weights.json').read_bytes()+(a.base_root/'particle-scatter-weights.json').read_bytes()).hexdigest()),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
