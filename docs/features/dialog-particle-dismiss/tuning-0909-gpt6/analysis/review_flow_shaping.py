"""同进度新旧画面对照与长宽比例检查。"""
from pathlib import Path
import sys,argparse,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import sampled,focus_bounds
OUT=HERE/'analysis/flow-shaping'

def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['ironman','attachment','color','thanos']);a=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for name in a.scenes:
        r=Renderer(name,ctx=ctx);old_path=HERE/f'archive/before-flow-shaping/{name}.npy'
        old=np.load(old_path,mmap_mode='r') if old_path.exists() else None
        lo,hi=focus_bounds(r.meta,True);w=330;h=round((hi-lo)*w/r.w)
        im=Image.new('RGB',(w*5,h*(2 if old is not None else 1)+80),'#0e141e');d=ImageDraw.Draw(im)
        d.text((10,6),name+'  上：上一版；下：局部流动 / 几何尺度 / 距离',font=font,fill='white')
        for j,t in enumerate([.35,.48,.61,.74,.87]):
            d.text((j*w+10,34),f't={t:.2f}',font=font,fill='white')
            arr=r.render(t)
            if old is not None:im.paste(Image.fromarray(sampled(old,t)[lo:hi]).resize((w,h)),(j*w,70))
            im.paste(Image.fromarray(arr[lo:hi]).resize((w,h)),(j*w,70+(h if old is not None else 0)))
        im.save(OUT/f'{name}-temporal.jpg',quality=96);r.close();print(name,flush=True)
    ctx.release()

if __name__=='__main__':main()
