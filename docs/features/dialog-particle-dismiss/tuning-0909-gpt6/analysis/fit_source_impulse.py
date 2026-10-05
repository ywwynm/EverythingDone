"""直接拟合共同的材料年龄运动函数；形状、颗粒与完整材料路径联合约束。"""
from pathlib import Path
import argparse,json,math,pickle,sys,time
import cv2,numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from shared_transport import transport_features
from check_particle_proxy import Splat,LO,SIZE
from particle_grain_proxy import alpha_moments,variance
from material_motion_constraints import trace_path,motion_penalty
from source_impulse import VELOCITY,ACCELERATION,RAMP,FIELD
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',type=Path,required=True);ap.add_argument('--grain',type=Path,required=True)
    ap.add_argument('--label',default='material-age-impulse');ap.add_argument('--steps',type=int,default=4000);ap.add_argument('--width',type=int,default=192)
    ap.add_argument('--review',type=Path);ap.add_argument('--motion-weight',type=float,default=1.5)
    ap.add_argument('--correspondence',type=Path);ap.add_argument('--matching-weight',type=float,default=.10)
    ap.add_argument('--initial',type=Path);a=ap.parse_args();out=HERE/'analysis/match-cache/source-impulse'/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(a.review,out)
    import torch
    import torch.nn.functional as F
    from scipy.spatial import cKDTree
    torch.set_num_threads(4);torch.manual_seed(935)
    s=Splat(n=160);tensor=s.tensor;data=pickle.loads(a.cache.read_bytes());grain=np.load(a.grain)
    assert list(grain['names'])==[d['name'] for d in data]
    names=list(OBSERVED_SITES);target_names=list(dict.fromkeys(d['name'] for d in data))
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);xx=LO+(xx+.5)/FIELD*SIZE;yy=LO+(yy+.5)/FIELD*SIZE
    features=[];rotations=[];scales=[];birth=[];paths=[]
    for ni,name in enumerate(names):
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));card=np.asarray(meta['rect'][2:])-meta['rect'][:2];span=min(card)
        sites=[Site(*v) for v in OBSERVED_SITES[name]]
        features.append(transport_features(xx,yy,0.,*card,meta['direction'],sites,True))
        angle=math.radians(meta['direction']);c,v=math.cos(angle),-math.sin(angle);rotations.append([[c,v],[-v,c]]);scales.append(span/card)
        saved=np.load(HERE/'analysis/match-cache/motion-continuity/all-material-return'/f'{name}.npz')
        source=trace_path(saved,seed=1935);p={k:tensor(v) if isinstance(v,np.ndarray) else v for k,v in source.items()};p['sid']=ni;paths.append(p)
        distance,indices=cKDTree(saved['source']/card).query(np.stack([xx,yy],-1).reshape(-1,2),k=4)
        w=1/np.maximum(distance,.0001)**2;w/=w.sum(-1,keepdims=True)
        birth.append(((-saved['trace'][0,:,4])[indices]*w).sum(-1).reshape(FIELD,FIELD))
    features,rotations,scales,birth=map(tensor,[features,rotations,scales,birth])
    net=torch.nn.Sequential(torch.nn.Linear(59,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,4)).cuda()
    with torch.no_grad():net[-1].weight.zero_();net[-1].bias.zero_()
    if a.initial:
        initial=json.loads(a.initial.read_text('utf-8'))
        with torch.no_grad():
            for layer,saved in zip([v for v in net if isinstance(v,torch.nn.Linear)],initial['layers']):
                layer.weight.copy_(tensor(saved['weight']));layer.bias.copy_(tensor(saved['bias']))

    def fields(ids):
        raw=net(features[ids]).tanh()
        return torch.cat([torch.einsum('bxyc,bcd->bxyd',raw[...,:2],rotations[ids])*VELOCITY,
            torch.einsum('bxyc,bcd->bxyd',raw[...,2:],rotations[ids])*ACCELERATION],-1)

    def sampled(field,query):
        return F.grid_sample(field.permute(2,0,1)[None],query[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T

    def displacement(value,age):
        age=age.clamp(min=0);travel=age.square()/(age+RAMP)
        return value[...,:2]*travel[...,None]+value[...,2:]*(.5*age.square())[...,None]

    def filters(image,d):
        return torch.stack([s.gaussian(image.permute(2,0,1)[None],max(.55,q*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]])

    def photo(image,d):
        delta=filters(image,d)-d['target_filtered'];w=d['mask'][None,:,:,None]*tensor([1.,.7,.4])[:,None,None,None]
        return ((delta.square()+.003**2).sqrt()*w).sum()/w.sum().clamp(min=1)/3

    rows=[]
    with torch.no_grad():
        for i,source in enumerate(data):
            d={key:tensor(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for key,v in source.items()};d['sid']=names.index(d['name'])
            d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
            mass=s.material_mass(d,torch.zeros_like(d['density']));d['render_anchor']=s.render(d['pos'],mass,d['color'],d['base'])
            d['target_filtered']=filters(d['target'],d);d['prior']=photo(d['actual'],d)
            d['actual_grain']=tensor(grain['actual'][i]);d['target_grain']=tensor(grain['target'][i])
            m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0]);d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base'])
            d['grain_prior']=((abs(d['actual_grain']-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3
            rows.append(d)
        if a.correspondence:
            matches=pickle.loads(a.correspondence.read_bytes())
            if len(matches)!=len(rows):raise ValueError('输运对应帧数不一致')
            for d,m in zip(rows,matches):
                if d['name']!=m['name'] or abs(d['progress']-m['phase'])>1e-6:raise ValueError('输运对应缓存的素材或进度不一致')
                values=sampled(tensor(np.concatenate([m['flow'],m['weight'][...,None]],-1)),d['pos'])
                d['match_delta']=values[:,:2]*SIZE/d['scale']
                d['match_weight']=values[:,2]*d['mass'].clamp(max=1)*d['warm']

    def photo_loss(i,value,guidance=False):
        d=rows[i];impulse=sampled(value,d['origin']);delta=displacement(impulse,d['age'])
        dp=delta*d['scale']/SIZE;pos=d['pos']+dp;carrier=d['carrier']+dp
        density=s.cohort(d,carrier)-d['cohort_anchor'];scatter=s.scatter(d,carrier)
        mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
        pos=s.fission_position(d,pos,density)
        image=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
        ratio=photo(image,d)/d['prior'].clamp(min=.012)
        m1,m2=alpha_moments(s,d,density,scatter[:,0])
        detail=(d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])).clamp(min=0)
        grain_error=((abs(detail-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3/d['grain_prior'].clamp(min=.004)
        loss=ratio+.6*grain_error+2*F.relu(ratio-1)+2*F.relu(grain_error-1)
        if guidance and a.correspondence:
            error=(delta-d['match_delta']).square().sum(-1)
            loss+=a.matching_weight*(error*d['match_weight']).sum()/d['match_weight'].sum().clamp(min=1)/.10**2
        return loss

    def path_loss(p,value):
        impulse=sampled(value,p['origin']);move=displacement(impulse[None],p['age'])
        return motion_penalty(torch,p['base']+move,p)

    def topology(value,ids):
        phases=tensor([.15,.30,.45,.60,.80])
        age=(phases[:,None,None,None]-birth[None,ids]).clamp(min=0)
        move=displacement(value[None],age)*scales[None,ids,None,None,:]
        dx=(move[:,:,:-1,1:]-move[:,:,:-1,:-1])*FIELD/SIZE
        dy=(move[:,:,1:,:-1]-move[:,:,:-1,:-1])*FIELD/SIZE
        jac=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        return jac

    guard_ids=[i for i,name in enumerate(names) if name not in target_names]
    optimizer=torch.optim.AdamW(net.parameters(),lr=.0003,weight_decay=.0001);history=[];best=float('inf');start=time.monotonic()
    with torch.no_grad():baseline=float(torch.stack([photo_loss(i,torch.zeros((FIELD,FIELD,4),device='cuda')) for i in range(len(rows))]).mean())
    print('材料年龄共同校准初始画面损失',baseline,flush=True)
    for step in range(a.steps):
        for group in optimizer.param_groups:group['lr']=.0003*(.08+.92*.5*(1+math.cos(math.pi*step/a.steps)))
        optimizer.zero_grad();value=fields(list(range(len(names))))
        indices=torch.randint(len(rows),(2,)).tolist()
        image=torch.stack([photo_loss(i,value[rows[i]['sid']],guidance=True) for i in indices]).mean()
        p=paths[int(torch.randint(len(paths),(1,)))];motion=path_loss(p,value[p['sid']])
        check_ids=torch.randperm(len(names))[:4].tolist();jac=topology(value[check_ids],check_ids);fold=F.relu(.30-jac).square().flatten()
        guard=(value[guard_ids]/.10).square().mean()
        smooth=((value[:,1:]-value[:,:-1]).square().mean()+(value[:,:,1:]-value[:,:,:-1]).square().mean())*(FIELD/SIZE)**2
        loss=image+a.motion_weight*motion+.04*guard+.015*smooth+10*fold.mean()+5*fold.topk(64).values.mean()
        loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),2.);optimizer.step()
        if (step+1)%500==0:
            with torch.no_grad():
                value=fields(list(range(len(names))));photo_score=float(torch.stack([photo_loss(i,value[d['sid']]) for i,d in enumerate(rows)]).mean())
                motion=float(torch.stack([path_loss(p,value[p['sid']]) for p in paths]).mean());minimum=float(topology(value,list(range(len(names)))).min())
                score=photo_score+a.motion_weight*motion
            row=dict(step=step+1,score=score,photo_score=photo_score,baseline_photo_score=baseline,path_cost=motion,minimum_source_jacobian=minimum)
            history.append(row);print('材料年龄共同校准',step+1,round(time.monotonic()-start,1),row,flush=True)
            if score<best and minimum>.10:
                best=score;weights=dict(method='按源位置、方向及起点计算共同速度与加速度，以材料自身释放年龄积分，无全局周期位移',selection=row,
                    velocity_scale=VELOCITY,acceleration_scale=ACCELERATION,ramp=RAMP,motion_weight=a.motion_weight,layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in net if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(weights,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


def review(path,out):
    import moderngl
    from PIL import Image,ImageDraw,ImageFont
    from source_impulse import SourceImpulseRenderer,load_impulse
    from check_cloud_evolution import configuration
    from fit_particle_optics import measure
    from match_compare import Comparison
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);weights=load_impulse(path);report={}
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in OBSERVED_SITES:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        old=cls(name,**args);new=SourceImpulseRenderer(name,impulse=weights,**args)
        tw=290;th=round(old.h*tw/old.w);sheet=Image.new('RGB',(tw*5,(th+26)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);before=old.render(p);after=new.render(p);row=dict(phase=p)
            if c.scene.roi.any():
                target=measure(ref,c.scene.roi)
                for stage,f in [('before',before),('after',after)]:row[stage]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(title,frame) in enumerate([('华为原片',ref),('当前默认',before),('材料年龄校准实验',after)]):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26));draw.text((j*tw+3,k*(th+26)+3),f'{title} {p:.3f}',font=font,fill='white')
            rows.append(row)
        sheet.save(out/f'{name}.jpg',quality=95);old.close();new.close();report[name]=rows
        print(name,{stage:{key:float(np.mean([r[stage][key] for r in rows])) for key in ['rgb_4','grain']} for stage in ['before','after']} if c.scene.roi.any() else '未知背景，只作目视',flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
