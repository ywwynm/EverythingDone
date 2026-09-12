"""在共同归一化场上调整有限、平滑的形状系数，不保存逐帧遮罩。"""
from pathlib import Path
import sys,json,time,argparse
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_frame_difference import configure
from frame_difference import OUT,blur

def main():
    p=argparse.ArgumentParser();p.add_argument('--refine',action='store_true');a=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430);raw=np.load(OUT/'reference.npy',mmap_mode='r')
    config=json.loads((OUT/('combined-config.json' if a.refine else 'best-config.json')).read_text('utf-8'));config['release']=0.
    prefix='shape-refined' if a.refine else 'shape'
    times=np.arange(8,53,4)/60
    cut=lambda a:a[220:850,80:635]
    targets=[[cut(blur(raw[round(t*60)],s)) for s in [2,6,12]] for t in times]
    history=[];start=time.perf_counter();coeff=np.array(config.get('release_grid',[0.]*25))
    def evaluate(values):
        conf=dict(config,release_grid=values.tolist());configure(conf);r=renderer.Renderer('ironman',ctx=ctx)
        es=[]
        for t,refs in zip(times,targets):
            im=r.render(t);es.append(sum(w*abs(cut(blur(im,s))-ref).mean() for s,w,ref in zip([2,6,12],[.25,.5,.25],refs)))
        r.close();loss=float(np.mean(es));penalty=.40*float(np.mean(values**2))
        history.append(dict(loss=loss,coefficients=values.tolist()))
        return loss+penalty
    best=evaluate(coeff);print('起点',best,flush=True)
    for step in ([.024,.012,.006] if a.refine else [.045,.024,.012]):
        for i in range(25):
            chosen=coeff.copy();value=best
            for sign in [-1,1]:
                trial=coeff.copy();trial[i]=np.clip(trial[i]+sign*step,-.12,.12)
                loss=evaluate(trial)
                if loss<value-.0004:value=loss;chosen=trial
            if value<best:
                best=value;coeff=chosen
                (OUT/(prefix+'-config.json')).write_text(json.dumps(dict(config,release_grid=coeff.tolist()),indent=2),'utf-8')
                print('改善',len(history),i,round(best,4),round(time.perf_counter()-start,1),flush=True)
            if i%5==0:(OUT/(prefix+'-search.json')).write_text(json.dumps(history,indent=2),'utf-8')
        print('步幅完成',step,round(best,4),flush=True)
    (OUT/(prefix+'-search.json')).write_text(json.dumps(history,indent=2),'utf-8');ctx.release()

if __name__=='__main__':main()
