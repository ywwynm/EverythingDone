"""只重画全量状态排查选出的边缘、短寿命与方向代表，检查轮廓连续变化。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from review_flow_extension import configure
from export_videos import encode,PRE,POST,focus_bounds,sampled
OUT=HERE/'analysis/motion-field-extension'

def main():
    p=argparse.ArgumentParser();p.add_argument('--ids',nargs='+',default=['C225','C226','C079','C107','C005']);p.add_argument('--videos',action='store_true');a=p.parse_args()
    cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
    configure()
    observed=np.load(OUT/'observed-flow.npy');extended=np.fromfile(OUT/'extended-flow.f16','<f2').astype('float32').reshape(48,64,64,2)
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    for c in cases:
        if c['id'] not in a.ids:continue
        frames=[];meta=None
        for variant,field in [('原版',observed),('候选',extended)]:
            renderer.guidance=lambda:field
            r=renderer.Renderer(c['scene'],direction=c['angle'],seed=c['seed'],ctx=ctx);meta=r.meta
            if a.videos:
                data=np.lib.format.open_memmap(OUT/f'{c["id"]}-{variant}.npy',mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
                for i in range(121):data[i]=r.render(i/120)
                data.flush();frames.append(data)
            else:frames.append({t:r.render(t) for t in [.45,.55,.65,.75,.85]})
            r.close()
        lo,hi=focus_bounds(meta,True);w=360;h=round((hi-lo)*w/frames[0][0 if a.videos else .45].shape[1]);h+=h%2
        strip=Image.new('RGB',(w*5,h*2+120),'#101722');d=ImageDraw.Draw(strip)
        d.text((12,8),f'{c["id"]} · {c["title"]} · 种子 {c["seed"]} · 方向 {c["angle"]}°；上：原版，下：延续速度场',font=font,fill='white')
        for j,t in enumerate([.45,.55,.65,.75,.85]):
            d.text((j*w+10,38),f't={t:.2f}',font=font,fill='white')
            for i,data in enumerate(frames):
                src=sampled(data,t) if a.videos else data[t]
                strip.paste(Image.fromarray(src[lo:hi]).resize((w,h)),(j*w,70+i*(h+12)))
        strip.save(OUT/f'{c["id"]}-temporal.jpg',quality=95)
        if a.videos:
            def frame(t):
                phase=float(np.clip(t-PRE,0,1));im=Image.new('RGB',(w*2,h+100),'#101722');d=ImageDraw.Draw(im)
                for i,(title,data) in enumerate(zip(['原版','延续速度场'],frames)):
                    d.text((i*w+10,8),f'{title} · {c["id"]} · t={phase:.2f}',font=font,fill='white')
                    d.text((i*w+10,34),f'种子 {c["seed"]} · {c["angle"]}°',font=font,fill='#abbcd1')
                    im.paste(Image.fromarray(sampled(data,phase)[lo:hi]).resize((w,h)),(i*w,70))
                return np.asarray(im)
            path=HERE/f'videos/flow-extension-{c["id"]}-0.5x.mp4';encode(path,PRE+1+POST,.5,frame)
            import hashlib
            manifest=HERE/'videos/diagnostics.json';report=json.loads(manifest.read_text('utf-8'))
            report['videos']=[v for v in report['videos'] if v['file']!=path.name]
            report['videos'].append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),purpose='全量轨迹排查选出的代表输入慢放'))
            manifest.write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(c['id'],'完成',flush=True)
    ctx.release()

if __name__=='__main__':main()
