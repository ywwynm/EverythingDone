"""候选以完整画面和新增真机反例一起复核。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from calibrate_release_distribution import configure,OUT,BASE
from probe_device_filaments import contact
from export_videos import Reference,load_meta
from filament_lifetime import filament_energy
from verify_release_filaments import ridge_contrast
import verify_frame_calibration as vf

def main():
    config=json.loads((OUT/'candidate-config.json').read_text('utf-8'));ctx=moderngl.create_standalone_context(require=430)
    vf.BASE=BASE;Before=vf.frozen_renderer();configure(config)
    reports=[]
    for name,angle,seed in [('ironman',None,None),('thanos',130,323),('kobe',128,494),('color',135,489),('color',45,434),('attachment',65,909602),('holdout-compact-dialog',135,426)]:
        phases=[.33,.40,.56,2/3];old=Before(name,ctx=ctx,direction=angle,seed=seed);r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
        ims=[r.render(t) for t in phases];before=[old.render(t) for t in phases];meta=r.meta
        rows=[('修改前',before),('候选',ims)]
        if meta['reference']:
            ref=Reference(meta);rows=[('华为参考',[ref.at(t) for t in phases])]+rows
        contact(rows,f'candidate-{name}-{angle}',phases)
        if name=='ironman':
            energy=filament_energy(ims[-1],np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB')))
            reports.append(dict(case='old-filament',energy=energy,limit=9531.26796875));assert energy<9531.26796875
        old.close();r.close()
    for j in [1,2,3]:
        name=str(OUT/f'input-{j}');phases=[.24,.36,.50,.66];old=Before(name,ctx=ctx);r=renderer.Renderer(name,ctx=ctx)
        ims=[r.render(t) for t in phases];before=[old.render(t) for t in phases]
        contact([('修改前',before),('候选',ims)],f'candidate-recording-{j}-whole',phases)
        contact([('修改前',before),('候选',ims)],f'candidate-recording-{j}-detail',phases,r.meta['rect'])
        if j in [1,3]:
            score=ridge_contrast(r.render(.24,diagnostic=2),j);reports.append(dict(case=j,ridge=score,limit=2.5));assert score<2.5
        old.close();r.close()
    ctx.release();(OUT/'candidate-review.json').write_text(json.dumps(reports,indent=2),'utf-8');print(reports,flush=True)
if __name__=='__main__':main()
