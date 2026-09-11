"""同种子、真实屏幕内触点、同尺度的近远对照与跨素材检查。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import Reference,load_meta,label,BG,MUTED
from touch_geometry import distance_cases,distance_view_bounds
from rim_candidate import material_frame as candidate,frozen_shader
OUT=HERE/'analysis/rim-flow'

def main():
    ctx=moderngl.create_standalone_context(require=430);original=frozen_shader()
    shader=candidate(original);renderer.COMPUTE=shader
    for name in ['ironman','attachment','color']:
        meta=load_meta(name);bounds=distance_view_bounds(meta,135);cases=distance_cases(meta,135)
        for t in [.4,.55,.7]:
            w=480;h=round((bounds[3]-bounds[1])*w/meta['frame'][0])
            im=Image.new('RGB',(w*3,h+90),BG);d=ImageDraw.Draw(im)
            for col,c in enumerate(cases):
                r=renderer.Renderer(name,direction=c['angle'],touch_gap=c['gap'],view_bounds=bounds,ctx=ctx)
                label(d,(col*w+10,8),f'{meta["title"]} · {c["label"]} · {t:.2f}',25)
                label(d,(col*w+10,43),f'触点 {c["point"][0]:.0f}, {c["point"][1]:.0f}',20,MUTED)
                tile=Image.fromarray(r.render(t)).resize((w,h));im.paste(tile,(col*w,85));r.close()
            im.save(OUT/f'distance-{name}-{t:.2f}.jpg',quality=95)
        print(name,flush=True)
    for name,angle,seed in [('ironman',122,909602),('ironman-up-reference',90,489),('thanos',130,323),('kobe',128,494),('color',45,909602),('holdout-wide',180,0)]:
        meta=load_meta(name);r=renderer.Renderer(name,direction=angle,seed=seed,ctx=ctx)
        im=Image.new('RGB',(1680,660),BG);d=ImageDraw.Draw(im)
        for col,t in enumerate([.31,.43,.55,.70]):
            label(d,(col*420+10,8),f'{name} {t:.2f}',22)
            x,y,x1,y1=meta['rect'];margin=min(x1-x,y1-y)*.36
            frame=Image.fromarray(r.render(t)).crop((0,max(0,y-margin),meta['frame'][0],min(meta['frame'][1],y1+margin)))
            frame.thumbnail((420,615));im.paste(frame,(col*420+(420-frame.width)//2,40))
        im.save(OUT/f'candidate-{name}.jpg',quality=95);r.close()
    renderer.COMPUTE=original;ctx.release()

if __name__=='__main__':main()
