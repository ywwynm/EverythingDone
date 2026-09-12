"""重新测量参考运动的置信区，比较光流估计；候选不直接覆盖正式资源。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from scipy.ndimage import gaussian_filter,distance_transform_edt
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
from unified_model import field_rotation,smooth,guidance
from frame_difference import OUT

def main():
    meta=load_meta('ironman');reference=Reference(meta);x,y,x1,y1=meta['rect'];w,h=x1-x,y1-y
    yy,xx=np.mgrid[:64,:64].astype('float32');u=-.45+(xx+.5)/64*1.9-.5;v=-.45+(yy+.5)/64*1.9-.5
    a=field_rotation(meta['direction'],w,h);c,s=np.cos(a),np.sin(a)
    qx=((.5+c*u+s*v)*w+x).astype('float32');qy=((.5-s*u+c*v)*h+y).astype('float32')
    yy,xx=np.mgrid[:1280,:720].astype('float32');fields=[];confs=[]
    dis=cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM);dis.setFinestScale(0)
    dis.setGradientDescentIterations(32);dis.setVariationalRefinementIterations(7)
    for j in range(48):
        t=(j+.5)/48;ta=max(0,t-.008);tb=min(1,t+.008)
        ia=np.argmin(abs(reference.times-(3.25+ta*5.2)));ib=np.argmin(abs(reference.times-(3.25+tb*5.2)))
        dt=(reference.times[ib]-reference.times[ia])/5.2
        a0=cv2.cvtColor(reference.frames[ia],cv2.COLOR_RGB2GRAY);b0=cv2.cvtColor(reference.frames[ib],cv2.COLOR_RGB2GRAY)
        f=dis.calc(a0,b0,None);back=dis.calc(b0,a0,None)
        tracked=cv2.remap(back,xx+f[:,:,0],yy+f[:,:,1],cv2.INTER_LINEAR)
        fb=np.linalg.norm(f+tracked,axis=-1)
        speed=np.linalg.norm(f,axis=-1)/(dt*min(w,h))
        confidence=smooth((speed-.10)/.28)*np.exp(-(fb/.80)**2)
        normalized=np.stack((f[:,:,0]/(w*dt),f[:,:,1]/(h*dt)),axis=-1)
        normalized=np.clip(normalized,-2.4,2.4)
        screen=cv2.remap(normalized,qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
        fields.append(np.stack((c*screen[:,:,0]-s*screen[:,:,1],s*screen[:,:,0]+c*screen[:,:,1]),-1))
        confs.append(cv2.remap(confidence,qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT))
        if j%8==0:print('测量相位',j,flush=True)
    raw=np.stack(fields);confidence=gaussian_filter(np.stack(confs),(.4,.6,.6))
    valid=confidence>.55
    _,nearest=distance_transform_edt(~valid,sampling=(1.8,1,1),return_indices=True)
    extended=raw[tuple(nearest)]
    for _ in range(50):extended=np.where(valid[...,None],raw,gaussian_filter(extended,(.65,.8,.8,0),mode='nearest'))
    extended[...,1]=np.minimum(extended[...,1],-.10)
    weight=smooth((confidence-.20)/.50)[...,None]
    flow=raw*weight+extended*(1-weight)
    np.savez(OUT/'remeasured-flow.npz',flow=flow.astype('float32'),confidence=confidence.astype('float32'),raw=raw)
    print('完成',float(np.mean(valid)),flush=True)

if __name__=='__main__':main()
