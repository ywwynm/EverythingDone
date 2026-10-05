"""在当前完整 GPU 上测量交接时刻与宽度，校准共同函数并另相位复查。"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_handoff import HandoffRenderer,load_handoff
from surface_coherence import coherence_components
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES,measure
from match_compare import Comparison
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

OUT=HERE/'analysis/match-cache/surface-handoff'
N=64;PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84]
SCALES=np.array([.09,.65],np.float32);DELTAS=np.array([.03,.35],np.float32)


def prepare():
    sources=['renderer.py','surface_handoff.py','surface_coherence.py','particle_cohorts.py','particle_coverage.py','surface_transfer.py','shared_transport.py',
        'conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json',
        'particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','analysis/fit_surface_handoff.py']
    key=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in sources)).hexdigest()[:16]
    OUT.mkdir(parents=True,exist_ok=True);path=OUT/f'response-{key}.npz'
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=(xx+.5)/N;yy=(yy+.5)/N
    data={k:[] for k in ['features','prior','target','actual','slope','curve','mask','scene','phase']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=HandoffRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def compact(f):
            f=f.astype('float32')/255.
            values=[cv2.GaussianBlur(f,(0,0),sigma) for sigma in [2.,5.,12.]]
            values.append(cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.))
            return cv2.remap(np.concatenate(values,-1),qx,qy,cv2.INTER_LINEAR)
        mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['handoff_delta']=(0.,0.);base=compact(r.render(p));slopes=[];curves=[]
            for k,delta in enumerate(DELTAS):
                frames=[]
                for sign in [-1,1]:
                    q=[0.,0.];q[k]=float(sign*delta);r.program['handoff_delta']=q;frames.append(compact(r.render(p)))
                slopes.append((frames[1]-frames[0])/(2*delta));curves.append((frames[1]+frames[0]-2*base)/(2*delta**2))
            clock=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            prior=coherence_components(xx,yy,clock,w,h,m['direction'],sites,kw['coherence']);prior[...,0]*=r.handoff_clock_gain
            values=dict(features=transport_features(xx,yy,clock,w,h,m['direction'],sites,True),prior=prior,
                actual=base,target=compact(ref),slope=np.stack(slopes,-1),curve=np.stack(curves,-1),mask=mask,scene=ni,phase=clock)
            for k,v in values.items():data[k].append(v)
        r.close();print('交接时刻与宽度响应',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()};np.savez_compressed(path,**data);return data


def review(path,out,scenes):
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weights=load_handoff(frozen)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-35),max(0,y-35),min(m['frame'][0],x1+35),min(m['frame'][1],y1+35))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));d=ImageDraw.Draw(sheet)
        args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=HandoffRenderer(name,**args);after=HandoffRenderer(name,handoff=weights,**args);rows=[]
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref),('当前默认',before.render(p)),('共同渐隐交接',after.render(p))]
            for k,(title,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,after.handoff_clock_gain,{s:{k:round(np.mean([row[s][k] for row in rows]),4) for k in ['rgb_4','grain']} for s in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=8000);ap.add_argument('--label',default='joint-handoff');ap.add_argument('--review');ap.add_argument('--response');ap.add_argument('--scenes',nargs='+',default=NAMES);a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes)
    if a.response:
        cache=np.load(a.response);data={k:cache[k] for k in cache.files}
    else:data=prepare()
    import torch
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(q):
        q=np.ascontiguousarray(q,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,prior,target,actual,slope,curve,mask=[tensor(data[k]) for k in ['features','prior','target','actual','slope','curve','mask']]
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],32),torch.nn.Tanh(),torch.nn.Linear(32,32),torch.nn.Tanh(),torch.nn.Linear(32,2))
    old=json.loads((HERE/'surface-coherence-weights.json').read_text('utf-8'))
    with torch.no_grad():
        for layer,saved in zip([q for q in model if isinstance(q,torch.nn.Linear)],old['layers']):
            w=tensor(saved['weight']);b=tensor(saved['bias']);layer.weight[:w.shape[0]].copy_(w);layer.bias[:len(b)].copy_(b)
        previous_scales=old.get('scales',[old.get('scale',.035)])
        for k in range(2):
            if k<len(previous_scales):
                ratio=float(previous_scales[k]/SCALES[k]);model[-1].weight[k]*=ratio;model[-1].bias[k]*=ratio
            else:model[-1].weight[k].zero_();model[-1].bias[k].zero_()
    scales=tensor(SCALES);channels=tensor([.6]*3+[1.]*3+[.3]*3+[.25]*3)
    # 先复制已有时钟，再联合优化；零宽度时退化到已验收的交接。
    opt=torch.optim.Adam(model.parameters(),lr=.0005)
    for _ in range(700):
        ids=torch.randint(len(f),(5,));opt.zero_grad();v=model(f[ids]).tanh();err=v-prior[ids]/scales
        loss=err.square().mean();loss.backward();opt.step()
    def loss(ids,regular=True):
        v=model(f[ids]).tanh();delta=scales*v-prior[ids]
        image=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        weight=mask[ids][...,None];diff=torch.sqrt((image-target[ids]).square()+.003**2)
        e=(diff*weight*channels).sum()/weight.sum().clamp(min=1)/channels.sum()
        if not regular:return e
        dx=v[:,1:]-v[:,:-1];dy=v[:,:,1:]-v[:,:,:-1]
        return e+.0004*(delta/scales).square().mean()+.02*(dx.square().mean()+dy.square().mean())
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();best=1.3;opt=torch.optim.AdamW(model.parameters(),lr=.0005,weight_decay=.002);start=time.monotonic();history=[]
    print('共同交接初始',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(f),(6,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('共同交接',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scales=SCALES.tolist(),train=NAMES,holdout=[],selection=row,before=before,scores=s,
                    layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
