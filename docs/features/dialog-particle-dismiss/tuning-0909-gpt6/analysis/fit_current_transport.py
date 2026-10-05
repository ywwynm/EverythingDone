"""用当前完整材料状态细化已有共同输运函数；不再使用缺少交接的旧层缓存。

将实际颗粒按年龄分层，仅取消已知输运位移来构造离线可微展开点。覆盖、光照、
表面交接均来自当前渲染器，最终仍须完整 GPU 的不同相位复查。
"""
from pathlib import Path
import argparse,hashlib,json,math,sys,time
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from shared_transport import transport_features,load_transport
from check_cloud_evolution import configuration
from fit_particle_optics import measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=80;LO=-.38;SIZE=1.76
PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84]
OUT=HERE/'analysis/match-cache/current-transport'


class Layers(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform float refine_unwarp;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+',
        'move-=refine_unwarp*transport.xy*span*smoothstep(0.,.03,age);\n    vec2 vertex=move+',1)
    fragment_source=CoherenceRenderer.fragment_source.replace('void main(){','uniform int refine_layer;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','''
    if(refine_layer>=0){
        float mature=smoothstep(.06,.16,age),elder=smoothstep(.22,.38,age);
        vec3 ages=vec3(1.-mature,mature*(1.-elder),mature*elder);
        alpha=1.-pow(max(1.-alpha,.00001),ages[refine_layer]);
    }
    if(diagnostic==4)color=vec3(smoothstep(0.,.03,age));
    frag=vec4(color*alpha,alpha);''',1)


def prepare():
    inputs=['renderer.py','unified_model.py','shared_transport.py','surface_coherence.py','particle_cohorts.py','particle_coverage.py',
        'surface_transfer.py','conditional_material.py','coupled_transport.py','learned_release.py',
        'conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json',
        'surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json',
        'analysis/fit_current_transport.py']
    key=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in inputs)).hexdigest()[:16]
    OUT.mkdir(parents=True,exist_ok=True);path=OUT/f'layers-{key}.npz'
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    data={k:[] for k in ['cloud','warmup','base','actual','target','features','mask','rotation','scale','phase','scene']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=Layers(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def resample(f):return cv2.remap(cv2.GaussianBlur(f,(0,0),min(w,h)/N*.5),qx,qy,cv2.INTER_LINEAR)
        bg=c.scene.bg.astype('float32')/255.;bg=np.where(bg<=.04045,bg/12.92,((bg+.055)/1.055)**2.4);bg=resample(bg)
        mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        angle=math.radians(m['direction']);cs,ss=math.cos(angle),-math.sin(angle)
        def layer(material_pass,group,diagnostic=0):
            r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
            r.program['material_pass']=material_pass;r.program['refine_layer']=group;r.program['diagnostic']=diagnostic
            r.fg_tex.use(0);r.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST) if material_pass==0 else (moderngl.LINEAR,moderngl.LINEAR)
            r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
            q=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype('float32')
            return resample(cv2.resize(q,(r.w,r.h),interpolation=cv2.INTER_AREA))
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['refine_layer']=-1;r.program['refine_unwarp']=0.
            actual=resample(r.render(p).astype('float32')/255.)
            surface=layer(0,-1);r.program['refine_unwarp']=1.;cloud=[];warmup=[]
            for k in range(3):
                q=layer(1,k);gate=layer(1,k,4);cloud.append(q);warmup.append(gate[...,0]/np.maximum(gate[...,3],.001))
            clock=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            values=dict(cloud=cloud,warmup=warmup,base=surface[...,:3]+bg*(1-surface[...,3:]),actual=actual,
                target=resample(ref.astype('float32')/255.),features=transport_features(xx,yy,clock,w,h,m['direction'],sites,True),mask=mask,
                rotation=[[cs,ss],[-ss,cs]],scale=[min(w,h)/w,min(w,h)/h],phase=clock,scene=ni)
            for k,v in values.items():data[k].append(v)
        r.close();print('当前完整输运展开点',name,flush=True)
    ctx.release();data={k:np.asarray(v,np.float32) for k,v in data.items()};np.savez_compressed(path,**data);return data


def review(path,out,scenes):
    frozen=out/'review-weights.json';frozen.write_bytes(path.read_bytes());weight=load_transport(frozen)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-40),max(0,y-40),min(m['frame'][0],x1+40),min(m['frame'][1],y1+40))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));d=ImageDraw.Draw(sheet)
        args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        before=CoherenceRenderer(name,**args);args['transport_weights']=weight;after=CoherenceRenderer(name,**args);rows=[]
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref),('当前默认',before.render(p)),('共同输运细化',after.render(p))]
            for k,(title,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                if k:
                    metrics={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    reference_cloud,_=c.scene.blocks(c.scene.observe(ref)['particle']&c.scene.roi)
                    model_cloud,block_weight=c.scene.blocks(c.scene.observe(f)['particle']&c.scene.roi)
                    metrics['cloud_map']=float(abs(model_cloud-reference_cloud)[block_weight>.5].mean())
                    row['before' if k==1 else 'after']=metrics
            rows.append(row)
        before.close();after.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{s:{k:round(np.mean([row[s][k] for row in rows]),4) for k in ['rgb_4','grain','cloud_map']} for s in ['before','after']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=6000);ap.add_argument('--label',default='current-refinement');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES);a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes)
    data=prepare();initial=json.loads((HERE/'conditional-transport-weights.json').read_text('utf-8'))
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(q):
        q=np.ascontiguousarray(q,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,cloud,warmup,base,target,actual,mask,phase,rotation,scale=[tensor(data[k]) for k in ['features','cloud','warmup','base','target','actual','mask','phase','rotation','scale']]
    first=len(initial['layers'][0]['bias']);second=len(initial['layers'][1]['bias'])
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],first),torch.nn.Tanh(),torch.nn.Linear(first,second),torch.nn.Tanh(),torch.nn.Linear(second,3))
    with torch.no_grad():
        for layer,saved in zip([q for q in model if isinstance(q,torch.nn.Linear)],initial['layers']):
            layer.weight.copy_(tensor(saved['weight']));layer.bias.copy_(tensor(saved['bias']))
    opacity=cloud[...,3].clamp(0,.999);tau=-torch.log1p(-opacity);color=cloud[...,:3]/opacity[...,None].clamp(min=.001)
    yy,xx=torch.meshgrid(torch.arange(N),torch.arange(N),indexing='ij');coords=torch.stack([xx,yy],-1).float();grid=(coords+.5)/N*2-1
    offset=None;anchor_field=None;anchor_density=None
    def field(ids):
        output=model(f[ids]).tanh();v=initial['displacement_scale']*phase[ids,None,None,None]*output[...,:2]
        v=torch.einsum('bxyc,bcd->bxyd',v,rotation[ids])*scale[ids,None,None,:]
        steps=initial['flow_steps'];v=v/(2**steps)
        for _ in range(steps):v=v+F.grid_sample(v.permute(0,3,1,2),grid[None]+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return v,initial['density_scale']*output[...,2]
    def render(ids):
        displacement,density=field(ids);batch=len(ids)
        delta=torch.zeros_like(density) if anchor_density is None else density-anchor_density[ids]
        pos=coords[None,None]+displacement[:,None]*warmup[ids,...,None]*(N/SIZE)
        p0=pos.floor().detach();frac=pos-p0;mass=tau[ids]*torch.exp(delta[:,None]*warmup[ids])
        values=torch.cat([color[ids]*mass[...,None],mass[...,None]],-1).reshape(batch,-1,4)
        accum=torch.zeros((batch,N*N,4))
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            q=p0+torch.tensor([dx,dy]);valid=(q[...,0]>=0)&(q[...,0]<N)&(q[...,1]>=0)&(q[...,1]<N)
            w=(frac[...,0] if dx else 1-frac[...,0])*(frac[...,1] if dy else 1-frac[...,1])*valid
            index=(q[...,1].clamp(0,N-1)*N+q[...,0].clamp(0,N-1)).long().reshape(batch,-1)
            accum.scatter_add_(1,index[...,None].expand(-1,-1,4),values*w.reshape(batch,-1,1))
        total=accum[...,3];alpha=1-torch.exp(-total);rgb=accum[...,:3]/total[...,None].clamp(min=.001)
        image=rgb.reshape(batch,N,N,3)*alpha.reshape(batch,N,N,1)+base[ids]*(1-alpha.reshape(batch,N,N,1))
        image=torch.where(image<=.0031308,12.92*image,1.055*image.clamp(min=.0031308).pow(1/2.4)-.055)
        if offset is not None:image=image+offset[ids]
        return image,displacement,delta
    with torch.no_grad():
        outputs=[field(list(range(i,min(len(f),i+10)))) for i in range(0,len(f),10)]
        anchor_field=torch.cat([q[0] for q in outputs]);anchor_density=torch.cat([q[1] for q in outputs])
        offset=actual-torch.cat([render(list(range(i,min(len(f),i+10))))[0] for i in range(0,len(f),10)])
    def blur(x):return F.avg_pool2d(F.pad(x.permute(0,3,1,2),(2,2,2,2),mode='replicate'),5,stride=1).permute(0,2,3,1)
    low_target=blur(target)
    def loss(ids,regular=True):
        image,v,d=render(ids);w=mask[ids][...,None]
        def norm(diff):return (torch.sqrt(diff.square()+.003**2)*w).sum()/w.sum().clamp(min=1)/3
        error=norm(image-target[ids])
        if not regular:return error
        error=error+.25*norm(blur(image)-low_target[ids])
        change=v-anchor_field[ids];cx=(change[:,:-1,1:]-change[:,:-1,:-1])*N/SIZE;cy=(change[:,1:,:-1]-change[:,:-1,:-1])*N/SIZE
        dx=(v[:,:-1,1:]-v[:,:-1,:-1])*N/SIZE;dy=(v[:,1:,:-1]-v[:,:-1,:-1])*N/SIZE
        det=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        return error+.002*(cx.square().mean()+cy.square().mean())+.08*F.relu(.2-det).square().mean()+.025*change.square().mean()+.0004*d.square().mean()
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();best=1.3;opt=torch.optim.AdamW(model.parameters(),lr=.00014,weight_decay=.001);start=time.monotonic();history=[]
    print('当前完整渲染初始',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(f),(5,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('当前共同输运',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result={k:initial[k] for k in ['displacement_scale','density_scale','flow_steps','canonical_gain','multiscale_features']}
                result.update(train=NAMES,holdout=[],selection=row,before=before,scores=s,
                    layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
