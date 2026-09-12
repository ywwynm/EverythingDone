"""导出三个真机输入反例的修复前后视频，大文件仍放在统一视频目录。"""
from pathlib import Path
import sys,json,shutil,hashlib
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import OUT,PRE,POST,BG,MUTED,encode,label,footer,code_hash
from reproduce_device_filaments import OUT as ANALYSIS
from probe_filament_layers import BASE
import verify_frame_calibration as vf

def main():
    ctx=moderngl.create_standalone_context(require=430);vf.BASE=BASE;Before=vf.frozen_renderer();items=[]
    for j in [1,2,3]:
        directory=ANALYSIS/f'input-{j}';meta=json.loads((directory/'scene.json').read_text('utf-8'))
        arrays=[]
        for title,Class in [('修复前',Before),('本轮共同模型',renderer.Renderer)]:
            r=Class(str(directory),ctx=ctx)
            frames=np.lib.format.open_memmap(ANALYSIS/f'review-{j}-{title}.npy',mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
            for k in range(121):frames[k]=r.render(k/120)
            frames.flush();r.close();arrays.append(frames)
        for rate in [1.,.5]:
            def frame(t,rate=rate):
                p=float(np.clip(t-PRE,0,1));idx=round(p*120);w=480;h=round(meta['frame'][1]*w/meta['frame'][0]);h+=h%2
                im=Image.new('RGB',(960,h+120),BG);d=ImageDraw.Draw(im)
                for col,title in enumerate(['修复前','本轮共同模型']):
                    label(d,(col*w+14,8),title,25);label(d,(col*w+14,46),f'真机输入 {j} · 进度 {p:.3f} · {rate:g} 倍速',20,MUTED)
                    im.paste(Image.fromarray(arrays[col][idx]).resize((w,h),Image.Resampling.LANCZOS),(col*w,78))
                footer(im,'相同素材、方向、种子及触点；原录像种子未知，此处复现同类释放形状',p)
                return np.asarray(im)
            item=encode(OUT/f'dialog-release-filament-{j}-{rate:g}x.mp4',PRE+1+POST,rate,frame)
            item['sha256']=hashlib.sha256((OUT/item['file']).read_bytes()).hexdigest();items.append(item)
        fixtures=HERE/'android-unified-fixtures';name=meta['name']
        shutil.copy2(directory/'foreground.png',fixtures/f'{name}.png')
        (fixtures/f'{name}.json').write_text(json.dumps(meta,ensure_ascii=False),'utf-8')
    inputs={str(p.relative_to(ANALYSIS)):hashlib.sha256(p.read_bytes()).hexdigest() for j in [1,2,3] for p in (ANALYSIS/f'input-{j}').iterdir() if p.suffix in ['.json','.png']}
    ctx.release();(OUT/'release-filament-videos.json').write_text(json.dumps(dict(code_hash=code_hash(),videos=items,inputs=inputs),ensure_ascii=False,indent=2),'utf-8')
if __name__=='__main__':main()
