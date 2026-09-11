"""保留本轮新参考的旧模型对照，并制作同进度接触表。"""
from pathlib import Path
import sys,json
import moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import Reference,focus_bounds,sampled
from evaluate_targeted_release import frozen
OUT=HERE/'analysis/targeted-release'

def main():
    ctx=moderngl.create_standalone_context(require=430);old,_=frozen()
    directory=HERE/'archive/before-targeted-release';name='ironman-up-reference'
    if not (directory/f'{name}.npy').exists():
        r=old(name,ctx=ctx);a=np.lib.format.open_memmap(directory/f'{name}.npy',mode='w+',dtype=np.uint8,shape=(121,r.h,r.w,3))
        for j in range(121):a[j]=r.render(j/120)
        a.flush();r.close()
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for name in ['ironman-up-reference','ironman','thanos','kobe','attachment','color','language']:
        r=Renderer(name,ctx=ctx);ref=Reference(r.meta);has_ref=ref.frames is not None
        previous=np.load(directory/f'{name}.npy',mmap_mode='r');lo,hi=focus_bounds(r.meta,True)
        w=330;h=round((hi-lo)*w/r.w);rows=3 if has_ref else 2
        im=Image.new('RGB',(w*5,(h+36)*rows),'#0e141e');d=ImageDraw.Draw(im)
        for col,t in enumerate([.16,.32,.48,.64,.80]):
            panels=[(sampled(previous,t),'上一版'),(r.render(t),'本轮')]
            if has_ref:panels.insert(0,(ref.at(t),'华为参考'))
            for row,(arr,title) in enumerate(panels):
                d.text((col*w+8,row*(h+36)+6),f'{title} · {t:.2f}',font=font,fill='white')
                im.paste(Image.fromarray(arr[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(col*w,row*(h+36)+34))
        im.save(OUT/f'{name}-temporal.jpg',quality=95);r.close();print('画面对照',name,flush=True)
    ctx.release()

if __name__=='__main__':main()
