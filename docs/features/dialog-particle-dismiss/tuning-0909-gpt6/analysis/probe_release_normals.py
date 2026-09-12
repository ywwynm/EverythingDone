"""检查先归一化释放梯度是否把平坦区及起点汇合处放大为独立剥离。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from ablate_edge_support import configure as frozen
from probe_edge_support import OUT
ORIGINAL=model.materials

def configure(softness=.5,sigma=7.):
    frozen('baseline')
    def build(width,height,foreground,direction,seed,cell_px=None):
        data=ORIGINAL(width,height,foreground,direction,seed,cell_px)
        nx,ny=data['nx'],data['ny'];cx,cy=data['cell'];span=min(width,height)
        field,_=model.release_components(nx,ny,direction,width,height,seed)
        field=model.refine_release(field,width,height,direction,data['panel_weight'])
        gy,gx=np.gradient(gaussian_filter(field,sigma),cy,cx)
        denom=np.sqrt(gx*gx+gy*gy+(softness/span)**2)
        normalx=gaussian_filter(gx/np.maximum(denom,1e-8),3)
        normaly=gaussian_filter(gy/np.maximum(denom,1e-8),3)
        ids=data['base'][:,3].astype(int)%(nx*ny)
        data['base'][:,4]=normalx.ravel()[ids];data['base'][:,5]=normaly.ravel()[ids]
        data['peel_compression']=model.peel_compression(normalx,normaly,cx,cy,span,direction).ravel()[ids]
        return data
    renderer.materials=build

def main():
    p=argparse.ArgumentParser();p.add_argument('--softness',type=float,default=.5);p.add_argument('--sigma',type=float,default=7.);a=p.parse_args();configure(a.softness,a.sigma)
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text('utf-8'))
    inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for q in inputs:
        r=renderer.Renderer('ironman' if q['name']=='ironman' else 'attachment',direction=q['angle'],seed=q['seed'],ctx=ctx)
        frames=[r.render(float(q['phase']+d)) for d in [-.06,0,.06]]
        np.save(OUT/q['name']/f'ablate-normal-{a.softness:g}-{a.sigma:g}.npy',np.array(frames));r.close()
        print(q['name'],a.softness,a.sigma,flush=True)
    ctx.release()
if __name__=='__main__':main()
