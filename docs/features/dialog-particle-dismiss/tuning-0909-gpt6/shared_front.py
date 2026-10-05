"""共同传播核实验：每个起点使用同一函数，时间随距离保持非负。

输入只有空间、方向和起点事件；不接收素材名、图像内容或参考帧。
"""
import math
import numpy as np
from learned_release import canonical_sites
from unified_model import release_components,refine_release


def site_features(nx,ny,width,height,direction,sites):
    yy,xx=np.mgrid[:ny,:nx].astype('float32')
    span=min(width,height);x=(xx+.5)/nx;y=(yy+.5)/ny
    wx,wy=math.cos(math.radians(direction)),-math.sin(math.radians(direction))
    values=[];radii=[];starts=[]
    for s in sites:
        dx=(x-s.x)*width/span;dy=(y-s.y)*height/span
        along=dx*wx+dy*wy;cross=-dx*wy+dy*wx
        r=np.sqrt(dx*dx+dy*dy+1e-5)
        # 起点到四边的距离让边缘发起和内部发起遵循不同的连续条件。
        constants=[s.x,1-s.x,s.y,1-s.y,wx,wy,width/span-1,height/span-1]
        local=[r,along,cross,along/r,cross/r,along*cross,along*along,cross*cross]
        local.extend(np.full_like(x,v) for v in constants)
        values.append(np.stack(local,-1));radii.append(r);starts.append(np.full_like(x,s.start))
    return np.stack(values,2),np.stack(radii,2),np.stack(starts,2)


def evaluate(f,r,start,weights):
    a=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights):
        a=a@w.T+b
        if i<len(weights)-1:a=np.tanh(a)
    # 有界的传播时长；强制起点本身等于事件开始时间，避免模型造出任意早发孤岛。
    delay=.16+.95/(1+np.exp(-np.clip(a[:,0],-15,15)))
    t=start+r*delay.reshape(r.shape)
    m=t.min(-1);tau=.018
    return m-tau*np.log(np.exp(-(t-m[...,None])/tau).sum(-1))


def predict(nx,ny,width,height,direction,seed,sites,weights,panel=0.):
    native,_=release_components(nx,ny,direction,width,height,seed)
    native=refine_release(native,width,height,direction,panel)
    a=site_features(nx,ny,width,height,direction,sites)
    c=site_features(nx,ny,width,height,direction,canonical_sites(width,height,direction))
    return np.clip(native+evaluate(*a,weights)-evaluate(*c,weights),.002,.84).astype('float32')
