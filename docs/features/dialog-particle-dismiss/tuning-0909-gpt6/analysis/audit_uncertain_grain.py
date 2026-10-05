"""不完整背景参考的可观测颗粒对比诊断；不把重建底图当作 RGB 真值。

局部高频能量是原片中可直接计算的图像量，但会混入壁纸、源纹理及拍摄噪声。
本工具同时展示这些干扰，结果只作诊断，不修改正式误差或运行模型。
"""
from pathlib import Path
import sys,json
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def grain(image):
    f=np.asarray(image,np.float32)
    return cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.).mean(-1)

def main():
    out=HERE/'analysis/match-cache/uncertain-grain';out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in ['reference-0926-2','reference-0926-3','ironman-up-reference']:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=r.meta['rect'];box=(max(0,x-45),max(0,y-45),min(r.w,x1+45),min(r.h,y1+45))
        roi=np.zeros((r.h,r.w),bool);roi[y:y1,x:x1]=True
        bg=grain(Image.open(r.directory/'background.png').convert('RGB'))
        before=grain(c.ref.nearest(0.)[1]);rows=[]
        tw,th=280,280;sheet=Image.new('RGB',(tw*4,(th+28)*4),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,requested in enumerate([.22,.38,.55,.70]):
            i,ref=c.ref.nearest(requested);p=float(c.ref.progress[i]);f=r.render(p)
            reference,model=grain(ref),grain(f)
            def heat(value):return cv2.applyColorMap(np.uint8(np.clip(value/12,0,1)*255),cv2.COLORMAP_INFERNO)[...,::-1]
            for k,(title,image) in enumerate([('华为原片',ref),('当前共同模型',f),('原片高频能量',heat(reference)),('模型高频能量',heat(model))]):
                sheet.paste(Image.fromarray(image).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+28)+28))
                draw.text((j*tw+4,k*(th+28)+4),f'{title} {p:.3f}',font=font,fill='white')
            rows.append(dict(phase=p,observed_grain_difference=float(abs(reference-model)[roi].mean()),
                reference_mean=float(reference[roi].mean()),model_mean=float(model[roi].mean()),
                approximate_background_mean=float(bg[roi].mean()),initial_texture_mean=float(before[roi].mean()),
                strong_reference_fraction=float(((reference>np.maximum(bg*4,2))&roi).sum()/roi.sum())))
        sheet.save(out/f'{name}.jpg',quality=94);r.close();report[name]=rows;print(name,rows,flush=True)
    ctx.release();(out/'report.json').write_text(json.dumps(dict(scope='局部高频图像量；未知背景不参与 RGB 评分，没有将其认定为纯粒子真值',scenes=report),ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
