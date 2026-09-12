"""冻结候选后跨材质和方向检查，不为各素材设置参数。"""
from pathlib import Path
import sys,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_surface_transfer import shaders,original,OUT
import renderer
from export_videos import load_meta,label,focus_bounds,BG,Reference
CASES=[('ironman-up-reference',90,489),('thanos',130,323),('kobe',128,494),('attachment',135,0),
       ('color',45,3),('language',270,1),('holdout-wide',180,2718),('holdout-tall',315,218)]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--mode',default='balanced');mode_arg=parser.parse_args().mode
    ctx=moderngl.create_standalone_context(require=430)
    for scene,direction,seed in CASES:
        m=load_meta(scene);lo,hi=focus_bounds(m);w=320;h=round((hi-lo)*w/m['frame'][0]);row=h+34
        im=Image.new('RGB',(w*4,2*row),BG);d=ImageDraw.Draw(im)
        for j,mode in enumerate(['baseline',mode_arg]):
            for k,v in (original() if mode=='baseline' else shaders(mode)).items():setattr(renderer,k,v)
            r=renderer.Renderer(scene,direction=direction,seed=seed,ctx=ctx,cell_px=1.85 if mode.startswith('soft-') else 1.65 if mode!='baseline' else 2.35)
            for i,t in enumerate([.25,.40,.56,.72]):
                f=r.render(t);label(d,(i*w+6,j*row+3),f'{mode} · {t:.2f}',20)
                im.paste(Image.fromarray(f[lo:hi]).resize((w,h)),(i*w,j*row+34))
            r.close()
        im.save(OUT/f'cross-{mode_arg}-{scene}.jpg',quality=95);print(scene,flush=True)
        if m.get('reference'):
            ref=Reference(m);refim=Image.new('RGB',(w*4,row*3),BG);refdraw=ImageDraw.Draw(refim)
            for i,t in enumerate([.25,.40,.56,.72]):
                label(refdraw,(i*w+6,3),f'华为参考 · {t:.2f}',20)
                refim.paste(Image.fromarray(ref.at(t)[lo:hi]).resize((w,h)),(i*w,34))
            refim.paste(im,(0,row));refim.save(OUT/f'reference-{mode_arg}-{scene}.jpg',quality=96)
    ctx.release()

if __name__=='__main__':main()
