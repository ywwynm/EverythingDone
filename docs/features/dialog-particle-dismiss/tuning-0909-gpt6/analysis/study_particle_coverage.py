"""完整 GPU 共同面积／透过率消融；参考颗粒数量不通过整体图像模糊替代。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,CoverageRules
from fit_particle_optics import common,measure,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

VARIANTS={'before':CoverageRules(),'area-half':CoverageRules(.5),'area':CoverageRules(1.),'fine-area':CoverageRules(1.,.75),'fine-half':CoverageRules(.5,.75)}
VARIANTS.update({'count':CoverageRules(1.,1.,1.),'mixed-count':CoverageRules(1.,1.,.5),'coarse-area':CoverageRules(1.,1.2),'coarse-count':CoverageRules(1.,1.2,1.),'coarse-half':CoverageRules(.5,1.2)})
VARIANTS.update({'balanced':CoverageRules(1.,1.10,1.),'balanced-mixed':CoverageRules(1.,1.10,.75),'balanced-strong':CoverageRules(1.25,1.10,1.)})
VARIANTS.update({'survival-half':CoverageRules(1.,1.1,1.,.5),'survival-full':CoverageRules(1.,1.1,1.,1.)})

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='area-response');ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--variants',nargs='+',default=list(VARIANTS));ap.add_argument('--flow');ap.add_argument('--coverage');ap.add_argument('--base-coverage');ap.add_argument('--release');ap.add_argument('--cohorts');ap.add_argument('--microfacets',action='store_true');a=ap.parse_args()
    from particle_coverage import load_coverage
    if a.coverage:VARIANTS['candidate']=load_coverage(Path(a.coverage))
    if a.base_coverage:VARIANTS['before']=load_coverage(Path(a.base_coverage))
    out=HERE/'analysis/match-cache/particle-coverage'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);r=CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        if a.flow or a.release or a.cohorts or a.microfacets:
            from shared_transport import load_transport
            from learned_release import load_weights
            candidate_kw=dict(kw)
            candidate_cls=CoverageRenderer
            if a.microfacets:
                from particle_microfacets import MicrofacetRenderer
                candidate_cls=MicrofacetRenderer
            if a.cohorts:
                from particle_cohorts import CohortRenderer,load_cohorts
                candidate_cls=CohortRenderer;candidate_kw['cohorts']=load_cohorts(Path(a.cohorts))
            if a.flow:candidate_kw['transport_weights']=load_transport(Path(a.flow))
            if a.release:candidate_kw['weights']=load_weights(Path(a.release))
            candidate=candidate_cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**candidate_kw)
        else:candidate=r
        m=c.scene.meta;x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.18);box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(5*tw,(len(a.variants)+1)*(th+24)),(14,20,30));d=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=measure(ref,c.scene.roi);entry=dict(phase=p)
            def paste(frame,k,title):
                sheet.paste(Image.fromarray(frame).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
            paste(ref,0,'华为原片')
            for k,key in enumerate(a.variants,1):
                renderer=candidate if key=='candidate' else r
                renderer.apply_coverage(VARIANTS[key]);frame=renderer.render(p);paste(frame,k,key)
                entry[key]={key:float(np.abs(m-t).mean()) if m.size else None for key,m,t in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            rows.append(entry)
        if candidate is not r:candidate.close()
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        print(name,{v:tuple(round(np.mean([q[v][k] for q in rows]),3) if rows[0][v][k] is not None else None for k in ['rgb_4','grain']) for v in a.variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
