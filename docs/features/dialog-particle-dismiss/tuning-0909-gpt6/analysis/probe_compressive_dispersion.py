"""在已检测到的强压缩区域增加零均值独立速度；不削弱平均剥离和宏观卷动。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from ablate_edge_support import SHADERS,replace
from probe_edge_support import OUT

def configure(gain,protect_edge=False,threshold=1.5):
    c=SHADERS['COMPUTE']
    c=replace(c,'float depth_target=-state[i].pos.z*3.*roll_gain;',f'''
    uint dispersion_seed=transport_hash(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.w)^0x510e527fu);
    float dispersion_angle=transport_unit(dispersion_seed)*6.2831853;
    float dispersion_radius=sqrt(-2.*log(max(transport_unit(dispersion_seed^0x1f83d9abu),.004)));
    float compression_zone=smoothstep(1.5,8.,peel_compression[i]);
    vec2 disperse=vec2(cos(dispersion_angle),sin(dispersion_angle))*dispersion_radius;
    target+=disperse*span*{gain:.8f}*compression_zone*smoothstep(.010,.065,age)*exp(-age/.22);
    float depth_target=-state[i].pos.z*3.*roll_gain;''')
    if protect_edge:c=replace(c,'float compression_zone=smoothstep(1.5,8.,peel_compression[i]);','float compression_zone=smoothstep(1.5,8.,peel_compression[i])*pow(1.-exp(-min(free_edge.x,free_edge.y)/(span*.08)),2.);')
    c=replace(c,'smoothstep(1.5,8.,peel_compression[i])',f'smoothstep({threshold:.8f},8.,peel_compression[i])')
    renderer.COMPUTE=c;renderer.VERTEX=SHADERS['VERTEX'];renderer.FRAGMENT=SHADERS['FRAGMENT']

def main():
    p=argparse.ArgumentParser();p.add_argument('--gain',type=float,default=.4);p.add_argument('--protect-edge',action='store_true');p.add_argument('--threshold',type=float,default=1.5);a=p.parse_args();configure(a.gain,a.protect_edge,a.threshold)
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text('utf-8'))
    inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for q in inputs:
        r=renderer.Renderer('ironman' if q['name']=='ironman' else 'attachment',direction=q['angle'],seed=q['seed'],ctx=ctx)
        frames=[r.render(float(q['phase']+d)) for d in [-.06,0,.06]]
        np.save(OUT/q['name']/f'ablate-dispersion-{a.gain:g}{"-edge" if a.protect_edge else ""}{"-"+str(a.threshold) if a.threshold!=1.5 else ""}.npy',np.array(frames));r.close()
        print(q['name'],a.gain,flush=True)
    ctx.release()
if __name__=='__main__':main()
