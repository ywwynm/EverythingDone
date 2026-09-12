"""空间剥离候选的连续验收；钢铁侠始终直接使用原参考。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_projected_peel import configure,ProjectedRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def main():
    configure();ProjectedRenderer.projection=1.;ProjectedRenderer.restore_strength=True
    ProjectedRenderer.unilateral=True;ProjectedRenderer.free_surface=True
    ctx=moderngl.create_standalone_context(require=430)
    cases=[('down','attachment',270,909602),('up','attachment',90,909602),('right','attachment',0,42),('ironman','ironman',122,909602)]
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for key,scene,angle,seed in cases:
        r=ProjectedRenderer(scene,direction=angle,seed=seed,ctx=ctx)
        frames=[];states=[]
        for i in range(61):
            frames.append(r.render(i/60))
            if i in range(12,49,3):states.append(np.frombuffer(r.state.read(),'float32').reshape(-1,8).copy())
        frames=np.array(frames);np.save(OUT/f'continuous-{key}.npy',frames)
        np.savez_compressed(OUT/f'continuous-{key}-states.npz',states=states,base=r.base)
        ref=Reference(load_meta(scene)) if scene=='ironman' else None
        for group,indices in enumerate([range(12,25,3),range(27,40,3),range(42,55,3)]):
            im=Image.new('RGB',(360*5,672*(2 if ref else 1)),'#101620');d=ImageDraw.Draw(im)
            for col,i in enumerate(indices):
                rows=[('华为原始参考',ref.at(i/60)),('空间约束候选',frames[i])] if ref else [('空间约束候选',frames[i])]
                for row,(title,f) in enumerate(rows):
                    h=640;pixels=Image.fromarray(f)
                    if scene!='ironman':pixels=pixels.crop((40,450,680,1090))
                    im.paste(pixels.resize((360,h)),(col*360,row*672+32))
                    d.text((col*360+3,row*672+3),f'{title} · {i/60:.2f}',font=font,fill='white')
            im.save(OUT/f'continuous-{key}-{group}.png')
        r.close();print(key,'61 帧完成',flush=True)
    ctx.release()

if __name__=='__main__':main()
