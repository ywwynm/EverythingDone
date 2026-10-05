"""完整 GPU 绘制核对照；所有序列使用相同核，不能用整体模糊替代颗粒变化。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_kernel import KernelRenderer,KernelRules
from particle_cohorts import load_cohorts
from particle_coverage import load_coverage
from fit_particle_optics import common,measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


VARIANTS={'current':KernelRules(),'gauss-compact':KernelRules(1.,1.5,.19),
          'gauss-balanced':KernelRules(1.,1.5,.22),'gauss-half':KernelRules(.5,1.5,.20),
          'gauss-soft':KernelRules(1.,1.8,.22)}


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--label',default='shape-kernel');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-kernel'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw.update(coverage=load_coverage(HERE/'particle-coverage-rules.json'),cohorts=load_cohorts(HERE/'particle-cohort-weights.json'))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={};start=time.monotonic()
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];pad=30
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*(len(VARIANTS)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        r=KernelRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);rows=[]
        for j,p in enumerate([.24,.40,.56,.72]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=measure(ref,c.scene.roi)
            def paste(f,k,title):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片');row=dict(phase=p)
            for k,(label,q) in enumerate(VARIANTS.items(),1):
                r.apply_kernel(q);frame=r.render(p);paste(frame,k,label)
                row[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
                if j==1:Image.fromarray(frame).save(out/f'{name}-{label}-whole.png')
            if j==1:Image.fromarray(ref).save(out/f'{name}-reference-whole.png')
            rows.append(row)
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{label:{key:round(np.mean([q[label][key] for q in rows]),4) for key in ['rgb_4','grain']} for label in VARIANTS},round(time.monotonic()-start,1),flush=True)
    ctx.release()


if __name__=='__main__':main()
