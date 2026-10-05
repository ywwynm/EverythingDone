"""在真实 GPU 上测量共同材料响应，校准 12 个全参考共用系数。"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import cv2,numpy as np,moderngl
from scipy.optimize import lsq_linear
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_optics import OpticsRenderer,OpticsRules
from conditional_material import ConditionalRules
from surface_transfer import load_transfer
from shared_transport import load_transport
from learned_release import load_weights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

NAMES=['ironman','thanos','kobe','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in [1,4,5,6,7]]
def common():
    return dict(weights=load_weights(HERE/'conditional-release-weights.json'),transport_weights=load_transport(HERE/'conditional-transport-weights.json'),
        rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules']),transfer=load_transfer(HERE/'surface-transfer-rules.json'))

def measure(image,mask):
    f=image.astype('float32')
    return [cv2.GaussianBlur(f,(0,0),s)[mask] for s in [1.,4.,12.]]+[cv2.GaussianBlur(np.abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.)[mask]]

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='common-response');ap.add_argument('--review');ap.add_argument('--scenes',nargs='+',default=NAMES);ap.add_argument('--coverage');ap.add_argument('--cohorts');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-optics'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();start=time.monotonic()
    if a.coverage:
        from particle_coverage import load_coverage
        kw['coverage']=load_coverage(Path(a.coverage))
    if a.cohorts:
        from particle_cohorts import load_cohorts
        kw['cohorts']=load_cohorts(Path(a.cohorts))
    if not a.review:
        designs=[];residuals=[];weights=[];info=[]
        for name in a.scenes:
            c=Comparison(name);r=OpticsRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
            x,y,x1,y1=c.scene.meta['rect'];span=min(x1-x,y1-y);yy,xx=np.mgrid[:56,:56].astype('float32');xx=x+(-.25+(xx+.5)/56*1.5)*(x1-x);yy=y+(-.25+(yy+.5)/56*1.5)*(y1-y)
            mask=cv2.remap(c.scene.roi.astype('float32'),xx,yy,cv2.INTER_NEAREST)>0
            def compact(frame):return cv2.remap(cv2.GaussianBlur(frame.astype('float32')/255,(0,0),4.),xx,yy,cv2.INTER_LINEAR)[mask].reshape(-1)
            for p in [.13,.23,.33,.43,.53,.63,.73,.83]:
                idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);r.apply_optics(OpticsRules());before=compact(r.render(p));target=compact(ref)
                A=[]
                for k in range(12):
                    cs=np.zeros(12);delta=.04 if k<9 else -.15;cs[k]=delta
                    r.apply_optics(OpticsRules(coefficients=tuple(cs)));A.append((compact(r.render(p))-before)/delta)
                A=np.stack(A,axis=-1);b=target-before
                designs.append(A);residuals.append(b);weights.append(np.full(len(b),1/max(.02,float(np.mean(np.abs(b))))/np.sqrt(len(b)),np.float32));info.append(dict(name=name,phase=p))
            r.close();print('测量响应',name,round(time.monotonic()-start,1),flush=True)
        A=np.concatenate(designs);b=np.concatenate(residuals);w=np.concatenate(weights)
        np.savez_compressed(out/'response.npz',A=A,b=b,w=w)
        lower=np.array([-.025]*9+[-.75]*3);upper=np.array([.16]*9+[.15]*3)
        coef=np.zeros(12);history=[]
        for iteration in range(6):
            irls=w/np.sqrt(np.maximum(np.abs(b-A@coef),.012))
            matrix=A*irls[:,None];rhs=b*irls
            fit=lsq_linear(np.vstack([matrix,np.eye(12)*.6]),np.concatenate([rhs,np.zeros(12)]),bounds=(lower,upper),tol=1e-7)
            coef=fit.x;history.append(dict(iteration=iteration,coefficients=coef.tolist(),weighted_mae=float(np.sum(np.abs(b-A@coef)*w)/w.sum())))
        result=dict(optics=dataclasses.asdict(OpticsRules(coefficients=tuple(coef))),history=history,scenes=a.scenes,samples=info)
        (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print('共同系数',coef.tolist(),flush=True)
    else:
        config=json.loads(Path(a.review).read_text('utf-8'));optics=OpticsRules(**config['optics']);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
        for name in a.scenes:
            c=Comparison(name);r=OpticsRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
            m=c.scene.meta;x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.18);box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
            tw=320;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(5*tw,3*(th+24)),(14,20,30));d=ImageDraw.Draw(sheet);rows=[]
            for j,p in enumerate([.18,.29,.41,.58,.77]):
                idx,ref=c.ref.nearest(p);p=float(c.ref.progress[idx]);r.apply_optics(OpticsRules());before=r.render(p);r.apply_optics(optics);after=r.render(p)
                for k,(title,frame) in enumerate([('华为原片',ref),('当前默认',before),('共同光学实验',after)]):
                    sheet.paste(Image.fromarray(frame).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+24)+24));d.text((j*tw+3,k*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
                target=measure(ref,c.scene.roi);es=[]
                for frame in [before,after]:es.append({key:float(np.abs(m-t).mean()) for key,m,t in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)})
                rows.append(dict(phase=p,before=es[0],after=es[1]))
            r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
            print(name,{stage:{key:round(np.mean([q[stage][key] for q in rows]),4) for key in ['rgb_4','grain']} for stage in ['before','after']},flush=True)
            (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
