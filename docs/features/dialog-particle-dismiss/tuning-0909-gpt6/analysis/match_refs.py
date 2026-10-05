"""逐帧对齐用的参考帧库：动画区间内全部独立帧（去除慢放重复帧），手持片按手机平面配准。

输出只供离线测量与验收，运行模型不读取。每帧的时刻取该画面在原片中第一次出现的时刻；
慢放录像把一个真实画面重复 2～5 帧，重复帧不携带新的运动信息。
"""
from pathlib import Path
import argparse,hashlib,json,sys
import cv2
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
CACHE=HERE/'analysis/match-cache'
NEW=[f'reference-0926-{i}' for i in range(1,8)]
OLD=['spiderman-original','ironman','thanos','kobe','ironman-up-reference']
ALL=NEW+OLD
# 与 prepare_reference_suite_0926.SPECS 的编号对应；旧参考的原片本身已是手机平面。
NUMBER={f'reference-0926-{i}':i for i in range(1,8)}|{'spiderman-original':8}
PRE=.08   # 起点之前多取的进度，用于确认开场静止状态


def meta(name):
    return json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))


class Source:
    """按原片时刻取出与场景同一坐标系的画面。"""
    def __init__(self,name):
        self.m=meta(name);self.path=self.m['reference']['path']
        self.registration=None;self.stored=None
        if name in NUMBER:
            from prepare_reference_suite_0926 import SPECS,Registration
            spec=next(s for s in SPECS if s['number']==NUMBER[name])
            if not spec.get('static'):self.registration=Registration(self.path,spec)
        elif name=='ironman-up-reference':
            # 旧流程已把全部原片帧配准进 reference.npy（含重复帧），直接复用。
            d=HERE/'assets'/name;self.stored=(np.load(d/'reference.npy',mmap_mode='r'),np.load(d/'reference-times.npy'))
        self.cap=cv2.VideoCapture(self.path);self.fps=self.cap.get(cv2.CAP_PROP_FPS)

    def frames(self,t0,t1):
        if self.stored is not None:
            # 重复帧仍按原始画面判断；配准后的画面取自已有存储。
            t0=max(t0,float(self.stored[1][0]));t1=min(t1,float(self.stored[1][-1]))
        yield from self.video_frames(t0,t1)

    def video_frames(self,t0,t1):
        self.cap.set(cv2.CAP_PROP_POS_FRAMES,max(0,int(np.floor(t0*self.fps))))
        i=int(self.cap.get(cv2.CAP_PROP_POS_FRAMES))
        while True:
            ok,f=self.cap.read()
            if not ok:return
            t=i/self.fps;i+=1
            if t<t0-1e-6:continue
            if t>t1+1e-6:return
            yield t,f

    def place(self,t,f):
        if self.registration is not None:return self.registration.at(t)
        if self.stored is not None:
            frames,times=self.stored;i=int(np.argmin(abs(times-t)))
            if abs(times[i]-t)>.5/self.fps:raise RuntimeError(f'{self.m["name"]} 缺少 {t:.3f}s 的配准帧')
            return np.asarray(frames[i]).copy()
        rgb=cv2.cvtColor(f,cv2.COLOR_BGR2RGB)
        w,h=self.m['frame']
        if rgb.shape[1]!=w or rgb.shape[0]!=h:raise RuntimeError(f'{self.m["name"]} 画面尺寸与场景不符')
        return rgb


def build(name,force=False):
    out=CACHE/name;out.mkdir(parents=True,exist_ok=True)
    m=meta(name);r=m['reference'];span=r['end']-r['start'];valid=r.get('valid_progress',1.)
    stamp=dict(path=r['path'],start=r['start'],end=r['end'],valid=valid,pre=PRE,version=3)
    info=out/'frames.json'
    if not force and info.exists() and json.loads(info.read_text('utf-8')).get('stamp')==stamp:return
    src=Source(name);t0=r['start']-PRE*span;t1=r['start']+valid*span
    raw=[];stamps=[]
    for t,f in src.frames(t0,t1):
        raw.append(cv2.resize(f,(f.shape[1]//2,f.shape[0]//2),interpolation=cv2.INTER_AREA).astype('int16'));stamps.append(t)
    def change(a,b):
        # 重复帧只有重新编码的噪声，超过 14 灰阶的像素极少（实测 0–25 个）；
        # 真正的新画面即使只有开场一小块在变，也有数百个以上。整幅平均会稀释这种局部变化。
        return int((np.abs(a-b).max(2)>14).sum())
    diff=np.array([10**9]+[change(raw[i],raw[i-1]) for i in range(1,len(raw))])
    # 手持片的重复帧遇到编码刷新时也会有上百个强变化像素，但远少于相机运动造成的变化；
    # 以邻近真实新帧的中位数为尺度（静止屏录开场的真实变化只有数百个像素，仍然保留）。
    new=diff>60
    for i in range(1,len(raw)):
        window=diff[max(1,i-15):i+16];real=window[window>60]
        if len(real)>=3:new[i]&=diff[i]>.15*np.median(real)
    keep=np.flatnonzero(new);del raw
    repeats=np.diff(np.append(keep,len(stamps)))
    first=np.array([stamps[i] for i in keep])
    # 慢放与剪辑变速把均匀采集的画面按不同重复次数展开（例如前段每帧重复 3 次、后段 2 次）。
    # 独立帧序号才与真实采集时间成正比；只用场景的 start/end 两个时刻定标。
    k=np.arange(len(keep),dtype='float64')
    ks=float(np.interp(r['start'],first,k));ke=float(np.interp(r['end'],first,k,right=np.nan))
    if not np.isfinite(ke):
        # 片尾缺失：按有效区间内的平均独立帧间隔外推终点序号。
        inside=first>=r['start'];rate=(k[inside][-1]-ks)/max(first[inside][-1]-r['start'],1e-6)
        ke=ks+rate*(r['end']-r['start'])
    progress_index=(k-ks)/(ke-ks)
    fitted=r['start']+progress_index*span
    kept=[];j=0
    for idx,(t,f) in enumerate(src.frames(t0,t1)):
        if j<len(keep) and idx==keep[j]:kept.append(src.place(t,f));j+=1
    frames=np.lib.format.open_memmap(out/'frames.npy',mode='w+',dtype='uint8',shape=(len(kept),)+kept[0].shape)
    for i,q in enumerate(kept):frames[i]=q
    frames.flush()
    progress=progress_index
    video_progress=(first-r['start'])/span
    np.save(out/'times.npy',fitted);np.save(out/'first-times.npy',first);np.save(out/'progress.npy',progress)
    np.save(out/'video-progress.npy',video_progress);np.save(out/'repeats.npy',repeats)
    shift=progress-video_progress
    info.write_text(json.dumps(dict(stamp=stamp,count=len(kept),fps=src.fps,frames_per_progress=float(ke-ks),
        index_vs_video_shift=dict(max=float(abs(shift[(video_progress>=0)&(video_progress<=valid)]).max())),
        repeat_histogram={str(q):int(v) for q,v in zip(*np.unique(repeats,return_counts=True))},
        note='进度按独立帧序号换算（真实采集时间），只用 start/end 两个时刻定标；video-progress 为原片时间线性换算值。'),ensure_ascii=False,indent=2),'utf-8')
    print(name,'独立帧',len(kept),'每单位进度',round(ke-ks,1),'帧','与原片时间换算的最大偏差',
          round(float(abs(shift[(video_progress>=0)&(video_progress<=valid)]).max()),3),
          '重复分布',{int(q):int(v) for q,v in zip(*np.unique(repeats,return_counts=True))},flush=True)


class RefFrames:
    def __init__(self,name):
        d=CACHE/name
        self.frames=np.load(d/'frames.npy',mmap_mode='r');self.times=np.load(d/'times.npy');self.progress=np.load(d/'progress.npy')
        self.meta=meta(name)
    def __len__(self):return len(self.progress)
    def nearest(self,p):
        i=int(np.argmin(abs(self.progress-p)));return i,np.asarray(self.frames[i])


def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=ALL);p.add_argument('--force',action='store_true');a=p.parse_args()
    for name in a.scenes:build(name,a.force)


if __name__=='__main__':main()
