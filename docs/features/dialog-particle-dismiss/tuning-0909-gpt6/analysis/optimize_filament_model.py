"""联合约束完整画面、颗粒对比度及已复现的孤立分支，校准共同参数。"""
from pathlib import Path
import sys,json,copy,time
import numpy as np,moderngl
from scipy.optimize import minimize
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from filament_lifetime import candidate,filament_energy
from filament_probe import OUT
from frame_difference import blur
from optimize_optical_statistics import contrast
from export_videos import Reference,load_meta

def main():
    source=json.loads((HERE/'analysis/common-shape-calibration.json').read_text('utf-8'))['config']
    ref=Reference(load_meta('ironman'));times=sorted(set((np.arange(8,53,4)/60).tolist()+[2/3]))
    refs=[ref.at(t) for t in times];targets=[[blur(im,s)[150:930,35:685] for s in [2,6,12]]+[contrast(im)[150:930,35:685]] for im in refs]
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'));energy_reference=filament_energy(ref.at(2/3),bg)
    ctx=moderngl.create_standalone_context(require=430);history=[];best=[1e9,None];started=time.monotonic()
    def evaluate(x):
        config=copy.deepcopy(source)
        config['cohort_model']=dict(early_gain=float(x[0]),late_gain=float(x[1]),birth_start=.12,birth_span=float(x[2]),speed_spread=float(x[3]))
        config['motion']['guide']=float(x[4]);config['motion']['peel']=float(x[5]);config['optical']['base_light']=float(x[6])
        candidate(config,'combined');r=renderer.Renderer('ironman',ctx=ctx);es=[];cs=[];energy=0.
        for t,refs in zip(times,targets):
            im=r.render(t);es.append(sum(w*abs(blur(im,s)[150:930,35:685]-target).mean() for s,w,target in zip([2,6,12],[.25,.5,.25],refs[:3])))
            cs.append(abs(contrast(im)[150:930,35:685]-refs[3]).mean())
            if abs(t-2/3)<1e-5:energy=filament_energy(im,bg)
        r.close();excess=max(energy/energy_reference-1.4,0)
        loss=float(np.mean(es)+.12*np.mean(cs)+.4*excess)
        row=dict(loss=loss,appearance=float(np.mean(es)),contrast=float(np.mean(cs)),energy=energy,config=config);history.append(row)
        if loss<best[0]:
            best[:]=[loss,config];(OUT/'optimized-config.json').write_text(json.dumps(config,indent=2),'utf-8')
            print('改善',len(history),round(loss,4),'结构',round(row['appearance'],4),'细缕',round(energy),np.round(x,3).tolist(),flush=True)
        if len(history)%10==0:(OUT/'parameter-search.json').write_text(json.dumps(history,indent=2),'utf-8')
        return loss
    start=[.70,.94,.32,.5,.84,1.44,.34]
    evaluate(start)
    minimize(evaluate,start,method='Powell',bounds=[(.56,.78),(.88,1.04),(.20,.46),(.25,.75),(.76,1.00),(1.05,1.95),(.25,.64)],options=dict(maxiter=3,maxfev=175,xtol=.02,ftol=.001))
    (OUT/'parameter-search.json').write_text(json.dumps(history,indent=2),'utf-8');print('完成',round(time.monotonic()-started,1),best[0],flush=True);ctx.release()

if __name__=='__main__':main()
