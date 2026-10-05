"""按微片透明度的一、二阶矩预测局部颗粒对比，仅用于离线导数诊断。"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]


def alpha_moments(s,d,density_delta,scatter_density):
    torch=s.torch;old=d['density'];new=old+density_delta;area,count=d['coverage']
    cap=-d['keep0'].log();before=torch.minimum(old*area,cap)*count;after=torch.minimum(new*area,cap)*count
    keep=(d['keep0']*after.exp()).clamp(0,1)
    lo=(keep-.05).clamp(min=0);hi=(keep+.05).clamp(max=1)
    u=((d['keep_random']-lo)/(hi-lo).clamp(min=.00001)).clamp(0,1)
    retain=(1-count)*(d['keep_random']<=d['keep0'])+count*(1-u*u*(3-2*u))
    age=((d['age']-.008)/.052).clamp(0,1);warm=age*age*(3-2*age)
    ratio=(density_delta*area-(after-before)).exp();strength,threshold=d['fission']
    projected=d['projected']*ratio.sqrt();q=((projected-threshold)/(threshold*.8)).clamp(0,1)
    fage=((d['age']-.015)/.065).clamp(0,1)
    divided=strength*q*q*(3-2*q)*fage*fage*(3-2*fage)
    fraction=torch.where(d['child']<.5,1-divided,divided)
    alpha=d['unit_alpha']*((1-warm+warm*retain)*fraction)[:,None]
    alpha=1-(1-alpha).clamp(min=.00001).pow(scatter_density.exp()[:,None])
    sample_area=d['sample_area']*ratio
    return alpha.mean(-1)*sample_area,alpha.square().mean(-1)*sample_area


def variance(s,pos,mass,squared_mass,color,base):
    torch=s.torch;n=s.n
    q=pos*n-.5;ij=q.floor().long();f=q-ij
    value=torch.cat([mass[:,None],squared_mass[:,None],color*mass[:,None],color*squared_mass[:,None],color.square()*squared_mass[:,None]],-1)
    accum=torch.zeros((n*n,11),device=pos.device)
    for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
        xx=ij[:,0]+dx;yy=ij[:,1]+dy
        weight=(f[:,0] if dx else 1-f[:,0])*(f[:,1] if dy else 1-f[:,1])
        valid=(xx>=0)&(xx<n)&(yy>=0)&(yy<n)
        index=(yy.clamp(0,n-1)*n+xx.clamp(0,n-1)).view(-1,1).expand(-1,11)
        accum.scatter_add_(0,index,value*(weight*valid)[:,None])
    v=s.gaussian(accum.reshape(n,n,11).permute(2,0,1)[None],.65)[0].permute(1,2,0)
    m1=v[...,:1];m2=v[...,1:2];c1=v[...,2:5]/m1.clamp(min=1e-7);c2=v[...,5:8]/m2.clamp(min=1e-7)
    color_var=(v[...,8:11]/m2.clamp(min=1e-7)-c2.square()).clamp(min=0)
    extinction=(2*m1-m2).clamp(min=1e-7)
    trans_var=((-extinction).exp()-(-2*m1).exp()).clamp(min=0)
    result=color_var*m2/extinction*(1-(-extinction).exp())+(c1-base).square()*trans_var
    mean=base*(-m1).exp()+c1*(1-(-m1).exp())
    slope=torch.where(mean<=.0031308,torch.full_like(mean,12.92),1.055/2.4*mean.clamp(min=.0031308).pow(1/2.4-1))
    result=(result+1e-9).sqrt()*slope
    return s.gaussian(result.permute(2,0,1)[None],1.4)[0].permute(1,2,0)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','kobe'])
    a=ap.parse_args()
    import cv2,moderngl
    from check_particle_proxy import capture,Splat,LO,SIZE
    from particle_proxy import ParticleProxy
    from fit_fine_transport_response import ProbeRenderer
    from check_cloud_evolution import configuration
    from dissolve_v2 import Site
    from v2_inputs import OBSERVED_SITES
    out=HERE/'analysis/match-cache/grain-moment-proxy';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE)
    s=Splat(device='cpu',n=128);s.torch.set_num_threads(4);report={}
    # 当前渲染环境的旧版 PyTorch 不提供 NumPy 2 桥接；用缓冲区交换，避免修改环境。
    def tensor(value):
        arr=np.ascontiguousarray(np.asarray(value,np.float32))
        result=s.torch.frombuffer(bytearray(arr.tobytes()),dtype=s.torch.float32).reshape(arr.shape)
        return result.reshape(()) if np.ndim(value)==0 else result
    s.tensor=tensor
    for name in a.scenes:
        r=ProbeRenderer(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw);reader=ParticleProxy(r)
        x,y,x1,y1=r.meta['rect'];w,h=x1-x,y1-y;yy,xx=np.mgrid[:s.n,:s.n].astype(np.float32)
        xx=x+(LO+(xx+.5)/s.n*SIZE)*w;yy=y+(LO+(yy+.5)/s.n*SIZE)*h
        rows=[]
        for phase in [.24,.41,.58]:
            for dim in [0,1,2]:
                actuals=[];estimates=[];mass_error=[]
                for sign in [-1,1]:
                    delta=np.zeros(3);delta[dim]=sign*(.012 if dim<2 else .18);r.program['transport_probe']=tuple(delta)
                    d=capture(r,phase,reader,s.n)
                    frame=r.render(phase).astype(np.float32)/255
                    grain=cv2.GaussianBlur(abs(frame-cv2.GaussianBlur(frame,(0,0),1.5)),(0,0),8.)
                    actuals.append(cv2.remap(grain,xx,yy,cv2.INTER_LINEAR))
                    td={k:s.tensor(v) for k,v in d.items() if isinstance(v,(np.ndarray,float,np.floating))}
                    with s.torch.no_grad():
                        m1,m2=alpha_moments(s,td,s.torch.zeros_like(td['density']),s.scatter(td,td['carrier'])[:,0])
                        mass_error.append(float(abs(m1-td['mass']).sum()/td['mass'].sum().clamp(min=1e-8)))
                        estimates.append(np.asarray(variance(s,td['pos'],m1,m2,td['color'],td['base']).tolist(),np.float32))
                target=actuals[1]-actuals[0];estimate=estimates[1]-estimates[0];mask=np.max(abs(target),-1)>.0001
                aa=target[mask].ravel();bb=estimate[mask].ravel();gain=float(np.dot(aa,bb)/max(np.dot(bb,bb),1e-12))
                row=dict(phase=phase,axis=dim,cosine=float(np.dot(aa,bb)/max(np.linalg.norm(aa)*np.linalg.norm(bb),1e-12)),best_positive_gain=gain,
                    relative_error_after_gain=float(np.linalg.norm(aa-max(gain,0)*bb)/max(np.linalg.norm(aa),1e-12)),mass_roundtrip=max(mass_error))
                rows.append(row);print(name,row,flush=True)
        reader.close();r.close();report[name]=rows
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
