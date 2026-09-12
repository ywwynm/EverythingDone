"""用源纹理相关性测量参考的静止覆盖；测量只用于诊断，不加入运行时。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,cv2
from scipy.ndimage import gaussian_filter,median_filter
from PIL import Image,ImageDraw
from export_videos import HERE,Reference,load_meta,sampled,label,BG
from renderer import Renderer
from unified_model import release_field
OUT=HERE/'analysis/density-timing';BASE=HERE/'archive/before-density-timing'

def highpass(a):return a-gaussian_filter(a,(3,3,0))

def main():
    meta=load_meta('ironman');ref=Reference(meta);old=np.load(BASE/'ironman.npy',mmap_mode='r')
    x,y,x1,y1=meta['rect'];crop=lambda a:a[y:y1,x:x1].astype('float32')/255
    s=crop(ref.original);b=crop(np.array(Image.open(HERE/'assets/ironman/background.png')))
    hp=highpass(s-b);variance=gaussian_filter((hp*hp).sum(axis=2),4)
    strength=gaussian_filter(((s-b)**2).sum(axis=2),4)
    static_valid=(variance>.003)&(strength>.012)
    tracks={};times=np.linspace(0,.9,91)
    for key,source in [('reference',ref.at),('baseline',lambda t:sampled(old,t))]:
        values=[]
        for t in times:
            frame=crop(source(t));h=highpass(frame-b)
            alpha=gaussian_filter((h*hp).sum(axis=2),4)/np.maximum(variance,.00005)
            values.append(np.clip(alpha,0,1.2))
        values=np.stack(values);values=median_filter(values,size=(3,1,1));values=np.minimum.accumulate(values,axis=0)
        clock=np.full(s.shape[:2],.9)
        for i,t in enumerate(times):clock[(values[i]<.5)&(clock==.9)]=t
        tracks[key]=(values,clock)
    diff=tracks['reference'][1]-tracks['baseline'][1]
    # 四个宽区域是报告标签，不进入模型。高频纹理太少处不作可信时钟统计。
    zones={'upper':(0,0,120,130),'tail':(0,275,125,474),'core':(170,120,315,330),'right':(310,70,468,340)}
    report={}
    for name,(l,t,r,bottom) in zones.items():
        valid=static_valid[t:bottom,l:r];report[name]={'valid':int(valid.sum())}
        for key,(_,clock) in tracks.items():report[name][key+'_half_exit']=np.quantile(clock[t:bottom,l:r][valid],[.25,.5,.75]).tolist()
        report[name]['median_delta']=float(np.median(diff[t:bottom,l:r][valid]))
    # 原图到参考初帧仅检查几何配准，报告估计位移，绝不修改对比画面。
    gray=lambda a:cv2.cvtColor(a,cv2.COLOR_RGB2GRAY)
    shift,response=cv2.phaseCorrelate(gray(s),gray(crop(ref.at(0))))
    report['source_registration']={'translation_px':list(shift),'response':response}
    np.savez(OUT/'measured-source-clock.npz',times=times,reference=tracks['reference'][0],baseline=tracks['baseline'][0],valid=static_valid)
    (OUT/'source-clock.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    im=Image.new('RGB',(480*3,530),BG);d=ImageDraw.Draw(im)
    for i,(title,arr) in enumerate([('华为参考：纹理半退出时刻',tracks['reference'][1]),('当前模型：纹理半退出时刻',tracks['baseline'][1]),('参考减模型，±0.15 秒',np.clip(diff/.30+.5,0,1))]):
        color=cv2.applyColorMap((np.clip(arr if i==2 else arr/.8,0,1)*255).astype('uint8'),cv2.COLORMAP_TURBO)[:,:,::-1]
        color[~static_valid]=(18,24,32)
        label(d,(i*480+8,8),title,20);im.paste(Image.fromarray(color).resize((468,474)),(i*480,44))
    im.save(OUT/'source-clock.jpg',quality=96)
    print(json.dumps(report,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
