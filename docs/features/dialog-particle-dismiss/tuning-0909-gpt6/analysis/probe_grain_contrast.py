"""粒度／透明度／反光方差独立消融；完整画面与多频段原片比较。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from fit_particle_optics import measure


class GrainRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){','uniform vec3 grain_controls;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+','scale*=exp(grain_controls.x*smoothstep(.025,.12,age));\n    vec2 vertex=move+',1)
    fragment_source=ScatterRenderer.fragment_source.replace('void main(){','uniform vec3 grain_controls;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);',r'''
    float grain_age=smoothstep(.025,.12,age);
    // 较大片元按面积反比减少份额，颗粒中心及其运动始终不变。
    float share=exp(-2.*max(grain_controls.x,0.)*grain_age);
    float selection=fract(random_out.x*79.431+random_out.w*31.781);
    float selected=1.-smoothstep(share-.025,share+.025,selection);
    alpha*=mix(1.,selected,smoothstep(0.,.025,1.-share));
    float probability=clamp(alpha/max(shape,.0001),0.,1.);
    float rank=fract(random_out.y*29.817+random_out.z*43.131);
    float opaque=1.-smoothstep(probability-.075,probability+.075,rank);
    alpha=mix(alpha,shape*opaque,grain_controls.y*grain_age);
    // 固定材料的反射差异，无随时间同步闪动；期望反射保持不变。
    float highlight=pow(max(random_out.w,.001),4.)*5.;
    color*=mix(1.,highlight,grain_controls.z*grain_age);
    frag=vec4(color*alpha,alpha);''',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw);self.program['grain_controls']=(0.,0.,0.)


def detail(frame,mask):
    f=frame.astype(np.float32)/255;luma=f@np.float32([.2126,.7152,.0722]);base=cv2.GaussianBlur(luma,(0,0),2.)
    v=luma-base;result=[]
    for sign in [1.,-1.]:
        for threshold in [.02,.06,.12]:
            tail=np.maximum(sign*v-threshold,0.)
            result.append(cv2.GaussianBlur(tail,(0,0),8.)[mask])
    return np.stack(result,-1)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman','thanos']);ap.add_argument('--large-opaque',action='store_true');ap.add_argument('--scatter',type=Path);a=ap.parse_args()
    out=HERE/'analysis/match-cache'/('grain-contrast-large' if a.large_opaque else 'grain-contrast');out.mkdir(parents=True,exist_ok=True)
    variants={'current':(0.,0.,0.),'radius':(np.log(1.35),0.,0.),'opacity':(0.,.35,0.),'reflection':(0.,0.,.45),
              'radius-opacity':(np.log(1.35),.35,0.),'radius-reflection':(np.log(1.35),0.,.45)}
    if a.large_opaque:variants={'current':(0.,0.,0.),'opaque':(0.,1.,0.),'radius1.6-opaque':(np.log(1.6),1.,0.),'radius2.2-opaque':(np.log(2.2),1.,0.)}
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    if a.scatter:
        from scatter_material import load_scatter
        kw['scatter']=load_scatter(a.scatter)
    for name in a.scenes:
        c=Comparison(name);r=GrainRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        tw=300;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*3,(th+26)*(len(variants)+1)),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for col,t in enumerate([.32,.49,.64]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);grain=detail(ref,c.scene.roi);row=dict(phase=p)
            for k,(label,controls) in enumerate([('华为原片',None),*variants.items()]):
                if controls is None:f=ref
                else:
                    r.program['grain_controls']=tuple(float(v) for v in controls);f=r.render(p)
                    row[label]={key:float(abs(u-v).mean()) if u.size else None for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    row[label]['signed_detail']=float(abs(detail(f,c.scene.roi)-grain).mean()) if grain.size else None
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+26)+26));draw.text((col*tw+2,k*(th+26)+2),f'{label} {p:.3f}',font=font,fill='white')
                if col==1:Image.fromarray(f).save(out/f'{name}-{label}.png')
            rows.append(row)
        r.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{m:round(float(np.mean([r[v][m] for r in rows])),5) for m in ['rgb_4','grain','signed_detail']} for v in variants} if c.scene.roi.any() else '背景不完整，仅目视比较',flush=True)
    ctx.release()


if __name__=='__main__':main()
