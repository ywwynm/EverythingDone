"""用两份实际 GPU 缓存检查运动更新的画面预测，避免只相信训练损失。"""
from pathlib import Path
import argparse,json,pickle,sys
import cv2,numpy as np
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from causal_velocity import coefficients,load,HORIZON,BIRTH_RAMP
from check_particle_proxy import Splat,SIZE
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--before',type=Path,required=True)
    ap.add_argument('--after',type=Path,required=True);ap.add_argument('--causal',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True);a=ap.parse_args()
    old=pickle.loads(a.before.read_bytes());new=pickle.loads(a.after.read_bytes());weights=load(a.causal)
    lookup={(d['name'],d['progress']):d for d in new};s=Splat(n=160);t=s.tensor;rows=[]
    for src in old:
        if src['name'] not in ['ironman','ironman-up-reference','kobe'] or not .25<src['progress']<.75:continue
        actual=lookup[(src['name'],src['progress'])]['actual'];d={k:t(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in src.items()}
        m=json.loads((HERE/'assets'/src['name']/'scene.json').read_text('utf-8'))
        q=src['origin']*SIZE-.38
        control=coefficients(q[:,0],q[:,1],*src['card'],m['direction'],[Site(*v) for v in OBSERVED_SITES[src['name']]],weights,src['phase']-src['age'])
        age=np.clip(src['age'],0,HORIZON);h=HORIZON;young=np.clip(src['age']/BIRTH_RAMP,0,1)
        basis=np.stack([young**2*(3-2*young),age-age**2/h+age**3/(3*h*h),age**2/h-2*age**3/(3*h*h),age**3/(3*h*h)+np.maximum(src['age']-h,0)],-1)
        with s.torch.no_grad():
            carrier=d['query']+t((control*basis[...,None]).sum(-2))*d['scale']/SIZE
            delta=s.cohort(d,carrier)-s.cohort(d,d['carrier']);sc=s.scatter(d,carrier);oldsc=s.scatter(d,d['carrier'])
            mass=s.material_mass(d,delta,sc[:,0]);color=s.shade(d['color'],oldsc,sc)
            pos=s.fission_position(d,d['pos']+carrier-d['carrier'],delta)
            prior=s.render(d['pos'],s.material_mass(d,s.torch.zeros_like(d['density'])),d['color'],d['base'])
            predicted=(d['actual']+s.render(pos,mass,color,d['base'])-prior).cpu().numpy()
        blur=lambda f:cv2.GaussianBlur(f,(0,0),max(.55,4*160/(SIZE*src['span'])))
        v=(blur(actual)-blur(src['actual']))*src['mask'][...,None]
        u=(blur(predicted)-blur(src['actual']))*src['mask'][...,None]
        target=blur(src['target']);mask=src['mask'][...,None]
        error=lambda f:float((abs(blur(f)-target)*mask).sum()/max(mask.sum()*3,1))
        row=dict(name=src['name'],phase=src['progress'],cosine=float(np.sum(u*v)/max(np.linalg.norm(u)*np.linalg.norm(v),1e-8)),relative_delta_error=float(np.linalg.norm(u-v)/max(np.linalg.norm(v),1e-8)),before=error(src['actual']),predicted=error(predicted),actual=error(actual))
        rows.append(row);print(row,flush=True)
    a.out.write_text(json.dumps(rows,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
