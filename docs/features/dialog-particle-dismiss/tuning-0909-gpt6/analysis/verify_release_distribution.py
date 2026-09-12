"""当前正式资源的新旧细缕、完整画面与距离验收。"""
from pathlib import Path
import sys,json
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,moderngl,numpy as np
from PIL import Image
from unified_model import model_fingerprint
import verify_filament_continuity as check
from reproduce_device_filaments import OUT,BASE
from probe_device_filaments import contact
from verify_release_filaments import ridge_contrast
from measure_peel_concentration import concentration
from filament_lifetime import filament_energy

def verify_old_tail():
    frames=np.load(OUT/'production-ironman.npy',mmap_mode='r');refs=np.load(OUT/'reference.npy',mmap_mode='r')
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
    energy=filament_energy(frames[40],bg)
    result=dict(model_hash=model_fingerprint(),phase=40/60,reference_energy=filament_energy(refs[40],bg),
        actual_energy=energy,limit=9531.26796875,
        scope='固定相位、种子与曝光的原始渲染回归；像素框仅测量用户已确认的反例，不进入运行端。')
    assert energy<result['limit'],result
    (OUT/'separation-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    return result

def main():
    check.OUT=OUT;check.FROZEN=BASE
    check.sheet=lambda rows,name,phases:contact(rows,name,phases)
    check.main()
    metrics=json.loads((OUT/'metrics.json').read_text('utf-8'))
    held=metrics['appearance']['summary']['held_phases']
    assert held['mae_6']['after']<held['mae_6']['before'],'完整画面结构误差未改善'
    verify_old_tail()
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for j in [1,3]:
        r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx);score=ridge_contrast(r.render(.24,diagnostic=2),j);r.close()
        rows.append(dict(case=j,phase=.24,ridge=score,limit=2.5));assert score<2.5,(j,score)
    r=renderer.Renderer(str(OUT/'input-2'),ctx=ctx);density,_=concentration(r,.56,[.05,.40,.66,.99]);r.close()
    rows.append(dict(case=2,phase=.56,concentration=density,limit=1.90));assert density['peak']<1.90,density
    ctx.release();(OUT/'release-regression.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),cases=rows,passed=True),indent=2),'utf-8')
    print('早期连续细线与后段密度峰回归通过',rows,flush=True)
if __name__=='__main__':main()
