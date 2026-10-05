"""检查同一高频总量下的亮暗对比分布；原片噪声与背景纹理仍包含在观测内。"""
from pathlib import Path
import argparse, hashlib, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from prepare_uncertain_grain_cache import observed_mask
from check_regional_structure import CONFIGS
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def tail_statistics(frame,mask):
    luma=np.asarray(frame,np.float32)@np.float32([.2126,.7152,.0722])/255
    hp=luma-cv2.GaussianBlur(luma,(0,0),1.5)
    v=hp[mask]
    return dict(absolute_mean=float(abs(v).mean()),
                positive_quantiles=np.quantile(np.maximum(v,0),[.95,.99,.999]).tolist(),
                negative_quantiles=np.quantile(np.maximum(-v,0),[.95,.99,.999]).tolist(),
                positive_fractions=[float((v>t).mean()) for t in [.04,.08,.16,.32]],
                negative_fractions=[float((v<-t).mean()) for t in [.04,.08,.16,.32]],
                positive_second_moment=float(np.maximum(v,0).dot(np.maximum(v,0))/len(v)),
                negative_second_moment=float(np.maximum(-v,0).dot(np.maximum(-v,0))/len(v)))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=HERE)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman','kobe'])
    ap.add_argument('--phases',type=float,nargs='+',default=[.22,.34,.46,.58,.70])
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    identity=hashlib.sha256(b''.join((a.root/f).read_bytes() for f in CONFIGS)).hexdigest()
    report=dict(identity=identity,root=str(a.root.resolve()),scenes={},
                scope='同一原位纹理已变化区域内的图像高频；含拍摄噪声、背景和源纹理，不能解释为纯粒子数')
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(a.root)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        scene=c.scene;tw=290;th=round(r.h*tw/r.w)
        sheet=Image.new('RGB',(tw*len(a.phases),(th+24)*2),(14,20,30));draw=ImageDraw.Draw(sheet)
        rows=[]
        for col,p in enumerate(a.phases):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);actual=r.render(p)
            changed=(scene.excess(ref,scene.srcmax,scene.srcmin)>36)&(scene.excess(actual,scene.srcmax,scene.srcmin)>36)
            safe=cv2.erode(changed.astype(np.uint8),np.ones((3,3),np.uint8)).astype(bool)
            mask=(observed_mask(scene.meta,ref.shape)>.5)&safe
            if scene.roi.any():mask&=scene.roi
            if mask.sum()<100:continue
            row=dict(phase=p,pixels=int(mask.sum()),reference=tail_statistics(ref,mask),model=tail_statistics(actual,mask))
            rows.append(row)
            for k,(title,frame) in enumerate([('华为原片',ref),('当前共同模型',actual)]):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+24)+24))
                draw.text((col*tw+3,k*(th+24)+2),f'{title} {p:.3f}',font=font,fill='white')
        r.close();sheet.save(a.out/f'{name}.jpg',quality=95)
        report['scenes'][name]=rows
        (a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,[(round(v['phase'],3),round(v['model']['absolute_mean']/v['reference']['absolute_mean'],3),
                    round(v['model']['positive_quantiles'][1]/max(v['reference']['positive_quantiles'][1],1e-8),3),
                    round(v['model']['negative_quantiles'][1]/max(v['reference']['negative_quantiles'][1],1e-8),3)) for v in rows],flush=True)
    ctx.release()


if __name__=='__main__':main()
