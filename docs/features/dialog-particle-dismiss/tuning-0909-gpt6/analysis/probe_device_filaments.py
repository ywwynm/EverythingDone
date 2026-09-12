"""真机新增反例的独立变量消融；仅在本进程覆盖着色器。"""
from pathlib import Path
import sys,json,copy,argparse
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
OUT=HERE/'analysis/device-filament-origins';OUT.mkdir(exist_ok=True)
ORIGINAL=(renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT)
GUIDANCE=renderer.guidance

def configure(kind):
    renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT=ORIGINAL
    renderer.guidance=GUIDANCE
    if kind=='no-peel':renderer.COMPUTE=renderer.COMPUTE.replace('peel*span*1.2615089','peel*span*.0000001')
    if kind=='smooth-flow':renderer.guidance=lambda:gaussian_filter(GUIDANCE(),(0,2,2,0))
    if kind=='no-flow':renderer.COMPUTE=renderer.COMPUTE.replace('sample0.xy*(guide_gain/.9)','sample0.xy*(guide_gain/.9)*.0000001')
    if kind=='no-handoff':
        renderer.VERTEX=renderer.VERTEX.replace('surface_out=mix(time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));','surface_out=time<=m.src.z?1.:0.;')
    if kind=='no-peel-motion':renderer.COMPUTE=renderer.COMPUTE.replace('peel*span*1.2615089','peel*span*.0000001')
    if kind in ['peel-dispersion','peel-drag','peel-half','peel-varied','peel-exponential']:
        renderer.COMPUTE=renderer.COMPUTE.replace('vec2 peel=-m.physical.xy;', '''uint peel_seed=floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au;
    vec2 peel_random=vec2(transport_unit(peel_seed),transport_unit(peel_seed^0x3c6ef372u));
    vec2 peel=-m.physical.xy;''')
        if kind=='peel-half':renderer.COMPUTE=renderer.COMPUTE.replace('peel*span*1.2615089','peel*span*.6307544')
        if kind=='peel-dispersion':
            renderer.COMPUTE=renderer.COMPUTE.replace('(.88+.24*m.random.w)', '(.15+1.70*peel_random.x)')
        if kind=='peel-drag':
            renderer.COMPUTE=renderer.COMPUTE.replace('(.88+.24*m.random.w)*exp(-age/.16)', '(.20+1.60*peel_random.x)*exp(-age/(.08+.16*peel_random.y))')
        if kind=='peel-varied':
            renderer.COMPUTE=renderer.COMPUTE.replace('(.88+.24*m.random.w)', '(.05+1.90*peel_random.x)')
            renderer.COMPUTE=renderer.COMPUTE.replace('target+=peel*span*', 'peel+=vec2(-peel.y,peel.x)*.90*(peel_random.y-.5);\n    target+=peel*span*')
        if kind=='peel-exponential':renderer.COMPUTE=renderer.COMPUTE.replace('(.88+.24*m.random.w)', '(-log(max(peel_random.x,.015)))')

def contact(rows,name,phases,crop=None):
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    w,h=(360,640) if crop is None else (440,440)
    canvas=Image.new('RGB',(len(phases)*w,len(rows)*(h+30)),'#0e141e');d=ImageDraw.Draw(canvas)
    for y,(label,frames) in enumerate(rows):
        for x,(t,frame) in enumerate(zip(phases,frames)):
            im=Image.fromarray(frame)
            if crop:im=im.crop(crop)
            canvas.paste(im.resize((w,h)),(x*w,y*(h+30)+30))
            d.text((x*w+3,y*(h+30)+3),f'{label} · {t:.3f}',font=font,fill='white')
    canvas.save(OUT/f'{name}.jpg',quality=96)

def main():
    p=argparse.ArgumentParser();p.add_argument('--scene',default='attachment');p.add_argument('--seed',type=int,default=909602);p.add_argument('--angle',type=float,default=135);a=p.parse_args()
    phases=[.30,.44,.60];ctx=moderngl.create_standalone_context(require=430);rows=[]
    for kind in ['current','no-peel','smooth-flow','no-flow','no-handoff','particles-only']:
        configure(kind);r=renderer.Renderer(a.scene,ctx=ctx,seed=a.seed,direction=a.angle)
        images=[r.render(t,diagnostic=2 if kind=='particles-only' else 0) for t in phases]
        meta=r.meta;r.close();np.save(OUT/f'{a.scene}-{kind}.npy',images);rows.append((kind,images));print(kind,flush=True)
    for start in range(1,len(rows),2):contact(rows[:1]+rows[start:start+2],f'{a.scene}-ablation-{start}',phases,meta['rect'])
    contact(rows[:1],f'{a.scene}-whole',phases)
    ctx.release()

if __name__=='__main__':main()
