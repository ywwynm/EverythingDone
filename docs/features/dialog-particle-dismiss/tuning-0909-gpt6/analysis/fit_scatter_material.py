"""实际 GPU 细粒覆盖／反射／色度联合响应，训练十段共用几何函数。"""
from pathlib import Path
import sys,argparse,json,hashlib,time
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer,load_scatter,scatter_field
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from shared_transport import transport_features,load_transport
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

OUT=HERE/'analysis/match-cache/scatter-material';N=80;LO=-.38;SIZE=1.76
PHASES=[.12,.22,.32,.42,.52,.62,.72,.82];EPS=np.array([.25,.025,.15],np.float32);SCALES=np.array([.9,.16,.75],np.float32)

def prepare(transport,initial=None,histogram=False):
    # 响应取决于整条绘制链和所有冻结配置，不能仅凭输运与细分文件复用旧缓存。
    files=['renderer.py','unified_model.py','coupled_transport.py','learned_release.py',
           'conditional_material.py','shared_transport.py','surface_transfer.py','particle_coverage.py',
           'particle_cohorts.py','surface_coherence.py','particle_fission.py','scatter_material.py',
           'conditional-release-weights.json','conditional-material-rules.json','surface-transfer-rules.json',
           'particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json',
           'analysis/fit_scatter_material.py']
    digest=hashlib.sha256(transport.read_bytes()+(initial.read_bytes() if initial else b'')+str(histogram).encode()+b''.join((HERE/p).read_bytes() for p in files))
    path=OUT/f'response-{digest.hexdigest()[:16]}.npz';OUT.mkdir(parents=True,exist_ok=True)
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);kw['transport_weights']=load_transport(transport)
    weights=load_scatter(initial) if initial else None
    y,x=np.mgrid[:N,:N].astype(np.float32);x=LO+(x+.5)/N*SIZE;y=LO+(y+.5)/N*SIZE
    data={k:[] for k in ['features','actual','target','slope','curve','prior','mask','scene','phase']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);x0,y0,x1,y1=c.scene.rect;w,h=x1-x0,y1-y0;sites=[Site(*v) for v in OBSERVED_SITES[name]]
        r=ScatterRenderer(name,ctx=ctx,quality=2,sites=sites,scatter=weights,**kw);qx=x0+x*w;qy=y0+y*h
        def compact(frame):
            f=frame.astype(np.float32)/255
            values=[cv2.GaussianBlur(f,(0,0),s) for s in [2.,5.,12.]]
            for s in [.7,1.5,3.]:values.append(cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),s)),(0,0),8.))
            if histogram:
                luminance=f@np.float32([.2126,.7152,.0722])
                detail=luminance-cv2.GaussianBlur(luminance,(0,0),1.5)
                # 相同的绝对高频能量可能来自亮点或暗点，分别比较局部尾部分布。
                # 所有参考共用阈值；它们只定义离线观测，不在画面中画亮带。
                for sign in [1.,-1.]:
                    for threshold in [.025,.06,.12,.24]:
                        soft=1./(1.+np.exp(np.clip(-(sign*detail-threshold)/.015,-30,30)))
                        values.append(cv2.GaussianBlur(soft,(0,0),8.)[...,None])
            return cv2.remap(np.concatenate(values,-1),qx,qy,cv2.INTER_LINEAR)
        mask=cv2.remap(c.scene.roi.astype(np.float32),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['scatter_delta']=(0.,0.,0.);actual=compact(r.render(p));slopes=[];curves=[]
            for k,eps in enumerate(EPS):
                frames=[]
                for sign in [-1,1]:
                    q=[0.,0.,0.];q[k]=float(eps*sign);r.program['scatter_delta']=q;frames.append(compact(r.render(p)))
                slopes.append((frames[1]-frames[0])/(2*eps));curves.append((frames[1]+frames[0]-2*actual)/(2*eps**2))
            phase=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            row=dict(features=transport_features(x,y,phase,w,h,r.direction,sites,True),actual=actual,target=compact(ref),
                     slope=np.stack(slopes,-1),curve=np.stack(curves,-1),prior=scatter_field(x,y,phase,w,h,r.direction,sites,weights),mask=mask,scene=ni,phase=phase)
            for k,v in row.items():data[k].append(v)
        r.close();print('实际细粒材质响应',name,flush=True)
    ctx.release();data={k:np.array(v) for k,v in data.items()};np.savez_compressed(path,**data);print(path,flush=True);return data

def review(weights,transport,out):
    weights=load_scatter(weights);ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in NAMES:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        baseline=cls(name,**args);args['transport_weights']=load_transport(transport);args['scatter']=weights;candidate=ScatterRenderer(name,**args)
        x,y,x1,y1=c.scene.rect;box=(max(0,x-35),max(0,y-35),min(c.scene.W,x1+35),min(c.scene.H,y1+35));tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]))
        sheet=Image.new('RGB',(tw*5,(th+25)*3),(14,20,30));d=ImageDraw.Draw(sheet);rows=[]
        for col,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);frames=[ref,baseline.render(p),candidate.render(p)];target=measure(ref,c.scene.roi);row=dict(phase=p)
            for k,(label,f) in enumerate(zip(['华为原片','当前默认','共同细粒材质'],frames)):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+25)+25));d.text((col*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={m:float(abs(u-v).mean()) for m,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        baseline.close();candidate.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        print(name,{stage:{m:round(float(np.mean([r[stage][m] for r in rows])),4) for m in ['rgb_4','grain']} for stage in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--transport',required=True);ap.add_argument('--label',default='joint');ap.add_argument('--steps',type=int,default=12000);ap.add_argument('--initial');ap.add_argument('--review');ap.add_argument('--histogram',action='store_true');ap.add_argument('--balanced',action='store_true',help='单独限制各尺度颜色退步，不以颗粒统计替代大尺度明暗')
    ap.add_argument('--temporal-weight',type=float,default=0.,help='约束真实材料路径上的新增明暗与覆盖变化')
    ap.add_argument('--trace-root',type=Path,default=HERE/'analysis/match-cache/motion-continuity/balanced-velocity')
    a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True);transport=out/'transport.json';transport.write_bytes(Path(a.transport).read_bytes())
    if a.review:return review(Path(a.review),transport,out)
    data=prepare(transport,Path(a.initial) if a.initial else None,a.histogram)
    import torch
    torch.set_num_threads(4);torch.manual_seed(928)
    def t(v):return torch.from_numpy(np.ascontiguousarray(v,np.float32)).cuda()
    f,actual,target,slope,curve,prior,mask=[t(data[k]) for k in ['features','actual','target','slope','curve','prior','mask']]
    model=torch.nn.Sequential(torch.nn.Linear(59,128),torch.nn.Tanh(),torch.nn.Linear(128,128),torch.nn.Tanh(),torch.nn.Linear(128,3)).cuda()
    with torch.no_grad():
        if a.initial:
            old=json.loads(Path(a.initial).read_text('utf-8'))
            for layer,saved in zip([q for q in model if isinstance(q,torch.nn.Linear)],old['layers']):layer.weight.copy_(t(saved['weight']));layer.bias.copy_(t(saved['bias']))
        else:model[-1].weight.zero_();model[-1].bias.zero_()
    scales=t(SCALES);channels=t([.7]*3+[1.]*3+([.9] if a.balanced else [.3])*3+[.55]*3+[.65]*3+[.45]*3+(([.15,.18,.2,.2] if a.balanced else [.4,.5,.6,.6])*2 if a.histogram else []))
    def error(image,ids):return (torch.sqrt((image-target[ids]).square()+.003**2)*mask[ids,...,None]*channels).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/channels.sum()
    def color_groups(image,ids):
        difference=torch.sqrt((image[...,:9]-target[ids,...,:9]).square()+.003**2)
        return (difference*mask[ids,...,None]).sum((1,2)).reshape(-1,3,3).mean(-1)/mask[ids].sum((1,2))[:,None].clamp(min=1)
    with torch.no_grad():
        start_error=error(actual,list(range(len(f))))
        start_colors=color_groups(actual,list(range(len(f))))
    path_features=[]
    if a.temporal_weight:
        rng=np.random.default_rng(928)
        for path in sorted(a.trace_root.glob('*.npz')):
            saved=np.load(path);tr=saved['trace'];card=np.array(saved['rect'][2:])-saved['rect'][:2]
            ts=tr[:,0,4]-tr[0,0,4]
            meta=json.loads((HERE/'assets'/path.stem/'scene.json').read_text('utf-8'));sites=[Site(*v) for v in OBSERVED_SITES[path.stem]]
            for center in np.arange(.15,.76,.05):
                phases=np.array([center-.05,center,center+.05]);hi=np.clip(np.searchsorted(ts,phases),1,len(ts)-1);lo=hi-1
                fraction=((phases-ts[lo])/(ts[hi]-ts[lo]))[:,None,None];q=tr[lo]*(1-fraction)+tr[hi]*fraction
                live=((q[:,:,4]>.06)&(q[:,:,4]<q[:,:,5]-.06)&(q[:,:,6]<.6)).all(0)&saved['mask']
                ids=np.flatnonzero(live)
                if len(ids)<16:continue
                ids=rng.choice(ids,128,replace=len(ids)<128);pos=q[:,ids,2:4]/card
                features=np.stack([transport_features(pos[k,:,0],pos[k,:,1],p,*card,meta['direction'],sites,True) for k,p in enumerate(phases)])
                path_features.append(features)
        if not path_features:raise ValueError('没有可用的实际材料路径')
        path_features=t(np.stack(path_features))
        with torch.no_grad():path_anchor=model(path_features).tanh()
    def path_loss():
        ids=torch.randint(len(path_features),(4,),device='cuda')
        delta=model(path_features[ids]).tanh()-path_anchor[ids]
        acceleration=(delta[:,2]-2*delta[:,1]+delta[:,0])/.05**2
        return acceleration.square().mean()/16.
    def loss(ids,regular=True):
        v=model(f[ids]).tanh();delta=v*scales-prior[ids];estimate=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        errs=error(estimate,ids)
        if not regular:return errs
        spatial=(v[:,1:]-v[:,:-1]).square().mean()+(v[:,:,1:]-v[:,:,:-1]).square().mean()
        guard=2.*torch.relu(color_groups(estimate,ids)/start_colors[ids].clamp(min=.008)-1.005).square().mean() if a.balanced else 0.
        return (errs/start_error[ids].clamp(min=.008)).mean()+4*torch.relu(errs-start_error[ids]).mean()+.008*v.square().mean()+.8*spatial+.12*torch.relu(abs(delta/scales)-.6).square().mean()+guard+(a.temporal_weight*path_loss() if a.temporal_weight else 0.)
    opt=torch.optim.AdamW(model.parameters(),lr=.0006,weight_decay=.001);history=[];best=1.3;start=time.monotonic()
    for step in range(a.steps):
        for g in opt.param_groups:g['lr']=.0006*(.1+.9*.5*(1+np.cos(np.pi*step/a.steps)))
        ids=torch.randint(len(f),(8,)).tolist();opt.zero_grad();loss(ids).backward();torch.nn.utils.clip_grad_norm_(model.parameters(),3.);opt.step()
        if (step+1)%500==0:
            with torch.no_grad():errors=torch.cat([loss(list(range(i,min(i+8,len(f)))),False) for i in range(0,len(f),8)])
            ratios={name:float(errors[k*8:(k+1)*8].mean()/start_error[k*8:(k+1)*8].mean()) for k,name in enumerate(NAMES)}
            score=float(np.mean(list(ratios.values()))+.3*max(ratios.values()));row=dict(step=step+1,score=score,ratios=ratios);history.append(row)
            print('共同细粒材质',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scales=SCALES.tolist(),train=NAMES,holdout=[],selection=row,histogram=a.histogram,balanced=a.balanced,temporal_weight=a.temporal_weight,layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
