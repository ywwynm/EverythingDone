"""修正共同速度场的小幅偏差；将孤立尘缕覆盖作为独立退化约束。"""
from pathlib import Path
import sys,json,copy,time
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from filament_lifetime import candidate,filament_energy
from filament_probe import OUT
from frame_difference import blur
from optimize_optical_statistics import contrast
from export_videos import Reference,load_meta

def main():
    config=json.loads((OUT/'refined-config.json').read_text('utf-8'));initial=np.array(config['flow_grid'])
    times=sorted(set((np.arange(8,53,4)/60).tolist()+[2/3]));ref=Reference(load_meta('ironman'));refs=[ref.at(t) for t in times]
    targets=[[blur(im,s)[150:930,35:685] for s in [2,6,12]]+[contrast(im)[150:930,35:685]] for im in refs]
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'));reference_energy=filament_energy(ref.at(2/3),bg)
    ctx=moderngl.create_standalone_context(require=430);history=[];started=time.monotonic()
    def evaluate(values):
        c=copy.deepcopy(config);c['flow_grid']=values.tolist();candidate(c,'combined');r=renderer.Renderer('ironman',ctx=ctx)
        errors=[];contrasts=[];energy=0
        for t,targets_at_t in zip(times,targets):
            im=r.render(t);errors.append(sum(w*abs(blur(im,s)[150:930,35:685]-target).mean() for s,w,target in zip([2,6,12],[.25,.5,.25],targets_at_t[:3])))
            contrasts.append(abs(contrast(im)[150:930,35:685]-targets_at_t[3]).mean())
            if abs(t-2/3)<1e-5:energy=filament_energy(im,bg)
        r.close();loss=float(np.mean(errors)+.12*np.mean(contrasts)+.6*max(energy/reference_energy-1.4,0))
        history.append(dict(loss=loss,appearance=float(np.mean(errors)),energy=energy,values=values.tolist()))
        return loss
    values=initial.copy();best=evaluate(values);print('起点',best,flush=True)
    for step in [.12,.06]:
        for index in range(50):
            selected=values.copy();lowest=best
            for sign in [-1,1]:
                test=values.copy();test[index]=np.clip(test[index]+sign*step,initial[index]-.18,initial[index]+.18)
                loss=evaluate(test)
                if loss<lowest-.0005:lowest=loss;selected=test
            if lowest<best:
                values=selected;best=lowest;c=copy.deepcopy(config);c['flow_grid']=values.tolist()
                (OUT/'flow-config.json').write_text(json.dumps(c,indent=2),'utf-8')
                print('改善',len(history),index,round(best,4),'耗时',round(time.monotonic()-started),flush=True)
            if index%5==0:(OUT/'filament-flow-search.json').write_text(json.dumps(history,indent=2),'utf-8')
    (OUT/'filament-flow-search.json').write_text(json.dumps(history,indent=2),'utf-8');ctx.release();print('完成',best,flush=True)

if __name__=='__main__':main()
