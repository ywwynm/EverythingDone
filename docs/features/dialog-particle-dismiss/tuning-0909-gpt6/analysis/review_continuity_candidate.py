"""连续输运候选与原片对照；整机画面、静态误差和连续运动分别判断。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from kinematic_candidate import KinematicRenderer
from opacity_candidate import OpacityRenderer
from probe_motion_material import configure
from match_compare import Comparison
from fit_particle_optics import measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class CandidateRenderer(KinematicRenderer):
    fragment_source=OpacityRenderer.fragment_source
    def __init__(self,*a,opacity=0.,threshold=.006,profile='time-linear',**kw):
        super().__init__(*a,response=.025,acceleration=24.,**kw)
        configure(self,profile);self.program['opacity_distribution']=opacity;self.program['fission_threshold']=threshold


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=NAMES+['reference-0926-2','reference-0926-3']);ap.add_argument('--label',default='continuous-review');ap.add_argument('--opacity',type=float,default=0.);ap.add_argument('--threshold',type=float,default=.006);ap.add_argument('--profile',default='time-linear');ap.add_argument('--weights',type=Path,help='直接复核共同输运权重，不附加平滑或材质实验');a=ap.parse_args()
    out=HERE/'analysis/match-cache/motion-continuity'/a.label;out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in a.scenes:
        c=Comparison(name);base=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        if a.weights:
            from shared_transport import load_transport
            candidate_kw=dict(kw,transport_weights=load_transport(a.weights))
            new=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**candidate_kw)
        else:
            new=CandidateRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],opacity=a.opacity,threshold=a.threshold,profile=a.profile,**kw)
        tw=290;th=round(base.h*tw/base.w);sheet=Image.new('RGB',(tw*5,(th+26)*3),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,t in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);before=base.render(p);after=new.render(p);row=dict(phase=p)
            if c.scene.roi.any():
                target=measure(ref,c.scene.roi)
                for stage,f in [('before',before),('after',after)]:row[stage]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(title,frame) in enumerate([('华为原片',ref),('调整前',before),('连续输运候选',after)]):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26));draw.text((j*tw+3,k*(th+26)+3),f'{title} {p:.3f}',font=font,fill='white')
                if j==2:Image.fromarray(frame).save(out/f'{name}-{k}.png')
            rows.append(row)
        sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        summary={stage:{key:float(np.mean([q[stage][key] for q in rows])) for key in ['rgb_4','grain']} for stage in ['before','after']} if c.scene.roi.any() else '重建背景，仅目视'
        print(name,summary,flush=True);base.close();new.close();(out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
