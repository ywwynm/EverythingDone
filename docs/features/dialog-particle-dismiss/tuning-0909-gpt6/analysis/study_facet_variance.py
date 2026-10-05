"""固定共同轨迹，检验微片反光方差能否恢复细密亮点；不改默认路径。"""
from pathlib import Path
import sys,json,dataclasses,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class FacetRenderer(CoherenceRenderer):
    fragment_source=CoherenceRenderer.fragment_source.replace('void main(){','uniform vec3 facet_controls;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);', '''
    float facet_phase=age*(8.+4.*random_out.x)+random_out.w*6.2831853;
    float facet_response=pow(.5+.5*cos(facet_phase),4.)/.2734375;
    float released=smoothstep(.008,.06,age);
    float luma=dot(color,vec3(.2126,.7152,.0722));
    vec3 redistributed=mix(color,vec3(luma)*facet_response,facet_controls.x);
    color=max(vec3(0.),mix(color,redistributed,released)
        +vec3(facet_controls.y*facet_response-facet_controls.z)*released);
    frag=vec4(color*alpha,alpha);''',1)

    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self.program['facet_controls']=(0.,0.,0.)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/facet-variance';out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    variants={'当前':(1.65,(0.,0.,0.)),'细粒':(1.,(0.,0.,0.)),'同亮度分配':(1.,(.75,0.,0.)),'明暗重分配':(1.,(.50,.075,.060)),'较强反光':(1.,(.50,.15,.090))}
    for name in a.scenes:
        c=Comparison(name);r=FacetRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.rect;pad=round(c.scene.span*.16);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=280;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*6),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.22,.36,.51,.69]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            def paste(f,k,label):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,(label,(grain,facet)) in enumerate(variants.items(),1):
                r.apply_coverage(dataclasses.replace(kw['coverage'],grain=grain/kw['rules'].grain));r.program['facet_controls']=facet;f=r.render(p);paste(f,k,label)
                row[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                if j==2:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{key:round(float(np.mean([q[v][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in variants},flush=True);r.close()
    ctx.release()


if __name__=='__main__':main()
