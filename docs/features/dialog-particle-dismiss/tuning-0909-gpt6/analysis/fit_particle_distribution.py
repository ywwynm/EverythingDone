"""联合局部 RGB 分位数、均值和粒度响应，区分稠密细粒与稀疏大片。

运行参数仍为一份共同函数，白色项表示运动微片的中性反射，不改原表面或参考素材。
是否采用由真实 GPU 的另相位复核决定。
"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import cv2,numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_population import PopulationRenderer
from fit_particle_population import review
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES
from match_compare import Comparison
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

N=64;LO=-.25;SIZE=1.5;PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84]
SCALES=np.array([.60,.80,.12],np.float32);DELTAS=np.array([.30,.45,.06],np.float32)
OUT=HERE/'analysis/match-cache/particle-distribution'


def prepare(balanced=False):
    sources=['renderer.py','particle_population.py','surface_handoff.py','shared_transport.py','particle_cohorts.py','surface_coherence.py','surface_transfer.py','particle_coverage.py',
        'conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json',
        'particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','analysis/fit_particle_distribution.py']
    key=hashlib.sha256(str(balanced).encode()+b''.join((HERE/f).read_bytes() for f in sources)).hexdigest()[:16]
    OUT.mkdir(parents=True,exist_ok=True);path=OUT/f'response-{key}.npz'
    if path.exists():
        z=np.load(path);return {k:z[k] for k in z.files}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    data={k:[] for k in ['features','target','actual','slope','curve','mask','scene','phase']}
    for ni,name in enumerate(NAMES):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=PopulationRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);qx=x+xx*w;qy=y+yy*h
        oy,ox=np.mgrid[-5:6,-5:6];sx=np.clip(qx[...,None].round().astype('int32')+ox.ravel(),0,m['frame'][0]-1)
        sy=np.clip(qy[...,None].round().astype('int32')+oy.ravel(),0,m['frame'][1]-1)
        def compact(frame):
            f=frame.astype('float32')/255.
            mean=cv2.remap(cv2.GaussianBlur(f,(0,0),4.),qx,qy,cv2.INTER_LINEAR)
            bands=[]
            for sigma in [.7,1.5,3.]:
                e=cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),sigma)),(0,0),8.)
                bands.append(cv2.remap(e,qx,qy,cv2.INTER_LINEAR))
            samples=f[sy,sx]
            quantiles=np.quantile(samples,[.10,.35,.65,.90,.98],axis=-2).transpose(1,2,0,3).reshape(N,N,15).astype('float32')
            values=[mean,*bands,quantiles]
            if balanced:
                values.append(cv2.remap(cv2.GaussianBlur(f,(0,0),12.),qx,qy,cv2.INTER_LINEAR))
            return np.concatenate(values,-1)
        mask=cv2.remap(c.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST)
        for requested in PHASES:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);r.program['population_delta']=(0.,0.,0.);base=compact(r.render(p));slopes=[];curves=[]
            for k,delta in enumerate(DELTAS):
                frames=[]
                for sign in [-1,1]:
                    q=[0.,0.,0.];q[k]=float(sign*delta);r.program['population_delta']=q;frames.append(compact(r.render(p)))
                slopes.append((frames[1]-frames[0])/(2*delta));curves.append((frames[1]+frames[0]-2*base)/(2*delta**2))
            clock=p+r.layout_change*kw['rules'].clock*4*p*(1-p)
            values=dict(features=transport_features(xx,yy,clock,w,h,m['direction'],sites,True),actual=base,target=compact(ref),
                slope=np.stack(slopes,-1),curve=np.stack(curves,-1),mask=mask,scene=ni,phase=p)
            for k,v in values.items():data[k].append(v)
        r.close();print('局部分布实际响应',name,flush=True)
    ctx.release();data={k:np.asarray(v) for k,v in data.items()};np.savez_compressed(path,**data);return data


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=6000);ap.add_argument('--label',default='joint-distribution');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES)
    ap.add_argument('--balanced',action='store_true',help='联合约束大尺度覆盖和颗粒能量，避免二者互换')
    ap.add_argument('--response',help='明确复用相同完整模型的响应缓存');a=ap.parse_args()
    out=OUT/a.label;out.mkdir(parents=True,exist_ok=True)
    if a.review:return review(Path(a.review),out,a.scenes)
    if a.response:
        cached=np.load(a.response);data={k:cached[k] for k in cached.files}
    else:data=prepare(a.balanced)
    import torch
    torch.set_num_threads(4);torch.manual_seed(28)
    def tensor(q):
        q=np.ascontiguousarray(q,np.float32);return torch.frombuffer(bytearray(q.tobytes()),dtype=torch.float32).reshape(q.shape)
    f,target,actual,slope,curve,mask=[tensor(data[k]) for k in ['features','target','actual','slope','curve','mask']]
    model=torch.nn.Sequential(torch.nn.Linear(f.shape[-1],32),torch.nn.Tanh(),torch.nn.Linear(32,32),torch.nn.Tanh(),torch.nn.Linear(32,3))
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias);scales=tensor(SCALES)
    # 以局部像素分布对照而非逐粒随机位置对照；均值仍占主要份额。
    channels=tensor([1.]*3+[.30]*9+[.07]*15+[1.]*3 if a.balanced else [1.]*3+[.10]*9+[.14]*15)
    if a.balanced:assert target.shape[-1]==30,'平衡校准要求包含 12 像素尺度响应的新缓存'
    prior_diff=torch.sqrt((actual-target).square()+.003**2)
    def loss(ids,regular=True):
        v=model(f[ids]).tanh();delta=scales*v
        image=actual[ids]+(slope[ids]*delta[...,None,:]+curve[ids]*delta[...,None,:].square()).sum(-1)
        w=mask[ids][...,None]
        diff=torch.sqrt((image-target[ids]).square()+.003**2)
        e=(diff*w*channels).sum()/w.sum().clamp(min=1)/channels.sum()
        if not regular:return e
        rgb=(diff[...,:3]*w).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/3
        prior=(prior_diff[ids,...,:3]*w).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/3
        guard=0.
        if a.balanced:
            # 逐相位共同约束，不能让细节目标以大片覆盖失真为代价。
            for lo,hi,gain in [(6,9,3.),(27,30,1.5)]:
                current=(diff[...,lo:hi]*w).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/(hi-lo)
                baseline=(prior_diff[ids,...,lo:hi]*w).sum((1,2,3))/mask[ids].sum((1,2)).clamp(min=1)/(hi-lo)
                guard=guard+gain*torch.relu(current-baseline).mean()
        dx=v[:,1:]-v[:,:-1];dy=v[:,:,1:]-v[:,:,:-1]
        return e+guard+torch.relu(rgb-prior).mean()+.00025*v.square().mean()+.025*(dx.square().mean()+dy.square().mean())
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    before=scores();best=1.3;opt=torch.optim.AdamW(model.parameters(),lr=.0007,weight_decay=.004);start=time.monotonic();history=[]
    print('局部分布初始',before,flush=True)
    for step in range(a.steps):
        opt.zero_grad();v=loss(torch.randint(len(f),(6,)));v.backward();opt.step()
        if (step+1)%500==0:
            s=scores();ratios={n:s[n]/before[n] for n in NAMES};score=np.mean(list(ratios.values()))+.3*max(ratios.values());row=dict(step=step+1,score=float(score),ratios=ratios);history.append(row)
            print('共同局部分布',step+1,round(time.monotonic()-start,1),round(score,4),{k:round(v,3) for k,v in ratios.items()},flush=True)
            if score<best:
                best=score;result=dict(scales=SCALES.tolist(),train=NAMES,holdout=[],selection=row,before=before,scores=s,balanced=a.balanced,
                    layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
