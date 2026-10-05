"""年龄覆盖的零配置、原运动状态、首尾帧、回放及几何连续性检查。"""
from pathlib import Path
import argparse,json,sys
import dataclasses
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_cohorts import CohortRenderer,load_cohorts,cohort_field
from particle_coverage import CoverageRenderer,load_coverage
from fit_particle_optics import common
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',required=True);a=ap.parse_args();path=Path(a.candidate);cohort=load_cohorts(path)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json');report={}
    for name,origin in OBSERVED_SITES.items():
        sites=[Site(*s) for s in origin];opt=dict(kw,ctx=ctx,quality=2,sites=sites)
        before=CoverageRenderer(name,**opt);zero=CohortRenderer(name,cohorts=None,**opt);r=CohortRenderer(name,cohorts=cohort,**opt)
        zero_max=0;state_equal=True;initial_equal=True
        for p in [0.,.21,.47,.79,1.]:
            old=before.render(p);empty=zero.render(p);new=r.render(p)
            zero_max=max(zero_max,int(np.max(abs(old.astype('int16')-empty))))
            state_equal &= bool(np.array_equal(np.frombuffer(before.state.read(),np.float32),np.frombuffer(r.state.read(),np.float32)))
            if p==0:initial_equal=bool(np.array_equal(old,new))
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'));end=int(np.max(abs(new.astype('int16')-bg)))
        f=r.render(.4);r.render(.8);g=r.render(.4);seek=bool(np.array_equal(f,g));finite=bool(np.isfinite(np.frombuffer(r.state.read(),np.float32)).all())
        yy,xx=np.mgrid[:32,:32].astype('float32');xx=-.38+(xx+.5)/32*1.76;yy=-.38+(yy+.5)/32*1.76
        output=cohort_field(xx,yy,.5,r.cw,r.ch,r.direction,sites,cohort);permuted=cohort_field(xx,yy,.5,r.cw,r.ch,r.direction,list(reversed(sites)),cohort)
        perturb=cohort_field(xx,yy,.5,r.cw,r.ch,r.direction,[dataclasses.replace(s,x=s.x+.0001,y=s.y+.0001) for s in sites],cohort)
        order=float(abs(output-permuted).max());delta=float(abs(output-perturb).max())
        assert zero_max<=1 and state_equal and initial_equal and end<=1 and seek and finite and order<1e-4 and delta<.025,(name,zero_max,state_equal,initial_equal,end,seek,finite,order,delta)
        report[name]=dict(zero_config_max_error=zero_max,state_exact=state_equal,initial_exact=initial_equal,end_error=end,seek_equal=seek,finite=finite,permutation=order,perturbation=delta)
        print(name,report[name],flush=True);before.close();zero.close();r.close()
    ctx.release();(path.parent/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
