"""用真实 GPU 扰动验证逐材料散布近似；离线观测，不修改默认模型。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_proxy import ParticleProxy
from fit_gpu_transport_response import ProbeRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from surface_coherence import CoherenceRenderer

N=192;LO=-.38;SIZE=1.76

class CoupledProbe(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform vec3 transport_probe;\nvoid main(){',1)
    vertex_source=vertex_source.replace('move+=transport.xy*span*smoothstep(0.,.03,age);','move+=(transport.xy+transport_probe.xy)*span*smoothstep(0.,.03,age);',1)
    vertex_source=vertex_source.replace('density_out=transport.z*smoothstep(0.,.03,age);','density_out=(transport.z+transport_probe.z)*smoothstep(0.,.03,age);',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw);self.program['transport_probe']=(0.,0.,0.)

def linear(x):return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)

def capture(r,p,reader,n=N):
    actual=r.render(p);g=reader.read();surface=reader.surface()
    m=r.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
    yy,xx=np.mgrid[:n,:n].astype(np.float32);xx=x+(LO+(xx+.5)/n*SIZE)*w;yy=y+(LO+(yy+.5)/n*SIZE)*h
    # 在缩小之前低通，避免把独立颗粒的像素相位混叠成错误的云团颜色。
    sample=lambda im:cv2.remap(cv2.GaussianBlur(im,(0,0),max(.5,.45*w*SIZE/n),sigmaY=max(.5,.45*h*SIZE/n)),xx,yy,cv2.INTER_LINEAR)
    surface=cv2.resize(surface,(r.w,r.h),interpolation=cv2.INTER_AREA)
    bg=np.asarray(Image.open(r.directory/'background.png').convert('RGB'),np.float32)/255
    dim=r.bg_program['dim'].value;bg=linear(bg*(1-dim))
    base=surface[...,:3]+bg*(1-surface[...,3:])
    state=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)
    physical=state[:,:2]+state[:,4:6]*r.program['extrapolate'].value
    live=g['unit_mass']>.00001
    parent=g['parent'][live]
    cohort=np.frombuffer(r.cohort_tex.read(),np.float32).reshape(41,56,56,3)
    phase=np.clip(r.program['time'].value,0,1)*40;i=int(phase);j=min(40,i+1)
    cohort=cohort[i]*(1-(phase-i))+cohort[j]*(phase-i)
    result=dict(pos=((g['pos'][live]-[x,y])/[w,h]-LO)/SIZE,
        query=(physical[parent]/[w,h]-LO)/SIZE,
        origin=(r.base[parent,:2]/[w,h]-LO)/SIZE,
        color=g['color'][live],mass=g['mass'][live]*n*n/(w*h*SIZE*SIZE),
        unit_mass=g['unit_mass'][live]*n*n/(w*h*SIZE*SIZE),density=g['density'][live],
        keep0=float(r.program['conditional_keep'].value),keep_random=np.mod(g['random'][live,0]*47.123+g['random'][live,2]*19.817,1),
        coverage=np.array([r.coverage_rules.area,r.coverage_rules.count_share],np.float32),cohort=cohort,
        warm=np.clip(g['age'][live]/.03,0,1)**2*(3-2*np.clip(g['age'][live]/.03,0,1)),
        base=sample(base),actual=sample(actual.astype(np.float32)/255),phase=r.program['time'].value,
        span=r.span,card=np.array([w,h]),n=n,age=g['age'][live])
    if hasattr(r,'scatter_tex'):
        scatter=np.frombuffer(r.scatter_tex.read(),np.float32).reshape(41,64,64,3)
        result['scatter']=scatter[i]*(1-(phase-i))+scatter[j]*(phase-i)
        result['carrier']=(g['carrier'][live]/[w,h]-LO)/SIZE
        result.update(unit_alpha=g['unit_alpha'][live],sample_area=g['area'][live]*n*n/(w*h*SIZE*SIZE),
            projected=g['projected'][live],child=g['child'][live],scaled_offset=g['scaled_offset'][live]/[w,h]/SIZE,
            fission=np.array([r.program['fission_strength'].value,r.program['fission_threshold'].value],np.float32))
    return result

class Splat:
    def __init__(self,device='cuda',n=N):
        import torch
        self.torch=torch;self.device=device;self.n=n
    def tensor(self,x):
        return self.torch.as_tensor(np.asarray(x,np.float32),device=self.device)
    def gaussian(self,x,sigma):
        torch=self.torch;F=torch.nn.functional
        radius=max(1,int(sigma*3));q=torch.arange(-radius,radius+1,device=x.device,dtype=x.dtype)
        kernel=torch.exp(-.5*(q/sigma)**2);kernel/=kernel.sum()
        c=x.shape[1]
        return F.conv2d(F.conv2d(x,kernel.view(1,1,1,-1).expand(c,1,1,-1),padding=(0,radius),groups=c),kernel.view(1,1,-1,1).expand(c,1,-1,1),padding=(radius,0),groups=c)
    def area_ratio(self,d,density_delta):
        torch=self.torch;old=d['density'];new=old+density_delta;area,count=d['coverage']
        cap=-d['keep0'].log();before=torch.minimum(old*area,cap)*count;after=torch.minimum(new*area,cap)*count
        return (density_delta*area-(after-before)).exp()
    def fission_position(self,d,pos,density_delta):
        if 'unit_alpha' not in d:return pos
        return pos+d['scaled_offset']*(self.area_ratio(d,density_delta).sqrt()-1)[:,None]
    def material_mass(self,d,density_delta,scatter_density=None):
        torch=self.torch;old=d['density'];new=old+density_delta;area,count=d['coverage']
        cap=-d['keep0'].log();before=torch.minimum(old*area,cap)*count;after=torch.minimum(new*area,cap)*count
        keep=(d['keep0']*after.exp()).clamp(0,1)
        lo=(keep-.05).clamp(min=0);hi=(keep+.05).clamp(max=1)
        u=((d['keep_random']-lo)/(hi-lo).clamp(min=.00001)).clamp(0,1)
        retain=(1-count)*(d['keep_random']<=d['keep0'])+count*(1-u*u*(3-2*u))
        age=((d['age']-.008)/(.06-.008)).clamp(0,1);warm=age*age*(3-2*age)
        if 'unit_alpha' in d:
            if float(area)!=1.:raise ValueError('完整细分材料近似目前只验证了默认面积覆盖')
            ratio=(density_delta*area-(after-before)).exp();strength,threshold=d['fission']
            projected=d['projected']*ratio.sqrt();q=((projected-threshold)/(threshold*.8)).clamp(0,1)
            fage=((d['age']-.015)/.065).clamp(0,1);divided=strength*q*q*(3-2*q)*fage*fage*(3-2*fage)
            fraction=torch.where(d['child']<.5,1-divided,divided)
            alpha=d['unit_alpha']*((1-warm+warm*retain)*fraction)[:,None]
            if scatter_density is None:scatter_density=self.scatter(d,d['carrier'])[:,0]
            alpha=1-(1-alpha).clamp(min=.00001).pow(scatter_density.exp()[:,None])
            return alpha.mean(-1)*d['sample_area']*ratio
        return d['unit_mass']*(density_delta*area-(after-before)).exp()*(1-warm+warm*retain)
    def cohort(self,d,pos):
        torch=self.torch;F=torch.nn.functional
        value=F.grid_sample(d['cohort'].permute(2,0,1)[None],pos[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
        def ease(lo,hi):
            q=((d['age']-lo)/(hi-lo)).clamp(0,1);return q*q*(3-2*q)
        mature=ease(.06,.16);elder=ease(.22,.38)
        ages=torch.stack([1-mature,mature*(1-elder),mature*elder],-1)
        return (value*ages).sum(-1)*ease(.005,.035)
    def scatter(self,d,pos):
        F=self.torch.nn.functional
        value=F.grid_sample(d['scatter'].permute(2,0,1)[None],pos[None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
        u=((d['age']-.02)/.08).clamp(0,1)
        return value*(u*u*(3-2*u))[:,None]
    def shade(self,color,old,new):
        # 反解共同中性反射／色度的线性部分，保留逐片携带的源颜色。
        torch=self.torch;weights=color.new_tensor([.2126,.7152,.0722])
        luma=(color*weights).sum(-1,keepdim=True)-old[:,1:2]
        source=(color-old[:,1:2]+luma*old[:,2:3])/(1+old[:,2:3]).clamp(min=.05)
        return (source+new[:,1:2]+new[:,2:3]*(source-luma)).clamp(min=0)
    def render(self,pos,mass,color,base,return_opacity=False):
        torch=self.torch;n=self.n
        q=pos*n-.5;ij=q.floor().long();f=q-ij
        value=torch.cat([color*mass[:,None],mass[:,None]],-1)
        accum=torch.zeros((n*n,4),device=pos.device)
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            xx=ij[:,0]+dx;yy=ij[:,1]+dy
            weight=(f[:,0] if dx else 1-f[:,0])*(f[:,1] if dy else 1-f[:,1])
            valid=(xx>=0)&(xx<n)&(yy>=0)&(yy<n)
            index=(yy.clamp(0,n-1)*n+xx.clamp(0,n-1)).view(-1,1).expand(-1,4)
            accum.scatter_add_(0,index,value*(weight*valid)[:,None])
        accum=accum.reshape(n,n,4).permute(2,0,1)[None]
        # 低分辨率单元承载多片；保留总面积，用小足迹减少网格相位误差。
        accum=self.gaussian(accum,.65)[0].permute(1,2,0)
        amount=accum[...,3:];opacity=1-torch.exp(-amount)
        col=accum[...,:3]/amount.clamp(min=1e-7)
        result=(col*opacity+base*(1-opacity)).clamp(min=0)
        result=torch.where(result<=.0031308,result*12.92,1.055*result.clamp(min=1e-8).pow(1/2.4)-.055)
        return (result,opacity) if return_opacity else result

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);ap.add_argument('--coupled',action='store_true');ap.add_argument('--fine',action='store_true');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-proxy'/('fine' if a.fine else 'coupled' if a.coupled else 'simple');out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=a.fine);ctx=moderngl.create_standalone_context(require=430);s=Splat();report={}
    for name in a.scenes:
        c=Comparison(name);cls=CoupledProbe if a.coupled else ProbeRenderer
        if a.fine:
            from fit_fine_transport_response import ProbeRenderer as FineProbe
            cls=FineProbe
        r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw);reader=ParticleProxy(r);rows=[]
        for p in [.34,.54,.74]:
            d=capture(r,p,reader);pos,mass,color,base,warm=[s.tensor(d[k]) for k in ['pos','mass','color','base','warm']]
            td={k:s.tensor(v) for k,v in d.items() if isinstance(v,(np.ndarray,float))}
            frames=[];metrics={}
            def smooth(x):return cv2.GaussianBlur(x,(0,0),2.)
            with s.torch.no_grad():
                proxy=s.render(pos,mass,color,base).cpu().numpy()
                rebuilt=s.material_mass(td,s.torch.zeros_like(td['density']))
                metrics['mass_roundtrip']=dict(relative_error=float(s.torch.linalg.vector_norm(rebuilt-mass)/s.torch.linalg.vector_norm(mass).clamp(min=1e-8)),
                    total_ratio=float(rebuilt.sum()/mass.sum().clamp(min=1e-8)))
                for k,amount in enumerate([.01,.01,.12]):
                    reals=[];estimates=[]
                    for sign in [-1,1]:
                        probe=np.zeros(3);probe[k]=amount*sign;r.program['transport_probe']=tuple(probe)
                        g=capture(r,p,reader);reals.append(g['actual'])
                        dp=pos+s.tensor(probe[:2]*d['span']/d['card']/SIZE)*warm[:,None]
                        density=float(probe[2])*warm
                        carrier=td['carrier'] if 'unit_alpha' in td else pos
                        if a.coupled or a.fine:density=density+s.cohort(td,carrier+dp-pos)-s.cohort(td,carrier)
                        dm=s.material_mass(td,density)
                        dc=color
                        if a.fine:
                            before=s.scatter(td,td['carrier']);after=s.scatter(td,td['carrier']+dp-pos)
                            dm=s.material_mass(td,density,after[:,0]) if 'unit_alpha' in td else dm*(after[:,0]-before[:,0]).exp()
                            dc=s.shade(color,before,after)
                        dp=s.fission_position(td,dp,density)
                        estimates.append(s.render(dp,dm,dc,base).cpu().numpy())
                    actual=smooth(reals[1]-reals[0]);pred=smooth(estimates[1]-estimates[0])
                    weights=np.max(abs(actual),axis=2)>.0005
                    v=actual[weights].ravel();u=pred[weights].ravel()
                    metrics[str(k)]=dict(cosine=float(np.dot(u,v)/max(np.linalg.norm(u)*np.linalg.norm(v),1e-8)),relative_error=float(np.linalg.norm(u-v)/max(np.linalg.norm(v),1e-8)),norm_ratio=float(np.linalg.norm(u)/max(np.linalg.norm(v),1e-8)))
                    frames.extend([np.clip(actual*6+.5,0,1),np.clip(pred*6+.5,0,1)])
                frames=[d['actual'],proxy,*frames]
            r.program['transport_probe']=(0.,0.,0.)
            im=np.concatenate(frames,axis=1);Image.fromarray(np.uint8(np.clip(im*255,0,255))).save(out/f'{name}-{p:.2f}.png')
            rows.append(dict(phase=p,count=len(mass),response=metrics));print(name,p,metrics,flush=True)
        report[name]=rows;reader.close();r.close();(out/'response-check.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),'utf-8')
    ctx.release()

if __name__=='__main__':main()
