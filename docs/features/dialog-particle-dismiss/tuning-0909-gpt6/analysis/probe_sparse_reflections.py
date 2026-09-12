"""检验更稀疏的微片反光，控制平均亮度，避免重新出现生硬亮带。"""
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
    ctx=moderngl.create_standalone_context(require=430);config=json.loads((OUT/'refined-config.json').read_text('utf-8'))
    phases=[.33,.40,.55,2/3,.78];scores=[]
    for scene in ['ironman','thanos']:
        ref=Reference(load_meta(scene));refs=[ref.at(t) for t in phases];rows=[('华为参考',refs)]
        for tag,gain,power in [('候选原光照',.18,12),('稀疏反光轻',.36,48),('稀疏反光中',.54,64),('稀疏反光强',.80,96)]:
            c=copy.deepcopy(config);c['optical'].update(glint=gain,glint_power=power);candidate(c,'combined')
            r=renderer.Renderer(scene,ctx=ctx);ims=[r.render(t) for t in phases];r.close();rows.append((tag,ims))
            values=[]
            for im,target in zip(ims,refs):
                values.append(dict(structure=float(abs(blur(im,6)-blur(target,6))[150:930,35:685].mean()),
                    contrast=float(abs(contrast(im)-contrast(target))[150:930,35:685].mean())))
            scores.append(dict(scene=scene,tag=tag,values=values));np.save(OUT/f'reflection-{scene}-{power}.npy',ims)
        sheet(rows,f'reflections-{scene}',phases)
    (OUT/'reflections.json').write_text(json.dumps(scores,ensure_ascii=False,indent=2),'utf-8');ctx.release()

if __name__=='__main__':main()
