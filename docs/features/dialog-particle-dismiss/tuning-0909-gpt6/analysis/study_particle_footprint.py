"""原尺寸 GPU 足迹消融；相同规则用于所有华为参考。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_footprint import FootprintRenderer,FootprintRules
from particle_coverage import CoverageRules
from shared_transport import load_transport
from fit_particle_optics import common,measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--variants',nargs='+');ap.add_argument('--label',default='footprint-response');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-coverage'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    kw['transport_weights']=load_transport(HERE/'analysis/match-cache/shared-transport/coverage-refined-flow.json')
    variants={'current':FootprintRules(),'soft1.25':FootprintRules(1.25),'soft1.5':FootprintRules(1.5),'soft2':FootprintRules(2.),'soft1.5-tint':FootprintRules(1.5,.6),'tint':FootprintRules(1.,.6)}
    if a.variants:variants={key:variants[key] for key in a.variants}
    for name in a.scenes:
        c=Comparison(name);r=FootprintRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],coverage=CoverageRules(1,1.1,1),**kw)
        x,y,x1,y1=c.scene.meta['rect'];pad=round(min(x1-x,y1-y)*.18);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(5*tw,(len(variants)+1)*(th+24)),(14,20,30));d=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=measure(ref,c.scene.roi);entry=dict(phase=p)
            def paste(frame,k,title):
                sheet.paste(Image.fromarray(frame).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,(key,rules) in enumerate(variants.items(),1):
                r.apply_footprint(rules);frame=r.render(p);paste(frame,k,key)
                entry[key]={key:float(np.abs(m-t).mean()) if m.size else None for key,m,t in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            rows.append(entry)
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        print(name,{v:tuple(round(np.mean([q[v][k] for q in rows]),3) if rows[0][v][k] is not None else None for k in ['rgb_4','grain']) for v in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
