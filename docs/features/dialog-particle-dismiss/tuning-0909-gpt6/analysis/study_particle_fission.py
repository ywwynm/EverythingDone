"""放大微片细分与覆盖、明暗的联合消融，真实 GPU 与原片共同复核。"""
from pathlib import Path
import argparse,json,sys,itertools
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_fission import FissionRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--grid',action='store_true');ap.add_argument('--transport');ap.add_argument('--label',default='');ap.add_argument('--threshold',type=float,default=.004);a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-fission'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    variants=[(0.,0.,0.),(1.,0.,0.),(1.,.35,.025),(1.,.65,.06)]
    if a.transport:
        from shared_transport import load_transport
        frozen=out/'transport.json';frozen.write_bytes(Path(a.transport).read_bytes());kw['transport_weights']=load_transport(frozen)
        variants=[(0.,0.,0.),(.5,0.,0.),(1.,0.,0.),(1.,0.,.02)]
    if a.grid:variants=[(0.,0.,0.)]+[(1.,d,c) for d,c in itertools.product([0.,.25,.5,.75],[0.,.02,.06,.12])]
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in ['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']:
        c=Comparison(name);r=FissionRenderer(name,ctx=ctx,quality=2,sites=[Site(*q) for q in OBSERVED_SITES[name]],**kw);rows=[]
        r.program['fission_threshold']=a.threshold
        box=c.scene.rect;x,y,x1,y1=box;box=(max(0,x-35),max(0,y-35),min(c.scene.W,x1+35),min(c.scene.H,y1+35))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+25)*(len(variants)+1)),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,t in enumerate([.23,.38,.53,.70]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref)]
            for vi,(strength,density,neutral) in enumerate(variants):
                r.program['fission_strength']=strength;r.program['fission_density']=density;r.program['fission_neutral']=neutral
                f=r.render(p);frames.append((f'细分 {strength:g} 覆盖 {density:g} 反射 {neutral:g}',f))
                row[str(vi)]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(label,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));draw.text((j*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
                if j==1:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        r.close();report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95)
        print(name,{str(k):{m:round(float(np.mean([q[str(k)][m] for q in rows])),3) for m in ['rgb_4','grain']} for k in range(len(variants))},flush=True)
        (out/'review.json').write_text(json.dumps(dict(variants=variants,results=report),ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
