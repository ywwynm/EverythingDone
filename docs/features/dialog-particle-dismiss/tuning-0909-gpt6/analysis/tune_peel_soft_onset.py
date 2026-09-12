"""保留弱压缩，仅对会压成细线的强压缩逐渐介入；候选不改正式文件。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import Reference,load_meta
from frame_difference import metrics
from probe_filament_layers import OUT,BASE

ctx=moderngl.create_standalone_context(require=430)
original=renderer.COMPUTE
needle='peel/=1.+2.*peel_compression[i]*.16*1.2141309*(.10+1.80*peel_response);'
assert original.count(needle)==1
fixture=json.loads((Path(__file__).with_name('filament-cohort.json')).read_text('utf-8'))
axis=np.array(fixture['transverse_axis']);ref=Reference(load_meta('ironman'))
refs=np.stack([ref.at(i/60) for i in range(61)]);old=np.load(BASE/'ironman.npy',mmap_mode='r')[::2]
mask=np.zeros((1280,720),bool);mask[150:930,35:685]=True
baseline=metrics(old,refs,mask);before=float(np.mean([baseline[i]['mae_6'] for i in range(8,53)]));rows=[]
for onset in [0.,.25,.5,1.]:
    renderer.COMPUTE=original if onset==0 else original.replace(needle,
        f'float peel_pressure=2.*peel_compression[i]*.16*1.2141309*(.10+1.80*peel_response); peel/=1.+peel_pressure*peel_pressure/(peel_pressure+{onset:.6f});')
    r=renderer.Renderer('attachment',ctx=ctx,direction=270,seed=909602);r.seek(.3)
    chosen=np.isin(r.base[:,3].astype('int64'),fixture['ids']);state=np.frombuffer(r.state.read(),'float32').reshape(-1,8)
    width=float(np.diff(np.percentile(state[chosen,:2]@axis,[10,90]))[0]);r.close()
    r=renderer.Renderer('ironman',ctx=ctx);images=np.stack([r.render(i/60) for i in range(61)]);r.close()
    scored=metrics(images,refs,mask);after=float(np.mean([scored[i]['mae_6'] for i in range(8,53)]))
    row=dict(onset=onset,width80=width,structure_before=before,structure_after=after,ratio=after/before)
    rows.append(row);print(json.dumps(row),flush=True)
    np.save(OUT/f'onset-{onset:g}-ironman.npy',images)
(OUT/'soft-onset-candidates.json').write_text(json.dumps(rows,indent=2),'utf-8');ctx.release()
