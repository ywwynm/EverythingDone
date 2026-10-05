"""粒子间相对扩散消融；连续、无位置裁切，保持源色和原共同运动。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class MixingRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform float mixing_strength;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', '''
    uint mixing_key=floatBitsToUint(m.random.x)^floatBitsToUint(m.random.w)^0x3c6ef372u;
    float mixing_radius=sqrt(-2.*log(max(grid_unit(mixing_key),.001)));
    float mixing_angle=grid_unit(mixing_key^0x9e3779b9u)*6.2831853;
    vec2 mixing_random=mixing_radius*vec2(cos(mixing_angle),sin(mixing_angle));
    float mixing_age=(1.-exp(-age/.08))*sqrt(age);
    move+=mixing_random*span*mixing_strength*mixing_age;
    vec2 vertex=move+''',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw);self.program['mixing_strength']=0.


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--strengths',nargs='+',type=float,default=[0.,.04,.10,.18]);a=ap.parse_args()
    out=HERE/'analysis/match-cache/material-mixing';out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in a.scenes:
        c=Comparison(name);r=MixingRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.rect;pad=round(c.scene.span*.20);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*(1+len(a.strengths))),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.22,.36,.51,.69]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            def paste(f,k,label):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,strength in enumerate(a.strengths,1):
                r.program['mixing_strength']=strength*r.layout_change;f=r.render(p);paste(f,k,f'扩散 {strength:g}')
                row[str(strength)]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                if j==2:Image.fromarray(f).save(out/f'{name}-{strength:g}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{str(v):{key:round(float(np.mean([q[str(v)][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in a.strengths},flush=True);r.close()
    ctx.release()


if __name__=='__main__':main()
