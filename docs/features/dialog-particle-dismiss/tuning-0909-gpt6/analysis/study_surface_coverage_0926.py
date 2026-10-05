"""独立检查表面解除观测：背景投影与连续帧确认，不把一次 RGB 波动当成永久解除。"""
from pathlib import Path
import json,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import median_filter
from scipy.optimize import isotonic_regression
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'


def measure(name):
    m=load_meta(name);ref=Reference(m);x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
    size=(96,round(96*h/w));fg=np.array(Image.open(HERE/'assets'/name/'foreground.png'))
    src=fg[:,:,:3].astype('float32');bg=np.array(Image.open(HERE/'assets'/name/'background.png'))[y:y1,x:x1].astype('float32')
    src=cv2.GaussianBlur(src,(0,0),1.5);bg=cv2.GaussianBlur(bg,(0,0),1.5)
    vector=src-bg;norm=np.sum(vector*vector,2)
    phases=np.linspace(0,min(.92,m['reference'].get('valid_progress',1)),93)
    responses=[]
    for t in phases:
        target=cv2.GaussianBlur(ref.at(t)[y:y1,x:x1].astype('float32'),(0,0),1.5)
        # 光照和配准误差不能单独决定解除；用源图—背景方向估计材料覆盖。
        alpha=np.sum((target-bg)*vector,2)/np.maximum(norm,100.)
        residual=np.mean(np.square(target-(bg+alpha[:,:,None]*vector)),2)
        alpha=np.clip(alpha,0,1.15)*np.exp(-np.maximum(residual-150,0)/2500.)
        responses.append(cv2.resize(alpha,size,interpolation=cv2.INTER_AREA))
    responses=median_filter(np.array(responses),size=(3,1,1))
    initial=np.quantile(responses[:10],.9,axis=0)
    # 保序回归允许中途曝光抖动，避免第一帧失配让以后所有帧都判为已释放。
    arrival=np.zeros(size[::-1],dtype='float32')
    for yy in range(size[1]):
        for xx in range(size[0]):
            curve=isotonic_regression(responses[:,yy,xx],increasing=False).x
            cross=np.flatnonzero(curve<max(.80*initial[yy,xx],.65))
            arrival[yy,xx]=phases[cross[0]] if len(cross) else phases[-1]
    valid=(cv2.resize(norm,size)>3600)&(initial>.85)&(cv2.resize(fg[:,:,3],size)==255)
    weight=valid&(arrival>.02)&(arrival<phases[-1]-.02)
    np.savez_compressed(OUT/f'{name}-coverage-measurements.npz',arrival=arrival,weight=weight,initial_valid=valid,phases=phases,responses=responses)
    return m,arrival,weight


def main():
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17);rows=[]
    for name in OBSERVED_SITES:
        m,arrival,weight=measure(name)
        old=np.load(OUT/f'{name}-surface-measurements.npz')
        both=weight&old['weight'];delta=arrival-old['arrival']
        rows.append(dict(scene=name,points=int(weight.sum()),common=int(both.sum()),mean_shift=float(delta[both].mean()),absolute_shift=float(abs(delta[both]).mean())))
        sheet=Image.new('RGB',(720,278),'#0e141e');d=ImageDraw.Draw(sheet)
        for j,(a,v,label) in enumerate([(old['arrival'],old['weight'],'原 RGB 阈值'),(arrival,weight,'覆盖投影与连续确认'),(abs(delta)*3,both,'两种测量差 ×3')]):
            rgb=cv2.applyColorMap(np.uint8(np.clip(a,0,1)*255),cv2.COLORMAP_TURBO)[:,:,::-1];rgb[~v]=[24,28,38]
            sheet.paste(Image.fromarray(rgb).resize((236,240)),(j*240,32));d.text((j*240+3,3),label,font=font,fill='white')
        sheet.save(OUT/f'{name}-coverage-observer.jpg',quality=95)
        print(rows[-1],flush=True)
    (OUT/'coverage-observer.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
