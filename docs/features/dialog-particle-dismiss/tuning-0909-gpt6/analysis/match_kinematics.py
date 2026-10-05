"""原片运动学：表面解除时刻 + PIV 速度场 + 拉格朗日示踪，得到“释放后年龄 → 位移”。

只用于离线建模与验收。示踪点从各自的解除时刻出发，沿同一场景坐标内插的 PIV 速度积分；
PIV 只在粒子能量足够的窗口有效，缺测处用邻近有效样本，超出 2 个窗口仍无样本即停止该示踪点。
"""
from pathlib import Path
import argparse,json,math,sys
import cv2
import numpy as np
from scipy.ndimage import gaussian_filter,median_filter

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_refs import RefFrames
from match_observe import Scene,piv,OCC_T

OUT=HERE/'analysis/match-cache/kinematics'
STEP=12


def surface_arrival(scene,frames,progress,valid):
    """每个控件像素最后一次仍是原位表面的进度（3 帧中值去噪，单调化）。"""
    x,y,x1,y1=scene.rect;shape=(y1-y,x1-x)
    use=[k for k,p in enumerate(progress) if -.02<=p<=valid]
    stack=[]
    for k in use:
        f=np.asarray(frames[k]);es=scene.excess(f,scene.srcmax,scene.srcmin)[y:y1,x:x1]
        stack.append(gaussian_filter((es<=OCC_T*.6).astype('float32'),2.)>.5)
    stack=median_filter(np.array(stack),size=(3,1,1))
    # 开场可能有确认框遮挡，不要求第一帧就像源图；取最后一次仍是原位表面之后的那一帧。
    n=len(use);seen=stack.any(0)
    last=n-1-np.argmax(stack[::-1],axis=0)
    ps=np.array([progress[k] for k in use])
    arrival=np.where(last>=n-1,np.nan,ps[np.clip(last+1,0,n-1)])
    distinct=scene.distinct[y:y1,x:x1]&scene.card[y:y1,x:x1]
    arrival[~distinct|~seen]=np.nan
    return arrival


def velocity_fields(scene,ref,valid,window=32,step=STEP,span=3):
    """独立帧对 (k, k+span) 的 PIV，按中点进度记录速度（像素/进度）。"""
    fields=[]
    ks=[k for k,p in enumerate(ref.progress) if -.01<=p<=valid]
    # 首尾相接、互不重叠的帧对，位移才能逐段累加。
    for k in ks[::span]:
        j=k+span
        if j>=len(ref) or ref.progress[j]>valid+1e-6:break
        dt=float(ref.progress[j]-ref.progress[k])
        if dt<=1e-4:continue
        v=piv(scene,np.asarray(ref.frames[k]),np.asarray(ref.frames[j]),window=window,step=step)
        fields.append(dict(t=float(ref.progress[k]+ref.progress[j])/2,t0=float(ref.progress[k]),dt=dt,v=v))
    return fields


def grid_field(scene,v,step=STEP):
    """散点矢量 -> 规则格点（NaN 为缺测）。"""
    gw=scene.W//step+1;gh=scene.H//step+1
    g=np.full((gh,gw,2),np.nan,np.float32)
    for x,y,dx,dy,resp,fill in v:
        g[int(round((y-step/2)/step)),int(round((x-step/2)/step))]=(dx,dy)
    return g


def sample(g,x,y,step=STEP,reach=2):
    gx=(x-step/2)/step;gy=(y-step/2)/step;i=int(round(gy));j=int(round(gx))
    best=None
    for r in range(reach+1):
        acc=[];w=[]
        for di in range(-r,r+1):
            for dj in range(-r,r+1):
                a,b=i+di,j+dj
                if 0<=a<g.shape[0] and 0<=b<g.shape[1] and not math.isnan(g[a,b,0]):
                    d=math.hypot(gy-a,gx-b);acc.append(g[a,b]);w.append(1/(.5+d))
        if acc:
            w=np.array(w);return (np.array(acc)*w[:,None]).sum(0)/w.sum()
    return None


def track(scene,arrival,fields,seed_step=10):
    """示踪点在解除时刻出发，逐段积分 PIV 位移。"""
    x0,y0,_,_=scene.rect;tr=[]
    times=np.array([f['t0'] for f in fields]);grids=[grid_field(scene,f['v']) for f in fields]
    for yy in range(seed_step//2,arrival.shape[0],seed_step):
        for xx in range(seed_step//2,arrival.shape[1],seed_step):
            T=arrival[yy,xx]
            if not np.isfinite(T):continue
            px,py=x0+xx+.5,y0+yy+.5;path=[(T,0.,0.)]
            start=int(np.searchsorted(times,T))
            for f,g in zip(fields[start:],grids[start:]):
                d=sample(g,px,py)
                if d is None:break
                px+=float(d[0]);py+=float(d[1])
                path.append((f['t0']+f['dt'],px-(x0+xx+.5),py-(y0+yy+.5)))
            tr.append(dict(x=xx/arrival.shape[1],y=yy/arrival.shape[0],T=float(T),path=path))
    return tr


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','reference-0926-5','spiderman-original','reference-0926-6'])
    a=ap.parse_args();OUT.mkdir(parents=True,exist_ok=True)
    for name in a.scenes:
        s=Scene(name);R=RefFrames(name);valid=s.meta['reference'].get('valid_progress',1.)
        arrival=surface_arrival(s,R.frames,R.progress,valid)
        np.save(OUT/f'{name}-arrival.npy',arrival)
        print(name,'解除时刻测得',int(np.isfinite(arrival).sum()),'像素，中位',round(float(np.nanmedian(arrival)),3),flush=True)
        fields=velocity_fields(s,R,valid)
        np.save(OUT/f'{name}-piv.npy',np.array([dict(t=f['t'],t0=f['t0'],dt=f['dt'],v=f['v']) for f in fields],dtype=object),allow_pickle=True)
        print(name,'PIV 帧对',len(fields),flush=True)


if __name__=='__main__':main()
