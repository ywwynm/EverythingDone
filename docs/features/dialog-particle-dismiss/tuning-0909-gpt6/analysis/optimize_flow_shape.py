"""在既有共同输运场上校准宽尺度速度，所有粒子继续连续积分。"""
from pathlib import Path
import sys,json,time,argparse
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_frame_difference import configure
from frame_difference import OUT,blur

def main():
    p=argparse.ArgumentParser();p.add_argument('--temporal',action='store_true');args=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430);raw=np.load(OUT/'reference.npy',mmap_mode='r')
    config=json.loads((OUT/('flow-shape-config.json' if args.temporal else 'shape-config.json')).read_text('utf-8'))
    field_key='flow_slope' if args.temporal else 'flow_grid';prefix='flow-temporal' if args.temporal else 'flow-shape'
    times=np.arange(8,53,4)/60;cut=lambda a:a[220:850,80:635]
    targets=[[cut(blur(raw[round(t*60)],s)) for s in [2,6,12]] for t in times]
    history=[];start=time.perf_counter();coeff=np.zeros(50)
    def evaluate(values):
        conf=dict(config);conf[field_key]=values.tolist();configure(conf);r=renderer.Renderer('ironman',ctx=ctx)
        es=[]
        for t,refs in zip(times,targets):
            im=r.render(t);es.append(sum(w*abs(cut(blur(im,s))-ref).mean() for s,w,ref in zip([2,6,12],[.25,.5,.25],refs)))
        r.close();loss=float(np.mean(es));penalty=.04*float(np.mean(values**2))
        history.append(dict(loss=loss,coefficients=values.tolist()));return loss+penalty
    best=evaluate(coeff);print('起点',best,flush=True)
    for step in ([.24,.12] if args.temporal else [.16,.08,.04]):
        for i in range(50):
            chosen=coeff.copy();value=best
            for sign in [-1,1]:
                trial=coeff.copy();trial[i]=np.clip(trial[i]+sign*step,-.48,.48)
                loss=evaluate(trial)
                if loss<value-.0004:value=loss;chosen=trial
            if value<best:
                best=value;coeff=chosen
                conf=dict(config);conf[field_key]=coeff.tolist()
                (OUT/(prefix+'-config.json')).write_text(json.dumps(conf,indent=2),'utf-8')
                print('改善',len(history),i,round(best,4),round(time.perf_counter()-start,1),flush=True)
            if i%10==0:(OUT/(prefix+'-search.json')).write_text(json.dumps(history,indent=2),'utf-8')
        print('步幅完成',step,round(best,4),flush=True)
    (OUT/(prefix+'-search.json')).write_text(json.dumps(history,indent=2),'utf-8');ctx.release()

if __name__=='__main__':main()
