"""实际 GPU 的局部结构复核；区域规则统一，剔除拟合帧，不输出运行控制图。"""
from pathlib import Path
import argparse, hashlib, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from prepare_uncertain_grain_cache import high_frequency, observed_mask
from fit_particle_optics import measure

CONFIGS=['conditional-release-weights.json','conditional-material-rules.json',
         'conditional-transport-weights.json','surface-transfer-rules.json',
         'particle-coverage-rules.json','particle-cohort-weights.json',
         'surface-coherence-weights.json','particle-scatter-weights.json']


def regional(error,mask):
    """对应训练损失的两种区域尺度，但输入来自真实完整分辨率渲染。"""
    values=[]
    for n in [8,16]:
        area=cv2.resize(mask,(n,n),interpolation=cv2.INTER_AREA)
        total=cv2.resize(error*mask,(n,n),interpolation=cv2.INTER_AREA)
        cost=(total/np.maximum(area,.01))[area>.55]
        if len(cost):
            take=max(1,int(np.ceil(len(cost)*.20)))
            values.append(float(np.sort(cost)[-take:].mean()))
    return float(np.mean(values)) if values else None


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--before',type=Path,default=HERE)
    ap.add_argument('--after',type=Path,required=True)
    ap.add_argument('--exclude',type=Path,required=True,help='本轮实际使用的 grain-targets.npz，仅用于排除观测')
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES))
    ap.add_argument('--carried',type=Path,help='未采用的静态速度积分权重，仅替换 after 的研究渲染器')
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    training=np.load(a.exclude);identity=hashlib.sha256()
    for root in [a.before,a.after]:
        for file in CONFIGS:identity.update((root/file).read_bytes())
    identity.update(Path(__file__).read_bytes());identity.update(a.exclude.read_bytes())
    if a.carried:identity.update(a.carried.read_bytes())
    report=dict(identity=identity.hexdigest(),before=str(a.before.resolve()),after=str(a.after.resolve()),
                excluded=str(a.exclude.resolve()),scenes={},
                scope='原片留出帧、实际 GPU、两级网格最差五分之一区域；未知背景仅高频，非纯粒子密度')
    ctx=moderngl.create_standalone_context(require=430)
    variants={key:configuration(root) for key,root in [('before',a.before),('after',a.after)]}
    if a.carried:
        from carried_velocity import CarriedVelocityRenderer,load
        _,kw=variants['after'];variants['after']=(CarriedVelocityRenderer,dict(kw,carried_weights=load(a.carried)))
        report['carried']=str(a.carried.resolve())
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);scene=c.scene;rs={key:cls(name,ctx=ctx,quality=2,
            sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw) for key,(cls,kw) in variants.items()}
        excluded=training['phases'][training['names']==name]
        eligible=[i for i in c.index if all(abs(c.ref.progress[i]-excluded)>1e-6)]
        requests=[.19,.31,.47,.63,.78]
        chosen=sorted(set(min(eligible,key=lambda i:abs(c.ref.progress[i]-p)) for p in requests))
        x0,y0,x1,y1=scene.rect;w,h=x1-x0,y1-y0;n=160
        yy,xx=np.mgrid[:n,:n].astype(np.float32)
        qx=x0+(-.38+(xx+.5)/n*1.76)*w;qy=y0+(-.38+(yy+.5)/n*1.76)*h
        sample=lambda f:cv2.remap(f,qx,qy,cv2.INTER_LINEAR)
        mask=sample(scene.roi.astype(np.float32))
        grain_mask=mask if mask.any() else sample(observed_mask(scene.meta,(scene.H,scene.W)))
        tw=270;th=round(scene.H*tw/scene.W);dy=th+25
        sheet=Image.new('RGB',(tw*len(chosen),dy*3),(14,20,30));draw=ImageDraw.Draw(sheet)
        rows=[]
        for col,i in enumerate(chosen):
            ref=np.asarray(c.ref.frames[i]);p=float(c.ref.progress[i]);row=dict(index=int(i),phase=p)
            rgb=sample(cv2.GaussianBlur(ref.astype(np.float32)/255,(0,0),4.))
            grain=sample(high_frequency(ref));reference=measure(ref,scene.roi) if mask.any() else None
            frames=[ref]
            for key,r in rs.items():
                frame=r.render(p);frames.append(frame)
                actual=sample(cv2.GaussianBlur(frame.astype(np.float32)/255,(0,0),4.))
                particle=sample(high_frequency(frame))
                metrics=dict(regional_rgb=regional(abs(actual-rgb).mean(-1),mask),
                             regional_high_frequency=regional(abs(particle-grain).mean(-1),grain_mask))
                if reference is not None:
                    metrics.update({label:float(abs(u-v).mean()) for label,u,v in zip(
                        ['rgb_1','rgb_4','rgb_12','grain'],measure(frame,scene.roi),reference)})
                else:
                    metrics['observable_high_frequency']=float((abs(particle-grain)*grain_mask[...,None]).sum()/grain_mask.sum()/3*255)
                row[key]=metrics
            for k,(title,frame) in enumerate(zip(['华为原片','当前默认','局部结构实验'],frames)):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*dy+25))
                draw.text((col*tw+3,k*dy+2),f'{title} {p:.3f}',font=font,fill='white')
            rows.append(row)
        for r in rs.values():r.close()
        sheet.save(a.out/f'{name}.jpg',quality=95)
        keys=rows[0]['before'].keys()
        summary={stage:{k:float(np.mean([v[stage][k] for v in rows if v[stage][k] is not None]))
                        if any(v[stage][k] is not None for v in rows) else None for k in keys} for stage in ['before','after']}
        report['scenes'][name]=dict(summary=summary,rows=rows)
        print(name,summary,flush=True)
        (a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        assert ctx.error=='GL_NO_ERROR'
    ctx.release()


if __name__=='__main__':main()
