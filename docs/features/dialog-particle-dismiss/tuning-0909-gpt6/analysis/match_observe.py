"""原片与模型共用的同口径观测量；只用于离线验收与拟合，不进入运行模型。

- 占用：像素是否不同于干净背景（背景取 5×5 邻域的上下包络，吸收亚像素配准误差）。
- 粒子能量：既不像背景、也不像原位未解除表面的像素（离开原位的颗粒）。
- 表面保留：与源控件纹理的局部一致程度。
- PIV：对粒子能量图做窗口互相关，得到云团的局部位移；与粒子身份无关。
所有量都在同一场景坐标、同一阈值下计算，原片和模型只换输入画面。
"""
from pathlib import Path
import json,sys
import cv2
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from reference_metrics_0926 import valid_pixels

OCC_T=36.          # 与背景包络的最小差值（0–255），低于它视为背景
HANDHELD={'reference-0926-1','reference-0926-2','reference-0926-3','reference-0926-4','reference-0926-7','ironman-up-reference'}
# 手持屏摄只在手机屏幕内有画面（颗粒飞出屏幕即不可见），屏幕外是桌面、手、叠加字幕。
SCREEN={'reference-0926-7':(80,125,640,1080),'reference-0926-1':(80,120,640,1080),'reference-0926-4':(75,120,645,1090),
        'ironman-up-reference':(80,160,645,1110),'reference-0926-2':(80,120,640,1080),'reference-0926-3':(80,120,640,1080)}
BLOCK=8            # 低频比较的块尺寸（场景像素）


class Scene:
    def __init__(self,name):
        d=HERE/'assets'/name
        self.name=name;self.meta=json.loads((d/'scene.json').read_text('utf-8'))
        # 与原生渲染器一致：有修过的背景（残留颗粒、桌面重排遮住的壁纸）就用修过的
        self.bg=np.array(Image.open(d/('background-clean.png' if (d/'background-clean.png').exists() else 'background.png')).convert('RGB')).astype('float32')
        self.source=np.array(Image.open(d/'source.png').convert('RGB')).astype('float32')
        fg=np.array(Image.open(d/'foreground.png').convert('RGBA'))
        self.W,self.H=self.meta['frame'];x,y,x1,y1=self.meta['rect'];self.rect=(x,y,x1,y1)
        self.span=min(x1-x,y1-y)
        k=np.ones((5,5),np.uint8)
        # 手持屏摄按手机平面配准，边框、反光附近仍有数像素的残余错位；背景包络放宽到 9×9。
        kb=np.ones((9,9),np.uint8) if name in HANDHELD else k
        self.bgmax=cv2.dilate(self.bg,kb);self.bgmin=cv2.erode(self.bg,kb)
        self.srcmax=cv2.dilate(self.source,k);self.srcmin=cv2.erode(self.source,k)
        self.valid=valid_pixels(self.meta)
        card=np.zeros((self.H,self.W),bool);card[y:y1,x:x1]=fg[:,:,3]>128
        self.card=card
        # 比较区域：控件外扩 0.45 短边；超出画面的部分自然截断。
        margin=round(self.span*.45);roi=np.zeros((self.H,self.W),bool)
        roi[max(0,y-margin):min(self.H,y1+margin),max(0,x-margin):min(self.W,x1+margin)]=True
        self.roi=roi&self.valid
        # 源纹理与背景几乎相同的像素无法区分“保留”与“解除”。
        self.distinct=np.maximum(np.maximum(self.srcmin-self.bgmax,0),np.maximum(self.bgmin-self.srcmax,0)).max(2)>OCC_T
        self.bh=self.H//BLOCK;self.bw=self.W//BLOCK
        self.threshold=np.full((self.H,self.W),OCC_T,np.float32)
        # 原片中与动画无关、途中会变化的叠加层（状态栏换成水印等）；只作测量遮罩。
        if name=='reference-0926-5':self.roi[:62]=False
        if name in SCREEN:
            sx,sy,sx1,sy1=SCREEN[name];inside=np.zeros_like(self.roi);inside[sy:sy1,sx:sx1]=True;self.roi&=inside
        # 录屏顶部状态栏（录制计时、通知）随时间变化，与动画无关。
        if name=='spiderman-original':self.roi[:40]=False
        # 参考 4 拍摄者的手指在控件左下方进入画面，范围大于原有遮罩。
        if name=='reference-0926-4':self.roi[620:,:440]=False

    def calibrate_noise(self,frames):
        """用动画开始前的原片帧估计控件外每个像素的配准／叠字噪声；原片与模型共用这张阈值图。"""
        noise=np.zeros((self.H,self.W),np.float32)
        for f in frames:noise=np.maximum(noise,self.excess(f,self.bgmax,self.bgmin))
        noise=cv2.dilate(noise,np.ones((3,3),np.uint8))
        outside=~cv2.dilate(self.card.astype(np.uint8),np.ones((9,9),np.uint8)).astype(bool)
        self.threshold=np.where(outside,np.maximum(OCC_T,noise*1.15+6),OCC_T).astype(np.float32)

    def cropped(self,box):
        """拟合时只在比较区域内计算，结果与整幅计算后裁剪相同。"""
        y0,y1,x0,x1=box;c=Scene.__new__(Scene)
        c.__dict__.update(self.__dict__)
        for k in ['bg','source','bgmax','bgmin','srcmax','srcmin','valid','card','roi','distinct','threshold']:
            setattr(c,k,getattr(self,k)[y0:y1,x0:x1])
        c.H,c.W=y1-y0,x1-x0;c.bh,c.bw=c.H//BLOCK,c.W//BLOCK
        return c

    def excess(self,frame,hi,lo):
        f=frame.astype('float32')
        return np.maximum(np.maximum(f-hi,0),np.maximum(lo-f,0)).max(2)

    def source_transform(self,frame):
        """每帧估计源图到画面的全局颜色映射（每通道增益、偏移）。

        录屏里确认框退场、卡片状态恢复会给整张卡片罩上一层均匀的明暗变化（参考 5 约 25 灰阶），
        不是粒子效果；不校正就会把整张卡片误判成已解除。只用与源图相符的像素两轮稳健拟合，
        映射超出合理范围或内点太少时退回恒等。模型帧上它自然接近恒等。
        """
        m=self.card&self.distinct
        if m.sum()<2000:return None
        f=frame[::3,::3][m[::3,::3]].astype('float32');s=self.source[::3,::3][m[::3,::3]]
        use=np.abs(f-s).max(1)<70
        gain=np.ones(3,'float32');bias=np.zeros(3,'float32')
        for _ in range(2):
            if use.sum()<600:return None
            for c in range(3):
                A=np.stack([s[use,c],np.ones(use.sum(),'float32')],1)
                sol=np.linalg.lstsq(A,f[use,c],rcond=None)[0];gain[c],bias[c]=sol
            pred=s*gain+bias;use=np.abs(f-pred).max(1)<22
        if np.any(gain<.6) or np.any(gain>1.25) or np.any(bias<-25) or np.any(bias>70) or use.mean()<.30:return None
        if np.all(np.abs(gain-1)<.02) and np.all(np.abs(bias)<3):return None
        # 只有映射确实比恒等解释了明显更多的像素（整卡均匀变化）才采用，避免后段被颗粒带偏。
        if use.mean()<(np.abs(f-s).max(1)<22).mean()+.10:return None
        return gain,bias

    def observe(self,frame):
        """单帧观测：全分辨率掩码与低频块统计。"""
        eb=self.excess(frame,self.bgmax,self.bgmin)      # 偏离背景
        t=self.source_transform(frame)
        if t is None:es=self.excess(frame,self.srcmax,self.srcmin)    # 偏离原位控件
        else:
            g,b=t;es=self.excess(frame,self.srcmax*g+b,self.srcmin*g+b)
        occ=eb>self.threshold
        particle=occ&(es>OCC_T)                          # 既非背景也非原位表面
        surface=self.card&self.distinct&(es<=OCC_T*.5)&occ
        lum=frame.astype('float32')@np.float32([.299,.587,.114])
        return dict(occ=occ,particle=particle,surface=surface,energy=eb,lum=lum)

    def blocks(self,values,mask=None):
        """按 BLOCK 求均值，只统计 roi 内像素；返回 (均值, 有效像素占比)。"""
        h,w=self.bh*BLOCK,self.bw*BLOCK
        m=self.roi[:h,:w].astype('float32') if mask is None else (self.roi&mask)[:h,:w].astype('float32')
        v=values[:h,:w].astype('float32')
        num=(v*m).reshape(self.bh,BLOCK,self.bw,BLOCK).sum((1,3))
        den=m.reshape(self.bh,BLOCK,self.bw,BLOCK).sum((1,3))
        return num/np.maximum(den,1),den/(BLOCK*BLOCK)


def piv(scene,a,b,window=32,step=16,mask=None):
    """粒子能量图的窗口互相关；返回格点坐标与位移（场景像素）。"""
    ea=cv2.GaussianBlur(np.minimum(scene.excess(a,scene.bgmax,scene.bgmin),scene.excess(a,scene.srcmax,scene.srcmin)),(0,0),.8)
    eb=cv2.GaussianBlur(np.minimum(scene.excess(b,scene.bgmax,scene.bgmin),scene.excess(b,scene.srcmax,scene.srcmin)),(0,0),.8)
    hann=cv2.createHanningWindow((window,window),cv2.CV_32F)
    x,y,x1,y1=scene.rect;s=scene.span;out=[]
    for yy in range(max(0,y-s//2),min(scene.H-window,y1+s//2),step):
        for xx in range(max(0,x-s//2),min(scene.W-window,x1+s//2),step):
            if not scene.roi[yy+window//2,xx+window//2]:continue
            pa=ea[yy:yy+window,xx:xx+window];pb=eb[yy:yy+window,xx:xx+window]
            fa=(pa>OCC_T).mean();fb=(pb>OCC_T).mean()
            if fa<.06 or fb<.06:continue
            (dx,dy),resp=cv2.phaseCorrelate(pa-pa.mean(),pb-pb.mean(),hann)
            if resp<.12 or abs(dx)>window*.4 or abs(dy)>window*.4:continue
            out.append((xx+window/2,yy+window/2,dx,dy,resp,min(fa,fb)))
    return np.array(out,dtype='float32').reshape(-1,6)


def structure_stats(scene,frame):
    """与位置无关的云团结构量：中尺度密度起伏（团块、细缕）与边界复杂度。

    逐像素误差会惩罚任何与原片不同相位的卷曲，只用它拟合会得到平滑、缺少卷曲的结果；
    这些统计量衡量“有多少结构”，不要求结构出现在同一位置。
    """
    o=scene.observe(frame);occ=(o['occ']&~o['surface']&scene.roi).astype('float32')
    d4=cv2.GaussianBlur(occ,(0,0),4);d16=cv2.GaussianBlur(occ,(0,0),16)
    cloud=d16>.06
    if cloud.sum()<400:return None
    band=float(np.mean(np.square(d4-d16)[cloud]))
    mask=(d4>.18).astype(np.uint8)
    edges=cv2.morphologyEx(mask,cv2.MORPH_GRADIENT,np.ones((3,3),np.uint8))
    area=float(mask.sum())
    rough=float(edges.sum()/max(np.sqrt(area),1.)) if area>200 else float('nan')
    return dict(band=band,roughness=rough,area=area/scene.span**2,mean_density=float(d4[cloud].mean()))


def blob_stats(scene,frame,region=None):
    """粒子尺度：粒子掩码的连通块面积中位数与占用率。"""
    o=scene.observe(frame);mask=o['particle']&scene.roi
    if region is not None:mask&=region
    n,lab,st,cen=cv2.connectedComponentsWithStats(mask.astype('uint8'),connectivity=8)
    areas=st[1:,cv2.CC_STAT_AREA]
    return dict(pixels=int(mask.sum()),blobs=int(n-1),area_median=float(np.median(areas)) if len(areas) else 0.,
                area_p75=float(np.percentile(areas,75)) if len(areas) else 0.)
