"""保守收缩共同校准幅度，同时通过三类录像反例及旧反例。"""
from pathlib import Path
import sys,json,copy
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import calibrate_release_distribution as c
from verify_release_filaments import ridge_contrast
from measure_peel_concentration import concentration
from export_videos import Reference,load_meta
from frame_difference import blur

def main():
    selected=json.loads((c.OUT/'candidate-config.json').read_text('utf-8'));ctx=moderngl.create_standalone_context(require=430)
    times=list(np.arange(8,53,4)/60);ref=Reference(load_meta('ironman'));targets=[[blur(ref.at(t),s)[150:930,35:685] for s in [2,6,12]] for t in times]
    bg=np.array(Image.open(c.HERE/'assets/ironman/background.png').convert('RGB'));rows=[]
    for blend in np.linspace(0,1,9):
        cfg={k:(np.asarray(c.INITIAL[k])*(1-blend)+np.asarray(v)*blend).tolist() for k,v in selected.items()};c.configure(cfg)
        r=c.renderer.Renderer('ironman',ctx=ctx);errors=[];energy=0.
        for t,target in zip(times,targets):
            im=r.render(t);errors.append(sum(w*abs(blur(im,s)[150:930,35:685]-v).mean() for s,w,v in zip([2,6,12],[.3,.5,.2],target)))
            if abs(t-2/3)<1e-5:energy=c.filament_energy(im,bg)
        r.close();scores=[]
        for j in [1,3]:
            r=c.renderer.Renderer(str(c.OUT/f'input-{j}'),ctx=ctx);scores.append(ridge_contrast(r.render(.24,diagnostic=2),j));r.close()
        r=c.renderer.Renderer(str(c.OUT/'input-2'),ctx=ctx);density,_=concentration(r,.56,[.05,.40,.66,.99]);r.close()
        row=dict(blend=float(blend),loss=float(np.mean(errors)),ridge=scores,energy=energy,density=density['peak'],config=cfg)
        row['passed']=max(scores)<2.5 and energy<9531.26796875 and density['peak']<1.9
        rows.append(row);print({k:v for k,v in row.items() if k!='config'},flush=True)
    valid=[r for r in rows if r['passed']];assert valid;best=min(valid,key=lambda r:r['loss'])
    (c.OUT/'constraint-selection.json').write_text(json.dumps(rows,indent=2),'utf-8')
    (c.OUT/'candidate-config.json').write_text(json.dumps(best['config'],indent=2),'utf-8');ctx.release()
    print('选择',best['blend'],best['loss'],flush=True)
if __name__=='__main__':main()
