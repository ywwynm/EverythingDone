"""保持微片平均覆盖，检验宽尺度分布能否恢复参考中的颗粒层次。"""
from pathlib import Path
import sys,json,copy
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from filament_lifetime import candidate
from filament_probe import OUT,sheet
from frame_difference import blur
from optimize_optical_statistics import contrast
from export_videos import Reference,load_meta

def main():
    ctx=moderngl.create_standalone_context(require=430);config=json.loads((OUT/'flow-config.json').read_text('utf-8'))
    phases=[.33,.40,.55,2/3,.78];scores=[]
    for scene in ['ironman','thanos']:
        ref=Reference(load_meta(scene));refs=[ref.at(t) for t in phases];rows=[('华为参考',refs)]
        for spread in [None,.28,.40,.55]:
            c=copy.deepcopy(config)
            if spread:c['optical']['size_spread']=spread
            candidate(c,'combined');r=renderer.Renderer(scene,ctx=ctx);ims=[r.render(t) for t in phases];r.close()
            rows.append((f'粒径分布 {spread}',ims));values=[]
            for im,target in zip(ims,refs):
                values.append(dict(structure=float(abs(blur(im,6)-blur(target,6))[150:930,35:685].mean()),
                    contrast=float(abs(contrast(im)-contrast(target))[150:930,35:685].mean())))
            scores.append(dict(scene=scene,spread=spread,values=values));np.save(OUT/f'scale-{scene}-{spread}.npy',ims)
        sheet(rows,f'scale-{scene}',phases)
    (OUT/'scales.json').write_text(json.dumps(scores,ensure_ascii=False,indent=2),'utf-8');ctx.release()

if __name__=='__main__':main()
