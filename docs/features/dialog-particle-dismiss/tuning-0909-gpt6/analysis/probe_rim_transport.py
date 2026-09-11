"""隔离叠加输运对弧边流场采样的影响，不修改发布模型。"""
from pathlib import Path
import sys
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import Reference,load_meta,label,BG
from rim_candidate import frozen_shader
OUT=HERE/'analysis/rim-flow'

def sheet(name,title,shader,ctx):
    renderer.COMPUTE=shader;r=renderer.Renderer('ironman',ctx=ctx)
    ref=Reference(load_meta('ironman'));times=[.40,.48,.55,.63]
    approved=np.load(HERE/'archive/observed-approved/ironman.npy',mmap_mode='r')
    im=Image.new('RGB',(1680,1140),BG);d=ImageDraw.Draw(im)
    for col,t in enumerate(times):
        for row,(text,frame) in enumerate([('华为参考',ref.at(t)),('认可控制组',approved[round(t*120)]),(title,r.render(t))]):
            label(d,(col*420+10,row*380+4),f'{text} · {t:.2f}',22)
            im.paste(Image.fromarray(frame).crop((82,450,418,728)).resize((420,348)),(col*420,row*380+32))
    im.save(OUT/f'transport-{name}.jpg',quality=95);r.close();print(name,flush=True)

def main():
    original=frozen_shader();ctx=moderngl.create_standalone_context(require=430)
    reduce=original.replace('*(.12+.50*reach)*carried*wind_gain;','*(.12+.50*reach)*carried*wind_gain*missing;').replace('float deficit=.16-axial;','float deficit=mix(.012,.16,missing)-axial;')
    modes=[]
    for gain in [.6,1.1,1.6]:
        emission='''vec2 peel=-m.physical.xy;
    peel-=wind*min(dot(peel,wind),0.);
    target+=peel*span*GAIN*exp(-age/.16)*smoothstep(.003,.028,age);
    float depth_target='''.replace('GAIN',str(gain))
        modes.append((f'normal-{gain}',f'解除时法向分离 {gain}',original.replace('float depth_target=',emission)))
    for name,title,shader in modes:sheet(name,title,shader,ctx)
    renderer.COMPUTE=original;ctx.release()

if __name__=='__main__':main()
