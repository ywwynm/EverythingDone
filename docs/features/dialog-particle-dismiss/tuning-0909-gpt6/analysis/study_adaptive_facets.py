"""固定真实材料状态，将过大绘制片细分为有限微片；完整交接模型下的消融。"""
from pathlib import Path
import sys,json,dataclasses,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from study_facet_variance import FacetRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class Array:
    def __init__(self,vao,program):self.vao=vao;self.program=program
    def render(self,**kw):
        if self.program['material_pass'].value==1:kw['instances']*=4
        return self.vao.render(**kw)
    def release(self):self.vao.release()


class SubfacetRenderer(FacetRenderer):
    vertex_source=FacetRenderer.vertex_source.replace('gl_InstanceID','parent_instance')
    vertex_source=vertex_source.replace('void main(){','''uniform float facet_subdivision;
void main(){
    int parent_instance=material_pass==1?gl_InstanceID/4:gl_InstanceID;
    int facet_index=material_pass==1?gl_InstanceID%4:0;
''',1)
    vertex_source=vertex_source.replace('uv=source/card;', '''
    float subdivision=material_pass==1?facet_subdivision:0.;
    float child_scale=facet_index==0?1.-.5*subdivision:.5*subdivision;
    float area_normalizer=inversesqrt(1.-subdivision+subdivision*subdivision);
    vec2 child_offset=(vec2(facet_index%2,facet_index/2)-.5)*cell*.5*subdivision;
    vec2 original_source=source;
    source=m.src.xy+(source-m.src.xy)*child_scale+child_offset;
    uv=source/card;''',1)
    vertex_source=vertex_source.replace('float loosen=smoothstep(.018,.18,age);','''
    uint child_key=uint(parent_instance)*0x9e3779b9u+uint(facet_index)*0x85ebca6bu;
    vec4 child_random=vec4(grid_unit(child_key),grid_unit(child_key^0x68bc21ebu),grid_unit(child_key^0x39b15u),grid_unit(child_key^0x946aeu));
    m.random=mix(m.random,child_random,subdivision);random_out=m.random;
    float loosen=smoothstep(.018,.18,age);''',1)
    vertex_source=vertex_source.replace('vec2 d=source-m.src.xy;','vec2 d=(original_source-m.src.xy)*child_scale;',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', '''
    scale*=area_normalizer;
    move+=rot*child_offset*scale;
    vec2 vertex=move+''',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw);self.program['facet_subdivision']=self.layout_change;self.vao=Array(self.vao,self.program)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);a=ap.parse_args()
    out=HERE/'analysis/match-cache/adaptive-facets';out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    variants={'当前':(0.,1.65,(0.,0.,0.)),'几何细分':(1.,1.65,(0.,0.,0.)),'细分与数量':(1.,1.,(0.,0.,0.)),'细分与反光':(1.,1.25,(.35,.045,.035))}
    for name in a.scenes:
        c=Comparison(name);r=SubfacetRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.rect;pad=round(c.scene.span*.16);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*5),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.22,.36,.51,.69]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            def paste(f,k,label):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,(label,(sub,grain,facet)) in enumerate(variants.items(),1):
                r.apply_coverage(dataclasses.replace(kw['coverage'],grain=grain/kw['rules'].grain));r.program['facet_subdivision']=sub*r.layout_change;r.program['facet_controls']=tuple(v*r.layout_change for v in facet);f=r.render(p);paste(f,k,label)
                row[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                if j==2:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{key:round(float(np.mean([q[v][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in variants},flush=True);r.close()
    ctx.release()


if __name__=='__main__':main()
