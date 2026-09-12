"""跨素材、方向和种子复现离群细缕，分离原表面、运动层和固定材料来源。"""
from pathlib import Path
import sys,json,shutil,argparse
import numpy as np,moderngl,cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
OUT=HERE/'analysis/recorded-filament-trace'
BASE=HERE/'archive/before-filament-layer-diagnosis'
CASES=[('attachment',270,909602),('attachment',90,909602),('attachment',135,909602),('attachment',0,42),
       ('color',270,909602),('color',135,42),('language',225,426),('language',180,241),
       ('ironman',122,909602),('thanos',122,42),('attachment',270,426),('attachment',270,241)]
def archive():
    if not (BASE/'identity.json').exists():
        (BASE/'shared').mkdir(parents=True,exist_ok=True)
        for name in ['renderer.py','unified_model.py','android_shaders.py','export_videos.py','touch_geometry.py']:shutil.copy2(HERE/name,BASE/name)
        for p in model.SHARED.iterdir():
            if p.is_file():shutil.copy2(p,BASE/'shared'/p.name)
        (BASE/'identity.json').write_text(json.dumps({'model_hash':model.model_fingerprint()},indent=2),'utf-8')
def montage(rows,path,rect):
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17);w=420;h=round((rect[3]-rect[1])/(rect[2]-rect[0])*w)
    sheet=Image.new('RGB',(len(rows[0][1])*w,len(rows)*(h+28)),'#101620');d=ImageDraw.Draw(sheet)
    for j,(title,ims) in enumerate(rows):
        for k,(t,im) in enumerate(ims):
            x=k*w;y=j*(h+28);sheet.paste(Image.fromarray(im).crop(rect).resize((w,h)),(x,y+28));d.text((x+4,y+3),f'{title} · {t:.2f}',font=font,fill='white')
    sheet.save(path,quality=95)
def main():
    p=argparse.ArgumentParser();p.add_argument('--limit',type=int,default=len(CASES));a=p.parse_args();archive()
    ctx=moderngl.create_standalone_context(require=430);phases=[.20,.30,.40,.50,.60]
    for j,(name,angle,seed) in enumerate(CASES[:a.limit]):
        r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
        dest=OUT/f'case-{j:02d}-{name}-{angle}-{seed}';dest.mkdir(exist_ok=True)
        rows=[]
        for diag,title in [(0,'完整'),(1,'蓝：原表面；橙：运动'),(2,'只有运动层'),(3,'只有原表面')]:
            frames=[(t,r.render(t,diagnostic=diag)) for t in phases];rows.append((title,frames))
            np.save(dest/f'layer-{diag}.npy',np.asarray([im for t,im in frames]))
        np.save(dest/'materials.npy',r.base)
        (dest/'input.json').write_text(json.dumps(dict(name=name,direction=angle,seed=seed,meta=r.meta,nx=r.nx,ny=r.ny,phases=phases,model_hash=model.model_fingerprint()),ensure_ascii=False,indent=2),'utf-8')
        x,y,x1,y1=r.meta['rect'];rect=[max(0,x-25),max(0,y-50),min(r.w,x1+25),min(r.h,y1+50)]
        montage(rows,dest/'layers.jpg',rect);r.close();print(dest.name,flush=True)
    ctx.release()
if __name__=='__main__':main()
