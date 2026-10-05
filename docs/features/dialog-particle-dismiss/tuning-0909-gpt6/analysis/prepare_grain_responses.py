"""用真实渲染的微小材质扰动建立颗粒对比导数；不改默认模型。"""
from pathlib import Path
import argparse,json,pickle,sys
import cv2,moderngl,numpy as np
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from prepare_uncertain_grain_cache import high_frequency
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from check_particle_proxy import LO,SIZE


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',type=Path,required=True);ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--root',type=Path,default=HERE,help='必须与材料缓存采用的完整基准配置一致')
    a=ap.parse_args();data=pickle.loads(a.cache.read_bytes());a.out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(a.root);ctx=moderngl.create_standalone_context(require=430)
    eps=np.array([.08,.014,.10],np.float32);slope=[];curve=[];zero=[]
    for name in dict.fromkeys(d['name'] for d in data):
        r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=r.meta['rect'];w,h=x1-x,y1-y;n=int(data[0]['n'])
        yy,xx=np.mgrid[:n,:n].astype(np.float32);qx=x+(LO+(xx+.5)/n*SIZE)*w;qy=y+(LO+(yy+.5)/n*SIZE)*h
        sample=lambda f:cv2.remap(high_frequency(f),qx,qy,cv2.INTER_LINEAR)
        for d in [d for d in data if d['name']==name]:
            r.program['scatter_delta']=(0.,0.,0.);base=sample(r.render(d['progress']));zero.append(base)
            slopes=[];curves=[]
            for k,e in enumerate(eps):
                sides=[]
                for sign in [-1,1]:
                    delta=np.zeros(3);delta[k]=sign*e;r.program['scatter_delta']=tuple(delta)
                    sides.append(sample(r.render(d['progress'])))
                slopes.append((sides[1]-sides[0])/(2*e))
                curves.append((sides[1]+sides[0]-2*base)/(2*e*e))
            slope.append(np.stack(slopes,-1));curve.append(np.stack(curves,-1))
        r.close();print('实际材质颗粒导数',name,flush=True)
    ctx.release()
    np.savez_compressed(a.out/'responses.npz',zero=zero,slope=slope,curve=curve,eps=eps,names=[d['name'] for d in data],phases=[d['progress'] for d in data])
    (a.out/'scope.json').write_text(json.dumps(dict(cache=str(a.cache),root=str(a.root.resolve()),method='真实 GPU 小扰动；只供局部材质导数，运动仍按实际材料散布近似'),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
