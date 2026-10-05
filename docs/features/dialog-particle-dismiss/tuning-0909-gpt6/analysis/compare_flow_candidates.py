"""历史 PIV 同格点对照，不作为模型选优：相关位移已在真实状态标定中失败。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,CoverageRules,load_coverage
from shared_transport import load_transport
from fit_particle_optics import common
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from match_observe import piv


def main():
    ctx=moderngl.create_standalone_context(require=430);kw=common();report={}
    old=load_transport(HERE/'analysis/match-cache/optics-before/conditional-transport-weights.json');cov=load_coverage(HERE/'particle-coverage-rules.json')
    variants={'before':(old,CoverageRules()),'coverage_only':(old,cov),'full_refinement':(kw['transport_weights'],cov)}
    for v in [.35,.65]:variants[str(v)]=(load_transport(HERE/f'analysis/match-cache/shared-transport/bounded-flow-{v}.json'),cov)
    for name in ['ironman','ironman-up-reference','reference-0926-7']:
        c=Comparison(name,piv_stride=4);rows=[]
        rs={k:CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**dict(kw,transport_weights=w,coverage=r)) for k,(w,r) in variants.items()}
        for i,j in c.piv_pairs:
            p,q=float(c.ref.progress[i]),float(c.ref.progress[j]);dt=q-p
            if dt<1e-4:continue
            def vectors(a,b):return {(int(v[0]),int(v[1])):v[2:4]/dt for v in piv(c.scene,a,b)}
            maps={'reference':vectors(np.asarray(c.ref.frames[i]),np.asarray(c.ref.frames[j]))}
            for k,r in rs.items():maps[k]=vectors(r.render(p),r.render(q))
            keys=set.intersection(*(set(v) for v in maps.values()))
            if len(keys)<5:continue
            rows.append(dict(phase=p,grids=len(keys),errors={k:float(np.median([np.linalg.norm(maps[k][z]-maps['reference'][z]) for z in keys])) for k in variants}))
        for r in rs.values():r.close()
        stats={k:float(np.median([r['errors'][k] for r in rows])) for k in variants};report[name]=dict(rows=rows,median=stats)
        print(name,len(rows),stats,flush=True)
        (HERE/'analysis/match-cache/particle-coverage/bounded-flow-motion.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
