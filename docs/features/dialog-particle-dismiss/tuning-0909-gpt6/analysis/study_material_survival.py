"""固定释放和流场，分离早期材料过早退场与绘制覆盖的影响。"""
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


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);a=ap.parse_args()
    out=HERE/'analysis/match-cache/material-survival';out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    variants={'当前':{},'延长存续':dict(life=1.35),'延长并细化':dict(life=1.35,size=1.,grain=1.15),'较长细粒':dict(life=1.8,size=.9,grain=1.05)}
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=c.scene.rect;pad=round(c.scene.span*.20);box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*5),(14,20,30));draw=ImageDraw.Draw(sheet)
        frames=[(float(c.ref.progress[i]),ref) for i,ref in [c.ref.nearest(t) for t in [.22,.36,.51,.69]]];rows=[dict(phase=p) for p,_ in frames]
        def paste(f,j,k,label,p):
            sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
        for j,(p,ref) in enumerate(frames):paste(ref,j,0,'华为原片',p)
        for k,(label,changes) in enumerate(variants.items(),1):
            args=dict(kw);args['rules']=dataclasses.replace(kw['rules'],**changes);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**args)
            for j,(p,ref) in enumerate(frames):
                f=r.render(p);paste(f,j,k,label,p);target=measure(ref,c.scene.roi)
                rows[j][label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                if j==2:Image.fromarray(f).save(out/f'{name}-{k}.png')
            r.close()
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{v:{key:round(float(np.mean([q[v][key] for q in rows])),4) for key in ['rgb_4','grain']} for v in variants},flush=True)
    ctx.release()


if __name__=='__main__':main()
