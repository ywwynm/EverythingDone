"""从运动拖影测原片的流动方向场，并求发散点（触点一侧的源点）。

把连续若干独立画面按“浅底取最暗 / 深底取最亮”叠加，颗粒的运动留下直线拖影；拖影方向用结构张量
在 16 像素块上估计，只保留一致性高的块。发散点取这些方向线的最小二乘交点；若方向几乎平行，
交点会退到很远处，对应平行流。这是对输入（触点位置）的测量，和测主方向一样，不拟合运动参数。
"""
from pathlib import Path
import argparse,json,math,sys
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_refs import RefFrames
from match_observe import Scene

OUT=HERE/'analysis/match-cache/streaks'
# 颗粒相对背景的明暗：深底素材取最亮，浅底取最暗。只影响叠加方式。
MODE={'ironman':'max','reference-0926-6':'max','reference-0926-3':'max'}


def composite(frames,mode):
    stack=np.stack([f.astype('int16') for f in frames])
    return (stack.min(0) if mode=='min' else stack.max(0)).astype('uint8')


def orientation(img,scene,block=16):
    g=cv2.GaussianBlur(cv2.cvtColor(img,cv2.COLOR_RGB2GRAY).astype('float32'),(0,0),1.)
    gx=cv2.Sobel(g,cv2.CV_32F,1,0,ksize=3);gy=cv2.Sobel(g,cv2.CV_32F,0,1,ksize=3)
    jxx=cv2.GaussianBlur(gx*gx,(0,0),block/2);jyy=cv2.GaussianBlur(gy*gy,(0,0),block/2);jxy=cv2.GaussianBlur(gx*gy,(0,0),block/2)
    out=[]
    for y in range(block//2,scene.H,block):
        for x in range(block//2,scene.W,block):
            if not scene.roi[y,x]:continue
            a,b,c=jxx[y,x],jyy[y,x],jxy[y,x]
            tr=a+b
            if tr<400:continue
            coherence=math.sqrt((a-b)**2+4*c*c)/tr
            if coherence<.45:continue
            # 梯度主方向与拖影垂直；拖影方向为次特征向量。
            theta=.5*math.atan2(2*c,a-b)+math.pi/2
            out.append((x,y,math.cos(theta),math.sin(theta),coherence))
    return np.array(out).reshape(-1,5)


def fit_source(samples,scene,wind):
    """源点 P = 中心 − 主方向·(边距 + d·短边) + 横向·l·短边；按角度残差稳健拟合（d 大时为平行流）。"""
    from scipy.optimize import least_squares
    X,Y,X1,Y1=scene.rect;center=np.array([(X+X1)/2,(Y+Y1)/2]);w=np.array(wind);c=np.array([-w[1],w[0]])
    edge=min((X1-X)/(2*max(abs(w[0]),1e-6)),(Y1-Y)/(2*max(abs(w[1]),1e-6)))
    pos=samples[:,:2];d=samples[:,2:4];wt=np.sqrt(samples[:,4])
    def residual(v):
        dist,lat,rot=v
        P=center-w*(edge+dist*scene.span)+c*lat*scene.span
        u=pos-P;u/=np.linalg.norm(u,axis=1,keepdims=True)
        # 整体再允许一个小的方向修正 rot（弧度），吸收主方向测量误差。
        cr,sr=math.cos(rot),math.sin(rot);u=np.stack([cr*u[:,0]-sr*u[:,1],sr*u[:,0]+cr*u[:,1]],1)
        return wt*np.arctan2(u[:,0]*d[:,1]-u[:,1]*d[:,0],(u*d).sum(1))
    best=None
    for d0 in [.3,1.,3.,10.]:
        r=least_squares(residual,[d0,0.,0.],bounds=([0.,-3.,-.6],[40.,3.,.6]),loss='soft_l1',f_scale=.35)
        if best is None or r.cost<best.cost:best=r
    dist,lat,rot=best.x
    res=np.degrees(np.abs(residual(best.x)/np.maximum(wt,1e-6)))
    parallel=np.degrees(np.abs(np.arctan2(d[:,0]*w[1]-d[:,1]*w[0],d@w)))
    return dict(distance=float(dist),lateral=float(lat),rotation_deg=float(np.degrees(rot)),residual_median_deg=float(np.median(res)),
                parallel_median_deg=float(np.median(parallel)),samples=int(len(samples)))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','reference-0926-5','spiderman-original','reference-0926-6','reference-0926-7','reference-0926-1','thanos','reference-0926-4','kobe','ironman-up-reference'])
    ap.add_argument('--window',type=float,default=.10);a=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in a.scenes:
        s=Scene(name);R=RefFrames(name);m=s.meta;valid=m['reference'].get('valid_progress',1.)
        ang=math.radians(m['direction']);wind=np.array([math.cos(ang),-math.sin(ang)])
        mode=MODE.get(name,'min');samples=[];tiles=[]
        for c in np.arange(.15,min(valid,.75),.1):
            idx=[i for i,p in enumerate(R.progress) if c<=p<c+a.window]
            if len(idx)<3:continue
            comp=composite([np.asarray(R.frames[i]) for i in idx],mode)
            o=orientation(comp,s)
            if len(o):
                # 方向与主方向同侧；同时只取颗粒云内的块（与背景差异足够大）。
                flip=np.sign(o[:,2]*wind[0]+o[:,3]*wind[1]);o[:,2]*=flip;o[:,3]*=flip
                diff=np.abs(comp.astype('float32')-s.bg).max(2);dsrc=np.abs(comp.astype('float32')-s.source).max(2)
                keep=np.array([diff[int(y),int(x)]>40 and dsrc[int(y),int(x)]>40 for x,y,*_ in o])
                o=o[keep];samples.append(o)
            vis=comp.copy()
            for x,y,dx,dy,w in (o if len(o) else []):
                cv2.line(vis,(int(x-dx*7),int(y-dy*7)),(int(x+dx*7),int(y+dy*7)),(0,255,80),1,cv2.LINE_AA)
            X,Y,X1,Y1=s.rect;mg=round(s.span*.5)
            t=Image.fromarray(vis[max(0,Y-mg):Y1+mg,max(0,X-mg):X1+mg]);t.thumbnail((360,460));ImageDraw.Draw(t).text((3,3),f'{c:.2f}',font=F,fill=(255,40,40));tiles.append(t)
        allv=np.concatenate(samples) if samples else np.zeros((0,5))
        if len(allv)<20:print(name,'样本不足');continue
        fit=fit_source(allv,s,wind)
        mean_dir=math.degrees(math.atan2(-np.mean(allv[:,3]),np.mean(allv[:,2])))%360
        report[name]=dict(fit,mean_direction=mean_dir,recorded_direction=m['direction'])
        print(name,{k:(round(v,3) if isinstance(v,float) else v) for k,v in report[name].items()},flush=True)
        sheet=Image.new('RGB',(sum(t.width+4 for t in tiles),max(t.height for t in tiles)),(14,20,30));xo=0
        for t in tiles:sheet.paste(t,(xo,0));xo+=t.width+4
        sheet.save(OUT/f'{name}-streaks.jpg',quality=90)
    (OUT/'streak-sources.json').write_text(json.dumps(report,ensure_ascii=False,indent=1),'utf-8')


if __name__=='__main__':main()
