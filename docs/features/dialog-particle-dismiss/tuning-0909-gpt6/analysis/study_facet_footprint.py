"""细粒与成像足迹联动消融；只滤波已释放微片，不模糊背景或原表面。"""
from pathlib import Path
import sys,json,dataclasses,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from study_adaptive_facets import SubfacetRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class FootprintRenderer(SubfacetRenderer):
    vertex_source=SubfacetRenderer.vertex_source.replace('void main(){','uniform float facet_psf;\nflat out float facet_mass;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 world=offset+vertex;','''
    facet_mass=0.;
    if(material_pass==1 && facet_psf>0. && facet_subdivision>.00001){
        vec2 side=cell*child_scale*scale*(1.+z/1000.)*vec2(mix(1.,.50+.50*tilt,loosen),1.);
        vec2 sigma=sqrt(pow(side*.30,vec2(2.))+vec2(facet_psf*facet_psf));
        facet_mass=side.x*side.y*.66/(6.2831853*sigma.x*sigma.y);
        vertex=move+rot*((c-.5)*6.*sigma);
        uv=(m.src.xy+child_offset)/card;
    }
    vec2 world=offset+vertex;''',1)
    fragment_source=SubfacetRenderer.fragment_source.replace('void main(){','flat in float facet_mass;\nvoid main(){',1)
    fragment_source=fragment_source.replace('float shape=mix(1.,cut,shape_out*.92);','''
    float shape=mix(1.,cut,shape_out*.92);
    if(facet_mass>0.)shape=facet_mass*exp(-18.*dot(q,q));''',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw);self.program['facet_psf']=0.


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/facet-footprint';out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    variants={'当前':(0.,1.65,0.,(0.,0.,0.)),'细片足迹':(1.,1.25,.6,(0.,0.,0.)),'较宽足迹':(1.,1.25,1.,(0.,0.,0.)),'细片足迹反光':(1.,1.25,.6,(.25,.03,.02))}
    for name in a.scenes:
        c=Comparison(name);r=FootprintRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.rect;pad=round(c.scene.span*.16);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*5),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.22,.36,.51,.69]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            def paste(f,k,label):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,(label,(sub,grain,psf,facet)) in enumerate(variants.items(),1):
                r.apply_coverage(dataclasses.replace(kw['coverage'],grain=grain/kw['rules'].grain));r.program['facet_subdivision']=sub*r.layout_change;r.program['facet_psf']=psf;r.program['facet_controls']=tuple(v*r.layout_change for v in facet);f=r.render(p);paste(f,k,label)
                row[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                if j==2:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{key:round(float(np.mean([q[v][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in variants},flush=True);r.close()
    ctx.release()


if __name__=='__main__':main()
