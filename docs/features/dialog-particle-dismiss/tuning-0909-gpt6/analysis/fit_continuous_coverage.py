"""当前完整材质下重新测量年龄覆盖；不改材料中心轨迹。

响应包括投影面积、随机保留与九子片交接，避免沿用粗片时代的线性覆盖近似。
所有参考训练同一几何函数；真实路径只用于离线时间约束，不写入运行资源。
"""
from pathlib import Path
import sys, json, hashlib, argparse, time
import numpy as np, cv2, moderngl
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from particle_cohorts import load_cohorts, cohort_field
from check_cloud_evolution import configuration
from shared_transport import transport_features
from fit_particle_optics import NAMES, measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

OUT=HERE/'analysis/match-cache/continuous-coverage'
N=80
PHASES=[.12,.22,.32,.42,.52,.62,.72,.82]


class Probe(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){',
        'uniform vec3 coverage_probe;\nvoid main(){',1)
    vertex_source=vertex_source.replace('dot(cohort,cohort_age)',
        'dot(cohort+coverage_probe,cohort_age)',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw)
        self.program['coverage_probe']=(0.,0.,0.)


def compact(frame,qx,qy):
    f=frame.astype(np.float32)/255
    values=[cv2.GaussianBlur(f,(0,0),s) for s in [2.,5.,12.]]
    for s in [.7,1.5,3.]:
        values.append(cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),s)),(0,0),8.))
    luma=f@np.float32([.2126,.7152,.0722])
    detail=luma-cv2.GaussianBlur(luma,(0,0),1.5)
    for sign in [1.,-1.]:
        for threshold in [.025,.06,.12,.24]:
            tail=1./(1.+np.exp(np.clip(-(sign*detail-threshold)/.015,-30,30)))
            values.append(cv2.GaussianBlur(tail,(0,0),8.)[...,None])
    return cv2.remap(np.concatenate(values,-1),qx,qy,cv2.INTER_LINEAR)


def prepare():
    from export_shared_candidate import signature
    key=hashlib.sha256(signature().encode()+Path(__file__).read_bytes()).hexdigest()[:16]
    OUT.mkdir(parents=True,exist_ok=True);path=OUT/f'response-{key}.npz'
    if path.exists():
        with np.load(path) as z:return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE)
    y,x=np.mgrid[:N,:N].astype(np.float32);x=-.38+(x+.5)/N*1.76;y=-.38+(y+.5)/N*1.76
    data={k:[] for k in ['features','actual','target','slope','curve','prior','mask','scene','phase']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);x0,y0,x1,y1=c.scene.rect;w,h=x1-x0,y1-y0
        sites=[Site(*v) for v in OBSERVED_SITES[name]]
        r=Probe(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x0+x*w;qy=y0+y*h
        mask=cv2.remap(c.scene.roi.astype(np.float32),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i])
            r.program['coverage_probe']=(0.,0.,0.);actual=compact(r.render(p),qx,qy)
            slopes=[];curves=[];eps=.20
            for k in range(3):
                values=[]
                for sign in [-1,1]:
                    q=[0.,0.,0.];q[k]=eps*sign;r.program['coverage_probe']=q
                    values.append(compact(r.render(p),qx,qy))
                slopes.append((values[1]-values[0])/(2*eps))
                curves.append((values[1]+values[0]-2*actual)/(2*eps**2))
            clock=float(r.program['time'].value)
            row=dict(features=transport_features(x,y,clock,w,h,r.direction,sites,True),
                actual=actual,target=compact(ref,qx,qy),slope=np.stack(slopes,-1),curve=np.stack(curves,-1),
                prior=cohort_field(x,y,clock,w,h,r.direction,sites,kw['cohorts']),mask=mask,scene=ni,phase=clock)
            for k,v in row.items():data[k].append(v)
        r.close();print('完整年龄覆盖响应',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()}
    np.savez_compressed(path,**data);print(path,flush=True);return data


def review(path,out,scenes):
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weights=load_cohorts(frozen)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in scenes:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=cls(name,**args);after=cls(name,**dict(args,cohorts=weights))
        tw=290;th=round(c.scene.H*tw/c.scene.W)
        sheet=Image.new('RGB',(tw*5,(th+27)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for col,p in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);frames=[ref,before.render(p),after.render(p)]
            row=dict(phase=p);target=measure(ref,c.scene.roi)
            for k,(label,f) in enumerate(zip(['华为原片','当前共同模型','重新校准年龄覆盖'],frames)):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+27)+27))
                draw.text((col*tw+2,k*(th+27)+2),f'{label} {p:.3f}',font=font,fill='white')
                if k and c.scene.roi.any():row['before' if k==1 else 'after']={m:float(abs(u-v).mean()) for m,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        print(name,{s:{m:round(float(np.mean([r[s][m] for r in rows])),5) for m in ['rgb_4','grain']} for s in ['before','after']} if c.scene.roi.any() else '仅目视复核',flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='measured-age-coverage');ap.add_argument('--steps',type=int,default=7000)
    ap.add_argument('--review',type=Path);ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES))
    ap.add_argument('--response',type=Path);ap.add_argument('--temporal-weight',type=float,default=.002)
    ap.add_argument('--prepare-only',action='store_true',help='用 everythingdone 生成真实 GPU 响应，CUDA 环境只训练')
    ap.add_argument('--trace-root',type=Path,default=HERE/'analysis/match-cache/motion-continuity/balanced-velocity')
    a=ap.parse_args();out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(a.review,out,a.scenes)
    if a.response:
        with np.load(a.response) as z:data={k:z[k] for k in z.files}
    else:data=prepare()
    if a.prepare_only:return
    import torch
    torch.set_num_threads(4);torch.manual_seed(928)
    def t(v):return torch.from_numpy(np.ascontiguousarray(v,np.float32)).cuda()
    f,actual,target,slope,curve,prior,mask=[t(data[k]) for k in ['features','actual','target','slope','curve','prior','mask']]
    old=json.loads((HERE/'particle-cohort-weights.json').read_text('utf-8'));scale=float(old['scale'])
    sizes=[len(q['bias']) for q in old['layers']]
    model=torch.nn.Sequential(torch.nn.Linear(59,sizes[0]),torch.nn.Tanh(),torch.nn.Linear(sizes[0],sizes[1]),torch.nn.Tanh(),torch.nn.Linear(sizes[1],3)).cuda()
    with torch.no_grad():
        for q,v in zip([q for q in model if isinstance(q,torch.nn.Linear)],old['layers']):q.weight.copy_(t(v['weight']));q.bias.copy_(t(v['bias']))
    channels=t([.7]*3+[1.]*3+[.9]*3+[.55]*3+[.65]*3+[.45]*3+[.15,.18,.2,.2]*2)
    def errors(image,ids):return (torch.sqrt((image-target[ids]).square()+.003**2)*mask[ids,...,None]*channels).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/channels.sum()
    with torch.no_grad():baseline=errors(actual,list(range(len(f))))
    paths=[];ages=[]
    for path in sorted(a.trace_root.glob('*.npz')):
        z=np.load(path);tr=z['trace'];ts=tr[:,0,4]-tr[0,0,4];card=np.array(z['rect'][2:])-z['rect'][:2]
        meta=json.loads((HERE/'assets'/path.stem/'scene.json').read_text('utf-8'));sites=[Site(*s) for s in OBSERVED_SITES[path.stem]]
        rng=np.random.default_rng(928)
        for center in np.arange(.15,.76,.05):
            phases=np.array([center-.05,center,center+.05]);hi=np.clip(np.searchsorted(ts,phases),1,len(ts)-1);lo=hi-1
            fraction=((phases-ts[lo])/(ts[hi]-ts[lo]))[:,None,None];q=tr[lo]*(1-fraction)+tr[hi]*fraction
            live=((q[:,:,4]>.035)&(q[:,:,4]<q[:,:,5]-.06)&(q[:,:,6]<.6)).all(0)&z['mask']
            ids=np.flatnonzero(live)
            if len(ids)<16:continue
            ids=rng.choice(ids,128,replace=len(ids)<128);pos=q[:,ids,2:4]/card
            paths.append(np.stack([transport_features(pos[k,:,0],pos[k,:,1],p,*card,meta['direction'],sites,True) for k,p in enumerate(phases)]))
            age=q[:,ids,4]
            def ease(lo,hi):
                u=np.clip((age-lo)/(hi-lo),0,1);return u*u*(3-2*u)
            mature=ease(.06,.16);elder=ease(.22,.38)
            ages.append(np.stack([1-mature,mature*(1-elder),mature*elder],-1))
    if not paths:raise ValueError('未找到实际材料轨迹')
    paths=t(np.asarray(paths));ages=t(np.asarray(ages))
    with torch.no_grad():path_anchor=(model(paths).tanh()*scale*ages).sum(-1)
    def loss(ids,regular=True):
        values=model(f[ids]).tanh()*scale;delta=values-prior[ids]
        prediction=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        e=errors(prediction,ids)
        if not regular:return e
        spatial=(delta[:,1:]-delta[:,:-1]).square().mean()+(delta[:,:,1:]-delta[:,:,:-1]).square().mean()
        pi=torch.randint(len(paths),(4,),device='cuda')
        carried=(model(paths[pi]).tanh()*scale*ages[pi]).sum(-1)-path_anchor[pi]
        acceleration=(carried[:,2]-2*carried[:,1]+carried[:,0])/.05**2
        return (e/baseline[ids].clamp(min=.008)).mean()+2*torch.relu(e/baseline[ids].clamp(min=.008)-1.005).mean()+.5*spatial+.015*delta.square().mean()+.5*torch.relu(abs(delta)-.35).square().mean()+a.temporal_weight*acceleration.square().mean()/16.
    optimizer=torch.optim.AdamW(model.parameters(),lr=.0004,weight_decay=.001)
    best=1.3;history=[];started=time.monotonic()
    for step in range(a.steps):
        for group in optimizer.param_groups:group['lr']=.0004*(.12+.88*.5*(1+np.cos(np.pi*step/a.steps)))
        ids=torch.randint(len(f),(8,),device='cuda');optimizer.zero_grad();loss(ids).backward();torch.nn.utils.clip_grad_norm_(model.parameters(),3.);optimizer.step()
        if (step+1)%500==0:
            with torch.no_grad():score_by_frame=torch.cat([loss(list(range(i,min(i+8,len(f)))),False) for i in range(0,len(f),8)])
            ratios={name:float(score_by_frame[k*8:(k+1)*8].mean()/baseline[k*8:(k+1)*8].mean()) for k,name in enumerate(NAMES)}
            score=float(np.mean(list(ratios.values()))+.3*max(ratios.values()));row=dict(step=step+1,score=score,ratios=ratios);history.append(row)
            print('年龄覆盖',step+1,round(time.monotonic()-started,1),round(score,5),{k:round(v,4) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scale=scale,train=NAMES,temporal_weight=a.temporal_weight,selection=row,layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
