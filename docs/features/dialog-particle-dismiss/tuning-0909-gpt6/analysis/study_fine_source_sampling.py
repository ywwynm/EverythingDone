"""独立核对九份子片的纹理采样，不修改运动、数量或默认渲染器。"""
from pathlib import Path
import sys, json
import numpy as np, moderngl
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES,measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

class LegacySourceRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace(
        'uv=(m.src.xy+(uv*card-m.src.xy)/3.+location*cell)/card;',
        'source=m.src.xy+(source-m.src.xy)/3.+location*cell;',1)

def main():
    out=HERE/'analysis/match-cache/fine-source-sampling';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in NAMES:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        old=LegacySourceRenderer(name,**args);new=cls(name,**args);rows=[]
        x,y,x1,y1=c.scene.rect;box=(max(0,x-35),max(0,y-35),min(c.scene.W,x1+35),min(c.scene.H,y1+35));tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]))
        sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));draw=ImageDraw.Draw(sheet)
        for col,requested in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            for k,(label,f) in enumerate([('华为原片',ref),('重复母片纹理',old.render(p)),('子片对应纹理',new.render(p))]):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+24)+24));draw.text((col*tw,k*(th+24)),f'{label} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'after']={m:float(abs(a-b).mean()) for m,a,b in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        old.close();new.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{s:{m:round(float(np.mean([v[s][m] for v in rows])),5) for m in ['rgb_4','grain']} for s in ['before','after']},flush=True)
    ctx.release()
if __name__=='__main__':main()
