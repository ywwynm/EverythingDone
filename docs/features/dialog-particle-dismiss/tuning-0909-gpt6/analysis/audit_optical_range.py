"""真实 GPU 的局部光学动态范围上界诊断；逐帧控制只用于定位瓶颈，禁止进入默认模型。"""
from pathlib import Path
import argparse, itertools, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from fit_particle_optics import measure


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','ironman','kobe'])
    ap.add_argument('--phases',nargs='+',type=float,default=[.24,.36,.52]);a=ap.parse_args()
    out=HERE/'analysis/match-cache/optical-range';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    options=np.array([(d,n,0.) for d,n in itertools.product([-1.5,-.6,-.25,0.,.25,.6,1.5],[-.25,-.08,0.,.08,.25,.6])],np.float32)
    weights=np.array([1.]*3+[.8]*3+[1.5]*3,np.float32)
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        original=r.scatter_tex.read();fields=np.frombuffer(original,np.float32).reshape(41,64,64,3)
        n=64;y,x=np.mgrid[:n,:n].astype(np.float32);x=-.38+(x+.5)/n*1.76;y=-.38+(y+.5)/n*1.76
        x0,y0,x1,y1=c.scene.rect;qx=x0+x*(x1-x0);qy=y0+y*(y1-y0)
        mask=cv2.remap(c.scene.roi.astype(np.float32),qx,qy,cv2.INTER_NEAREST)
        def compact(frame):
            f=frame.astype(np.float32)/255.
            values=[cv2.GaussianBlur(f,(0,0),s) for s in [4.,12.]]
            values.append(cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.))
            return cv2.remap(np.concatenate(values,-1),qx,qy,cv2.INTER_LINEAR)
        tw=300;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*len(a.phases),(th+24)*4),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for col,phase in enumerate(a.phases):
            i,ref=c.ref.nearest(phase);p=float(c.ref.progress[i]);target=compact(ref);reference_measure=measure(ref,c.scene.roi)
            r.scatter_tex.write(original);r.program['scatter_delta']=(0.,0.,0.);before=r.render(p)
            responses=[]
            for op in options:
                r.program['scatter_delta']=tuple(float(v) for v in op);responses.append(compact(r.render(p)))
            r.program['scatter_delta']=(0.,0.,0.)
            residual=np.stack(responses)-target[None]
            cost=(np.sqrt(residual**2+.002**2)*weights).mean(-1)
            cost+=.0003*((options/np.array([1.5,.6,1]))**2).sum(-1)[:,None,None]
            frames=[ref,before];row=dict(phase=p)
            for key,allowed in [('narrow',(abs(options[:,0])<=.25)&(abs(options[:,1])<=.08)),('wide',np.ones(len(options),bool))]:
                indices=np.flatnonzero(allowed);best=indices[np.argmin(cost[allowed],axis=0)]
                delta=options[best]*mask[...,None]
                for k in range(3):delta[...,k]=cv2.GaussianBlur(delta[...,k],(0,0),1.3)
                r.scatter_tex.write(np.ascontiguousarray(fields+delta[None]).tobytes())
                frame=r.render(p);frames.append(frame)
                row[key]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),reference_measure)}
                row[key]['control_abs_mean']=abs(delta[mask>.5]).mean(0).tolist()
                np.save(out/f'{name}-{col}-{key}.npy',delta)
            row['before']={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(before,c.scene.roi),reference_measure)}
            rows.append(row)
            for k,(label,f) in enumerate(zip(['华为原片','当前共同模型','小范围局部诊断','扩大范围局部诊断'],frames)):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+24)+24))
                draw.text((col*tw+3,k*(th+24)+2),f'{label} {p:.3f}',font=font,fill='white')
                Image.fromarray(f).save(out/f'{name}-{col}-{k}.png')
            print(name,round(p,3),{k:round(row[k]['rgb_4'],3) for k in ['before','narrow','wide']},flush=True)
        r.close();sheet.save(out/f'{name}.jpg',quality=95);report[name]=rows
        (out/'report.json').write_text(json.dumps(dict(diagnostic_only=True,scope='局部光学容量，不是共同模型改善；当前运动、寿命和源表面保持不变',scenes=report),ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
