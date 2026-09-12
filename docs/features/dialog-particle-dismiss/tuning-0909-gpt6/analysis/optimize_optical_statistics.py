"""以局部颗粒对比度约束明暗，避免低频误差偏好平坦灰片。"""
from pathlib import Path
import sys,json,time
import numpy as np,moderngl
from scipy.optimize import minimize
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_frame_difference import configure
from frame_difference import OUT,blur

def contrast(im):
    lum=im.astype('float32')@np.array([.2126,.7152,.0722],dtype='float32')
    high=lum-blur(lum,1.25)
    return np.sqrt(np.maximum(blur(high*high,4),0))

def main():
    ctx=moderngl.create_standalone_context(require=430);raw=np.load(OUT/'reference.npy',mmap_mode='r')
    base=json.loads((OUT/'flow-shape-config.json').read_text('utf-8'));times=np.arange(8,53,4)/60;cut=lambda a:a[220:850,80:635]
    targets=[[cut(blur(raw[round(t*60)],s)) for s in [2,6,12]]+[cut(contrast(raw[round(t*60)]))] for t in times]
    history=[];best=[1e9,None];start=time.perf_counter()
    def evaluate(x):
        config=dict(base,cell=float(x[0]),life=float(x[4]),optical=dict(base['optical'],glint=float(x[1]),base_light=float(x[2]),old_size=float(x[3]),young_size=float(x[5])))
        configure(config);r=renderer.Renderer('ironman',ctx=ctx);es=[];vars=[]
        for t,refs in zip(times,targets):
            im=r.render(t);e=sum(w*abs(cut(blur(im,s))-ref).mean() for s,w,ref in zip([2,6,12],[.25,.5,.25],refs[:3]))
            v=abs(cut(contrast(im))-refs[3]).mean();es.append(e);vars.append(v)
        r.close();loss=float(np.mean(es)+.25*np.mean(vars));history.append(dict(config=config,loss=loss,appearance=float(np.mean(es)),contrast=float(np.mean(vars))))
        if loss<best[0]:
            best[:]=[loss,config];(OUT/'optical-config.json').write_text(json.dumps(config,indent=2),'utf-8')
            print('改善',len(history),round(loss,4),round(np.mean(es),4),round(np.mean(vars),4),np.round(x,3).tolist(),flush=True)
        if len(history)%10==0:(OUT/'optical-search.json').write_text(json.dumps(history,indent=2),'utf-8')
        return loss
    initial=evaluate([1.85,.22,.70,.33335,.91575,1.])
    result=minimize(evaluate,[1.85,.22,.70,.33335,.91575,1.],method='Powell',bounds=[(1.6,2.65),(.15,1.1),(.25,.90),(.10,.40),(.75,1.08),(.55,1.1)],options=dict(maxiter=3,maxfev=150,xtol=.035,ftol=.002))
    (OUT/'optical-search.json').write_text(json.dumps(history,indent=2),'utf-8');print('完成',initial,best[0],flush=True);ctx.release()

if __name__=='__main__':main()
