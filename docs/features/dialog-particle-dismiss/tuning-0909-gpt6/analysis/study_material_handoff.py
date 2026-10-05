"""将材料保留时序与移动时序分开消融，确认过早破碎是否来自绘制交接。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_cohorts import CohortRenderer,load_cohorts
from particle_coverage import load_coverage
from fit_particle_optics import common,measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class HandoffRenderer(CohortRenderer):
    vertex_source=CohortRenderer.vertex_source.replace('void main(){','uniform float handoff_delay;\nvoid main(){',1)
    vertex_source=vertex_source.replace('surface_out=1.-smoothstep(',
        'float handoff_time=time-handoff_delay*4.*time*(1.-time);\n    surface_out=1.-smoothstep(',1)
    vertex_source=vertex_source.replace('source_clock+.060),time);','source_clock+.060),handoff_time);',1)
    vertex_source=vertex_source.replace('time<=m.src.z?1.:0.','handoff_time<=m.src.z?1.:0.',1)
    vertex_source=vertex_source.replace('source_clock+.060+transfer.y),time);','source_clock+.060+transfer.y),handoff_time);',1)
    vertex_source=vertex_source.replace('float paired=1.-smoothstep(0.,.016,time-m.src.z);',
        'float paired=1.-smoothstep(0.,.016,handoff_time-m.src.z);',1)


VARIANTS=[0.,-.025,.025,.05,.075]


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--label',default='coherence-time');a=ap.parse_args()
    out=HERE/'analysis/match-cache/material-handoff'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw.update(coverage=load_coverage(HERE/'particle-coverage-rules.json'),cohorts=load_cohorts(HERE/'particle-cohort-weights.json'))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={};start=time.monotonic()
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];pad=30
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*(len(VARIANTS)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        r=HandoffRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);rows=[]
        for j,p in enumerate([.24,.40,.56,.72]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=measure(ref,c.scene.roi)
            def paste(f,k,title):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片');row=dict(phase=p)
            for k,delay in enumerate(VARIANTS,1):
                r.program['handoff_delay']=delay;frame=r.render(p);paste(frame,k,f'交接偏移 {delay:g}')
                row[str(delay)]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            rows.append(row)
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{str(delay):{key:round(np.mean([q[str(delay)][key] for q in rows]),4) for key in ['rgb_4','grain']} for delay in VARIANTS},round(time.monotonic()-start,1),flush=True)
    ctx.release()


if __name__=='__main__':main()
