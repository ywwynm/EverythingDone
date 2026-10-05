"""完整渲染链的源纹理保留时序；不复用旧解除观测缓存，不产生运行控制图。"""
from pathlib import Path
import argparse, hashlib, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from check_regional_structure import CONFIGS
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from release_integrity import Probe


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--root',type=Path,default=HERE)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES))
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    identity=hashlib.sha256()
    for name in CONFIGS:identity.update((a.root/name).read_bytes())
    identity.update(Path(__file__).read_bytes())
    result=dict(identity=identity.hexdigest(),root=str(a.root.resolve()),scenes={},
        scope='完整渲染及原片的源纹理保留观测；不等同于粒子真实出生时刻，含遮挡及拍摄影响')
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(a.root)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in a.scenes:
        p=Probe(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*q) for q in OBSERVED_SITES[name]],**kw)
        refs=[np.asarray(p.comp.ref.frames[i]) for i in p.index]
        rendered=[r.render(float(t)) for t in p.progress];r.close()
        original=p.integrities(refs)
        current=p.integrities([cv2.GaussianBlur(f,(0,0),.65) for f in rendered])
        raw=p.integrities(rendered)
        reference=p.release(original);observed=p.release(current);unsmoothed=p.release(raw)
        valid=p.valid&np.isfinite(reference)&np.isfinite(observed)&(reference<.99)&(observed<.99)
        delta=observed-reference
        stats=dict(blocks=int(valid.sum()),mean_abs=float(abs(delta[valid]).mean()),
                   median=float(np.median(delta[valid])),quantiles=np.quantile(delta[valid],[.1,.5,.9]).tolist(),
                   smoothing_mean_shift=float(np.nanmean(observed-unsmoothed)))
        np.savez_compressed(a.out/f'{name}.npz',reference=reference,observed=observed,valid=valid,
            unsmoothed=unsmoothed,phases=p.progress,source_fraction=np.array(original),model_fraction=np.array(current),u=p.u,v=p.v)
        # 同一色阶，并放回完整手机中的原控件区域，避免脱离整体看差分图。
        x0,y0,x1,y1=p.rect;w,h=x1-x0,y1-y0;frames=[]
        for field in [reference,observed]:
            color=cv2.applyColorMap(np.uint8(np.nan_to_num(field,nan=1.)*255),cv2.COLORMAP_TURBO)[...,::-1]
            color[~p.valid]=[60,60,60]
            image=np.uint8(np.clip(p.scene.source,0,255));image[y0:y1,x0:x1]=cv2.resize(color,(w,h),interpolation=cv2.INTER_NEAREST)
            frames.append(image)
        heat=np.zeros((*delta.shape,3),np.uint8)+35
        heat[...,0]+=np.uint8(np.clip(np.nan_to_num(delta)/.20,0,1)*210)
        heat[...,2]+=np.uint8(np.clip(-np.nan_to_num(delta)/.20,0,1)*210)
        heat[~valid]=[35,35,35]
        image=np.uint8(np.clip(p.scene.source,0,255));image[y0:y1,x0:x1]=cv2.resize(heat,(w,h),interpolation=cv2.INTER_NEAREST);frames.append(image)
        tw=330;th=round(p.scene.H*tw/p.scene.W)
        sheet=Image.new('RGB',(3*tw,th+50),(14,20,30));draw=ImageDraw.Draw(sheet)
        for i,(title,image) in enumerate(zip(['原片：纹理解除时刻','模型：纹理解除时刻','红：模型偏晚；蓝：偏早'],frames)):
            sheet.paste(Image.fromarray(image).resize((tw,th),Image.Resampling.LANCZOS),(i*tw,48))
            draw.text((i*tw+5,3),title,font=font,fill='white')
        draw.text((5,25),'解除色阶由蓝到红：0 → 1；差值色阶 ±0.20 进度',font=font,fill='white')
        sheet.save(a.out/f'{name}.jpg',quality=95)
        result['scenes'][name]=stats;print(name,stats,flush=True)
        (a.out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
        assert ctx.error=='GL_NO_ERROR'
    ctx.release()


if __name__=='__main__':main()
