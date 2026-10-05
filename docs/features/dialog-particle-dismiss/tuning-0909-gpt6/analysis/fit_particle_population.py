"""在完整当前渲染处联合测量粒度和覆盖响应；优化一份共同条件函数。

响应只用于离线梯度，采用另一组相位和完整 GPU 复核，不能以近似损失代替验收。
"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_population import PopulationRenderer,load_population
from check_cloud_evolution import configuration
from fit_particle_optics import measure,NAMES
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

N=72;LO=-.25;SIZE=1.5
PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84]
SCALES=np.array([.35,.65],np.float32)
DELTAS=np.array([.25,.45],np.float32)


def identity():
    names=['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json',
        'surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json',
        'renderer.py','unified_model.py','particle_population.py','surface_handoff.py','surface_transfer.py','shared_transport.py','particle_coverage.py',
        'particle_cohorts.py','surface_coherence.py','analysis/fit_particle_population.py']
    return hashlib.sha256(b''.join((HERE/f).read_bytes() for f in names)).hexdigest()[:16]


def prepare():
    path=HERE/'analysis/match-cache/particle-population'/f'response-{identity()}.npz';path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    data={k:[] for k in ['features','target','actual','slope','curve','mask','scene','phase']}
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=PopulationRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def compact(f):
            f=f.astype('float32')/255.
            features=[cv2.GaussianBlur(f,(0,0),4.)]
            # 独立的颗粒尺度观测，减少仅优化均值明暗而丢失颗粒结构的偏差。
            for sigma in [.7,1.5,3.]:
                features.append(cv2.GaussianBlur(np.abs(f-cv2.GaussianBlur(f,(0,0),sigma)),(0,0),8.))
            return cv2.remap(np.concatenate(features,-1),qx,qy,cv2.INTER_LINEAR)
        mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['population_delta']=(0.,0.,0.);base=compact(r.render(p));slopes=[];curves=[]
            for k,delta in enumerate(DELTAS):
                frames=[]
                for sign in [-1,1]:
                    q=[0.,0.,0.];q[k]=float(sign*delta);r.program['population_delta']=q;frames.append(compact(r.render(p)))
                slopes.append((frames[1]-frames[0])/(2*delta));curves.append((frames[1]+frames[0]-2*base)/(2*delta**2))
            actual=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            values=dict(features=transport_features(xx,yy,actual,w,h,m['direction'],sites,True),target=compact(ref),actual=base,
                slope=np.stack(slopes,-1),curve=np.stack(curves,-1),mask=mask,scene=ni,phase=p)
            for k,v in values.items():data[k].append(v)
        r.close();print('数量／面积实际响应',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()};np.savez_compressed(path,**data);return data


def review(path,out,scenes,handoff=None):
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weights=load_population(frozen)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-35),max(0,y-35),min(m['frame'][0],x1+35),min(m['frame'][1],y1+35))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));d=ImageDraw.Draw(sheet)
        args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=PopulationRenderer(name,**args)
        if handoff:
            from surface_handoff import load_handoff
            args['handoff']=load_handoff(Path(handoff))
        after=PopulationRenderer(name,population=weights,**args);rows=[]
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref),('当前默认',before.render(p)),('共同材料交接' if handoff else '共同数量／面积',after.render(p))]
            for k,(title,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{s:{k:round(np.mean([row[s][k] for row in rows]),4) for k in ['rgb_4','grain']} for s in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=6000);ap.add_argument('--label',default='shared-population');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES)
    ap.add_argument('--response',help='明确复用同一当前配置已提取的响应缓存');ap.add_argument('--spectral',action='store_true');ap.add_argument('--handoff');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-population'/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes,a.handoff)
    if a.response:
        z=np.load(a.response);data={k:z[k] for k in z.files}
    else:data=prepare()
    import torch
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(v):
        q=np.ascontiguousarray(v,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,target,actual,slope,curve,mask=[tensor(data[k]) for k in ['features','target','actual','slope','curve','mask']]
    scales=tensor(SCALES);channel=tensor([1.]*3+[.8]*3+[1.]*3+[.8]*3)
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],32),torch.nn.Tanh(),torch.nn.Linear(32,32),torch.nn.Tanh(),torch.nn.Linear(32,2))
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias)
    def spectral_features(q):
        energy=q[...,3:].reshape(*q.shape[:-1],3,3).mean(-1)
        return energy[...,:2]/(energy[...,2:]+.003),energy[...,2]
    reference_spectrum,reference_energy=spectral_features(target)
    baseline_rgb=(torch.sqrt((actual[...,:3]-target[...,:3]).square()+.002**2)*mask[...,None]).sum((1,2,3))/mask.sum((1,2)).clamp(min=1)/3
    def loss(ids,regular=True):
        v=model(f[ids]).tanh();delta=scales*v;image=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        w=mask[ids][...,None]
        if a.spectral:
            rgb=(torch.sqrt((image[...,:3]-target[ids,...,:3]).square()+.002**2)*w).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/3
            spectrum,_=spectral_features(image)
            cloud_weight=mask[ids]*((reference_energy[ids]-.004)/.020).clamp(0,1)
            spectral=(abs(spectrum-reference_spectrum[ids])*cloud_weight[...,None]).sum()/cloud_weight.sum().clamp(min=1)/2
            e=rgb.mean()+.018*spectral
            if regular:e=e+3.*torch.relu(rgb-baseline_rgb[ids]).mean()
        else:e=(torch.sqrt((image-target[ids]).square()+.002**2)*w*channel).sum()/w.sum().clamp(min=1)/channel.sum()
        if not regular:return e
        dx=v[:,1:]-v[:,:-1];dy=v[:,:,1:]-v[:,:,:-1]
        return e+.0002*v.square().mean()+.02*(dx.square().mean()+dy.square().mean())
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();opt=torch.optim.AdamW(model.parameters(),lr=.0007,weight_decay=.004);best=1.3;start=time.monotonic();history=[]
    print('初始共同数量／面积损失',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(f),(6,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('共同数量／面积',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scales=SCALES.tolist(),train=NAMES,holdout=[],selection=row,before=before,scores=s,spectral=a.spectral,
                    layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
