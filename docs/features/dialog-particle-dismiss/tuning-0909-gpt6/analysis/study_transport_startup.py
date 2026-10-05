"""隔离新生颗粒过快承担全量输运修正的假设；共同参数，不更改默认路径。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class StartupRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform float transport_startup;\nvoid main(){',1)
    original='move+=transport.xy*span*smoothstep(0.,.03,age);'
    assert original in vertex_source
    vertex_source=vertex_source.replace(original,'move+=transport.xy*span*smoothstep(0.,transport_startup,age);',1)


def main():
    out=HERE/'analysis/match-cache/transport-startup';out.mkdir(parents=True,exist_ok=True)
    _,kw=configuration(HERE,scatter=False);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in ['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']:
        ctx=moderngl.create_standalone_context(require=430);c=Comparison(name)
        r=StartupRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        phases=[];tw=240;th=round(r.h*tw/r.w)
        sheet=Image.new('RGB',(tw*5,(th+24)*4),(14,20,30));d=ImageDraw.Draw(sheet)
        for j,t in enumerate([.16,.29,.43,.61,.79]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            sheet.paste(Image.fromarray(ref).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,24))
            d.text((j*tw+2,2),f'原片 {p:.3f}',font=font,fill='white')
            for k,start in enumerate([.03,.10,.20]):
                r.program['transport_startup']=start;f=r.render(p)
                row[str(start)]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,(k+1)*(th+24)+24))
                d.text((j*tw+2,(k+1)*(th+24)+2),f'运动交接 {start:g}',font=font,fill='white')
            phases.append(row)
        report[name]=phases;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{s:{key:float(np.mean([q[s][key] for q in phases])) for key in ['rgb_4','grain']} for s in ['0.03','0.1','0.2']},flush=True)
        r.close();ctx.release()
    (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
