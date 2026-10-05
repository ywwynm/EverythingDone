"""可微粗粒化渲染拟合共同形变基，再交给完整 GPU 颗粒渲染独立复查。

逐帧层缓存仅用于拟合；运行端只保存一份共同系数，不读取逐素材缓存。
"""
from pathlib import Path
import argparse,hashlib,json,sys,time
import numpy as np,cv2,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from conditional_material import ConditionalRenderer,ConditionalRules
from learned_release import load_weights
from flow_basis import basis
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

N=80;LO=-.38;SIZE=1.76
NAMES=['kobe','thanos','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in [1,4,5,6,7]]
PHASES=[.12,.20,.28,.36,.44,.52,.60,.68,.76,.84]
OUT=HERE/'analysis/match-cache/shared-flow-basis'


def linear(c):return np.where(c<=.04045,c/12.92,((c+.055)/1.055)**2.4)


def prepare(weight_path=None,transfer_config=None,names=None,coverage_config=None):
    names=NAMES if names is None else names
    weight_path=Path(weight_path) if weight_path else HERE/'conditional-release-weights.json'
    identity=hashlib.sha256(b'layer-residual-v2'+weight_path.read_bytes()+b''.join((HERE/p).read_bytes() for p in ['conditional_material.py','conditional-material-rules.json','learned_release.py','coupled_transport.py','renderer.py'])).hexdigest()[:12]
    if transfer_config:
        identity=hashlib.sha256(identity.encode()+Path(transfer_config).read_bytes()+(HERE/'surface_transfer.py').read_bytes()).hexdigest()[:12]
    if coverage_config:
        identity=hashlib.sha256(identity.encode()+Path(coverage_config).read_bytes()+(HERE/'particle_coverage.py').read_bytes()).hexdigest()[:12]
    identity=hashlib.sha256(identity.encode()+json.dumps(names).encode()).hexdigest()[:12]
    path=OUT/f'layers-{identity}.npz'
    if path.exists():
        d=np.load(path);return {k:d[k] for k in d.files}
    weights=load_weights(weight_path)
    rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    cls=ConditionalRenderer;extra={}
    if transfer_config:
        from surface_transfer import TransferRenderer,TransferRules
        d=json.loads(Path(transfer_config).read_text('utf-8'));rules=ConditionalRules(**d['material']);cls=TransferRenderer
        extra=dict(transfer=TransferRules(**d['transfer']),transport_weights=[(np.zeros((3,31),np.float32),np.zeros(3,np.float32))])
    if coverage_config:
        if not transfer_config:raise ValueError('覆盖实验需要明确表面交接配置')
        from particle_coverage import CoverageRenderer,load_coverage
        cls=CoverageRenderer;extra['coverage']=load_coverage(Path(coverage_config))
    ctx=moderngl.create_standalone_context(require=430)
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    collected={k:[] for k in ['cloud','surface','background','baseline','warmup','target','mask','basis','gain','scene','phase']}
    class LayerRenderer(cls):
        fragment_source=cls.fragment_source.replace('frag=vec4(color*alpha,alpha);','if(diagnostic==4)color=vec3(age);\n    frag=vec4(color*alpha,alpha);')
    for ni,name in enumerate(names):
        comp=Comparison(name);m=comp.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
        sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=LayerRenderer(name,sites=sites,weights=weights,rules=rules,ctx=ctx,quality=2,**extra)
        qx=x+xx*w;qy=y+yy*h
        # 在完整像素上先低通，再采样，避免把单个颗粒随机位置当成可导几何。
        sigma=min(w,h)/N*.50
        def resample(a):return cv2.remap(cv2.GaussianBlur(a,(0,0),sigma),qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
        bg=linear(comp.scene.bg/255.)
        mask=cv2.remap(comp.scene.roi.astype('float32'),qx,qy,cv2.INTER_NEAREST,borderMode=cv2.BORDER_CONSTANT)
        for t in PHASES:
            index,target=comp.ref.nearest(t);t=float(comp.ref.progress[index]);baseline=r.render(t)
            layers=[]
            for material_pass,diagnostic in [(0,0),(1,0),(1,4)]:
                r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
                r.program['material_pass']=material_pass;r.program['diagnostic']=diagnostic;r.fg_tex.use(0)
                r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
                a=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype('float32')
                a=cv2.resize(a,(r.w,r.h),interpolation=cv2.INTER_AREA)
                layers.append(resample(a))
            # 基函数按渲染器实际时钟求值；拟合、运行端使用同一时刻。
            actual=t+r.layout_change*rules.clock*4*t*(1-t)
            b=basis(xx,yy,actual,w,h,m['direction'],sites)
            warmup=np.clip(layers[2][...,0]/np.maximum(layers[2][...,3],.001)/.03,0,1);warmup=warmup*warmup*(3-2*warmup)
            for k,v in dict(cloud=layers[1],surface=layers[0],background=resample(bg),baseline=resample(baseline.astype('float32')/255.),warmup=warmup,target=resample(target.astype('float32')/255.),mask=mask,
                            basis=b,gain=np.float32(r.layout_change),scene=ni,phase=t).items():collected[k].append(v)
        r.close();print('层缓存',name,flush=True)
    ctx.release();result={k:np.asarray(v) for k,v in collected.items()};np.savez_compressed(path,**result);return result


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=1800);ap.add_argument('--label',default='first');a=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);data=prepare()
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(26)
    def tensor(a):return torch.tensor(a.tolist(),dtype=torch.float32)
    b=tensor(data['basis'].reshape(-1,N*N,54,2));gain=tensor(data['gain'])
    cloud=tensor(data['cloud'].reshape(-1,N*N,4));surface=tensor(data['surface']);bg=tensor(data['background']);target=tensor(data['target']);mask=tensor(data['mask'])
    warmup=tensor(data['warmup'].reshape(-1,N*N,1));baseline=tensor(data['baseline']);offset=None
    opacity=cloud[...,3].clamp(0,.999);tau=-torch.log1p(-opacity);color=cloud[...,:3]/opacity[...,None].clamp(min=.001)
    yy,xx=torch.meshgrid(torch.arange(N),torch.arange(N),indexing='ij');coords=torch.stack([xx,yy],-1).reshape(-1,2).float()
    param=torch.zeros(54,requires_grad=True);opt=torch.optim.Adam([param],lr=.018)
    def render(ids):
        coeff=.35*torch.tanh(param)
        field=.32*torch.tanh(torch.einsum('bnkc,k->bnc',b[ids],coeff)/.32)*gain[ids,None,None]
        pos=coords+field*warmup[ids]*(N/SIZE)
        p0=pos.floor().detach();f=pos-p0;batch=len(ids);pixels=N*N
        accum=torch.zeros((batch,pixels,4))
        values=torch.cat([color[ids]*tau[ids,:,None],tau[ids,:,None]],-1)
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            p=p0+torch.tensor([dx,dy]);valid=(p[...,0]>=0)&(p[...,0]<N)&(p[...,1]>=0)&(p[...,1]<N)
            weight=(f[...,0] if dx else 1-f[...,0])*(f[...,1] if dy else 1-f[...,1])*valid
            index=(p[...,1].clamp(0,N-1)*N+p[...,0].clamp(0,N-1)).long()
            accum.scatter_add_(1,index[...,None].expand(-1,-1,4),values*weight[...,None])
        total=accum[...,3];alpha=1-torch.exp(-total)
        rgb=accum[...,:3]/total[...,None].clamp(min=.001)*alpha[...,None]
        base=surface[ids,:,:,:3]+bg[ids]*(1-surface[ids,:,:,3:])
        result=rgb.reshape(batch,N,N,3)+base*(1-alpha.reshape(batch,N,N,1))
        result=torch.where(result<=.0031308,12.92*result,1.055*result.clamp(min=.0031308).pow(1/2.4)-.055)
        if offset is not None:result=result+offset[ids]
        return result,field.reshape(batch,N,N,2)
    def loss(ids,regular=True):
        rendered,field=render(ids);diff=rendered-target[ids]
        error=(torch.sqrt(diff*diff+.003**2)*mask[ids,:,:,None]).sum()/mask[ids].sum().clamp(min=1)/3
        if not regular:return error
        # 约束形变导数，禁止把宽粒子群压成孤立细线；惩罚局部折叠。
        dx=(field[:,:-1,1:]-field[:,:-1,:-1])*(N/SIZE)
        dy=(field[:,1:,:-1]-field[:,:-1,:-1])*(N/SIZE)
        det=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        curvature=(field[:,2:]-2*field[:,1:-1]+field[:,:-2]).square().mean()+(field[:,:,2:]-2*field[:,:,1:-1]+field[:,:,:-2]).square().mean()
        reg=.004*(dx.square().mean()+dy.square().mean())+.03*F.relu(.55-det).square().mean()+.001*torch.tanh(param).square().mean()+.2*curvature
        return error+reg
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    # 使用真实渲染器的零修正画面校准近似渲染的静态偏差；只拟合可微近似的变化量。
    with torch.no_grad():
        zero=torch.cat([render(list(range(i,min(i+10,len(cloud)))))[0] for i in range(0,len(cloud),10)])
        offset=baseline-zero
    start=time.monotonic();before=scores();print('初始',before,flush=True);best=None;history=[]
    for step in range(a.steps):
        opt.zero_grad();ids=torch.randint(len(cloud),(6,));value=loss(ids);value.backward();opt.step()
        if (step+1)%150==0:
            s=scores();ratios=[s[n]/before[n] for n in NAMES];score=float(np.mean(ratios)+.25*max(ratios))
            row=dict(step=step+1,score=score,scores=s,coefficients=(.35*torch.tanh(param)).detach().tolist(),before=before)
            history.append(row)
            if best is None or score<best['score']:best=row;(OUT/f'{a.label}.json').write_text(json.dumps(row,ensure_ascii=False,indent=2),'utf-8')
            print(step+1,round(time.monotonic()-start,1),round(score,4),{n:round(s[n]/before[n],3) for n in NAMES},flush=True)
    (OUT/f'{a.label}-history.json').write_text(json.dumps(history,ensure_ascii=False),'utf-8')


if __name__=='__main__':main()
