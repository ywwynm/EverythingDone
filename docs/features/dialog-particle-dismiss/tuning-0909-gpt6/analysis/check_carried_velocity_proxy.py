"""真实积分与训练积分算子的一致性核对，包含零修正和倒退回放。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from carried_velocity import CarriedVelocityRenderer,Weights,FIELD,LO,SIZE
from carried_velocity_operator import operators
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--age-slices',type=int,default=9)
    ap.add_argument('--out',type=Path,required=True);a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);report={}
    weights=Weights([(np.zeros((2,61 if a.age_slices>1 else 59),np.float32),np.zeros(2,np.float32))]);weights.age_slices=a.age_slices
    for name in ['reference-0926-2','ironman-up-reference','ironman']:
        args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        r=cls(name,**args);samples=[.17,.36,.57,.81,.30];frames=[r.render(p) for p in samples];r.close()
        r=CarriedVelocityRenderer(name,carried_weights=weights,**args);zero=[]
        for p,frame in zip(samples,frames):
            actual=r.render(p);error=int(abs(actual.astype(int)-frame.astype(int)).max());zero.append(error)
            assert error==0,('零场不相同',name,p,error)
        y,x=np.mgrid[:FIELD,:FIELD].astype(np.float32);x=LO+(x+.5)/FIELD*SIZE;y=LO+(y+.5)/FIELD*SIZE
        field=np.asarray([np.stack([.2*np.sin(x*3)*np.cos(y*4)*(1-age),.16*np.cos(x*4)*np.sin(y*3)*(1+age)],-1) for age in np.linspace(0,1,a.age_slices)],np.float32)
        r.carried_tex.write(field.tobytes());r.reset();phases=[p+r.layout_change*r.conditional_rules.clock*4*p*(1-p) for p in samples[:4]]
        saved=np.load(HERE/'analysis/match-cache/carried-velocity/paths'/f'{name}.npz')
        ops=operators(saved,phases,age_slices=a.age_slices);rows=[]
        for p,op in zip(samples,ops):
            r.render(p);read=np.frombuffer(r.carried.read(),np.float32).reshape(-1,4)
            actual=read[:,:2]+read[:,2:]*r.program['carried_extrapolate'].value
            predicted=op@field.reshape(-1,2);delta=actual-predicted
            row=dict(phase=p,maximum=float(abs(delta).max()),rms=float(np.sqrt(np.mean(delta**2))))
            assert row['maximum']<.0002,row
            rows.append(row)
        report[name]=dict(zero=zero,nonzero=rows);r.close();print(name,report[name],flush=True)
    assert ctx.error=='GL_NO_ERROR';ctx.release()
    (a.out/'report.json').write_text(json.dumps(dict(age_slices=a.age_slices,scenes=report),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
