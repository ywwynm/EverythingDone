"""当前共同模型的合成空间消融：保持源颜色、物理光照与运动不变。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
import renderer as base_renderer
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class CompositingRenderer(CoherenceRenderer):
    def __init__(self,*args,exponent=1.,**kwargs):
        self.fragment_source=CoherenceRenderer.fragment_source.replace('frag=vec4(surface_color*a,a);',f'frag=vec4(pow(max(surface_color,vec3(0)),vec3({1/exponent:.9f}))*a,a);')
        self.fragment_source=self.fragment_source.replace('frag=vec4(color*alpha,alpha);',f'frag=vec4(pow(max(color,vec3(0)),vec3({1/exponent:.9f}))*alpha,alpha);')
        background=base_renderer.BACKGROUND;output=base_renderer.OUTPUT
        try:
            base_renderer.BACKGROUND=background.replace('linear(texture(bg,uv).rgb*(1.-dim))',f'pow(linear(texture(bg,uv).rgb*(1.-dim)),vec3({1/exponent:.9f}))')
            base_renderer.OUTPUT=output.replace('srgb(texture(screen,vec2(uv.x,1.-uv.y)).rgb)',f'srgb(pow(max(texture(screen,vec2(uv.x,1.-uv.y)).rgb,vec3(0)),vec3({exponent:.9f})))')
            super().__init__(*args,**kwargs)
        finally:
            base_renderer.BACKGROUND=background;base_renderer.OUTPUT=output


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/shared-compositing';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);variants={'linear':1.,'power1.5':1.5,'power2.2':2.2}
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-30),max(0,y-30),min(m['frame'][0],x1+30),min(m['frame'][1],y1+30))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+25)*4),(14,20,30));d=ImageDraw.Draw(sheet)
        refs=[(float(c.ref.progress[i]),f) for i,f in [c.ref.nearest(t) for t in [.23,.38,.53,.70]]];rows=[dict(phase=p) for p,_ in refs]
        def paste(f,j,k,label,p):
            sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));d.text((j*tw+4,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
        for j,(p,ref) in enumerate(refs):paste(ref,j,0,'华为原片',p)
        for k,(key,exponent) in enumerate(variants.items(),1):
            r=CompositingRenderer(name,ctx=ctx,quality=2,exponent=exponent,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
            for j,(p,ref) in enumerate(refs):
                f=r.render(p);paste(f,j,k,key,p);target=measure(ref,c.scene.roi)
                rows[j][key]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            r.close()
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{k:{metric:round(np.mean([q[k][metric] for q in rows]),4) for metric in ['rgb_4','grain']} for k in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
