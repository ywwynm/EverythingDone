"""用真实 GPU 的完整响应校准七个共用时钟结点，包括释放变化引起的运动变化。

有限差分不固定颗粒层；各素材仅提供原片观测与几何输入，不保存逐素材规则。
"""
from pathlib import Path
import argparse,copy,json,sys,time
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,load_coverage
from learned_release import load_weights
from fit_particle_optics import common,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='rendered-clock');ap.add_argument('--epsilon',type=float,default=.016);ap.add_argument('--limit',type=float,default=.018);a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-coverage'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json')
    initial=json.loads((HERE/'conditional-release-weights.json').read_text('utf-8'));base=load_weights(HERE/'conditional-release-weights.json');offset=np.asarray(base.clock_offsets,np.float64)
    cases=[];phases=[.12,.28,.44,.60,.76]
    def features(frame,mask):
        rgb=frame.astype('float32')/255.
        return np.concatenate([cv2.GaussianBlur(rgb,(0,0),s)[::5,::5][mask].ravel()*w for s,w in [(4.,1.),(12.,.6)]])
    for name in NAMES:
        c=Comparison(name);mask=c.scene.roi[::5,::5];refs=[c.ref.nearest(p) for p in phases]
        times=[float(c.ref.progress[i]) for i,_ in refs];target=np.concatenate([features(f,mask) for _,f in refs]);cases.append((name,c,mask,times,target))
    target=np.concatenate([c[-1] for c in cases]);scales=None;history=[];started=time.monotonic()
    def render(delta):
        weights=copy.deepcopy(base);weights.clock_offsets=(offset+delta).tolist();images=[]
        for name,c,mask,times,_ in cases:
            r=CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**dict(kw,weights=weights))
            images.append(np.concatenate([features(r.render(p),mask) for p in times]));r.close()
        return np.concatenate(images)
    origin=render(np.zeros_like(offset));lengths=[len(c[-1]) for c in cases];starts=np.cumsum([0]+lengths)
    baseline={cases[i][0]:float(np.abs(origin[starts[i]:starts[i+1]]-target[starts[i]:starts[i+1]]).mean()) for i in range(len(cases))}
    norm=np.concatenate([np.full(lengths[i],1/(lengths[i]*max(baseline[cases[i][0]],.003)),np.float32) for i in range(len(cases))])
    print('真实时钟初始',baseline,'秒',round(time.monotonic()-started,1),flush=True)
    jac=[]
    for k in range(len(offset)):
        d=np.zeros_like(offset);d[k]=a.epsilon;positive=render(d);negative=render(-d);jac.append((positive-negative)/(2*a.epsilon))
        print('真实导数',k+1,len(offset),'秒',round(time.monotonic()-started,1),flush=True)
    jac=np.stack(jac,-1).astype('float64');residual=(origin-target).astype('float64');delta=np.zeros_like(offset)
    for _ in range(8):
        error=residual+jac@delta;weight=norm/np.sqrt(error*error+.008**2)
        lhs=jac.T@(jac*weight[:,None])+np.eye(len(offset))*.1;rhs=-(jac.T@(weight*residual))
        delta=np.clip(np.linalg.solve(lhs,rhs),-a.limit,a.limit)
    trials=[]
    for gain in [0.,.35,.7,1.]:
        prediction=origin if gain==0 else render(delta*gain)
        scores={cases[i][0]:float(np.abs(prediction[starts[i]:starts[i+1]]-target[starts[i]:starts[i+1]]).mean())/baseline[cases[i][0]] for i in range(len(cases))}
        score=float(np.mean(list(scores.values()))+.35*max(scores.values()));row=dict(gain=gain,score=score,scores=scores,delta=(delta*gain).tolist());trials.append(row)
        print('真实检查',row,flush=True)
        (out/'trials.json').write_text(json.dumps(dict(baseline=baseline,trials=trials),ensure_ascii=False,indent=2),'utf-8')
    best=min(trials,key=lambda q:q['score']);result=dict(initial);result.update(clock_offsets=(offset+best['delta']).tolist(),clock_calibration='全颗粒真实 GPU 七结点有限差分',clock_review=best)
    (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8');ctx.release()


if __name__=='__main__':main()
