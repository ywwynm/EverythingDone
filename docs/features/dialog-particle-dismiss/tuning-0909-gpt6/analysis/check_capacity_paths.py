"""把离线位移采样与实际 GPU 轨迹对应，排除路径约束的坐标、时钟和可见性错位。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from scipy.ndimage import map_coordinates
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from continuous_capacity_renderer import renderer
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--controls',type=Path,required=True);ap.add_argument('--traces',type=Path,required=True)
    ap.add_argument('--baseline',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return');a=ap.parse_args()
    cls=renderer(a.controls);_,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430);report={}
    for path in sorted(a.traces.glob('*.npz')):
        before=np.load(a.baseline/path.name);after=np.load(path);bt=before['trace'];at=after['trace'];card=before['rect'][2:]-before['rect'][:2];span=float(before['span'])
        r=cls(path.stem,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[path.stem]],**kw)
        volume=np.frombuffer(r.capacity_tex.read(),np.float32).reshape(41,56,56,2);r.close()
        clock=bt[:,0,4]-bt[0,0,4];ids=np.arange(0,bt.shape[1],max(1,bt.shape[1]//4096));b=bt[:,ids];actual=at[:,ids,2:4]
        query=(b[:,:,:2]/card+.38)/1.76*56-.5
        z=clock[:,None]*np.ones((1,len(ids)))*40
        delta=np.stack([map_coordinates(volume[...,k],[z,query[...,1],query[...,0]],order=1,mode='nearest') for k in range(2)],-1)
        u=np.clip(b[:,:,4]/.03,0,1);warm=u*u*(3-2*u)
        expected=b[:,:,2:4]+delta*span*warm[:,:,None]
        visible=(b[:,:,4]>.04)&(b[:,:,4]<b[:,:,5]-.055)&(b[:,:,6]<.6)&before['mask'][ids]
        diff=(expected-actual)/span
        row=dict(state_max_error=float(abs(bt[:,:,:2]-at[:,:,:2]).max()),center_max_short_side=float(abs(diff[visible]).max()),center_rms_short_side=float(np.sqrt(np.mean(diff[visible]**2))))
        print(path.stem,row,flush=True);report[path.stem]=row
    ctx.release();(a.traces/'reconstruction.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
