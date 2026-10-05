"""用真实完整渲染校准 16 个共同传播基系数，包含释放造成的后续运动变化。

不训练逐素材控制图；训练和复查时刻分开，并把零改动保留在候选中。
"""
from pathlib import Path
import argparse,copy,json,sys,time
import cv2,numpy as np,moderngl
from scipy.optimize import lsq_linear
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,load_coverage
from learned_release import load_weights
from fit_particle_optics import common,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',default='spatial-propagation');ap.add_argument('--initial');ap.add_argument('--epsilon',type=float,default=.012);ap.add_argument('--limit',type=float,default=.035);ap.add_argument('--resume',action='store_true');a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-coverage'/a.label;out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);kw=common();kw['coverage']=load_coverage(HERE/'particle-coverage-rules.json')
    path=Path(a.initial) if a.initial else HERE/'conditional-release-weights.json'
    initial=json.loads(path.read_text('utf-8'));base=load_weights(path)
    coef=np.asarray(initial.get('spatial_coefficients',[0.]*16),np.float64)
    cases=[];phases=[.16,.32,.48,.64,.80]
    def features(frame,mask):
        rgb=frame.astype('float32')/255.
        return np.concatenate([cv2.GaussianBlur(rgb,(0,0),s)[::5,::5][mask].ravel()*w for s,w in [(4.,1.),(12.,.6)]])
    for name in NAMES:
        c=Comparison(name);mask=c.scene.roi[::5,::5];refs=[c.ref.nearest(p) for p in phases]
        times=[float(c.ref.progress[i]) for i,_ in refs];target=np.concatenate([features(f,mask) for _,f in refs]);cases.append((name,c,mask,times,target))
    target=np.concatenate([c[-1] for c in cases]);started=time.monotonic()
    def render(delta):
        weights=copy.deepcopy(base);weights.spatial_coefficients=(coef+delta).tolist();images=[]
        for name,c,mask,times,_ in cases:
            r=CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**dict(kw,weights=weights))
            images.append(np.concatenate([features(r.render(p),mask) for p in times]));r.close()
        return np.concatenate(images)
    origin=render(np.zeros_like(coef));lengths=[len(c[-1]) for c in cases];starts=np.cumsum([0]+lengths)
    baseline={cases[i][0]:float(np.abs(origin[starts[i]:starts[i+1]]-target[starts[i]:starts[i+1]]).mean()) for i in range(len(cases))}
    norm=np.concatenate([np.full(lengths[i],1/(lengths[i]*max(baseline[cases[i][0]],.003)),np.float32) for i in range(len(cases))])
    print('共同空间传播初始',baseline,'秒',round(time.monotonic()-started,1),flush=True)
    jac=[]
    for k in range(len(coef)):
        saved=out/f'derivative-{k:02d}.npy'
        if a.resume and saved.exists():column=np.load(saved)
        else:
            d=np.zeros_like(coef);d[k]=a.epsilon;positive=render(d);negative=render(-d);column=(positive-negative)/(2*a.epsilon);np.save(saved,column)
        jac.append(column);print('真实导数',k+1,len(coef),'秒',round(time.monotonic()-started,1),flush=True)
    jac=np.stack(jac,-1).astype('float64');residual=(origin-target).astype('float64');delta=np.zeros_like(coef)
    for _ in range(8):
        error=residual+jac@delta;weight=np.sqrt(norm/np.sqrt(error*error+.008**2))
        matrix=np.vstack([jac*weight[:,None],np.eye(len(coef))*.8]);rhs=np.concatenate([-residual*weight,np.zeros(len(coef))])
        fit=lsq_linear(matrix,rhs,bounds=(-a.limit,a.limit),tol=1e-7);delta=fit.x
    trials=[]
    for gain in [0.,.35,.7,1.]:
        prediction=origin if gain==0 else render(delta*gain)
        scores={cases[i][0]:float(np.abs(prediction[starts[i]:starts[i+1]]-target[starts[i]:starts[i+1]]).mean())/baseline[cases[i][0]] for i in range(len(cases))}
        score=float(np.mean(list(scores.values()))+.35*max(scores.values()));row=dict(gain=gain,score=score,scores=scores,delta=(delta*gain).tolist());trials.append(row)
        print('完整响应检查',row,flush=True)
        result=dict(initial);result.update(spatial_coefficients=(coef+delta*gain).tolist(),spatial_limit=.06,spatial_calibration='全颗粒真实 GPU 共用方向／起点空间基有限差分',spatial_review=row)
        (out/f'gain-{gain:g}.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
        (out/'trials.json').write_text(json.dumps(dict(baseline=baseline,trials=trials),ensure_ascii=False,indent=2),'utf-8')
    best=min(trials,key=lambda q:q['score']);result=json.loads((out/f'gain-{best["gain"]:g}.json').read_text('utf-8'))
    (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8');ctx.release()


if __name__=='__main__':main()
