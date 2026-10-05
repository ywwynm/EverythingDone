"""以另一份空间变化材质核实真实微扰导数，不用拟合分数替代实际颗粒变化。"""
from pathlib import Path
import argparse,json,sys
import cv2,moderngl,numpy as np
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from prepare_uncertain_grain_cache import high_frequency
from scatter_material import load_scatter,scatter_field
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from check_particle_proxy import LO,SIZE


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',type=Path,required=True);a=ap.parse_args()
    cache=HERE/'analysis/match-cache/uncertain-grain/responses/responses.npz';z=np.load(cache)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430)
    weights=load_scatter(a.candidate);report={};n=z['zero'].shape[1]
    yy,xx=np.mgrid[:64,:64].astype(np.float32);fx=LO+(xx+.5)/64*SIZE;fy=LO+(yy+.5)/64*SIZE
    for name in ['ironman-up-reference','reference-0926-2','ironman','reference-0926-6']:
        sites=[Site(*v) for v in OBSERVED_SITES[name]]
        r=cls(name,ctx=ctx,quality=2,sites=sites,**dict(kw,scatter=weights))
        x,y,x1,y1=r.meta['rect'];w,h=x1-x,y1-y
        py,px=np.mgrid[:n,:n].astype(np.float32);qx=x+(LO+(px+.5)/n*SIZE)*w;qy=y+(LO+(py+.5)/n*SIZE)*h
        rows=[];indices=np.flatnonzero(z['names']==name)
        for index in indices[[2,4,6]]:
            p=float(z['phases'][index]);actual=cv2.remap(high_frequency(r.render(p)),qx,qy,cv2.INTER_LINEAR)-z['zero'][index]
            t=float(r.program['time'].value)
            # 和运行时一样，先生成相邻两个时间片，再线性插值。
            ts=np.clip(t,0,1)*40;i=int(ts);j=min(40,i+1)
            def field(weight):
                return scatter_field(fx,fy,i/40,w,h,r.direction,sites,weight)*(1-ts+i)+scatter_field(fx,fy,j/40,w,h,r.direction,sites,weight)*(ts-i)
            delta=cv2.resize(field(weights)-field(kw['scatter']),(n,n),interpolation=cv2.INTER_LINEAR)
            estimate=(z['slope'][index]*delta[...,None,:]+z['curve'][index]*delta[...,None,:]**2).sum(-1)
            mask=np.max(abs(actual),-1)>.00015;aa=actual[mask].ravel();bb=estimate[mask].ravel()
            row=dict(phase=p,cosine=float(np.dot(aa,bb)/max(np.linalg.norm(aa)*np.linalg.norm(bb),1e-12)),
                relative_error=float(np.linalg.norm(aa-bb)/max(np.linalg.norm(aa),1e-12)),actual_norm=float(np.linalg.norm(aa)),predicted_norm=float(np.linalg.norm(bb)))
            rows.append(row)
        report[name]=rows;r.close();print(name,rows,flush=True)
    ctx.release();(a.candidate.parent/'grain-response-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
