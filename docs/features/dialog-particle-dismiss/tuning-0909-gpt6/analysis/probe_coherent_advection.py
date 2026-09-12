"""只保留共同观测流场，隔离后加材料输运项；实验不会写正式文件。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from ablate_edge_support import SHADERS,replace
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def configure(gain=1.,floor=.0):
    c=SHADERS['COMPUTE']
    c=replace(c,'float depth_target=-state[i].pos.z*3.*roll_gain;',f'target=target*.000001+sample0.xy*(guide_gain/.9)*{gain:.8f}+flow*.018*smoothstep(.01,.09,age);\n    float depth_target=-state[i].pos.z*3.*roll_gain;')
    c=replace(c,'float deficit=.16-axial;',f'float deficit={floor:.8f}-axial;')
    c=replace(c,'sqrt(deficit*deficit+.0064)','sqrt(deficit*deficit+.000001)')
    renderer.COMPUTE=c;renderer.VERTEX=SHADERS['VERTEX'];renderer.FRAGMENT=SHADERS['FRAGMENT']

def main():
    p=argparse.ArgumentParser();p.add_argument('--gain',type=float,default=1);p.add_argument('--floor',type=float,default=0);a=p.parse_args();configure(a.gain,a.floor)
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text());inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for q in inputs:
        r=renderer.Renderer('ironman' if q['name']=='ironman' else 'attachment',direction=q['angle'],seed=q['seed'],ctx=ctx)
        frames=[r.render(float(q['phase']+d)) for d in [-.06,0,.06]];np.save(OUT/q['name']/f'ablate-coherent-{a.gain:g}-{a.floor:g}.npy',np.array(frames));r.close();print(q['name'],a.gain,a.floor,flush=True)
    ctx.release()
if __name__=='__main__':main()
