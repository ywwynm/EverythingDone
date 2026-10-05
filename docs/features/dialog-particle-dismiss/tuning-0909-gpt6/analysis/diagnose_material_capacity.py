"""离线逐格材质容量诊断，禁止作为生产模型；训练与实际 GPU 复核分开。"""
from pathlib import Path
import argparse, json, sys
import numpy as np
HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE/'analysis')]


def fit(a):
    import torch
    z = np.load(a.response)
    t = lambda v: torch.as_tensor(v,dtype=torch.float32,device='cuda')
    actual,target,slope,curve,mask,prior = [t(z[k]) for k in ['actual','target','slope','curve','mask','prior']]
    scale = t([.9,.16,.75]); param = torch.zeros_like(prior,requires_grad=True)
    opt = torch.optim.Adam([param],lr=.04)
    channel = t([1.]*9+[2.]*9+[.08]*8)
    history = []
    for i in range(800):
        # 当前函数的允许范围内求最优局部修正；信赖范围限制二阶近似外推。
        delta = torch.maximum(torch.minimum(param.tanh()*t([.45,.08,.40]),scale-prior),-scale-prior)
        est = actual+(slope*delta[...,None,:]+curve*delta[...,None,:].square()).sum(-1)
        error = ((est-target).square()+.0001).sqrt()*channel
        smooth = (delta[:,1:]-delta[:,:-1]).square().mean()+(delta[:,:,1:]-delta[:,:,:-1]).square().mean()
        loss = (error*mask[...,None]).sum()/(mask.sum()*channel.sum())+.008*smooth
        opt.zero_grad();loss.backward();opt.step()
        if i%200==0:
            value=float(loss.detach());history.append(value);print(i,value,flush=True)
    a.out.mkdir(parents=True,exist_ok=True)
    np.savez_compressed(a.out/'diagnostic-controls.npz',delta=delta.detach().cpu().numpy(),response=str(a.response))
    (a.out/'fit.json').write_text(json.dumps(dict(loss=history,diagnostic_only=True),indent=2),'utf-8')


def review(a):
    import cv2, moderngl
    from PIL import Image,ImageDraw,ImageFont
    from check_cloud_evolution import configuration
    from match_compare import Comparison
    from fit_particle_optics import measure,NAMES
    from dissolve_v2 import Site
    from v2_inputs import OBSERVED_SITES
    saved = np.load(a.out/'diagnostic-controls.npz')
    z = np.load(str(saved['response']));delta=saved['delta']
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        ni=NAMES.index(name);c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        original=r.scatter_tex.read()
        fields=np.frombuffer(original,np.float32).reshape(41,64,64,3)
        x,y,x1,y1=c.scene.rect;box=(max(0,x-35),max(0,y-35),min(r.w,x1+35),min(r.h,y1+35))
        tw=340;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(3*tw,3*(th+25)),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
        for col,phase_id in enumerate([1,2,3]):
            idx=ni*8+phase_id
            # 缓存包含内部时间；从原片进度恢复同一帧，不能再次应用内部时钟。
            requested=[.12,.22,.32,.42,.52,.62,.72,.82][phase_id]
            ii,ref=c.ref.nearest(requested);p=float(c.ref.progress[ii]);target=measure(ref,c.scene.roi)
            r.scatter_tex.write(original);before=r.render(p)
            d=cv2.resize(delta[idx],(64,64),interpolation=cv2.INTER_LINEAR)
            r.scatter_tex.write(np.ascontiguousarray(fields+d[None]).tobytes());after=r.render(p)
            row=dict(phase=p)
            for k,(title,f) in enumerate([('华为原片',ref),('当前共同模型',before),('逐格材质容量诊断',after)]):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+25)+25));draw.text((col*tw+3,k*(th+25)),f'{title} {p:.3f}',font=font,fill='white')
                if k:row['before' if k==1 else 'diagnostic']={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            rows.append(row)
        r.close();report[name]=rows;sheet.save(a.out/f'{name}.jpg',quality=95)
        print(name,{k:round(float(np.mean([row[k]['rgb_4'] for row in rows])),4) for k in ['before','diagnostic']},flush=True)
        (a.out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--response',type=Path);ap.add_argument('--out',type=Path,default=HERE/'analysis/match-cache/material-capacity');ap.add_argument('--review',action='store_true');ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','ironman','kobe']);a=ap.parse_args()
    review(a) if a.review else fit(a)


if __name__=='__main__':main()
