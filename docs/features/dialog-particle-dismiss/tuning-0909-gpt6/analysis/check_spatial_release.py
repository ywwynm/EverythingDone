"""共同空间校准的零修正、布局隔离、输入连续性与完整回放回归。"""
from pathlib import Path
import argparse,copy,dataclasses,json,sys
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from learned_release import load_weights,predict
from particle_coverage import CoverageRenderer,load_coverage
from fit_particle_optics import common
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',required=True);a=ap.parse_args()
    path=Path(a.candidate);base=load_weights(HERE/'analysis/match-cache/spatial-release-before/conditional-release-weights.json');new=load_weights(path)
    zero=copy.deepcopy(base);zero.spatial_coefficients=[0.]*16
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json');report={}
    for name,origins in OBSERVED_SITES.items():
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=m['rect'];sites=[Site(*q) for q in origins]
        def field(weights,ss=sites):return predict(96,96,x1-x,y1-y,m['direction'],m['seed'],ss,weights)
        original=field(base);empty=field(zero);candidate=field(new);permuted=field(new,list(reversed(sites)))
        limit=float(np.max(abs(candidate-original)));permutation=float(np.max(abs(permuted-candidate)))
        samples=[field(new,[dataclasses.replace(s,x=s.x+d,y=s.y+d) for s in sites]) for d in np.linspace(-.004,.004,17)]
        jumps=max(float(np.max(abs(a-b))) for a,b in zip(samples,samples[1:]))
        assert np.array_equal(empty,original) and limit<=.06001 and permutation<1e-4 and jumps<.015,(name,limit,permutation,jumps)
        r=CoverageRenderer(name,ctx=ctx,quality=2,sites=sites,**dict(kw,weights=new))
        before=CoverageRenderer(name,ctx=ctx,quality=2,sites=sites,**dict(kw,weights=base));layout=abs(r.layout_change-before.layout_change)
        assert layout<1e-9,(name,layout)
        before.close();finite=True
        for p in np.linspace(0,1,25):
            f=r.render(float(p));finite &= bool(np.isfinite(np.frombuffer(r.state.read(),np.float32)).all())
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'));end=float(np.max(abs(f.astype('int16')-bg)))
        first=r.render(.4);r.render(.8);back=r.render(.4);seek=bool(np.array_equal(first,back));r.close()
        assert finite and end<=1 and seek,(name,finite,end,seek)
        report[name]=dict(zero_exact=True,layout_difference=layout,maximum_release_change=limit,permutation=permutation,perturbation_step=jumps,finite=finite,end_error=end,seek_equal=seek)
        print(name,report[name],flush=True)
    ctx.release();(path.parent/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
