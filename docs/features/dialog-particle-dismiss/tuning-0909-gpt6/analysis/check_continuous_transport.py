"""共同运动权重的实际渲染回归；与完整原片及连续路径验收分开。"""
from pathlib import Path
import argparse,json,sys
import moderngl,numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from shared_transport import load_transport,correction
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--candidate',type=Path,required=True)
    ap.add_argument('--baseline',type=Path,default=HERE)
    ap.add_argument('--out',type=Path,required=True)
    a=ap.parse_args()
    cls,kw=configuration(a.baseline)
    candidate=load_transport(a.candidate)
    ctx=moderngl.create_standalone_context(require=430)
    y,x=np.mgrid[:56,:56].astype(np.float32)
    x=-.38+(x+.5)/56*1.76;y=-.38+(y+.5)/56*1.76
    report={}
    for name,origins in OBSERVED_SITES.items():
        sites=[Site(*s) for s in origins]
        old=cls(name,ctx=ctx,quality=2,sites=sites,**kw)
        new=cls(name,ctx=ctx,quality=2,sites=sites,**dict(kw,transport_weights=candidate))
        state_exact=True;finite=True;endpoints=[];density_error=0.
        for p in [0.,.17,.39,.58,.79,1.]:
            before=old.render(p);after=new.render(p)
            state_exact &= old.state.read()==new.state.read()
            finite &= bool(np.isfinite(np.frombuffer(new.state.read(),np.float32)).all())
            if p in [0.,1.]:endpoints.append(int(abs(before.astype(np.int16)-after).max()))
            arguments=(x,y,p,new.cw,new.ch,new.direction,sites)
            d0=correction(*arguments,kw['transport_weights'])[...,2]
            d1=correction(*arguments,candidate)[...,2]
            density_error=max(density_error,float(abs(d0-d1).max()))
        sample=new.render(.43);new.render(.76)
        seek_exact=bool(np.array_equal(sample,new.render(.43)))
        error=ctx.error
        row=dict(state_exact=state_exact,finite=finite,endpoint_max_delta=endpoints,
                 seek_exact=seek_exact,density_function_max_delta=density_error,gl_error=error)
        assert state_exact and finite and max(endpoints)<=1 and seek_exact and density_error<2e-5 and error=='GL_NO_ERROR',(name,row)
        old.close();new.close();report[name]=row
        print(name,row,flush=True)
    ctx.release()
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(dict(candidate=str(a.candidate),baseline=str(a.baseline),
        scope='运行与函数一致性，不代表华为视觉匹配',scenes=report),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
