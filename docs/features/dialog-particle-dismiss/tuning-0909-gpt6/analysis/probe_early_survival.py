"""仅诊断早生材料的绘制存续，保持已有轨迹和压力状态；不能直接用于运行。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure
from prepare_uncertain_grain_cache import high_frequency,observed_mask
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class EarlySurvivalRenderer(ScatterRenderer):
    fragment_source=ScatterRenderer.fragment_source.replace('void main(){','uniform float early_survival_gain,time;\nvoid main(){',1)
    fragment_source=fragment_source.replace('float life=life_out;', '''
    float life=life_out;
    if(early_survival_gain>0.){
        float birth=time-age;
        float early=1.-smoothstep(.15,.40,birth);
        life=min(life*(1.+early_survival_gain*early),max(.001,.985-birth));
    }
''',1)
    def __init__(self,*args,**kw):
        super().__init__(*args,**kw);self.program['early_survival_gain']=0.


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman','kobe']);a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);r=EarlySurvivalRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        samples=[c.ref.nearest(p) for p in [.22,.35,.48,.60,.74]];gains=[0.,.3,.6,1.]
        tw=250;th=round(r.h*tw/r.w);dy=th+25;sheet=Image.new('RGB',(tw*5,dy*5),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for j,(i,ref) in enumerate(samples):
            p=float(c.ref.progress[i]);row=dict(phase=p,variants={});images=[('华为原片',ref)]
            reference=measure(ref,c.scene.roi) if c.scene.roi.any() else None
            for gain in gains:
                r.program['early_survival_gain']=gain;frame=r.render(p);images.append((f'早生材料存续 +{gain:g}',frame))
                if reference is not None:score={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),reference)}
                else:
                    mask=observed_mask(c.scene.meta,frame.shape);score={'observable_high_frequency':float((abs(high_frequency(frame)-high_frequency(ref))*mask[...,None]).sum()/mask.sum()/3*255)}
                row['variants'][str(gain)]=score
            for k,(label,f) in enumerate(images):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*dy+25));draw.text((j*tw+3,k*dy+2),f'{label} {p:.3f}',font=font,fill='white')
            rows.append(row)
        sheet.save(a.out/f'{name}.jpg',quality=95);report[name]=rows;r.close()
        print(name,{str(g):{k:float(np.mean([q['variants'][str(g)][k] for q in rows])) for k in rows[0]['variants'][str(g)]} for g in gains},flush=True)
        (a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
