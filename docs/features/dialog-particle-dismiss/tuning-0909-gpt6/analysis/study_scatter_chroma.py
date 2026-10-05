"""共同细粒分布与源色／中性反射消融。只写诊断产物。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_fission import FissionRenderer
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from shared_transport import load_transport

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--transport');ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/scatter-chroma';out.mkdir(parents=True,exist_ok=True)
    variants=[(0.,.003,0.,0.),(1.,.003,0.,0.),(1.,.010,0.,0.),(1.,.010,.07,-.4),(1.,.010,.14,-.7),(1.,.010,.25,-.9)]
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    if a.transport:kw['transport_weights']=load_transport(Path(a.transport))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);r=FissionRenderer(name,ctx=ctx,quality=2,sites=[Site(*q) for q in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.rect;box=(max(0,x-35),max(0,y-35),min(c.scene.W,x1+35),min(c.scene.H,y1+35))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+25)*(len(variants)+1)),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for col,t in enumerate([.23,.38,.53,.70]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p);frames=[('华为原片',ref)]
            for k,(strength,spread,neutral,chroma) in enumerate(variants):
                for key,v in dict(strength=strength,spread=spread,neutral=neutral,chroma=chroma,threshold=.006).items():r.program['fission_'+key]=v
                f=r.render(p);frames.append((f'细分 {strength:g} 扩展 {spread:g} 无色 {neutral:g} 色度 {1+chroma:g}',f))
                row[str(k)]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(label,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+25)+25));draw.text((col*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
            rows.append(row)
        r.close();sheet.save(out/f'{name}.jpg',quality=95);report[name]=rows
        print(name,{k:{metric:round(float(np.mean([row[str(k)][metric] for row in rows])),4) for metric in ['rgb_4','grain']} for k in range(len(variants))},flush=True)
        (out/'review.json').write_text(json.dumps(dict(variants=variants,results=report),ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
