"""测量实际 GPU 上九个共同年龄／材料响应，不改变位置或逐素材输入。"""
from pathlib import Path
import json,sys,time
import numpy as np,cv2,moderngl
from scipy.optimize import lsq_linear
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_age_density import AgeDensityRenderer
from particle_coverage import load_coverage
from fit_particle_optics import common,measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison


def main():
    out=HERE/'analysis/match-cache/particle-coverage/age-density';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json');started=time.monotonic()
    designs=[];residuals=[];importance=[]
    for name in NAMES:
        c=Comparison(name);r=AgeDensityRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.meta['rect'];yy,xx=np.mgrid[:64,:64].astype('float32');xx=x+(-.25+(xx+.5)/64*1.5)*(x1-x);yy=y+(-.25+(yy+.5)/64*1.5)*(y1-y)
        mask=cv2.remap(c.scene.roi.astype('float32'),xx,yy,cv2.INTER_NEAREST)>0
        def compact(frame):
            f=frame.astype('float32')/255
            rgb=cv2.GaussianBlur(f,(0,0),4.)
            grain=cv2.GaussianBlur(np.abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.)*.65
            return np.concatenate([cv2.remap(z,xx,yy,cv2.INTER_LINEAR)[mask].reshape(-1) for z in [rgb,grain]])
        for p in [.13,.23,.33,.43,.53,.63,.73,.83]:
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);r.apply_age_density(np.zeros(9));baseline=compact(r.render(p));target=compact(ref);responses=[]
            for j in range(9):
                coeff=np.zeros(9);coeff[j]=.12;r.apply_age_density(coeff);responses.append((compact(r.render(p))-baseline)/.12)
            A=np.stack(responses,-1);b=target-baseline;designs.append(A);residuals.append(b);importance.append(np.full(len(b),1./max(.014,float(np.abs(b).mean()))/np.sqrt(len(b))))
        r.close();print('年龄覆盖导数',name,round(time.monotonic()-started,1),flush=True)
    A=np.concatenate(designs);b=np.concatenate(residuals);w=np.concatenate(importance);np.savez_compressed(out/'response.npz',A=A,b=b,w=w)
    coef=np.zeros(9);history=[]
    for _ in range(5):
        robust=w/np.sqrt(np.maximum(np.abs(b-A@coef),.01))
        fit=lsq_linear(np.vstack([A*robust[:,None],np.eye(9)*.4]),np.concatenate([b*robust,np.zeros(9)]),bounds=(-.55,.55))
        coef=fit.x;history.append(coef.tolist())
    (out/'candidate.json').write_text(json.dumps(dict(coefficients=coef.tolist(),history=history,scope='九个年龄与源亮度共同系数；无位置、序列标签或参考帧输入'),ensure_ascii=False,indent=2),'utf-8')
    print('候选',coef.tolist(),flush=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in NAMES:
        c=Comparison(name);r=AgeDensityRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.meta['rect'];pad=round(min(x1-x,y1-y)*.18);box=(max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(5*tw,3*(th+24)),(14,20,30));d=ImageDraw.Draw(sheet);rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);r.apply_age_density(np.zeros(9));before=r.render(p);r.apply_age_density(coef);after=r.render(p);entry=dict(phase=p);target=measure(ref,c.scene.roi)
            for k,(key,f) in enumerate([('华为原片',ref),('before',before),('candidate',after)]):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{key} {p:.3f}',font=font,fill='white')
                if k:entry[key]={key:float(np.abs(m-t).mean()) for key,m,t in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(entry)
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        print(name,{v:tuple(round(np.mean([q[v][k] for q in rows]),3) for k in ['rgb_4','grain']) for v in ['before','candidate']},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
