"""在真实白底截图上量当前合成的空间梯度；仅为边界诊断，不当作审美分数。"""
import json,cv2
import numpy as np
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from fields import field_grid
from export_videos import VERSION,code_hash

def linear(a):
    c=a.astype('float32')/255
    return np.where(c<.04045,c/12.92,((c+.055)/1.055)**2.4)

rows=[];font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
for name in ['attachment','attachment-image','language','color']:
    r=Renderer(name);m=r.meta;x,y,x1,y1=m['rect']
    old=np.load(HERE/'archive/r31'/f'{name}.npy',mmap_mode='r')
    fg=np.array(Image.open(r.directory/'foreground.png'));bg=np.array(Image.open(r.directory/'background.png'))
    white=cv2.erode(((fg[:,:,:3].min(axis=2)>242)&(fg[:,:,3]>242)).astype('uint8'),np.ones((5,5),np.uint8))>0
    T=field_grid(m,r.cw,r.ch,r.direction,None)
    ty,tx=np.gradient(gaussian_filter(T,3));gradient=np.maximum(np.hypot(tx,ty),.0001)
    images=[];measurements=[]
    for target in [.28,.40,.52,.68]:
        index=round(target*120);t=index/120;a=old[index];b=r.render(t);images.append((a,b))
        dim=m['dim_alpha']*(1.-float(np.clip((t-.18)/.55,0,1))**2)
        background=linear(bg.astype('float32')*(1-dim))[y:y1,x:x1]
        contrast=(1-background).sum(axis=2);front=white&(abs(T-t)<.080)&(contrast>.30)
        result={}
        for label,im in [('r31',a),('r33',b)]:
            coverage=np.clip((linear(im)[y:y1,x:x1]-background).sum(axis=2)/np.maximum(contrast,.001),0,1)
            gy,gx=np.gradient(gaussian_filter(coverage,2.0));strength=np.hypot(gx,gy)[front]
            result[label]={'p95_gradient_per_px':float(np.quantile(strength,.95)),'mean_gradient_per_px':float(np.mean(strength)),'sharp_fraction_over_004':float(np.mean(strength>.04))}
            gy,gx=np.gradient(gaussian_filter(coverage,12.0));coarse=np.hypot(gx,gy)[front]
            result[label]['p95_gradient_sigma12_per_px']=float(np.quantile(coarse,.95))
            distance=(t-T)/gradient;profile=[]
            for center in np.arange(-60,61,4):
                band=white&(abs(distance-center)<2)&(contrast>.30)
                profile.append(float(np.mean(coverage[band])) if band.sum()>40 else None)
            valid=np.array([v if v is not None else np.nan for v in profile]);slope=abs(np.diff(valid)/4)
            result[label]['normal_profile_step4px']=profile
            result[label]['normal_profile_peak_gradient']=float(np.nanmax(slope)) if np.isfinite(slope).any() else None
        measurements.append({'t':t,'white_front_pixels':int(front.sum()),'values':result})
    W=460;H=510 if name in ['color','language'] else 400;out=Image.new('RGB',(W*2,H*4),(18,23,31));d=ImageDraw.Draw(out)
    pad=round(min(r.cw,r.ch)*.22)
    for j,(a,b) in enumerate(images):
        for i,img in enumerate([a,b]):
            img=img[max(0,y-pad):min(r.h,y1+pad),max(0,x-pad):min(r.w,x1+pad)]
            im=Image.fromarray(img);im.thumbnail((W-8,H-36));out.paste(im,(i*W+(W-im.width)//2,j*H+34));d.text((i*W+10,j*H+5),f'{"r31" if i==0 else "r33"}  {measurements[j]["t"]:.2f}',font=font,fill='white')
    out.save(HERE/'analysis'/f'r33-ui-{name}.jpg',quality=96)
    row={'scene':name,'white_fraction':r.white_fraction,'release_spread_seconds':r.release_spread,'measurements':measurements};rows.append(row)
    print(name,'边界梯度 P95',[(round(v['values']['r31']['p95_gradient_per_px'],4),round(v['values']['r33']['p95_gradient_per_px'],4)) for v in measurements],flush=True);r.close()
    print(name,'宏观梯度 P95',[(round(v['values']['r31']['p95_gradient_sigma12_per_px'],4),round(v['values']['r33']['p95_gradient_sigma12_per_px'],4)) for v in measurements],flush=True)
(HERE/'analysis/white-boundary-qa.json').write_text(json.dumps({'version':VERSION,'code_hash':code_hash(),'scenes':rows,'meaning':'真实白色区域相对背景的近似线性覆盖率，经 sigma=2 px 平滑后的局部梯度；在相同传播场邻域比较，排除文字与低对比背景。不能直接等同人眼柔和程度。'},ensure_ascii=False,indent=2),encoding='utf-8')
