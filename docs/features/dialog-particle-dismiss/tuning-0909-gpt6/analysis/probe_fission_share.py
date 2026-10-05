"""独立检查细分份额与子片散布，保持母片运动、时序及光照不变。"""
from pathlib import Path
import argparse,json,sys
import cv2,moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def detail(frame,mask):
    value=np.asarray(frame,np.float32)/255
    lum=value@np.float32([.2126,.7152,.0722])
    hp=lum-cv2.GaussianBlur(lum,(0,0),1.5)
    return np.stack([cv2.GaussianBlur(1/(1+np.exp(np.clip(-(sign*hp-threshold)/.015,-30,30))),(0,0),8)[mask]
                     for sign in [1,-1] for threshold in [.025,.06,.12,.24]])


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=HERE)
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman','kobe'])
    ap.add_argument('--out',type=Path,required=True);a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(a.root);ctx=moderngl.create_standalone_context(require=430)
    variants=[('原细分',1.,.006,.010),('较少细分',.70,.006,.010),('较高尺寸阈值',1.,.008,.010),('较小子片散布',1.,.006,.005)]
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        tw=270;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*5,(th+25)*(len(variants)+1)),(14,20,30));draw=ImageDraw.Draw(sheet)
        rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);images=[('华为原片',ref)];row=dict(phase=p)
            target=measure(ref,c.scene.roi) if c.scene.roi.any() else None
            target_detail=detail(ref,c.scene.roi) if target else None
            for label,strength,threshold,spread in variants:
                # 诊断只改变几何参数；零值控制及原版布局保持共同缩放规则。
                g=r.layout_change
                r.program['fission_strength']=1+g*(strength-1)
                r.program['fission_threshold']=.006+g*(threshold-.006)
                r.program['fission_spread']=.010+g*(spread-.010)
                f=r.render(p);images.append((label,f))
                if target:
                    row[label]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    row[label]['signed_grain']=float(abs(detail(f,c.scene.roi)-target_detail).mean())
            for k,(label,f) in enumerate(images):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25))
                draw.text((j*tw+3,k*(th+25)+2),f'{label} {p:.3f}',font=font,fill='white')
            rows.append(row)
        sheet.save(a.out/f'{name}.jpg',quality=94)
        report[name]=dict(layout_change=r.layout_change,rows=rows)
        print(name,{label:{k:float(np.mean([v[label][k] for v in rows])) for k in ['rgb_4','grain','signed_grain']} for label,*_ in variants} if c.scene.roi.any() else '背景不完整，仅作原片目视对照',flush=True)
        r.close();(a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
