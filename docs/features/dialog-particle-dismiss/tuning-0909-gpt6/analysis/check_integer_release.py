"""确定性网格下复核剥离分布，保持均值、检查已知细缕和完整运动区。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_integer_grid import OLD,NEW
from verify_release_filaments import ridge_contrast
from measure_peel_concentration import concentration
from filament_lifetime import filament_energy
from reproduce_device_filaments import OUT
from frame_difference import blur

ctx=moderngl.create_standalone_context(require=430);vertex=renderer.VERTEX;compute=renderer.COMPUTE
refs=np.load(OUT/'reference.npy',mmap_mode='r');bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
rows=[]
for low in [.15,.10,.05,0.]:
    renderer.VERTEX=vertex.replace(OLD,NEW)
    renderer.COMPUTE=compute.replace('(.15+1.70*peel_response)',f'({low:.7f}+{2*(1-low):.7f}*peel_response)')
    scores=[]
    for j in [1,3]:
        r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx);scores.append(ridge_contrast(r.render(.24,diagnostic=2),j));r.close()
    r=renderer.Renderer(str(OUT/'input-2'),ctx=ctx);density,_=concentration(r,.56,[.05,.40,.66,.99]);r.close()
    r=renderer.Renderer('ironman',ctx=ctx);loss=[]
    for i in [20,24,29,33,40,47]:
        im=r.render(i/60);loss.append(float(abs(blur(im,6)-blur(refs[i],6))[150:930,35:685].mean()))
        if i==40:energy=filament_energy(im,bg)
    r.close();row=dict(min_gain=low,ridge=scores,concentration=density['peak'],energy=energy,structure=float(np.mean(loss)))
    row['passed']=max(scores)<2.5 and density['peak']<1.9 and energy<9531.26796875
    rows.append(row);print(json.dumps(row),flush=True)
ctx.release();(OUT/'integer-release-check.json').write_text(json.dumps(rows,indent=2),'utf-8')
