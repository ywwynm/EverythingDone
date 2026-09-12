"""检验确定性网格随机函数的视觉变化，运动模型保持不变。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from reproduce_device_filaments import OUT
from verify_release_filaments import ridge_contrast
from filament_lifetime import filament_energy
from frame_difference import PHASES,metrics
from probe_device_filaments import contact

OLD='''float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
vec2 jitter(vec2 g){return (vec2(hash(g),hash(g+vec2(17.1,5.9)))-.5)*.60;}'''
NEW='''uint grid_hash(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float grid_unit(uint x){return float(grid_hash(x)>>8u)*(1./16777216.);}
vec2 jitter(vec2 g){uint s=uint(g.x)*0x9e3779b9u^uint(g.y)*0x85ebca6bu;
    return (vec2(grid_unit(s),grid_unit(s^0x68bc21ebu))-.5)*.60;}'''

def main():
    assert OLD in renderer.VERTEX
    renderer.VERTEX=renderer.VERTEX.replace(OLD,NEW)
    ctx=moderngl.create_standalone_context(require=430)
    r=renderer.Renderer('ironman',ctx=ctx);frames=np.stack([r.render(t) for t in PHASES]);r.close()
    base=np.load(OUT/'production-ironman.npy',mmap_mode='r');refs=np.load(OUT/'reference.npy',mmap_mode='r')
    yy,xx=np.mgrid[:1280,:720];mask=(xx>=35)&(xx<685)&(yy>=150)&(yy<930)
    values={name:metrics(f,refs,mask) for name,f in [('before',base),('integer',frames)]}
    result={name:{metric:float(np.mean([v[metric] for v in rows[8:53]])) for metric in ['mae_0','mae_2','mae_6','mae_12']} for name,rows in values.items()}
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
    result['ironman_old_tail_energy']=filament_energy(frames[40],bg)
    result['early_filaments']=[]
    for j in [1,3]:
        r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx);score=ridge_contrast(r.render(.24,diagnostic=2),j)
        result['early_filaments'].append(dict(case=j,score=score));r.close()
    phases=[.33,.40,.48,.55,2/3,.78];indices=[round(p*60) for p in phases]
    contact([('华为参考',[refs[i] for i in indices]),('当前候选',[base[i] for i in indices]),('整数网格',[frames[i] for i in indices])],'integer-grid',phases)
    np.save(OUT/'integer-grid-ironman.npy',frames)
    (OUT/'integer-grid-probe.json').write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result),flush=True);ctx.release()

if __name__=='__main__':main()
