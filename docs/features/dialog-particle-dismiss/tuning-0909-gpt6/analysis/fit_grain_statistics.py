"""按多个空间频段联合校准共同粒度和光学量；避免低频 RGB 偏向粗大片。

只求五个共有数，作用强度沿现有布局差异连续变化；不接受素材专用参数。
"""
from pathlib import Path
import argparse,json,sys,time
import cv2,numpy as np,moderngl
from scipy.optimize import lsq_linear
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_optics import OpticsRenderer,OpticsRules
from particle_cohorts import load_cohorts
from particle_coverage import load_coverage
from fit_particle_optics import common,measure,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison


BASE=np.array([1.,1.,1.,0.,0.])
EPS=np.array([.05,.10,.05,.012,.06])
LOW=np.array([.55,.60,.45,-.02,-.70]);HIGH=np.array([1.15,2.6,1.20,.14,.20])


def apply(r,params):
    size,count,opacity,neutral,chroma=params;g=r.layout_change
    r.apply_optics(OpticsRules(coefficients=tuple([neutral*g]*9+[chroma*g]*3),size=1+g*(size-1),count=1+g*(count-1),opacity=1+g*(opacity-1)))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--review');ap.add_argument('--initial');ap.add_argument('--label',default='multiband');a=ap.parse_args()
    out=HERE/'analysis/match-cache/grain-statistics'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw.update(coverage=load_coverage(HERE/'particle-coverage-rules.json'),cohorts=load_cohorts(HERE/'particle-cohort-weights.json'))
    initial=BASE if not a.initial else np.array(json.loads(Path(a.initial).read_text('utf-8'))['parameters'])
    candidates={'before':initial}
    if a.review:candidates['candidate']=np.array(json.loads(Path(a.review).read_text('utf-8'))['parameters'])
    else:
        for k in range(5):
            for sign in [-1,1]:
                q=initial.copy();q[k]+=EPS[k]*sign;candidates[f'{k}/{sign}']=q
    A=[];b=[];weight=[];meta=[];report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);start=time.monotonic()
    for name in NAMES:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
        yy,xx=np.mgrid[:40,:40].astype('float32');xx=x+(-.20+(xx+.5)/40*1.4)*w;yy=y+(-.20+(yy+.5)/40*1.4)*h
        mask=cv2.remap(c.scene.roi.astype('float32'),xx,yy,cv2.INTER_NEAREST)>0
        def compact(frame):
            f=frame.astype('float32')/255.
            values=[cv2.GaussianBlur(f,(0,0),s) for s in [4.,12.]]
            for s in [.4,.8,1.6,3.2]:values.append(cv2.GaussianBlur(np.abs(f-cv2.GaussianBlur(f,(0,0),s)),(0,0),8.))
            return [cv2.remap(v,xx,yy,cv2.INTER_LINEAR)[mask].ravel() for v in values]
        r=OpticsRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);rows=[]
        box=(max(0,x-30),max(0,y-30),min(m['frame'][0],x1+30),min(m['frame'][1],y1+30));tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*5,(th+24)*3),(14,20,30));d=ImageDraw.Draw(sheet)
        for j,p in enumerate([.18,.29,.41,.58,.77] if a.review else [.16,.32,.48,.64,.80]):
            idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);target=compact(ref);results={};frames={}
            for label,q in candidates.items():apply(r,q);f=r.render(p);results[label]=compact(f);frames[label]=f
            if a.review:
                for k,(title,f) in enumerate([('华为原片',ref),('本轮采用',frames['before']),('多频段实验',frames['candidate'])]):
                    sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                target_full=measure(ref,c.scene.roi)
                rows.append({label:{key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target_full)} for label,f in frames.items()})
            else:
                # 以该频段的参考能量归一化，不能让较小幅度的细粒频段在目标中消失。
                for band in range(6):
                    derivative=np.stack([(results[f'{k}/1'][band]-results[f'{k}/-1'][band])/(2*EPS[k]) for k in range(5)],-1)
                    residual=target[band]-results['before'][band]
                    norm=1 if band<2 else 1.0/max(.008,float(np.mean(target[band])))
                    norm*=1.0 if band==0 else .25 if band==1 else .10
                    A.append(derivative);b.append(residual);weight.append(np.full(len(residual),norm/np.sqrt(len(residual)),np.float32))
        r.close();print(name,round(time.monotonic()-start,1),flush=True)
        if a.review:
            report[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
            print({k:{f:round(np.mean([q[k][f] for q in rows]),4) for f in ['rgb_4','grain']} for k in candidates},flush=True)
            (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()
    if not a.review:
        A=np.concatenate(A);b=np.concatenate(b);weight=np.concatenate(weight);delta=np.zeros(5)
        for _ in range(6):
            irls=weight/np.sqrt(np.maximum(np.abs(b-A@delta),.005))
            fit=lsq_linear(np.vstack([A*irls[:,None],np.diag([.8,.15,.3,2.,.5])]),np.concatenate([b*irls,np.zeros(5)]),bounds=(LOW-initial,HIGH-initial),tol=1e-7)
            delta=fit.x
        result=dict(parameters=(initial+delta).tolist(),description='共同尺寸、数量、透过率、无色反射和色度；以现有布局差异连续插值',baseline=initial.tolist())
        (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(result,flush=True)


if __name__=='__main__':main()
