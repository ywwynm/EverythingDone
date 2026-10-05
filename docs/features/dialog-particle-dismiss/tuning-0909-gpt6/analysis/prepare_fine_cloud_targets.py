"""为已有真实微片缓存补充原片／模型同口径云团占用，只用于离线优化。"""
from pathlib import Path
import argparse,json,pickle,sys
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_particle_proxy import LO,SIZE
from check_cloud_evolution import configuration
from shared_transport import load_transport
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',required=True);ap.add_argument('--out',required=True);a=ap.parse_args()
    data=pickle.loads(Path(a.cache).read_bytes());cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430)
    previous=None;r=None
    for d in data:
        name=d['name']
        if name!=previous:
            if r:r.close()
            c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
            x,y,x1,y1=c.scene.rect;w,h=x1-x,y1-y;n=d['n'];yy,xx=np.mgrid[:n,:n].astype(np.float32)
            xx=x+(LO+(xx+.5)/n*SIZE)*w;yy=y+(LO+(yy+.5)/n*SIZE)*h
            previous=name;print('同口径云团目标',name,flush=True)
        p=d['progress'];_,ref=c.ref.nearest(p);actual=r.render(p)
        def observe(f):
            particle=(c.scene.observe(f)['particle']&c.scene.roi).astype(np.float32)
            particle=cv2.GaussianBlur(particle,(0,0),max(.5,.45*w*SIZE/n),sigmaY=max(.5,.45*h*SIZE/n))
            return cv2.remap(particle,xx,yy,cv2.INTER_LINEAR)
        d['target_cloud']=observe(ref);d['actual_cloud']=observe(actual)
    r.close();ctx.release();path=Path(a.out);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(pickle.dumps(data,protocol=5));print(path,flush=True)
if __name__=='__main__':main()
