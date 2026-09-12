"""用录像面板、背景与相近释放布局检查早期细缕。"""
from pathlib import Path
import json,sys
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_device_filaments import OUT,configure,contact

def main():
    ctx=moderngl.create_standalone_context(require=430)
    for data in json.loads((OUT/'reproduction-inputs.json').read_text('utf-8')):
        j=data['video'];phases=[data['phase'],data['phase']+.12,data['phase']+.26];rows=[]
        for kind in ['current','peel-half','peel-dispersion','peel-drag']:
            configure(kind);r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx)
            images=[r.render(t,diagnostic=2 if kind=='particles-only' else 0) for t in phases]
            np.save(OUT/f'replay-{j}-{kind}.npy',images)
            r.close();rows.append((kind,images))
        for start in range(1,len(rows),2):contact(rows[:1]+rows[start:start+2],f'replay-{j}-dispersion-{start}',phases,data['rect'])
        contact(rows[:1],f'replay-{j}-whole',phases)
        print(j,flush=True)
    ctx.release()
if __name__=='__main__':main()
