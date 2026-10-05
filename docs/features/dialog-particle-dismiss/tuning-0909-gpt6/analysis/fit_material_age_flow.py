"""以真实 GPU 的年龄分解响应校准共同材料输运，最终另相位实际复核。"""
from pathlib import Path
import argparse,hashlib,json,math,sys,time
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from material_age_flow import AgeFlowRenderer,load_age_flow
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES,measure
from shared_transport import transport_features
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=64;FIELD=56;LO=-.38;SIZE=1.76;PHASES=[.12,.22,.32,.42,.52,.62,.72,.82]
OUT=HERE/'analysis/match-cache/material-age-flow'


def prepare():
    files=['renderer.py','surface_coherence.py','surface-coherence-weights.json','particle_cohorts.py','particle-cohort-weights.json',
           'conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json',
           'particle-coverage-rules.json','material_age_flow.py','analysis/fit_material_age_flow.py']
    key=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in files)).hexdigest()[:16];path=OUT/f'response-{key}.npz'
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    yy,xx=np.mgrid[:N,:N].astype(np.float32);xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    fy,fx=np.mgrid[:FIELD,:FIELD].astype(np.float32);fx=LO+(fx+.5)/FIELD*SIZE;fy=LO+(fy+.5)/FIELD*SIZE
    data={k:[] for k in ['features','actual','target','slope','lookup','mask','phase','rotation','scale']}
    for name in NAMES:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=AgeFlowRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def sample(f):return cv2.remap(f,qx,qy,cv2.INTER_LINEAR)
        def compact(f):
            f=f.astype(np.float32)/255.
            return np.concatenate([sample(cv2.GaussianBlur(f,(0,0),s)) for s in [4.,12.,24.]],-1)
        mask=sample(c.scene.roi.astype(np.float32));angle=math.radians(m['direction']);cs,ss=math.cos(angle),-math.sin(angle)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);zero=np.zeros((3,3),np.float32)
            r.program['age_flow_diagnostic']=-1;r.program['age_flow_probe'].write(zero.tobytes());actual=compact(r.render(p))
            lookups=[]
            for age in range(3):
                r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
                r.program['material_pass']=1;r.program['age_flow_diagnostic']=age;r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
                layer=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype(np.float32)
                layer=sample(cv2.GaussianBlur(cv2.resize(layer,(r.w,r.h),interpolation=cv2.INTER_AREA),(0,0),4.))
                lookup=layer[...,:2]/np.maximum(layer[...,3:],.0001)
                fallback=np.stack([(xx-LO)/SIZE,(yy-LO)/SIZE],-1)
                lookups.append(np.where(layer[...,3:]>.003,lookup,fallback))
            r.program['age_flow_diagnostic']=-1;slopes=[]
            for age in range(3):
                cols=[]
                for k,delta in enumerate([.012,.012,.20]):
                    values=[]
                    for sign in [-1,1]:
                        q=zero.copy();q[age,k]=delta*sign;r.program['age_flow_probe'].write(q.tobytes());values.append(compact(r.render(p)))
                    cols.append((values[1]-values[0])/(2*delta))
                slopes.append(np.stack(cols,-1))
            clock=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            values=dict(features=transport_features(fx,fy,clock,w,h,m['direction'],sites,True),actual=actual,target=compact(ref),
                        slope=np.stack(slopes,-2),lookup=np.stack(lookups),mask=mask,phase=clock,rotation=[[cs,ss],[-ss,cs]],scale=[min(w,h)/w,min(w,h)/h])
            for k,v in values.items():data[k].append(v)
        r.close();print('材料年龄响应',name,flush=True)
    ctx.release();data={k:np.asarray(v,np.float32) for k,v in data.items()};np.savez_compressed(path,**data);return data


def review(path,out,scenes):
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weights=load_age_flow(frozen)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    from surface_coherence import CoherenceRenderer
    for name in scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];pad=round(c.scene.span*.18)
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad));tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]))
        sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=CoherenceRenderer(name,**args);after=AgeFlowRenderer(name,age_flow=weights,**args)
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            for k,(title,f) in enumerate([('华为原片',ref),('当前默认',before.render(p)),('共同材料输运',after.render(p))]):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                if k:
                    metrics={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    cloud,_=c.scene.blocks(c.scene.observe(ref)['particle']&c.scene.roi);pred,mask=c.scene.blocks(c.scene.observe(f)['particle']&c.scene.roi)
                    metrics['cloud_map']=float(abs(cloud-pred)[mask>.5].mean());row['before' if k==1 else 'after']=metrics
                if j==2:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        print(name,{s:{k:round(float(np.mean([q[s][k] for q in rows])),4) for k in ['rgb_4','grain','cloud_map']} for s in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='first');ap.add_argument('--steps',type=int,default=5000);ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--response');ap.add_argument('--smoothness',type=float,default=.008);ap.add_argument('--trust',type=float,default=.035)
    ap.add_argument('--device',default='cpu');ap.add_argument('--width',type=int,default=48);ap.add_argument('--learning-rate',type=float,default=.00045);ap.add_argument('--initial');a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes)
    if a.response:
        z=np.load(a.response);data={k:z[k] for k in z.files}
    else:data=prepare()
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(29)
    def tensor(q):
        q=np.ascontiguousarray(q,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape).to(a.device)
    f,actual,target,slope,lookup,mask,phase,rotation,scale=[tensor(data[k]) for k in ['features','actual','target','slope','lookup','mask','phase','rotation','scale']]
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,9)).to(a.device)
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias)
    if a.initial:
        config=json.loads(Path(a.initial).read_text('utf-8'))
        with torch.no_grad():
            for layer,saved in zip([q for q in model if isinstance(q,torch.nn.Linear)],config['layers']):
                layer.weight.copy_(tensor(saved['weight']));layer.bias.copy_(tensor(saved['bias']))
    yy,xx=torch.meshgrid(torch.arange(FIELD,device=a.device),torch.arange(FIELD,device=a.device),indexing='ij');grid=(torch.stack([xx,yy],-1).float()+.5)/FIELD*2-1
    channels=tensor([1.]*3+[.65]*3+[.35]*3);prior=torch.sqrt((actual-target).square()+.003**2)
    def field(ids):
        raw=model(f[ids]).tanh().reshape(len(ids),FIELD,FIELD,3,3)
        v=.18*phase[ids,None,None,None,None]*raw[...,:2]
        v=torch.einsum('bxyac,bcd->bxyad',v,rotation[ids])*scale[ids,None,None,None,:]/32.
        groups=v.permute(0,3,1,2,4).reshape(-1,FIELD,FIELD,2)
        for _ in range(5):groups=groups+F.grid_sample(groups.permute(0,3,1,2),grid[None]+groups*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        v=groups.reshape(len(ids),3,FIELD,FIELD,2).permute(0,2,3,1,4)/scale[ids,None,None,None,:]
        return torch.cat([v,.6*raw[...,2:]],-1)
    def loss(ids,regular=True):
        current=field(ids);groups=current.permute(0,3,4,1,2).reshape(-1,3,FIELD,FIELD)
        delta=F.grid_sample(groups,lookup[ids].reshape(-1,N,N,2)*2-1,padding_mode='border',align_corners=False)
        delta=delta.reshape(len(ids),3,3,N,N).permute(0,3,4,1,2)
        estimate=actual[ids]+(slope[ids]*delta[...,None,:,:]).sum((-1,-2))
        diff=torch.sqrt((estimate-target[ids]).square()+.003**2);w=mask[ids][...,None]
        e=(diff*w*channels).sum()/w.sum().clamp(min=1)/channels.sum()
        if not regular:return e
        per=(diff*w*channels).sum((1,2,3))/w.sum((1,2,3)).clamp(min=1)/channels.sum()
        baseline=(prior[ids]*w*channels).sum((1,2,3))/w.sum((1,2,3)).clamp(min=1)/channels.sum()
        move=current[...,:2];dx=(move[:,:-1,1:]-move[:,:-1,:-1])*FIELD/SIZE;dy=(move[:,1:,:-1]-move[:,:-1,:-1])*FIELD/SIZE
        physical=move*scale[ids,None,None,None,:]
        px=(physical[:,:-1,1:]-physical[:,:-1,:-1])*FIELD/SIZE;py=(physical[:,1:,:-1]-physical[:,:-1,:-1])*FIELD/SIZE
        det=(1+px[...,0])*(1+py[...,1])-px[...,1]*py[...,0]
        trust=F.relu(abs(move)-a.trust).square().mean()+.02*F.relu(abs(current[...,2])-.30).square().mean()
        return e+torch.relu(per-baseline).mean()+2.*trust+a.smoothness*(dx.square().mean()+dy.square().mean())+.08*F.relu(.3-det).square().mean()+.001*current[...,2].square().mean()
    def scores():
        with torch.no_grad():return {name:float(loss(list(range(k*len(PHASES),(k+1)*len(PHASES))),False)) for k,name in enumerate(NAMES)}
    before=scores();best=1.3;optimizer=torch.optim.AdamW(model.parameters(),lr=a.learning_rate,weight_decay=.002);start=time.monotonic();history=[]
    print('共同材料输运初始',before,flush=True)
    for step in range(a.steps):
        optimizer.zero_grad();e=loss(torch.randint(len(f),(5,),device=a.device));e.backward();optimizer.step()
        if (step+1)%500==0:
            values=scores();ratios={name:values[name]/before[name] for name in NAMES};score=float(np.mean(list(ratios.values()))+.3*max(ratios.values()))
            row=dict(step=step+1,score=score,ratios=ratios);history.append(row)
            print('共同材料输运',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(move_scale=.18,density_scale=.6,train=NAMES,holdout=[],selection=row,layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
