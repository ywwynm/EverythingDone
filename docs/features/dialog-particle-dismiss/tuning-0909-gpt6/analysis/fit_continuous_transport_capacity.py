"""连续输运的离线容量诊断：联合整段时间拟合，不导出逐素材运行规则。"""
from pathlib import Path
import argparse, json, pickle, sys, time
import cv2, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_particle_proxy import Splat, LO, SIZE
from shared_transport import correction, load_transport
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

FIELD=56
CONTROL=18
KNOTS=9
MAGNITUDE=.40
STEPS=7


def time_basis(times):
    p=np.asarray(times);u=np.clip(p,0,1)*(KNOTS-3)
    i=np.minimum(np.floor(u).astype(int),KNOTS-4);v=u-i
    weights=np.stack([(1-v)**3,3*v**3-6*v*v+4,-3*v**3+3*v*v+3*v+1,v**3],-1)/6
    result=np.zeros((*p.shape,KNOTS),np.float32)
    for k in range(4):np.put_along_axis(result,(i+k)[...,None],weights[...,k:k+1],-1)
    return result


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--cache',type=Path,required=True)
    ap.add_argument('--label',default='continuous-capacity')
    ap.add_argument('--steps',type=int,default=2400)
    ap.add_argument('--review',action='store_true')
    ap.add_argument('--prepare-grain',action='store_true')
    ap.add_argument('--grain',type=Path)
    ap.add_argument('--full-paths',action='store_true',help='统一使用早、中、晚三段材料窗口与提炼阶段的连续约束')
    ap.add_argument('--all-visible-paths',action='store_true',help='覆盖不同释放时刻材料的全部可见区间')
    ap.add_argument('--traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    a=ap.parse_args();out=HERE/'analysis/match-cache/transport-capacity'/a.label
    out.mkdir(parents=True,exist_ok=True)
    if a.prepare_grain:return prepare_grain(a.cache,out)
    if a.review:return review(out)
    data=pickle.loads(a.cache.read_bytes());names=list(dict.fromkeys(d['name'] for d in data))
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(930)
    s=Splat(n=160);t=s.tensor
    rows=[{k:t(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in d.items()} for d in data]
    if a.grain:
        from particle_grain_proxy import alpha_moments,variance
        grain=np.load(a.grain)
        assert list(grain['names'])==[d['name'] for d in data]
        assert np.max(abs(grain['phases']-[d['progress'] for d in data]))<1e-6
        for i,d in enumerate(rows):
            d['actual_grain']=t(grain['actual'][i]);d['target_grain']=t(grain['target'][i])
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32)
    px=LO+(xx+.5)/FIELD*SIZE;py=LO+(yy+.5)/FIELD*SIZE
    grid=t(np.stack([(xx+.5)/FIELD*2-1,(yy+.5)/FIELD*2-1],-1))[None]
    weights=load_transport(HERE/'conditional-transport-weights.json')
    for d,source in zip(rows,data):
        meta=json.loads((HERE/'assets'/d['name']/'scene.json').read_text('utf-8'))
        d['baseline_field']=t(correction(px,py,float(source['phase']),*source['card'],meta['direction'],[Site(*v) for v in OBSERVED_SITES[d['name']]],weights))
        d['basis']=t(time_basis(source['phase']));d['sid']=names.index(d['name'])
    controls=torch.nn.Parameter(torch.zeros((len(names),KNOTS,CONTROL,CONTROL,2),device='cuda'))

    def field(sid,basis,phase,scale):
        raw=torch.einsum('tk,kxyc->txyc',basis,controls[sid])
        raw=F.interpolate(raw.permute(0,3,1,2),size=(FIELD,FIELD),mode='bilinear',align_corners=False).permute(0,2,3,1).tanh()
        v=raw*MAGNITUDE*phase[:,None,None,None]*scale[None,None,None,:]/2**STEPS
        for _ in range(STEPS):
            v=v+F.grid_sample(v.permute(0,3,1,2),grid+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return v/scale[None,None,None,:]

    def lookup(value,query):
        return F.grid_sample(value.permute(2,0,1)[None],query[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T

    def filters(image,d):
        return torch.stack([s.gaussian(image.permute(2,0,1)[None],max(.55,q*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]])

    def photo(image,d):
        delta=filters(image,d)-d['target_filtered'];w=d['mask'][None,:,:,None]*t([1.,.7,.4])[:,None,None,None]
        return ((delta.square()+.003**2).sqrt()*w).sum()/w.sum().clamp(min=1)/3

    with torch.no_grad():
        for d in rows:
            d['cohort_anchor']=s.cohort(d,d['carrier']);d['scatter_anchor']=s.scatter(d,d['carrier'])
            d['mass']=s.material_mass(d,torch.zeros_like(d['density']))
            d['render_anchor']=s.render(d['pos'],d['mass'],d['color'],d['base'])
            d['target_filtered']=filters(d['target'],d);d['prior']=photo(d['actual'],d)
            if a.grain:
                m1,m2=alpha_moments(s,d,torch.zeros_like(d['density']),d['scatter_anchor'][:,0])
                d['grain_anchor']=variance(s,d['pos'],m1,m2,d['color'],d['base'])
                d['grain_prior']=((abs(d['actual_grain']-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3

    paths=[];rng=np.random.default_rng(930)
    for sid,name in enumerate(names):
        q=np.load(a.traces/f'{name}.npz');tr=q['trace'];card=q['rect'][2:]-q['rect'][:2];span=float(q['span'])
        if a.all_visible_paths:
            from material_motion_constraints import trace_path,motion_penalty
            source=trace_path(q);p={key:t(value) if isinstance(value,np.ndarray) else value for key,value in source.items()}
            meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));sites=[Site(*v) for v in OBSERVED_SITES[name]]
            p.update(sid=sid,basis=t(time_basis(source['phase'])),baseline_field=t(np.stack([correction(px,py,float(time),*card,meta['direction'],sites,weights)[...,:2] for time in source['phase']])))
            paths.append(p);continue
        if a.full_paths:
            ts=tr[:,0,4]-tr[0,0,4]
            meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
            sites=[Site(*v) for v in OBSERVED_SITES[name]]
            for start,stop in [(4,18),(10,28),(18,34)]:
                times=np.arange(start,stop,dtype=np.float32)/40
                hi=np.clip(np.searchsorted(ts,times),1,len(ts)-1);lo=hi-1
                fraction=((times-ts[lo])/(ts[hi]-ts[lo]))[:,None,None]
                tq=tr[lo]*(1-fraction)+tr[hi]*fraction
                live=((tq[:,:,4]>.04)&(tq[:,:,4]<tq[:,:,5]-.055)&(tq[:,:,6]<.6)).all(0)&q['mask']
                take=np.flatnonzero(live)
                if len(take)<16:continue
                take=rng.choice(take,min(len(take),256),replace=False);tq=tq[:,take]
                cross=np.array([-q['wind'][1],q['wind'][0]]);positions=tq[:,:,2:4]/span;lat=positions@cross
                base_field=np.stack([correction(px,py,float(p),*card,meta['direction'],sites,weights)[...,:2] for p in times])
                paths.append(dict(sid=sid,basis=t(time_basis(times)),phase=t(times),scale=t(span/card),
                    query=t((tq[:,:,:2]/card-LO)/SIZE),base=t(positions),cross=t(cross),wind=t(q['wind']),
                    excess=t(abs(np.diff(lat,axis=0)).sum(0)-abs(lat[-1]-lat[0])),
                    acceleration=t(np.linalg.norm(np.diff(positions,n=2,axis=0)/.025**2,axis=-1)),baseline_field=t(base_field)))
            continue
        # 年龄差对应内部时钟，不是经过共同时间重映射之前的播放进度。
        clock=tr[:,0,4]-tr[0,0,4];ids=np.flatnonzero((q['phases']>=.26)&(q['phases']<=.72))[::3]
        live=((tr[ids,:,4]>.04)&(tr[ids,:,4]<tr[ids,:,5]-.055)&(tr[ids,:,6]<.6)).all(0)&q['mask']
        take=np.flatnonzero(live)
        if len(take)<16:continue
        take=rng.choice(take,min(len(take),256),replace=False);positions=tr[ids][:,take,2:4]/span
        cross=np.array([-q['wind'][1],q['wind'][0]])
        lateral=positions@cross;excess=abs(np.diff(lateral,axis=0)).sum(0)-abs(lateral[-1]-lateral[0])
        paths.append(dict(sid=sid,basis=t(time_basis(clock[ids])),phase=t(clock[ids]),scale=t(span/card),
            query=t((tr[ids][:,take,2:4]/card-LO)/SIZE),base=t(positions),cross=t(cross),wind=t(q['wind']),excess=t(excess)))

    def path_loss(p):
        f=field(p['sid'],p['basis'],p['phase'],p['scale'])
        if a.full_paths or a.all_visible_paths:
            shifted=grid+p['baseline_field']*p['scale'][None,None,None,:]*2/SIZE
            f=F.grid_sample(f.permute(0,3,1,2),shifted,padding_mode='border',align_corners=False).permute(0,2,3,1)
        move=F.grid_sample(f.permute(0,3,1,2),p['query'][:,None]*2-1,padding_mode='border',align_corners=False)[:,:,0].permute(0,2,1)
        pos=p['base']+move;lat=pos@p['cross']
        if a.all_visible_paths:return motion_penalty(torch,pos,p)
        excess=abs(lat[1:]-lat[:-1]).sum(0)-abs(lat[-1]-lat[0])
        if a.full_paths:
            added=F.relu(excess-p['excess']*1.025-.0003)/.012
            velocity=(pos[1:]-pos[:-1])/.025
            accel=(pos[2:]-2*pos[1:-1]+pos[:-2])/.025**2
            acceleration=F.relu(torch.linalg.vector_norm(accel,dim=-1)-p['acceleration']*1.05-.10)
            return added.square().mean()+.5*added.topk(min(32,len(added))).values.square().mean()+.05*acceleration.square().mean()+.2*F.relu(-(velocity@p['wind'])-.04).square().mean()
        added=F.relu(excess-p['excess']*1.05-.0005)/.012
        dt=p['phase'][1:]-p['phase'][:-1]
        velocity=(pos[1:]-pos[:-1])/dt[:,None,None]
        reverse=F.relu(-(velocity@p['wind'])-.025)
        mvel=(move[1:]-move[:-1])/dt[:,None,None]
        acceleration=(mvel[1:]-mvel[:-1])/((dt[1:]+dt[:-1])*.5)[:,None,None]
        return added.square().mean()+.5*added.topk(min(32,len(added))).values.square().mean()+reverse.square().mean()*.2+acceleration.square().mean()*.0005

    def output(i):
        d=rows[i];extra=field(d['sid'],d['basis'][None],d['phase'].reshape(1),d['scale'])[0]
        shifted=grid+d['baseline_field'][None,...,:2]*d['scale']*2/SIZE
        carried=F.grid_sample(extra.permute(2,0,1)[None],shifted,padding_mode='border',align_corners=False)[0].permute(1,2,0)
        delta=lookup(carried,d['query'])*d['warm'][:,None]
        dp=delta*d['scale']/SIZE;pos=d['pos']+dp
        density=s.cohort(d,d['carrier']+dp)-d['cohort_anchor']
        scatter=s.scatter(d,d['carrier']+dp)
        mass=s.material_mass(d,density,scatter[:,0]);color=s.shade(d['color'],d['scatter_anchor'],scatter)
        pos=s.fission_position(d,pos,density)
        image=d['actual']+s.render(pos,mass,color,d['base'])-d['render_anchor']
        grain_error=image.new_tensor(0.)
        if a.grain:
            m1,m2=alpha_moments(s,d,density,scatter[:,0])
            observed=(d['actual_grain']+.40*(variance(s,pos,m1,m2,color,d['base'])-d['grain_anchor'])).clamp(min=0)
            grain_error=((abs(observed-d['target_grain'])+.0005)*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3/d['grain_prior'].clamp(min=.004)
        full=(d['baseline_field'][...,:2]+carried)*d['scale']
        dx=(full[:-1,1:]-full[:-1,:-1])*FIELD/SIZE;dy=(full[1:,:-1]-full[:-1,:-1])*FIELD/SIZE
        jac=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        return image,carried,jac,grain_error

    opt=torch.optim.Adam([controls],lr=.012);start=time.monotonic();history=[];best=float('inf')
    for step in range(a.steps):
        for g in opt.param_groups:g['lr']=.012*(.08+.92*.5*(1+np.cos(np.pi*step/a.steps)))
        ids=torch.randint(len(rows),(2,)).tolist();opt.zero_grad();loss=0.
        for i in ids:
            d=rows[i];image,delta,jac,grain_error=output(i);penalty=F.relu(.12-jac).square().flatten()
            loss+=photo(image,d)/d['prior'].clamp(min=.012)+8*penalty.mean()+5*penalty.topk(24).values.mean()
            if a.grain:loss+=.6*grain_error+2*F.relu(grain_error-1)
            loss+=.008*((delta[1:]-delta[:-1]).square().mean()+(delta[:,1:]-delta[:,:-1]).square().mean())*(FIELD/SIZE)**2
        loss=loss/len(ids)+.5*path_loss(paths[int(torch.randint(len(paths),(1,)))])
        loss.backward();torch.nn.utils.clip_grad_norm_([controls],2.);opt.step()
        if (step+1)%300==0:
            with torch.no_grad():
                ratios=[];grain_ratios=[];minimum=1.
                for i,d in enumerate(rows):
                    image,delta,jac,grain_error=output(i);ratios.append(float(photo(image,d)/d['prior']));minimum=min(minimum,float(jac.min()));grain_ratios.append(float(grain_error))
                temporal=float(torch.stack([path_loss(p) for p in paths]).mean())
            score=float(np.mean(ratios))+.5*temporal
            if a.grain:score+=.6*np.mean(grain_ratios)+2*np.maximum(np.asarray(grain_ratios)-1,0).mean()
            row=dict(step=step+1,ratios=ratios,grain_ratios=grain_ratios,minimum_jacobian=minimum,path_cost=temporal,score=score)
            history.append(row);print('整段连续容量',step+1,round(time.monotonic()-start,1),round(score,4),round(minimum,5),round(temporal,4),flush=True)
            if score<best and minimum>.025:
                best=score
                np.savez_compressed(out/'temporal-controls.npz',controls=controls.detach().cpu().numpy(),names=names,magnitude=MAGNITUDE,flow_steps=STEPS)
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    (out/'inputs.json').write_text(json.dumps(dict(cache=str(a.cache),names=names,full_paths=a.full_paths,all_visible_paths=a.all_visible_paths,scope='只作连续容量诊断；不能作为逐素材运行模型'),ensure_ascii=False,indent=2),'utf-8')


def prepare_grain(cache,out):
    import moderngl
    from check_cloud_evolution import configuration
    from match_compare import Comparison
    data=pickle.loads(cache.read_bytes());ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    actual=[];target=[];previous=None;r=None
    for d in data:
        name=d['name']
        if name!=previous:
            if r:r.close()
            c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
            x,y,x1,y1=c.scene.rect;n=d['n'];yy,xx=np.mgrid[:n,:n].astype(np.float32)
            xx=x+(LO+(xx+.5)/n*SIZE)*(x1-x);yy=y+(LO+(yy+.5)/n*SIZE)*(y1-y)
            previous=name;print('实际颗粒对比度目标',name,flush=True)
        _,ref=c.ref.nearest(d['progress']);frame=r.render(d['progress'])
        for image,store in [(ref,target),(frame,actual)]:
            f=image.astype(np.float32)/255
            g=cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.)
            store.append(cv2.remap(g,xx,yy,cv2.INTER_LINEAR))
    r.close();ctx.release()
    path=out/'grain-targets.npz'
    np.savez_compressed(path,actual=actual,target=target,names=[d['name'] for d in data],phases=[d['progress'] for d in data])
    print(path,flush=True)


def review(out):
    import moderngl
    from scipy.ndimage import map_coordinates
    from PIL import Image, ImageDraw, ImageFont
    from scatter_material import ScatterRenderer
    from check_cloud_evolution import configuration
    from match_compare import Comparison
    from fit_particle_optics import measure
    saved=np.load(out/'temporal-controls.npz');controls=saved['controls']
    class Probe(ScatterRenderer):
        vertex_source=ScatterRenderer.vertex_source.replace('void main(){','uniform sampler2D capacity_field;\nvoid main(){',1)
        vertex_source=vertex_source.replace('vec3 transport=texture(transport_field,vec3(q,phase)).xyz;',
            'vec3 transport=texture(transport_field,vec3(q,phase)).xyz;transport.xy+=texture(capacity_field,q).xy;',1)
        def __init__(self,*a,**kw):
            super().__init__(*a,**kw)
            self.capacity=self.ctx.texture((FIELD,FIELD),2,np.zeros((FIELD,FIELD,2),np.float32).tobytes(),dtype='f4')
            self.capacity.filter=(moderngl.LINEAR,moderngl.LINEAR);self.capacity.repeat_x=self.capacity.repeat_y=False;self.program['capacity_field']=12
        def render(self,*a,**kw):self.capacity.use(12);return super().render(*a,**kw)
        def close(self):super().close();self.capacity.release()
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32)
    for sid,name in enumerate(saved['names']):
        c=Comparison(name);r=Probe(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        tw=300;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*5,(th+25)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            index,ref=c.ref.nearest(p);p=float(c.ref.progress[index]);r.capacity.write(np.zeros((FIELD,FIELD,2),np.float32).tobytes());before=r.render(p)
            clock=r.program['time'].value;scale=np.array([r.span/r.cw,r.span/r.ch],np.float32)
            raw=np.einsum('k,kxyc->xyc',time_basis(clock),controls[sid]);raw=np.tanh(cv2.resize(raw,(FIELD,FIELD),interpolation=cv2.INTER_LINEAR))
            v=raw*float(saved['magnitude'])*clock*scale/2**int(saved['flow_steps'])
            for _ in range(int(saved['flow_steps'])):
                v+=np.stack([map_coordinates(v[...,k],[yy+v[...,1]*FIELD/SIZE,xx+v[...,0]*FIELD/SIZE],order=1,mode='nearest') for k in range(2)],-1)
            volume=np.frombuffer(r.transport_tex.read(),np.float32).reshape(41,FIELD,FIELD,3)
            t=clock*40;i=min(40,int(t));base=volume[i]*(1-(t-i))+volume[min(i+1,40)]*(t-i)
            coords=[yy+base[...,1]*scale[1]*FIELD/SIZE,xx+base[...,0]*scale[0]*FIELD/SIZE]
            extra=np.stack([map_coordinates(v[...,k]/scale[k],coords,order=1,mode='nearest') for k in range(2)],-1)
            r.capacity.write(extra.astype(np.float32).tobytes());after=r.render(p);target=measure(ref,c.scene.roi);row=dict(phase=p)
            for k,(label,f) in enumerate([('华为原片',ref),('当前共同模型',before),('连续容量诊断，非运行模型',after)]):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));draw.text((j*tw+2,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={key:float(abs(a-b).mean()) for key,a,b in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        sheet.save(out/f'{name}.jpg',quality=95);r.close();report[name]=rows
        print(name,{stage:{k:float(np.mean([q[stage][k] for q in rows])) for k in ['rgb_4','grain']} for stage in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
