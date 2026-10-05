"""检验细分采样是否保留云团覆盖；完整画面与原尺寸颗粒指标同时比较。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_sampling import SamplingRenderer
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);ap.add_argument('--scatter',action='store_true');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-sampling'/('scatter' if a.scatter else 'stratified');out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    variants={'before':(1,0.,0.),'stratified':(2,0.,0.),'independent':(2,1.,0.)}
    if a.scatter:variants={'before':(1,0.,0.),'scatter-small':(3,1.,.006),'scatter-medium':(3,1.,.012)}
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-30),max(0,y-30),min(m['frame'][0],x1+30),min(m['frame'][1],y1+30))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+25)*4),(14,20,30));d=ImageDraw.Draw(sheet)
        r=SamplingRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);rows=[]
        for j,t in enumerate([.23,.38,.53,.70]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref)]
            for key,(n,ind,spread) in variants.items():
                r.program['sampling_side']=n;r.program['sampling_independence']=ind;r.program['sampling_spread']=spread;f=r.render(p);frames.append((key,f))
                row[key]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(label,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));d.text((j*tw+4,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
            rows.append(row)
        r.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,{k:{metric:round(np.mean([q[k][metric] for q in rows]),4) for metric in ['rgb_4','grain']} for k in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
