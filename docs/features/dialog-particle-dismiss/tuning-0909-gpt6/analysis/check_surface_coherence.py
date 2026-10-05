"""交接场检查：零配置、运动状态、首尾、回放、输入连续性和时钟单调性。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer,load_coherence,coherence_field
from particle_cohorts import CohortRenderer
from fit_surface_coherence import settings
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',required=True);a=ap.parse_args();path=Path(a.candidate);weights=load_coherence(path)
    ctx=moderngl.create_standalone_context(require=430);kw=settings();report={}
    for name,origins in OBSERVED_SITES.items():
        sites=[Site(*s) for s in origins];options=dict(kw,ctx=ctx,quality=2,sites=sites)
        before=CohortRenderer(name,**options);zero=CoherenceRenderer(name,**options);r=CoherenceRenderer(name,coherence=weights,**options)
        zero_max=0;state_equal=True;initial_equal=True
        for p in [0.,.21,.47,.79,1.]:
            old=before.render(p);empty=zero.render(p);new=r.render(p)
            zero_max=max(zero_max,int(np.max(abs(old.astype('int16')-empty))))
            state_equal &= bool(np.array_equal(np.frombuffer(before.state.read(),np.float32),np.frombuffer(r.state.read(),np.float32)))
            if p==0:initial_equal=bool(np.array_equal(old,new))
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'));end=int(np.max(abs(new.astype('int16')-bg)))
        f=r.render(.4);r.render(.8);g=r.render(.4);seek=bool(np.array_equal(f,g));finite=bool(np.isfinite(np.frombuffer(r.state.read(),np.float32)).all())
        yy,xx=np.mgrid[:32,:32].astype('float32');xx=(xx+.5)/32;yy=(yy+.5)/32
        result=coherence_field(xx,yy,.5,r.cw,r.ch,r.direction,sites,weights)
        order=float(abs(result-coherence_field(xx,yy,.5,r.cw,r.ch,r.direction,list(reversed(sites)),weights)).max())
        delta=float(abs(result-coherence_field(xx,yy,.5,r.cw,r.ch,r.direction,[dataclasses.replace(s,x=s.x+.0001,y=s.y+.0001) for s in sites],weights)).max())
        clock=[]
        for t in np.linspace(0,1,121):
            v=coherence_field(xx,yy,t,r.cw,r.ch,r.direction,sites,weights)
            clock.append(t-v*4*t*(1-t))
        rate=float(np.diff(np.array(clock),axis=0).min()*120)
        assert zero_max<=1 and state_equal and initial_equal and end<=1 and seek and finite and order<1e-5 and delta<.001,(name,zero_max,state_equal,initial_equal,end,seek,finite,order,delta,rate)
        assert r.coherence_minimum_rate>=.14999
        report[name]=dict(zero_config_max_error=zero_max,state_exact=state_equal,initial_exact=initial_equal,end_error=end,seek_equal=seek,finite=finite,permutation=order,perturbation=delta,min_handoff_clock_rate=rate,texture_clock_gain=r.coherence_clock_gain,texture_minimum_rate=r.coherence_minimum_rate)
        print(name,report[name],flush=True);before.close();zero.close();r.close()
    ctx.release();(path.parent/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
