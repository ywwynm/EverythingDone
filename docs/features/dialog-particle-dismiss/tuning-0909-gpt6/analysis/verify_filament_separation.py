"""以用户标出的完整渲染反例检查孤立尘缕；诊断框不参与模型运算。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import Reference,load_meta
from filament_lifetime import filament_energy
from filament_probe import OUT

def main():
    ctx=moderngl.create_standalone_context(require=430);r=Renderer('ironman',ctx=ctx)
    im=r.render(2/3);r.close();ctx.release()
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
    reference=filament_energy(Reference(load_meta('ironman')).at(2/3),bg);actual=filament_energy(im,bg)
    limit=reference*1.6
    data=dict(model_hash=model_fingerprint(),phase=2/3,reference_energy=reference,actual_energy=actual,limit=limit,
        scope='固定相位、种子与曝光的原始渲染回归；像素框仅用于测量用户已确认的反例，不进入运行端。')
    (OUT/'separation-check.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8');print(data,flush=True)
    assert actual<limit,'用户标出的细缕区域仍有显著超量的孤立粒子覆盖'

if __name__=='__main__':main()
