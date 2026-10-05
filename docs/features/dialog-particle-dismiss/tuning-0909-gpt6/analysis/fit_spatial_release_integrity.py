"""用原片与实际颗粒同口径的表面解除时刻校准共同空间基，避开随机粒子位置导数噪声。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from scipy.ndimage import map_coordinates,gaussian_filter
from scipy.optimize import least_squares
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from learned_release import load_weights,features,spatial_basis
from particle_coverage import CoverageRenderer,load_coverage
from fit_particle_optics import common
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from release_integrity import Probe


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='spatial-integrity');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-coverage'/a.label;out.mkdir(parents=True,exist_ok=True)
    kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json');ctx=moderngl.create_standalone_context(require=430);matrices=[];targets=[];factors=[];report={}
    for name,origin in OBSERVED_SITES.items():
        p=Probe(name);m=p.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*q) for q in origin]
        r=CoverageRenderer(name,ctx=ctx,quality=2,sites=sites,**kw);observed=p.model(r);gain=r.layout_change;r.close();reference=p.huawei()
        valid=p.valid&np.isfinite(reference)&np.isfinite(observed)&(reference<.99)&(observed<.99)
        delta=np.where(valid,np.clip(reference-observed,-.15,.15),0)
        smooth_delta=gaussian_filter(delta,.8)/np.maximum(gaussian_filter(valid.astype('float32'),.8),1e-5)
        _,native=features(128,128,w,h,m['direction'],m['seed'],sites,rotation_aligned=kw['weights'].rotation_aligned)
        basis=spatial_basis(128,128,w,h,m['direction'],sites,native)
        yb,xb=np.meshgrid(p.v,p.u,indexing='ij');coords=[yb*128-.5,xb*128-.5]
        b=np.stack([map_coordinates(basis[...,k],coords,order=1,mode='nearest') for k in range(basis.shape[-1])],-1)[valid]
        clock_derivative=1+gain*kw['rules'].clock*4*(1-2*observed)
        target=(smooth_delta*clock_derivative)[valid]
        matrices.append(b);targets.append(target);factors.append(np.full(len(b),1/np.sqrt(max(1,len(b))),np.float32))
        report[name]=dict(blocks=int(valid.sum()),mean_error=float(abs(delta[valid]).mean()),signed_error=float(delta[valid].mean()))
        np.savez_compressed(out/f'{name}.npz',reference=reference,observed=observed,valid=valid,delta=smooth_delta)
        print('完整度测量',name,report[name],flush=True)
    ctx.release();matrix=np.concatenate(matrices);target=np.concatenate(targets);weight=np.concatenate(factors)
    def residual(coef):
        prediction=.06*np.tanh(matrix@coef/.06)
        return np.concatenate([(prediction-target)*weight,coef*.08])
    fit=least_squares(residual,np.zeros(16),bounds=(-.12,.12),max_nfev=100,ftol=1e-9)
    initial=json.loads((HERE/'conditional-release-weights.json').read_text('utf-8'))
    for scale in [.35,.7,1.]:
        result=dict(initial);result.update(spatial_coefficients=(fit.x*scale).tolist(),spatial_limit=.06,spatial_calibration='实际渲染与原片同口径表面完整度共同校准',spatial_scale=scale)
        (out/f'gain-{scale:g}.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
    (out/'measurements.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print('共同系数',fit.x.tolist(),flush=True)


if __name__=='__main__':main()
