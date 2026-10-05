"""未采用消融：只按实际材料分布减弱内部附加卷动，主流场与材质不变。"""
from pathlib import Path
import argparse, json, sys
import numpy as np, moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from pressure_grid import PROJECT
from check_cloud_evolution import configuration
from diagnose_motion_continuity import trajectory_reader,metrics
from match_compare import Comparison
from fit_particle_optics import measure
from prepare_uncertain_grain_cache import high_frequency,observed_mask
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class BoundaryCurlRenderer(ScatterRenderer):
    compute_source=ScatterRenderer.compute_source.replace('void main(){','''
uniform float boundary_curl_strength;
float boundary_curl(vec2 p,float age){
    vec2 uv=(p-peel_bounds.xy)/peel_bounds.zw;
    float boundary=texture(peel_field,uv).w;
    return 1.-boundary_curl_strength*(1.-boundary)*smoothstep(.02,.075,age);
}
void main(){''',1)
    compute_source=compute_source.replace('curl_gain*conditional_curl;',
        'curl_gain*conditional_curl;\n    if(boundary_curl_strength>0.)flow*=boundary_curl(p,age);',1)
    compute_source=compute_source.replace('target+=curl(p+transport_random*span*.4,time)*span*.075*conditional_curl*smoothstep(.04,.16,age)*independent;', '''
    vec2 extra_curl=curl(p+transport_random*span*.4,time)*span*.075*conditional_curl*smoothstep(.04,.16,age)*independent;
    if(boundary_curl_strength>0.)extra_curl*=boundary_curl(p,age);
    target+=extra_curl;''',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw)
        project=PROJECT.replace('imageStore(pressure_field,q,vec4(-correction,0.,0.));', '''
        float rho=max(velocity[i].z,0.);
        ivec2 D=ivec2(3,0),E=ivec2(0,3);
        vec2 grad=vec2(velocity[at(q+D)].z-velocity[at(q-D)].z,
                       velocity[at(q+E)].z-velocity[at(q-E)].z)*16.;
        float relative=length(grad)/max(rho,.03);
        float edge=smoothstep(1.5,10.,relative);
        edge=mix(1.,edge,smoothstep(.01,.20,rho));
        imageStore(pressure_field,q,vec4(-correction,rho,edge));''')
        grid=self.pressure_grid;grid.project.release();grid.project=self.ctx.compute_shader(project)
        grid.project['grid_shape']=grid.shape
        grid.velocity.clear();grid.reset()
        self.compute['boundary_curl_strength']=0.

    def configure(self,strength):
        self.compute['boundary_curl_strength']=float(strength)
        self.reset()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--root',type=Path,default=HERE);ap.add_argument('--scenes',nargs='+',default=['reference-0926-2','ironman-up-reference','ironman'])
    ap.add_argument('--strengths',nargs='+',type=float,default=[0.,.65,1.]);a=ap.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430)
    cls,kw=configuration(a.root);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        baseline=cls(name,**args)
        samples=[]
        before=[];states=[];zero=[]
        for t in [.18,.29,.41,.58,.77]:
            i,f=c.ref.nearest(t);p=float(c.ref.progress[i]);samples.append((p,f))
            before.append(baseline.render(p))
            states.append(np.frombuffer(baseline.state.read(),np.float32).copy())
        baseline.close();r=BoundaryCurlRenderer(name,**args)
        for (p,_),frame,state in zip(samples,before,states):
            actual=r.render(p)
            diff=abs(actual.astype(int)-frame.astype(int))
            state_error=float(abs(np.frombuffer(r.state.read(),np.float32)-state).max()/r.span)
            zero.append(dict(phase=p,max_pixel_delta=int(diff.max()),changed_pixel_fraction=float((diff.max(-1)>0).mean()),state_delta_per_span=state_error))
        print('零消融',name,zero,flush=True)
        # 该消融着色器不与原着色器逐位等价，完整保留零强度的舍入控制量。
        # 后续数值和视觉比较均须先对照本消融的零强度，不能声称零改动完全相同。
        if not all(np.isfinite(v['state_delta_per_span']) for v in zero):raise ValueError(zero)
        report[name]={'zero_equivalence':zero}
        for strength in a.strengths:
            r.configure(strength);tw=270;th=round(r.h*tw/r.w)
            sheet=Image.new('RGB',(5*tw,2*(th+25)),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
            for j,(p,reference) in enumerate(samples):
                frame=r.render(p);row=dict(phase=p)
                if c.scene.roi.any():
                    row.update({k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),measure(reference,c.scene.roi))})
                else:
                    mask=observed_mask(c.scene.meta,frame.shape)
                    row['observable_high_frequency']=float((abs(high_frequency(frame)-high_frequency(reference))*mask[...,None]).sum()/mask.sum()/3*255)
                rows.append(row)
                for k,(label,f) in enumerate([('华为原片',reference),(f'边缘卷动实验 {strength:g}',frame)]):
                    sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25))
                    draw.text((j*tw+3,k*(th+25)+2),f'{label} {p:.3f}',font=font,fill='white')
            sheet.save(a.out/f'{name}-{strength:g}.jpg',quality=95)
            r.configure(strength);read,close=trajectory_reader(r);phases=np.arange(0,.901,1/120);tr=[]
            for p in phases:r.render(float(p));tr.append(read())
            tr=np.asarray(tr);fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
            sx=np.clip(r.base[:,0].astype(int),0,r.cw-1);sy=np.clip(r.base[:,1].astype(int),0,r.ch-1)
            motion,_,_=metrics(tr,phases,np.array(r.wind),r.span,fg[sy,sx,3]>.5);close()
            keys=[k for k in rows[0] if k!='phase'];summary={k:float(np.mean([v[k] for v in rows])) for k in keys}
            report[name][str(strength)]=dict(summary=summary,motion=motion,rows=rows)
            print(name,strength,summary,'同步',motion['displayed']['maximum_synchronized_turn_fraction'],'往返',motion['displayed']['lateral_excess_quantiles'],flush=True)
            (a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        r.close()
    ctx.release()


if __name__=='__main__':main()
