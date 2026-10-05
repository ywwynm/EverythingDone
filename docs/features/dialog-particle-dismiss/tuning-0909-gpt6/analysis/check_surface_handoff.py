"""用 GPU 实际表面覆盖检查交接单调性，并核对首尾、运动状态和输入连续性。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_handoff import HandoffRenderer,load_handoff,handoff_field
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def source_reader(r):
    program=r.ctx.program(vertex_shader=r.vertex_source,varyings=['surface_out'])
    vao=r.ctx.vertex_array(program,[]);buffer=r.ctx.buffer(reserve=r.n*4)
    def read():
        for name in program:
            member=program[name]
            if isinstance(member,moderngl.Uniform) and name in r.program:
                member.value=r.program[name].value
        vao.transform(buffer,mode=moderngl.POINTS,vertices=1,instances=r.n)
        return np.frombuffer(buffer.read(),np.float32).copy()
    return read,lambda:(buffer.release(),vao.release(),program.release())


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',required=True);ap.add_argument('--population');ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES));a=ap.parse_args()
    path=Path(a.candidate);weights=load_handoff(path);_,kw=configuration(HERE,scatter=False);report={};cls=HandoffRenderer;extras={}
    if a.population:
        from particle_population import PopulationRenderer,load_population
        cls=PopulationRenderer;extras['population']=load_population(Path(a.population))
    for name in a.scenes:
        ctx=moderngl.create_standalone_context(require=430)
        sites=[Site(*s) for s in OBSERVED_SITES[name]];args=dict(ctx=ctx,quality=2,sites=sites,**kw)
        old=CoherenceRenderer(name,**args);zero=cls(name,**args);r=cls(name,handoff=weights,**extras,**args)
        max_zero=0;zero_mean=0.;equal=True
        for p in [0.,.21,.47,.79,1.]:
            f=old.render(p);g=zero.render(p);new=r.render(p);difference=abs(f.astype('int16')-g)
            max_zero=max(max_zero,int(difference.max()));zero_mean=max(zero_mean,float(difference.mean()))
            equal&=old.state.read()==r.state.read()
            if p==0:initial=bool(np.array_equal(f,new))
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'));end=int(abs(new.astype('int16')-bg).max())
        read,close=source_reader(r);previous=None;increase=0.;mean_increase=0.;nonmonotone=0
        primary=r.base[:,3]<r.nx*r.ny
        for p in np.linspace(0,1,81):
            r.render(float(p));current=read()[primary]
            if p==0:assert float(current.min())>.999
            if p==1:assert float(current.max())<.001
            if previous is not None:
                delta=np.maximum(current-previous,0);increase=max(increase,float(delta.max()));mean_increase=max(mean_increase,float(delta.mean()));nonmonotone+=int((delta>.002).sum())
            previous=current
        assert ctx.error=='GL_NO_ERROR'
        close();f=r.render(.45);r.render(.75);seek=np.array_equal(f,r.render(.45))
        yy,xx=np.mgrid[:32,:32].astype('float32');xx=(xx+.5)/32;yy=(yy+.5)/32
        v=handoff_field(xx,yy,.5,r.cw,r.ch,r.direction,sites,weights)
        order=float(abs(v-handoff_field(xx,yy,.5,r.cw,r.ch,r.direction,list(reversed(sites)),weights)).max())
        perturbed=[dataclasses.replace(s,x=s.x+.0001,y=s.y+.0001) for s in sites]
        delta=float(abs(v-handoff_field(xx,yy,.5,r.cw,r.ch,r.direction,perturbed,weights)).max())
        row=dict(zero_max=max_zero,zero_mean=zero_mean,state_exact=equal,initial_exact=initial,end_error=end,seek_exact=bool(seek),source_max_recovery=increase,source_mean_recovery=mean_increase,source_recovery_samples=nonmonotone,permutation=order,perturbation=delta,clock_gain=r.handoff_clock_gain)
        report[name]=row;print(name,row,flush=True);old.close();zero.close();r.close();ctx.release()
        (path.parent/('combined-checks.json' if a.population else 'checks.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        # 数量由 uniform 移至逐顶点变量后，新 2 仅一个抗锯齿边缘像素出现 2 级舍入差；
        # 同时约束绝对峰值与全帧均值，不能用均值掩盖区域改变。
        assert max_zero<=2 and zero_mean<.00001 and equal and initial and end<=1 and seek and increase<.002 and order<1e-5 and delta<.002,(name,row)


if __name__=='__main__':main()
