"""从源纹理保留率测量解除时刻；输出只用于离线损失，不供运行模型读取。"""
from pathlib import Path
import json,sys
import cv2
import numpy as np
from scipy.optimize import least_squares
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
from front_transport import FrontRules,ReleaseSite,front_field
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'
TRAIN=[f'reference-0926-{i}' for i in [1,4,5,6,7]]+['spiderman-original']


def measure(name):
    m=load_meta(name);ref=Reference(m);x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
    size=(96,round(96*h/w));fg=np.array(Image.open(HERE/'assets'/name/'foreground.png'))
    src=fg[:,:,:3].astype('float32');bg=np.array(Image.open(HERE/'assets'/name/'background.png'))[y:y1,x:x1].astype('float32')
    soft_source=cv2.GaussianBlur(src,(0,0),.8)
    contrast=np.mean(np.abs(src-bg),axis=2)
    tolerance=np.clip(contrast*.20,12,24)
    phases=np.linspace(0,min(.92,m['reference'].get('valid_progress',1)),74)
    responses=[]
    for t in phases:
        target=ref.at(t)[y:y1,x:x1].astype('float32')
        soft_target=cv2.GaussianBlur(target,(0,0),.8)
        # 配准残差不足一像素也会让高反差纹理被误判为解除。
        # 仅观测器容忍一个像素的局部取样误差，渲染帧不作任何对齐变换。
        delta=np.full(src.shape[:2],1e4,dtype='float32')
        for dy,dx in [(0,0),(1,0),(-1,0),(0,1),(0,-1)]:
            residual=np.mean(np.abs(soft_target-np.roll(soft_source,(dy,dx),(0,1))),axis=2)
            delta=np.minimum(delta,cv2.GaussianBlur(residual,(0,0),1.2))
        response=np.exp(-np.square(delta/tolerance))
        responses.append(cv2.resize(cv2.GaussianBlur(response,(0,0),2),size,interpolation=cv2.INTER_AREA))
    responses=np.array(responses)
    initial=np.quantile(responses[:12],.90,axis=0)
    # 三帧平均排除屏摄噪点；确认框覆盖过且无法恢复源纹理的点不参与测量。
    filtered=np.stack([responses[max(0,j-1):j+2].mean(0) for j in range(len(phases))])
    survived=filtered>np.maximum(.52*initial,.26)
    departed=np.minimum.accumulate(survived,axis=0)
    index=departed.sum(0).clip(0,len(phases)-1)
    arrival=phases[index]
    initial_valid=(cv2.resize(contrast,size)>28)&(initial>.57)&(cv2.resize(fg[:,:,3],size)==255)
    weight=initial_valid&(arrival>.025)&(arrival<phases[-1]-.02)
    np.savez_compressed(OUT/f'{name}-surface-measurements.npz',arrival=arrival,weight=weight,initial_valid=initial_valid,phases=phases,responses=responses)
    return m,arrival,weight


def main():
    cases=[];font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for name in OBSERVED_SITES:
        m,arrival,weight=measure(name);cases.append((name,m,arrival,weight))
        print(name,'可信点',int(weight.sum()),'解除中位进度',round(float(np.median(arrival[weight])),3),flush=True)
    keys=['duration','exponent','rate_variation','anisotropy','interior','edge_retention']
    limits=np.array([[.25,1.5],[.65,2.4],[.25,.65],[0,.88],[0,1.8],[0,.40]])
    def predict(m,shape,v,name):
        x,y,x1,y1=m['rect'];sites=[ReleaseSite(*q) for q in OBSERVED_SITES[name]]
        return front_field(shape[1],shape[0],x1-x,y1-y,m['direction'],m['seed'],sites,FrontRules(**dict(zip(keys,v))))
    def residual(v):
        errors=[]
        for name,m,arrival,weight in cases:
            if name not in TRAIN:continue
            delta=(predict(m,arrival.shape,v,name)-arrival)[weight]
            # 相同视频等权；少数配准失效点由稳健损失处理。
            errors.extend(delta[::max(1,len(delta)//700)][:700])
        return np.array(errors)
    initial=np.array([.80,.9,.5,.1,.1,.1]);fit=least_squares(residual,initial,bounds=limits.T,loss='soft_l1',f_scale=.055,diff_step=1e-3)
    report=dict(training=TRAIN,global_rules=dict(zip(keys,map(float,fit.x))),cases=[])
    for name,m,arrival,weight in cases:
        pred=predict(m,arrival.shape,fit.x,name)
        report['cases'].append(dict(scene=name,mean_phase_error=float(np.abs(pred-arrival)[weight].mean()),points=int(weight.sum())))
        sheet=Image.new('RGB',(720,280),'#0e141e');d=ImageDraw.Draw(sheet)
        for i,(field,label) in enumerate([(arrival,'源表面解除 · 测量'),(pred,'共同前沿 · 预测'),(np.abs(pred-arrival)*3,'时序差 ×3')]):
            rgb=cv2.applyColorMap(np.uint8(np.clip(field,0,1)*255),cv2.COLORMAP_TURBO)[:,:,::-1];rgb[~weight]=[24,28,38]
            im=Image.fromarray(rgb);im.thumbnail((235,242));im=im.resize((235,242))
            sheet.paste(im,(i*240,30));d.text((i*240+2,3),label,font=font,fill='white')
        sheet.save(OUT/f'{name}-surface-fit.jpg',quality=95)
    (OUT/'surface-joint-fit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(report['global_rules'],flush=True)


if __name__=='__main__':main()
