"""共同颗粒大小/数量分配消融：保留母片与运动，改变同等总面积的分配。"""
from pathlib import Path
import sys,json,dataclasses,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from measure_particle_footprint import geometry_reader,geometry_stats


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);a=ap.parse_args()
    out=HERE/'analysis/match-cache/grain-allocation';out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    variants={'当前':1.65,'中间分配':1.25,'完整数量':1.0}
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);read,close=geometry_reader(r)
        box=c.scene.rect;pad=round(c.scene.span*.20);x,y,x1,y1=box;box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*4),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.23,.38,.53,.70]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            def paste(f,k,label):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,(label,grain) in enumerate(variants.items(),1):
                r.apply_coverage(dataclasses.replace(kw['coverage'],grain=grain/kw['rules'].grain));f=r.render(p);paste(f,k,label)
                row[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                row[label]['gpu']=geometry_stats(r,read(),c.scene)
                if j==2:Image.fromarray(f).save(out/f'{name}-{grain}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{key:round(float(np.mean([q[v][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in variants},flush=True)
        close();r.close()
    ctx.release()


if __name__=='__main__':main()
