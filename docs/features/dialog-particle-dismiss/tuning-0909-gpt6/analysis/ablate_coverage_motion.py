"""历史 PIV 消融，不作为运动验收；同格点不能消除缩小和淡出对相关峰的污染。"""
from pathlib import Path
import sys,json,argparse
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
    ap=argparse.ArgumentParser();ap.add_argument('--flow');ap.add_argument('--output',default='common-grid-motion');a=ap.parse_args()
    ctx=moderngl.create_standalone_context(require=430);kw=common();report={}
    current_flow=load_transport(Path(a.flow)) if a.flow else kw['transport_weights'];old_flow=load_transport(HERE/'analysis/match-cache/optics-before/conditional-transport-weights.json');coverage=load_coverage(HERE/'particle-coverage-rules.json')
    for name in ['ironman','ironman-up-reference','reference-0926-7']:
        c=Comparison(name,piv_stride=5)
        variants={'before':(old_flow,CoverageRules()),'only_flow':(current_flow,CoverageRules()),'only_coverage':(old_flow,coverage),'candidate':(current_flow,coverage)}
        renderers={key:CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**dict(kw,transport_weights=flow,coverage=cov)) for key,(flow,cov) in variants.items()}
        rows=[]
        for i,j in c.piv_pairs:
            pa,pb=float(c.ref.progress[i]),float(c.ref.progress[j]);dt=pb-pa
            if dt<1e-4:continue
            def vectors(a,b):return {(int(v[0]),int(v[1])):v[2:4]/dt for v in piv(c.scene,a,b)}
            maps={'reference':vectors(np.asarray(c.ref.frames[i]),np.asarray(c.ref.frames[j]))}
            for key,r in renderers.items():maps[key]=vectors(r.render(pa),r.render(pb))
            keys=set.intersection(*(set(q) for q in maps.values()))
            if len(keys)<5:continue
            row=dict(phase=pa,grids=len(keys),errors={key:float(np.median([np.linalg.norm(maps[key][p]-maps['reference'][p]) for p in keys])) for key in variants});rows.append(row)
        for r in renderers.values():r.close()
        means={key:float(np.median([r['errors'][key] for r in rows])) for key in variants};report[name]=dict(pairs=rows,median=means)
        print(name,means,flush=True)
        (HERE/'analysis/match-cache/particle-coverage'/f'{a.output}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
