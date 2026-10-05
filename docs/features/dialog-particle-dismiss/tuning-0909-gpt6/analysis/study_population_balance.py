"""检查两类共同材料拟合能否兼顾覆盖和颗粒统计；只作离线组合实验。"""
from pathlib import Path
import argparse, json, sys
import numpy as np
import moderngl
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_population import PopulationRenderer, load_population, population_field
from check_cloud_evolution import configuration
from fit_particle_optics import measure, NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--scenes',nargs='+',default=NAMES)
    ap.add_argument('--ratios',nargs='+',type=float,default=[0.,.25,.5,.75,1.])
    a=ap.parse_args()
    out=HERE/'analysis/match-cache/population-balance';out.mkdir(parents=True,exist_ok=True)
    paths=[HERE/'analysis/match-cache/particle-population/joint-population/candidate.json',
           HERE/'analysis/match-cache/particle-distribution/joint-distribution/candidate.json']
    weights=[load_population(p) for p in paths]
    _,kw=configuration(HERE,scatter=False)
    yy,xx=np.mgrid[:56,:56].astype('float32')
    xx=-.38+(xx+.5)/56*1.76;yy=-.38+(yy+.5)/56*1.76
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        ctx=moderngl.create_standalone_context(require=430)
        c=Comparison(name);sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=PopulationRenderer(name,ctx=ctx,quality=2,sites=sites,**kw)
        fields=[np.asarray([population_field(xx,yy,t,r.cw,r.ch,r.direction,sites,w)
                             for t in np.linspace(0,1,41)],np.float32) for w in weights]
        values={"default":np.zeros_like(fields[0])}
        values.update({f"mix-{v:g}":(1-v)*fields[0]+v*fields[1] for v in a.ratios})
        phases=[];frames={key:[] for key in ['reference',*values]}
        for requested in [.18,.29,.41,.58,.77]:
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi)
            row=dict(phase=p);frames['reference'].append(ref)
            for key,field in values.items():
                r.population_tex.write(np.ascontiguousarray(field).tobytes());im=r.render(p)
                row[key]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(im,c.scene.roi),target)}
                frames[key].append(im)
            phases.append(row)
        report[name]=dict(rows=phases,summary={key:{k:float(np.mean([q[key][k] for q in phases]))
                          for k in ['rgb_1','rgb_4','rgb_12','grain']} for key in values})
        if name in ['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']:
            tw=240;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*5,(th+24)*len(frames)),(14,20,30));d=ImageDraw.Draw(sheet)
            for row,(key,images) in enumerate(frames.items()):
                for col,im in enumerate(images):
                    sheet.paste(Image.fromarray(im).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*(th+24)+24))
                    d.text((col*tw+3,row*(th+24)+3),f'{key} {phases[col]["phase"]:.3f}',font=font,fill='white')
            sheet.save(out/f'{name}.jpg',quality=94)
        r.close();ctx.release()
        (out/'review.json').write_text(json.dumps(dict(inputs=[str(p) for p in paths],scenes=report),ensure_ascii=False,indent=2),'utf-8')
        print(name,report[name]['summary'],flush=True)


if __name__=='__main__':main()
