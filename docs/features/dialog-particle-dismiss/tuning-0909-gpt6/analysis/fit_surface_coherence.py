"""用真实交接响应拟合共同局部场，再由完整 GPU 在其它相位复查。

局部响应忽略邻近材料覆盖的非对角项，因此只接受实际渲染复核后的改动。
"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer,load_coherence
from particle_cohorts import load_cohorts
from particle_coverage import load_coverage
from shared_transport import transport_features
from fit_particle_optics import common,measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=64;PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84];DELTA=.025;SCALE=.035


def settings():
    kw=common();kw.update(coverage=load_coverage(HERE/'particle-coverage-rules.json'),cohorts=load_cohorts(HERE/'particle-cohort-weights.json'))
    return kw


def prepare():
    filenames=['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface_coherence.py','surface_transfer.py','analysis/fit_surface_coherence.py']
    key=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in filenames)).hexdigest()[:14]
    path=HERE/'analysis/match-cache/surface-coherence'/f'response-{key}.npz';path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);kw=settings();data={k:[] for k in ['features','target','actual','slope','curve','mask','scene','phase']}
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=(xx+.5)/N;yy=(yy+.5)/N
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=CoherenceRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def compact(f):return cv2.remap(cv2.GaussianBlur(f.astype('float32')/255.,(0,0),4.),qx,qy,cv2.INTER_LINEAR)
        mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);frames=[]
            for delay in [-DELTA,0.,DELTA]:
                r.program['handoff_delay']=delay;frames.append(compact(r.render(p)))
            actual=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            slope=(frames[2]-frames[0])/(2*DELTA);curve=(frames[2]+frames[0]-2*frames[1])/(2*DELTA**2)
            values=dict(features=transport_features(xx,yy,actual,w,h,m['direction'],sites,True),target=compact(ref),actual=frames[1],slope=slope,curve=curve,mask=mask,scene=ni,phase=p)
            for k,v in values.items():data[k].append(v)
        r.close();print('交接响应',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()};np.savez_compressed(path,**data)
    return data


def review(path,out):
    # 一次性冻结输入，避免拟合进程仍在写入时，不同场景读到不同候选。
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weights=load_coherence(frozen)
    ctx=moderngl.create_standalone_context(require=430);kw=settings();font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in NAMES:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-30),max(0,y-30),min(m['frame'][0],x1+30),min(m['frame'][1],y1+30))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));d=ImageDraw.Draw(sheet)
        args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=CoherenceRenderer(name,**args);after=CoherenceRenderer(name,coherence=weights,**args);rows=[]
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref),('当前默认',before.render(p)),('共同局部交接',after.render(p))]
            for k,(title,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{s:{k:round(np.mean([row[s][k] for row in rows]),4) for k in ['rgb_4','grain']} for s in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=3000);ap.add_argument('--label',default='local-time');ap.add_argument('--review');a=ap.parse_args()
    out=HERE/'analysis/match-cache/surface-coherence'/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out)
    data=prepare()
    import torch
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(v):
        q=np.ascontiguousarray(v,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,target,actual,slope,curve,mask=[tensor(data[k]) for k in ['features','target','actual','slope','curve','mask']]
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],32),torch.nn.Tanh(),torch.nn.Linear(32,32),torch.nn.Tanh(),torch.nn.Linear(32,1))
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias)
    def loss(ids,regular=True):
        v=model(f[ids]).tanh();delta=SCALE*v;image=actual[ids]+slope[ids]*delta+curve[ids]*delta.square();w=mask[ids][...,None]
        e=(torch.sqrt((image-target[ids]).square()+.003**2)*w).sum()/w.sum().clamp(min=1)/3
        if not regular:return e
        dx=v[:,1:]-v[:,:-1];dy=v[:,:,1:]-v[:,:,:-1]
        return e+.0003*v.square().mean()+.03*(dx.square().mean()+dy.square().mean())
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();opt=torch.optim.AdamW(model.parameters(),lr=.0007,weight_decay=.004);best=1.30;start=time.monotonic();history=[]
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(f),(6,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('共同交接',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scale=SCALE,train=NAMES,holdout=[],selection=row,before=before,scores=s,layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
