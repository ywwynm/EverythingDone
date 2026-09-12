"""只优化共同物理/材质系数；训练相位与逐帧复核相位分离。"""
from pathlib import Path
import sys,json,time
import numpy as np,moderngl
from scipy.optimize import minimize
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_frame_difference import configure
from frame_difference import OUT,blur
TIMES=np.arange(12,49,4)/60
BOUNDS=[(0,.6),(.75,1.50),(.10,1.15),(.20,2.80),(.65,1.08),(.04,.40),(.008,.070),(-.025,.07)]
START=[0,1,1,2.2,.70,.36,.012,0]

def configuration(x):
    return dict(release=float(x[0]),motion=dict(guide=float(x[1]),ambient=float(x[2]),peel=float(x[3])),life=float(x[4]),optical=dict(old_size=float(x[5]),diffuse=float(x[6])),surface_delay=float(x[7]))

def main():
    ctx=moderngl.create_standalone_context(require=430);raw=np.load(OUT/'reference.npy',mmap_mode='r')
    cut=lambda a:a[220:850,80:635]
    refs=[[cut(blur(raw[round(t*60)],s)) for s in [2,6,12]] for t in TIMES]
    history=[];best=[1e9,None];start=time.perf_counter()
    def objective(x):
        config=configuration(x);configure(config);r=renderer.Renderer('ironman',ctx=ctx)
        loss=0.;phase=[]
        for t,targets in zip(TIMES,refs):
            a=r.render(t);e=[float(abs(cut(blur(a,s))-b).mean()) for s,b in zip([2,6,12],targets)]
            phase.append(e);loss+=.25*e[0]+.50*e[1]+.25*e[2]
        r.close();loss/=len(TIMES)
        history.append(dict(config=config,loss=loss,phase_errors=phase))
        (OUT/'parameter-search.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
        if loss<best[0]:
            best[:]=[loss,config];(OUT/'best-config.json').write_text(json.dumps(config,indent=2),'utf-8')
            print('改进',len(history),round(loss,4),np.round(x,4).tolist(),round(time.perf_counter()-start,1),flush=True)
        elif len(history)%10==0:print('检查',len(history),'当前最好',round(best[0],4),flush=True)
        return loss
    initial=objective(np.array(START))
    result=minimize(objective,START,method='Powell',bounds=BOUNDS,options=dict(maxiter=3,maxfev=145,xtol=.055,ftol=.003))
    print('结束',initial,best,flush=True);ctx.release()

if __name__=='__main__':main()
