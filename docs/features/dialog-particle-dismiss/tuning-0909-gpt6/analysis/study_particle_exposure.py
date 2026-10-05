"""诊断归一化短曝光对颗粒质感的影响；完整线性光积分，不改模型轨迹或颜色。

此处只作观察模型消融，不能由录屏文件帧率推断手机内部曝光或动画时长。
"""
from pathlib import Path
import argparse,json,sys
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_cohorts import CohortRenderer,load_cohorts
from particle_coverage import load_coverage
from fit_particle_optics import common,measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def linear(x):
    x=x.astype('float32')/255.
    return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)


def srgb(x):
    return np.uint8(np.clip(np.where(x<=.0031308,x*12.92,1.055*np.maximum(x,.0031308)**(1/2.4)-.055)*255+.5,0,255))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-exposure';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw.update(coverage=load_coverage(HERE/'particle-coverage-rules.json'),cohorts=load_cohorts(HERE/'particle-cohort-weights.json'))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    shutters=[0.,.008,.016,.032]
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.18)
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*5),(14,20,30));draw=ImageDraw.Draw(sheet)
        r=CohortRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);rows=[]
        for j,p in enumerate([.24,.40,.56,.72]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=measure(ref,c.scene.roi)
            offsets=sorted(set([round(s*v,8) for s in shutters for v in [-.5,-.25,0.,.25,.5]]))
            samples={delta:r.render(float(np.clip(p+delta,0,1))) for delta in offsets}
            frames=[ref];row=dict(phase=p)
            for shutter in shutters:
                if shutter==0:f=samples[0.]
                else:
                    # 五点 Simpson 积分，统一在线性光域平均。
                    q=[linear(samples[round(shutter*v,8)]) for v in [-.5,-.25,0.,.25,.5]]
                    f=srgb(sum(v*w for v,w in zip(q,[1,4,2,4,1]))/12.)
                frames.append(f)
                row[str(shutter)]={key:float(np.abs(v-t).mean()) for key,v,t in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
            for k,(title,f) in enumerate(zip(['华为原片','当前模型','曝光 0.008','曝光 0.016','曝光 0.032'],frames)):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));draw.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
        r.close();sheet.save(out/f'{name}.jpg',quality=95);report[name]=rows
        print(name,{str(s):{key:round(np.mean([v[str(s)][key] for v in rows]),4) for key in ['rgb_4','grain']} for s in shutters},flush=True)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
