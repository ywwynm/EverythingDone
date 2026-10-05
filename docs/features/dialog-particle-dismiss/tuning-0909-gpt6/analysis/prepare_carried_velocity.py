"""采集静态速度修正的实际积分采样；只保存离线基准路径。"""
from pathlib import Path
import argparse,hashlib,json,sys
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from diagnose_motion_continuity import trajectory_reader
from carried_velocity import CADENCE


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--out',type=Path,required=True)
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430)
    for name in OBSERVED_SITES:
        r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        read,close=trajectory_reader(r);times=np.arange(0,1.00001,CADENCE);rows=[]
        display=np.linspace(0,1,10001);clock=display+r.layout_change*r.conditional_rules.clock*4*display*(1-display)
        for t in times:
            progress=float(np.interp(t,clock,display))
            r.render(progress);rows.append(read()[:,2:4])
        np.savez_compressed(a.out/f'{name}.npz',times=times,carrier=np.asarray(rows),source=r.base[:,:2],birth=r.base[:,2],card=[r.cw,r.ch],span=r.span,direction=r.direction)
        close();r.close();print(name,len(times),flush=True)
    ctx.release()
    names=['conditional-release-weights.json','conditional-material-rules.json','conditional-transport-weights.json','surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','particle-scatter-weights.json']
    digest=hashlib.sha256(b''.join((HERE/f).read_bytes() for f in names)).hexdigest()
    (a.out/'scope.json').write_text(json.dumps(dict(identity=digest,cadence=CADENCE,source='共同默认实际 GPU，内部时钟均匀采样，无参考路径真值'),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
