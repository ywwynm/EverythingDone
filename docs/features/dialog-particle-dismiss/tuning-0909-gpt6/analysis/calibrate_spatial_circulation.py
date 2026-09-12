"""以华为原片校准低频共同旋流，不以旧版画面为拟合目标。实验结果不写正式资源。"""
from pathlib import Path
import sys,json,time,argparse
import numpy as np,moderngl,cv2
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from probe_projected_peel import configure,ProjectedRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

FLOW=model.guidance().copy()
RULES=model.RULES.copy()
MODE='pic'

def bases():
    tt,yy,xx=np.mgrid[:48,:64,:64];u=-.45+(xx+.5)/64*1.9;v=-.45+(yy+.5)/64*1.9;t=(tt+.5)/48
    envelope=model.smooth((t-.10)/.24)*(1-model.smooth((t-.73)/.20))
    result=[]
    for cy in np.linspace(-.02,1.02,5):
        for cx in np.linspace(-.02,1.02,5):
            dx=u-cx;dy=v-cy;sigma=.24;weight=np.exp(-(dx*dx+dy*dy)/sigma**2)*envelope
            # 从流函数求旋度；参数改变卷动，不另造内部速度汇。
            result.append(np.stack([dy,-dx],-1)*weight[...,None]*(2/sigma))
    return np.array(result,dtype='float32')

BASIS=bases()

def apply(coefficients):
    model.RULES.update(RULES)
    configure(flip=MODE=='flip');ProjectedRenderer.projection=1.;ProjectedRenderer.restore_strength=MODE!='flip';ProjectedRenderer.flip=MODE=='flip'
    ProjectedRenderer.unilateral=True;ProjectedRenderer.free_surface=True;ProjectedRenderer.surface_threshold=.025
    flow=FLOW+np.tensordot(np.asarray(coefficients,'float32'),BASIS,axes=1)
    renderer.guidance=lambda:flow
    return flow

def main():
    global MODE
    p=argparse.ArgumentParser();p.add_argument('--step',type=float,default=.18);p.add_argument('--resume',action='store_true');p.add_argument('--mode',default='pic',choices=['pic','flip']);a=p.parse_args();MODE=a.mode
    prefix='spatial-circulation' if MODE=='pic' else 'pressure-circulation'
    ctx=moderngl.create_standalone_context(require=430);ref=Reference(load_meta('ironman'))
    times=[.2,.3,.4,.5,.6,.7,.8]
    crop=np.s_[150:930,35:685]
    targets=[[cv2.GaussianBlur(ref.at(t).astype('float32'),(0,0),s)[crop] for s in [3,7,14]] for t in times]
    filename=OUT/f'{prefix}-config.json'
    coefficients=np.array(json.loads(filename.read_text())['coefficients']) if a.resume and filename.exists() else np.zeros(25)
    history=[];started=time.monotonic()
    def evaluate(x):
        apply(x);r=ProjectedRenderer('ironman',ctx=ctx);errors=[]
        for t,target in zip(times,targets):
            frame=r.render(t).astype('float32')
            errors.append(sum(w*np.abs(cv2.GaussianBlur(frame,(0,0),s)[crop]-v).mean() for s,w,v in zip([3,7,14],[.2,.55,.25],target)))
        r.close();loss=float(np.mean(errors)+.025*np.mean(x*x));history.append(dict(loss=loss,coefficients=x.tolist()))
        return loss
    current=evaluate(coefficients);initial=current
    for i in range(len(coefficients)):
        chosen=coefficients.copy();lowest=current
        for sign in [-1,1]:
            x=coefficients.copy();x[i]+=sign*a.step;loss=evaluate(x)
            if loss<lowest-.001:lowest=loss;chosen=x
        coefficients=chosen;current=lowest
        filename.write_text(json.dumps(dict(initial=initial,loss=current,coefficients=coefficients.tolist(),training_times=times),indent=2),'utf-8')
        print(i,round(current,5),round(time.monotonic()-started,1),'秒',flush=True)
    (OUT/f'{prefix}-search.json').write_text(json.dumps(history,indent=2),'utf-8')
    apply(coefficients);r=ProjectedRenderer('ironman',ctx=ctx);frames=np.stack([r.render(i/60) for i in range(61)]);r.close();np.save(OUT/f'{prefix}-ironman.npy',frames)
    ctx.release()

if __name__=='__main__':main()
