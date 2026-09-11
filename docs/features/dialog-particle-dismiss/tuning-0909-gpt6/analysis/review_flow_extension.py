"""候选场的小规模视觉回路，使用用户正在审阅的正式画廊输入。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import encode,sampled,focus_bounds,PRE,POST
OUT=HERE/'analysis/motion-field-extension'

def configure():
    if '    // 观测场的静止背景不是固体边界。' in renderer.COMPUTE:
        begin=renderer.COMPUTE.index('    // 观测场的静止背景不是固体边界。')
        end=renderer.COMPUTE.index('    float response=',begin)
        renderer.COMPUTE=renderer.COMPUTE[:begin]+renderer.COMPUTE[end:]
    field=np.fromfile(OUT/'extended-flow.f16',dtype='<f2').astype('float32').reshape(48,64,64,2)
    renderer.guidance=lambda:field

def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['ironman','color','attachment','thanos']);p.add_argument('--videos',action='store_true');a=p.parse_args()
    configure();ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    for name in a.scenes:
        r=renderer.Renderer(name,ctx=ctx)
        cache=np.lib.format.open_memmap(OUT/f'{name}-extended.npy',mode='w+',dtype=np.uint8,shape=(121,r.h,r.w,3))
        for i in range(121):cache[i]=r.render(i/120)
        cache.flush()
        old=np.load(HERE/f'archive/before-motion-continuity/{name}.npy',mmap_mode='r')
        rejected=np.load(HERE/f'archive/rejected-low-speed/{name}.npy',mmap_mode='r')
        lo,hi=focus_bounds(r.meta,True);w=480;h=round((hi-lo)*w/r.w);h+=h%2
        def frame(time):
            phase=float(np.clip(time-PRE,0,1));im=Image.new('RGB',(w*3,h+100),'#101722');d=ImageDraw.Draw(im)
            for col,(arr,title) in enumerate([(old,'原已发布版本'),(rejected,'被否决的微小速度补偿'),(cache,'延续无观测区域的运动场')]):
                d.text((col*w+10,8),title,font=font,fill='white')
                d.text((col*w+10,36),f'{r.meta["title"]} · t={phase:.2f}',font=font,fill='#abbcd1')
                im.paste(Image.fromarray(sampled(arr,phase)[lo:hi]).resize((w,h)),(col*w,70))
            return np.asarray(im)
        for t in [.35,.55,.70,.84]:Image.fromarray(frame(PRE+t)).save(OUT/f'{name}-compare-{round(t*100):02d}.jpg',quality=95)
        if a.videos:
            for rate in [.5]:
                path=HERE/f'videos/flow-extension-{name}-{rate:g}x.mp4'
                encode(path,PRE+1+POST,rate,frame)
                import hashlib
                manifest=HERE/'videos/diagnostics.json';data=json.loads(manifest.read_text('utf-8'))
                data['videos']=[v for v in data['videos'] if v['file']!=path.name]
                data['videos'].append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),purpose='无观测区运动场延续候选，三列原版／被否决版／新候选'))
                manifest.write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
        print(name,'原画廊种子',r.meta['seed'],'候选缓存完成',flush=True);r.close()
    ctx.release()

if __name__=='__main__':main()
