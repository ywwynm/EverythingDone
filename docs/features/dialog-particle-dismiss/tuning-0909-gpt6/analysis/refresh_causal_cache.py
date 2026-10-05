"""在候选运动的真实 GPU 结果上更新局部散布近似，不修改默认渲染。"""
from pathlib import Path
import argparse, hashlib, json, pickle, sys
import cv2, moderngl, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from causal_velocity import CausalVelocityRenderer,load
from check_cloud_evolution import configuration
from check_particle_proxy import capture,LO,SIZE
from particle_proxy import ParticleProxy
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--cache',type=Path,required=True)
    ap.add_argument('--causal',type=Path,required=True)
    ap.add_argument('--label',required=True)
    a=ap.parse_args();out=HERE/'analysis/match-cache/causal-velocity'/a.label
    out.mkdir(parents=True,exist_ok=True)
    rows=pickle.loads(a.cache.read_bytes());weights=load(a.causal)
    from export_shared_candidate import signature
    identity=hashlib.sha256((signature()+str(a.cache.resolve())).encode()+a.causal.read_bytes()+(HERE/'analysis/causal_velocity.py').read_bytes()).hexdigest()
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE)
    updated=[];actual_grain=[];target_grain=[];names=list(dict.fromkeys(d['name'] for d in rows))
    for name in names:
        c=Comparison(name)
        r=CausalVelocityRenderer(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],causal=weights,**kw)
        reader=ParticleProxy(r)
        for source in [d for d in rows if d['name']==name]:
            p=float(source['progress']);n=int(source['n']);d=dict(source)
            d.update(capture(r,p,reader,n));d['causal_cache_identity']=identity
            updated.append(d)
            # 同一真实画面计算颗粒目标和锚点，不复用旧模型的颗粒对比度。
            x,y,x1,y1=r.meta['rect'];w,h=x1-x,y1-y
            yy,xx=np.mgrid[:n,:n].astype(np.float32)
            xx=x+(LO+(xx+.5)/n*SIZE)*w;yy=y+(LO+(yy+.5)/n*SIZE)*h
            def detail(frame):
                value=frame.astype(np.float32)/255
                contrast=cv2.GaussianBlur(abs(value-cv2.GaussianBlur(value,(0,0),1.5)),(0,0),8.)
                return cv2.remap(contrast,xx,yy,cv2.INTER_LINEAR)
            _,ref=c.ref.nearest(p)
            actual_grain.append(detail(r.render(p)));target_grain.append(detail(ref))
        reader.close();r.close();print(name,len(updated),flush=True)
    ctx.release()
    # 完整写出后再替换，避免拟合误读尚未完成的缓存。
    target=out/'particles.pkl';temporary=out/'particles.pending'
    temporary.write_bytes(pickle.dumps(updated,protocol=pickle.HIGHEST_PROTOCOL));temporary.replace(target)
    np.savez_compressed(out/'grain-targets.npz',names=[d['name'] for d in updated],phases=[d['progress'] for d in updated],actual=actual_grain,target=target_grain,identity=identity)
    (out/'cache.json').write_text(json.dumps(dict(identity=identity,source=str(a.cache.resolve()),causal=str(a.causal.resolve()),frames=len(updated)),ensure_ascii=False,indent=2),'utf-8')
    print(target,flush=True)


if __name__=='__main__':main()
