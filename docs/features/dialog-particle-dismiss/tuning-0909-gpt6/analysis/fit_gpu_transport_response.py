"""用完整 GPU 的局部位移/覆盖响应细化同一输运权重，避免先粗粒化再重建。

响应展开点仅在离线拟合使用；运行仍使用原 shared_transport 的相同三个输出。
颜色诊断读回像素处实际材料的场采样位置，不把屏幕位置误当作原场坐标。
"""
from pathlib import Path
import sys,json,argparse,math,time,hashlib
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from shared_transport import transport_features,load_transport
from match_compare import Comparison
from fit_particle_optics import NAMES
from fit_current_transport import review
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=64;FIELD=56;LO=-.38;SIZE=1.76
PHASES=[.12,.22,.32,.42,.52,.62,.72,.82]
DELTAS=np.array([.012,.012,.20],np.float32)
OUT=HERE/'analysis/match-cache/gpu-transport-response'


class ProbeRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform vec3 transport_probe;\nflat out vec2 probe_input;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 q=(move/card+vec2(.38))/1.76;','vec2 q=(move/card+vec2(.38))/1.76;\n    probe_input=q;',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', 'move+=transport_probe.xy*span*smoothstep(0.,.03,age);\n    vec2 vertex=move+',1)
    vertex_source=vertex_source.replace('float count_shift=min(density_out*coverage_area,','density_out+=transport_probe.z*smoothstep(0.,.03,age);\n    float count_shift=min(density_out*coverage_area,',1)
    fragment_source=CoherenceRenderer.fragment_source.replace('void main(){','flat in vec2 probe_input;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','if(diagnostic==4)color=vec3(probe_input,1.);\n    frag=vec4(color*alpha,alpha);',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw);self.program['transport_probe']=(0.,0.,0.)


def prepare(initial,grain=None,occupancy=False):
    files=['renderer.py','surface_coherence.py','surface-coherence-weights.json','particle_cohorts.py',
        'particle-cohort-weights.json','conditional-release-weights.json','conditional-material-rules.json',
        'surface-transfer-rules.json','particle-coverage-rules.json','analysis/fit_gpu_transport_response.py']
    key=hashlib.sha256(initial.read_bytes()+str((grain,occupancy)).encode()+b''.join((HERE/f).read_bytes() for f in files if (HERE/f).exists())).hexdigest()[:16]
    path=OUT/f'response-{key}.npz'
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);kw['transport_weights']=load_transport(initial)
    if grain is not None:
        import dataclasses
        kw['coverage']=dataclasses.replace(kw['coverage'],grain=grain/kw['rules'].grain)
    yy,xx=np.mgrid[:N,:N].astype(np.float32);xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    fy,fx=np.mgrid[:FIELD,:FIELD].astype(np.float32);fx=LO+(fx+.5)/FIELD*SIZE;fy=LO+(fy+.5)/FIELD*SIZE
    data={k:[] for k in ['features','target','actual','slope','lookup','mask','phase','rotation','scale','scene']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=ProbeRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def sample(f):return cv2.remap(f,qx,qy,cv2.INTER_LINEAR)
        def compact(f):
            channels=[]
            if occupancy:
                scene=c.scene;eb=scene.excess(f,scene.bgmax,scene.bgmin);es=scene.excess(f,scene.srcmax,scene.srcmin)
                soft=lambda v:1./(1.+np.exp(np.clip(-v,-25.,25.)))
                cloud=soft((eb-scene.threshold)/8.)*soft((es-28.)/8.)
                channels=[sample(cv2.GaussianBlur(cloud,(0,0),s))[...,None] for s in [8.,20.]]
            f=f.astype(np.float32)/255.
            return np.concatenate([*[sample(cv2.GaussianBlur(f,(0,0),s)) for s in [4.,12.,24.]],*channels],-1)
        mask=sample(c.scene.roi.astype(np.float32));angle=math.radians(m['direction']);cs,ss=math.cos(angle),-math.sin(angle)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['transport_probe']=(0.,0.,0.);actual=compact(r.render(p))
            # 只画运动材料，读回混合前的场坐标和透明度。
            r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
            r.program['material_pass']=1;r.program['diagnostic']=4;r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
            layer=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype(np.float32)
            layer=cv2.resize(layer,(r.w,r.h),interpolation=cv2.INTER_AREA);layer=cv2.GaussianBlur(layer,(0,0),4.);layer=sample(layer)
            lookup=layer[...,:2]/np.maximum(layer[...,3:],.0001)
            fallback=np.stack([(xx-LO)/SIZE,(yy-LO)/SIZE],-1);lookup=np.where(layer[...,3:]>.003,lookup,fallback)
            slope=[]
            for k,delta in enumerate(DELTAS):
                frames=[]
                for sign in [-1,1]:
                    q=[0.,0.,0.];q[k]=float(delta*sign);r.program['transport_probe']=q;frames.append(compact(r.render(p)))
                slope.append((frames[1]-frames[0])/(2*delta))
            clock=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            row=dict(features=transport_features(fx,fy,clock,w,h,m['direction'],sites,True),target=compact(ref),actual=actual,
                slope=np.stack(slope,-1),lookup=lookup,mask=mask,phase=clock,rotation=[[cs,ss],[-ss,cs]],scale=[min(w,h)/w,min(w,h)/h],scene=ni)
            for k,v in row.items():data[k].append(v)
        r.close();print('真实 GPU 输运响应',name,flush=True)
    ctx.release();data={k:np.asarray(v,np.float32) for k,v in data.items()};np.savez_compressed(path,**data);return data


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--initial',default=str(HERE/'conditional-transport-weights.json'));ap.add_argument('--steps',type=int,default=3000);ap.add_argument('--label',default='first');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--grain',type=float);ap.add_argument('--occupancy',action='store_true');a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes)
    initial=Path(a.initial);data=prepare(initial,a.grain,a.occupancy);config=json.loads(initial.read_text('utf-8'))
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(q):
        q=np.ascontiguousarray(q,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,target,actual,slope,lookup,mask,phase,rotation,scale=[tensor(data[k]) for k in ['features','target','actual','slope','lookup','mask','phase','rotation','scale']]
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],48),torch.nn.Tanh(),torch.nn.Linear(48,48),torch.nn.Tanh(),torch.nn.Linear(48,3))
    with torch.no_grad():
        for layer,saved in zip([q for q in model if isinstance(q,torch.nn.Linear)],config['layers']):layer.weight.copy_(tensor(saved['weight']));layer.bias.copy_(tensor(saved['bias']))
    yy,xx=torch.meshgrid(torch.arange(FIELD),torch.arange(FIELD),indexing='ij');grid=(torch.stack([xx,yy],-1).float()+.5)/FIELD*2-1
    def field(ids):
        out=model(f[ids]).tanh();v=config['displacement_scale']*phase[ids,None,None,None]*out[...,:2]
        v=torch.einsum('bxyc,bcd->bxyd',v,rotation[ids])*scale[ids,None,None,:]/(2**config['flow_steps'])
        for _ in range(config['flow_steps']):v=v+F.grid_sample(v.permute(0,3,1,2),grid[None]+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return torch.cat([v/scale[ids,None,None,:],config['density_scale']*out[...,2:]],-1)
    def sample_field(field,ids):return F.grid_sample(field.permute(0,3,1,2),lookup[ids]*2-1,padding_mode='border',align_corners=False).permute(0,2,3,1)
    with torch.no_grad():
        anchors=torch.cat([field(list(range(i,min(len(f),i+8)))) for i in range(0,len(f),8)])
        anchor_sample=sample_field(anchors,list(range(len(f))))
    channels=tensor([1.]*3+[.65]*3+[.35]*3+([1.8,1.8] if a.occupancy else []));baseline=torch.sqrt((actual-target).square()+.003**2)
    def loss(ids,regular=True):
        current=field(ids);delta=sample_field(current,ids)-anchor_sample[ids]
        estimate=actual[ids]+(slope[ids]*delta[...,None,:]).sum(-1)
        diff=torch.sqrt((estimate-target[ids]).square()+.003**2);w=mask[ids][...,None]
        error=(diff*w*channels).sum()/w.sum().clamp(min=1)/channels.sum()
        if not regular:return error
        d=current-anchors[ids];move=d[...,:2]
        # 局部线性化的信赖区限制；不在运行时夹紧粒子位置。
        trust=F.relu(abs(move)-.025).square().mean()+.02*F.relu(abs(d[...,2])-.3).square().mean()
        derivx=(move[:,:-1,1:]-move[:,:-1,:-1])*FIELD/SIZE;derivy=(move[:,1:,:-1]-move[:,:-1,:-1])*FIELD/SIZE
        full=current[...,:2]*scale[ids,None,None,:]
        dx=(full[:,:-1,1:]-full[:,:-1,:-1])*FIELD/SIZE;dy=(full[:,1:,:-1]-full[:,:-1,:-1])*FIELD/SIZE
        det=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        per=(diff*w*channels).sum((1,2,3))/w.sum((1,2,3)).clamp(min=1)/channels.sum()
        prior=(baseline[ids]*w*channels).sum((1,2,3))/w.sum((1,2,3)).clamp(min=1)/channels.sum()
        rgb_guard=0.
        if a.occupancy:
            rgb=((diff[...,:9]-baseline[ids,...,:9])*w*channels[:9]).sum((1,2,3))/w.sum((1,2,3)).clamp(min=1)/channels[:9].sum()
            rgb_guard=3.*torch.relu(rgb-.0003).mean()
        return error+torch.relu(per-prior).mean()+rgb_guard+2.*trust+.01*(derivx.square().mean()+derivy.square().mean())+.06*F.relu(.2-det).square().mean()+.001*d[...,2].square().mean()
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();best=1.3;opt=torch.optim.AdamW(model.parameters(),lr=.00010,weight_decay=.0005);start=time.monotonic();history=[]
    print('GPU 响应初始',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();e=loss(torch.randint(len(f),(6,)));e.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('实际响应细化',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result={k:config[k] for k in ['displacement_scale','density_scale','flow_steps','canonical_gain','multiscale_features']};result.update(train=NAMES,holdout=[],selection=row,grain=a.grain,occupancy=a.occupancy,
                    layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
