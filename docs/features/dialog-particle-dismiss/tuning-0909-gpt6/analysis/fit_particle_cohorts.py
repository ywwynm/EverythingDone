"""分年龄层求共同覆盖响应；近似结果必须交给完整 GPU 与原片验证。"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import cv2,numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,load_coverage
from fit_particle_optics import common,NAMES
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from fit_shared_flow_basis import linear,N,LO,SIZE,PHASES


def prepare():
    identity=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in ['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json','particle-coverage-rules.json','particle_coverage.py','surface_transfer.py','shared_transport.py','analysis/fit_particle_cohorts.py'])).hexdigest()[:14]
    path=HERE/'analysis/match-cache/particle-cohorts'/f'layers-{identity}.npz';path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json')
    class Layers(CoverageRenderer):
        fragment_source=CoverageRenderer.fragment_source.replace('void main(){','uniform int cohort_layer;\nvoid main(){',1)
        fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','''
        if(cohort_layer>=0){
            float mature=smoothstep(.06,.16,age),elder=smoothstep(.22,.38,age);
            vec3 group=vec3(1.-mature,mature*(1.-elder),mature*elder)*smoothstep(.005,.035,age);
            alpha=1.-pow(max(1.-alpha,.00001),group[cohort_layer]);
        }
        frag=vec4(color*alpha,alpha);''',1)
    data={k:[] for k in ['cloud','surface','bg','actual','target','mask','features','scene','phase']}
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=Layers(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        def resample(f):return cv2.remap(cv2.GaussianBlur(f,(0,0),min(w,h)/N*.50),qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
        bg=resample(linear(c.scene.bg.astype('float32')/255.));mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        for t in PHASES:
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);r.program['cohort_layer']=-1;actual=r.render(p);layers=[]
            for material_pass,cohort in [(0,-1),(1,0),(1,1),(1,2)]:
                r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
                r.program['material_pass']=material_pass;r.program['cohort_layer']=cohort;r.program['diagnostic']=0;r.fg_tex.use(0)
                r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
                arr=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype('float32');arr=cv2.resize(arr,(r.w,r.h),interpolation=cv2.INTER_AREA);layers.append(resample(arr))
            actual_p=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            f=transport_features(xx,yy,actual_p,w,h,m['direction'],sites,True)
            for k,v in dict(cloud=layers[1:],surface=layers[0],bg=bg,actual=resample(actual.astype('float32')/255.),target=resample(np.asarray(ref).astype('float32')/255.),mask=mask,features=f,scene=ni,phase=p).items():data[k].append(v)
        r.close();print('年龄层',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()};np.savez_compressed(path,**data);return data


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=4000);ap.add_argument('--label',default='shared-cohorts');a=ap.parse_args();data=prepare()
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(a):
        q=np.ascontiguousarray(a,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    features=tensor(data['features']);cloud=tensor(data['cloud']);surface=tensor(data['surface']);bg=tensor(data['bg']);actual=tensor(data['actual']);target=tensor(data['target']);mask=tensor(data['mask'])
    alpha=cloud[...,3].clamp(0,.999);tau=-torch.log1p(-alpha);color=cloud[...,:3]/alpha[...,None].clamp(min=.001)
    base=surface[...,:3]+bg*(1-surface[...,3:]);offset=None
    model=torch.nn.Sequential(torch.nn.Linear(features.shape[-1],32),torch.nn.Tanh(),torch.nn.Linear(32,32),torch.nn.Tanh(),torch.nn.Linear(32,3))
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias)
    scale=1.2
    def render(ids):
        density=scale*model(features[ids]).tanh();depth=tau[ids]*torch.exp(density.permute(0,3,1,2))
        total=depth.sum(1);alpha=1-torch.exp(-total);rgb=(color[ids]*depth[...,None]).sum(1)/total[...,None].clamp(min=.001)
        result=rgb*alpha[...,None]+base[ids]*(1-alpha[...,None]);result=torch.where(result<=.0031308,12.92*result,1.055*result.clamp(min=.0031308).pow(1/2.4)-.055)
        if offset is not None:result=result+offset[ids]
        return result,density
    def blur(q):return F.avg_pool2d(F.pad(q.permute(0,3,1,2),(2,2,2,2),mode='replicate'),5,stride=1).permute(0,2,3,1)
    target_low=blur(target)
    def loss(ids,regular=True):
        image,d=render(ids);w=mask[ids][...,None]
        e=(torch.sqrt((image-target[ids]).square()+.003**2)*w).sum()/w.sum().clamp(min=1)/3
        if not regular:return e
        e=e+.4*(torch.sqrt((blur(image)-target_low[ids]).square()+.003**2)*w).sum()/w.sum().clamp(min=1)/3
        dx=d[:,1:]-d[:,:-1];dy=d[:,:,1:]-d[:,:,:-1]
        return e+.0007*d.square().mean()+.03*(dx.square().mean()+dy.square().mean())
    with torch.no_grad():offset=actual-torch.cat([render(list(range(i,min(len(cloud),i+10))))[0] for i in range(0,len(cloud),10)])
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();best=1.35;opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.004);start=time.monotonic();history=[]
    out=HERE/'analysis/match-cache/particle-cohorts'/a.label;out.mkdir(parents=True,exist_ok=True)
    print('年龄响应初始',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(cloud),(6,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=float(np.mean(list(ratios.values()))+.35*max(ratios.values()))
            row=dict(step=step+1,score=score,ratios=ratios);history.append(row);print('年龄覆盖',step+1,round(time.monotonic()-start,1),round(score,4),{n:round(r,3) for n,r in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scale=scale,calibration='共同起点几何、位置、进度条件下的三年龄覆盖响应',train=NAMES,holdout=[],before=before,scores=s,selection=row,layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
