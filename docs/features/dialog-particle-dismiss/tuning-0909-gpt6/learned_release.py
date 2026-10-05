"""由起点几何预测释放场的共同残差函数；不接收素材名，不存逐素材输出。

实验权重由离线训练生成。运行时仅依赖 NumPy/SciPy；不读取参考动画。
"""
import json, math
import numpy as np
from scipy.ndimage import map_coordinates
from unified_model import release_components, refine_release, smooth


def canonical_sites(width,height,direction):
    """已有共同场的标准初始布局，随方向一起旋转；所有素材使用同一定义。"""
    from dataclasses import dataclass
    @dataclass(frozen=True)
    class Event:
        x: float
        y: float
        start: float
    a=math.radians(122.-direction);c,s=math.cos(a),math.sin(a)
    return [Event(.5+c*(x-.5)-s*(y-.5),.5+s*(x-.5)+c*(y-.5),t)
            for x,y,t in [(.62,1.,.02),(1.,.62,.11),(0.,0.,.12)]]


def features(nx,ny,width,height,direction,seed,sites,panel=0.,rotation_aligned=False):
    native,_=release_components(nx,ny,direction,width,height,seed)
    native=refine_release(native,width,height,direction,panel)
    yy,xx=np.mgrid[:ny,:nx].astype('float32')
    x=(xx+.5)/nx;y=(yy+.5)/ny;span=min(width,height)
    wx,wy=math.cos(math.radians(direction)),-math.sin(math.radians(direction))
    sx=np.array([s.x for s in sites],np.float32);sy=np.array([s.y for s in sites],np.float32)
    st=np.array([s.start for s in sites],np.float32)
    dx=(x[:,:,None]-sx)*width/span;dy=(y[:,:,None]-sy)*height/span
    distance=np.sqrt(dx*dx+dy*dy+.0001)
    along=dx*wx+dy*wy;across=-dx*wy+dy*wx
    position=map_coordinates(native,[sy*ny-.5,sx*nx-.5],order=1,mode='nearest')
    compatibility=np.abs(position-st).mean()
    if rotation_aligned:
        along_position=(x-.5)*width/span*wx+(y-.5)*height/span*wy
        across_position=-(x-.5)*width/span*wy+(y-.5)*height/span*wx
        px=.5+along_position;py=.5+across_position
        extent_a=abs(wx)*width/span+abs(wy)*height/span
        extent_b=abs(wy)*width/span+abs(wx)*height/span
        invariants=[extent_a,extent_b,abs(width-height)/span,(width*height)/(span*span)]
    else:px,py=x,y;invariants=[wx,wy,width/span-1,height/span-1]
    values=[native,px,py,px*(1-px),py*(1-py),px*py,
            *(np.full_like(x,v) for v in invariants),
            np.full_like(x,compatibility),np.full_like(x,len(sites)/4)]
    # 多尺度软聚合保持对起点顺序不变；不按最近起点切换离散分支。
    for rate in [2.,6.,16.]:
        cost=distance+st
        weights=np.exp(-rate*(cost-cost.min(2,keepdims=True)));weights/=weights.sum(2,keepdims=True)
        moments=[along*along,across*across,along*across] if rotation_aligned else [dx*dx,dy*dy,dx*dy]
        for a in [distance,along,across,st+np.zeros_like(dx),*moments]:values.append((weights*a).sum(2))
    return np.stack(values,-1).astype('float32'),native


class Weights(list):
    rotation_aligned=False
    minimum_sites=2
    clock_knots=None
    clock_offsets=None
    spatial_coefficients=None
    spatial_limit=.06


def load_weights(path):
    data=json.loads(path.read_text('utf-8'))
    result=Weights((np.asarray(a['weight'],np.float32),np.asarray(a['bias'],np.float32)) for a in data['layers'])
    result.rotation_aligned=data.get('rotation_aligned',False)
    result.minimum_sites=data.get('minimum_sites',2)
    result.clock_knots=data.get('clock_knots')
    result.clock_offsets=data.get('clock_offsets')
    result.spatial_coefficients=data.get('spatial_coefficients')
    result.spatial_limit=float(data.get('spatial_limit',.06))
    return result


def spatial_basis(nx,ny,width,height,direction,sites,native):
    """低频传播校正；仅依赖几何与起点，随方向旋转、对起点排列不变。"""
    yy,xx=np.mgrid[:ny,:nx].astype('float32')
    x=(xx+.5)/nx;y=(yy+.5)/ny;span=min(width,height)
    wx,wy=math.cos(math.radians(direction)),-math.sin(math.radians(direction))
    ea=abs(wx)*width/span+abs(wy)*height/span
    eb=abs(wy)*width/span+abs(wx)*height/span
    a=2*((x-.5)*width/span*wx+(y-.5)*height/span*wy)/ea
    b=2*(-(x-.5)*width/span*wy+(y-.5)*height/span*wx)/eb
    sx=np.asarray([s.x for s in sites]);sy=np.asarray([s.y for s in sites]);st=np.asarray([s.start for s in sites])
    dx=(x[...,None]-sx)*width/span;dy=(y[...,None]-sy)*height/span
    distance=np.sqrt(dx*dx+dy*dy+.0001);cost=distance+st
    soft=np.exp(-6*(cost-cost.min(-1,keepdims=True)));soft/=soft.sum(-1,keepdims=True)
    d=(soft*distance).sum(-1)-.4
    qa=(soft*(dx*wx+dy*wy)).sum(-1)
    qb=(soft*(-dx*wy+dy*wx)).sum(-1)
    start=(soft*st).sum(-1)-.1
    values=[np.ones_like(a),a,b,a*a-1/3,b*b-1/3,a*b,a*(a*a-.6),b*(b*b-.6),
            (a*a-1/3)*b,(b*b-1/3)*a,d,qa,qb,start,qa*qb,d*(native-.4)]
    envelope=np.sin(np.pi*np.clip(native/.90,0,1))
    return (np.stack(values,-1)*envelope[...,None]).astype('float32')


def spatial_correction(nx,ny,width,height,direction,sites,native,weights):
    coefficients=getattr(weights,'spatial_coefficients',None)
    if coefficients is None:return 0.
    basis=spatial_basis(nx,ny,width,height,direction,sites,native)
    limit=weights.spatial_limit
    return limit*np.tanh((basis@np.asarray(coefficients,np.float32))/limit)


def geometric_arrival(nx,ny,width,height,direction,sites):
    """超出参考几何范围时采用连续传播；不让条件函数的外推产生大块同步释放区。"""
    yy,xx=np.mgrid[:ny,:nx].astype('float32')
    x=(xx+.5)/nx;y=(yy+.5)/ny;span=min(width,height)
    a=math.radians(direction);wx,wy=math.cos(a),-math.sin(a)
    times=[]
    for site in sites:
        dx=(x-site.x)*width/span;dy=(y-site.y)*height/span
        along=dx*wx+dy*wy;across=-dx*wy+dy*wx
        distance=np.sqrt((along/.94)**2+(across/1.06)**2+.0001)
        times.append(site.start+.58*distance+.024*along)
    values=np.stack(times)
    low=values.min(0)
    # 平滑最小值不会在两个起点的等距线上切换离散分支。
    arrival=low-.035*np.log(np.exp(-(values-low)/.035).sum(0))
    first=float(arrival.min());last=float(arrival.max())
    return (.014+.72*np.maximum((arrival-first)/max(last-first,1e-6),0)**1.16).astype('float32')


def predict(nx,ny,width,height,direction,seed,sites,weights,panel=0.,spatial=True):
    aligned=getattr(weights,'rotation_aligned',False)
    f,native=features(nx,ny,width,height,direction,seed,sites,panel,aligned)
    canonical,_=features(nx,ny,width,height,direction,seed,canonical_sites(width,height,direction),panel,aligned)
    def evaluate(f):
        a=f.reshape(-1,f.shape[-1])
        for i,(w,b) in enumerate(weights):
            a=a@w.T+b
            if i<len(weights)-1:a=np.tanh(a)
        return np.tanh(a[:,0]).reshape(ny,nx)
    # 同一个函数的条件差；标准布局保留共同场，仅叠加所有布局共用的时间校准。
    residual=.65*(evaluate(f)-evaluate(canonical))
    clock_correction=0.
    if getattr(weights,'clock_knots',None) is not None:
        clock_correction=np.interp(native,weights.clock_knots,weights.clock_offsets).astype('float32')
    correction=spatial_correction(nx,ny,width,height,direction,sites,native,weights) if spatial else 0.
    learned=np.clip(native+clock_correction+residual+correction,.002,.84)
    # 现有有效参考仅覆盖近方形、2～4 个起点。外推置信度只由几何输入决定，
    # 与素材、图片内容、文件名无关；长条弹窗和单起点使用有界传播先验。
    aspect=max(width,height)/min(width,height)
    confidence=1-float(smooth((aspect-1.10)/.35))
    confidence*=float(smooth(len(sites)-getattr(weights,'minimum_sites',2)+1))*float(1-smooth((len(sites)-4)/2))
    if confidence<1:
        geometric=geometric_arrival(nx,ny,width,height,direction,sites)
        anchor=geometric_arrival(nx,ny,width,height,direction,canonical_sites(width,height,direction))
        difference=float(np.sqrt(np.mean((geometric-anchor)**2)))
        change=1.-math.exp(-(difference/.08)**2)
        prior=native+change*(geometric-native)
        learned=confidence*learned+(1-confidence)*prior
    return learned.astype('float32')
