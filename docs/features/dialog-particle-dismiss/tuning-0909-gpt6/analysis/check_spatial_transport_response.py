"""验证共同输运拟合近似对空间形变的响应；只写诊断结果，不改运行配置。"""
from pathlib import Path
import argparse, hashlib, json, sys
import cv2, moderngl, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from check_particle_proxy import capture, Splat, LO, SIZE
from particle_proxy import ParticleProxy
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman'])
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4)
    ctx=moderngl.create_standalone_context(require=430);s=Splat(n=160)
    cls,kw=configuration(HERE);result={}
    yy,xx=np.mgrid[:56,:56].astype(np.float32)
    xx=LO+(xx+.5)/56*SIZE;yy=LO+(yy+.5)/56*SIZE
    gaussian=np.exp(-((xx-.40)**2+(yy-.50)**2)/.20)
    bases=[np.stack([gaussian,gaussian*0],-1),np.stack([gaussian*0,gaussian],-1),
           np.stack([-(yy-.5),xx-.5],-1)*gaussian[...,None]*3,
           np.stack([(xx-.5),-(yy-.5)],-1)*gaussian[...,None]*3]
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        reader=ParticleProxy(r);original=np.frombuffer(r.transport_tex.read(),np.float32).reshape(41,56,56,3).copy()
        rows=[]
        for requested in [.34,.56]:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i])
            d=capture(r,p,reader,n=160)
            td={k:s.tensor(v) for k,v in d.items() if isinstance(v,(np.ndarray,float,np.floating))}
            x,y,x1,y1=r.meta['rect'];w,h=x1-x,y1-y
            gy,gx=np.mgrid[:160,:160].astype(np.float32)
            gx=x+(LO+(gx+.5)/160*SIZE)*w;gy=y+(LO+(gy+.5)/160*SIZE)*h
            sample=lambda f:cv2.remap(cv2.GaussianBlur(f,(0,0),max(.5,.45*w*SIZE/160),sigmaY=max(.5,.45*h*SIZE/160)),gx,gy,cv2.INTER_LINEAR)
            target=sample(np.asarray(ref,np.float32)/255)
            mask=cv2.remap(c.scene.roi.astype(np.float32),gx,gy,cv2.INTER_LINEAR)
            if not mask.any():mask=np.ones((160,160),np.float32)
            old_cohort=s.cohort(td,td['carrier']);old_scatter=s.scatter(td,td['carrier'])
            with torch.no_grad():anchor=s.render(td['pos'],s.material_mass(td,torch.zeros_like(td['density'])),td['color'],td['base'])
            for k,basis in enumerate(bases):
                for amplitude in [.005,.02,.06]:
                    real=[];pred=[]
                    for sign in [-1,1]:
                        field=basis*(sign*amplitude)
                        changed=original.copy();changed[...,:2]+=field[None]
                        r.transport_tex.write(changed.tobytes())
                        real.append(sample(r.render(p).astype(np.float32)/255))
                        with torch.no_grad():
                            delta=F.grid_sample(s.tensor(field).permute(2,0,1)[None],td['query'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T*td['warm'][:,None]
                            shift=delta*float(d['span'])/td['card']/SIZE
                            carrier=td['carrier']+shift
                            density=s.cohort(td,carrier)-old_cohort
                            scatter=s.scatter(td,carrier)
                            mass=s.material_mass(td,density,scatter[:,0])
                            color=s.shade(td['color'],old_scatter,scatter)
                            pos=s.fission_position(td,td['pos']+shift,density)
                            pred.append(d['actual']+(s.render(pos,mass,color,td['base'])-anchor).cpu().numpy())
                    r.transport_tex.write(original.tobytes())
                    # 两种滤波都与当前拟合图像项一致；此处仅检查响应，不表示参考拟合程度。
                    metrics=[]
                    for sigma in [4,12]:
                        blur=lambda f:cv2.GaussianBlur(f,(0,0),max(.55,sigma*160/(SIZE*r.span)))
                        actual=blur(real[1]-real[0]);estimated=blur(pred[1]-pred[0]);valid=mask>.5
                        u=estimated[valid].ravel();v=actual[valid].ravel()
                        cost=lambda f:float((abs(blur(f)-blur(target))*mask[...,None]).sum()/mask.sum()/3)
                        metrics.append(dict(sigma=sigma,cosine=float(u@v/max(np.linalg.norm(u)*np.linalg.norm(v),1e-12)),
                            relative_error=float(np.linalg.norm(u-v)/max(np.linalg.norm(v),1e-12)),
                            actual_loss_delta=cost(real[1])-cost(real[0]),predicted_loss_delta=cost(pred[1])-cost(pred[0])))
                    rows.append(dict(phase=p,basis=k,amplitude=amplitude,metrics=metrics))
            print(name,round(p,3),{amp:round(float(np.mean([m['cosine'] for row in rows if row['phase']==p and row['amplitude']==amp for m in row['metrics']])),4) for amp in [.005,.02,.06]},flush=True)
        reader.close();r.close();result[name]=rows
        (a.out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
