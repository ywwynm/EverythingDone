"""与 Android 独立执行同一建材规则；接口不接收场景名、起点标注或拟合配置。"""
from pathlib import Path
import math
import numpy as np
from scipy.ndimage import gaussian_filter,map_coordinates

ROOT=next(p for p in Path(__file__).resolve().parents if (p/'gradlew.bat').is_file())
SHARED=ROOT/'shared/particle-dismiss'

def load_rules():
    values={}
    for line in (SHARED/'rules.properties').read_text('utf-8').splitlines():
        if line.strip() and not line.startswith('#'):
            k,v=line.split('=',1);values[k]=v if k=='release_origins' else float(v)
    return values

RULES=load_rules()
RELEASE=np.fromfile(SHARED/'common-release.f32',dtype='<f4').reshape(int(RULES['release_height']),int(RULES['release_width']))

def smooth(value):
    a=np.clip(value,0,1);return a*a*(3-2*a)

def random_values(count,seed):
    # 使用相同的 32 位整数运算，消除 NumPy 与 Java 随机序列差异。
    salt=(int(seed)^(int(seed)>>32))&0xffffffff
    x=np.arange(count*4,dtype=np.uint32)+np.uint32(salt)
    x=(x^(x>>16))*np.uint32(0x7feb352d)
    x=(x^(x>>15))*np.uint32(0x846ca68b)
    x=x^(x>>16)
    return ((x>>8).astype('float32')/np.float32(16777216)).reshape(count,4)

def field_rotation(direction,width,height):
    radians=math.radians(direction)
    return math.atan2(math.sin(radians)/height,math.cos(radians)/width)-math.pi/2


def field_geometry(width,height):
    """长轴只温和放大局部流动尺度，剩余长度由重叠的局部解除区域覆盖。"""
    span=min(width,height);ratio=max(width,height)/span
    effective=ratio if ratio<=1.2 else 1.2+.15*(1-math.exp(-(ratio-1.2)/.15))
    return (span*effective if width>height else width,
            span*effective if height>=width else height,
            float(smooth((ratio-1.2)/.6)),float(height>=width))


def flow_anchors(x,y,width,height,direction,params):
    """权重固定在材料出生位置，避免粒子运动时在局部流场间跳换。"""
    fw,fh,strength,axis=field_geometry(width,height);span=min(width,height)
    coordinate=(y if axis else x)/(span*.96);index=np.floor(coordinate)
    weight=smooth(coordinate-index);angle=math.radians(direction)
    cross=np.array([math.sin(angle),math.cos(angle)])
    def anchor(i):
        along=i*(span*.96)*strength
        sideways=span*.16*strength*np.sin(i*2.4+float(params[10]))
        return ((0 if axis else along)+cross[0]*sideways,
                (along if axis else 0)+cross[1]*sideways)
    return anchor(index),anchor(index+1),weight


def touch_gap_from_point(x,y,width,height):
    """沿中心到触点的射线，测控件边缘之外的距离，以短边为单位。"""
    dx=x-width*.5;dy=y-height*.5;distance=math.hypot(dx,dy)
    if distance<1e-6:return 0.
    ux=abs(dx/distance);uy=abs(dy/distance)
    edge=min(width/(2*max(ux,1e-9)),height/(2*max(uy,1e-9)))
    return max(0.,(distance-edge)/min(width,height))


def touch_strength_from_point(x,y,width,height,bounds):
    """真实触点在同一方向可用背景中的相对位置；不把屏幕边缘用于粒子碰撞。"""
    dx=x-width*.5;dy=y-height*.5;distance=math.hypot(dx,dy)
    if distance<1e-6:return 0.
    ux,uy=dx/distance,dy/distance;left,top,right,bottom=bounds
    edge=min(width/(2*max(abs(ux),1e-9)),height/(2*max(abs(uy),1e-9)))
    limits=[]
    if abs(ux)>1e-9:limits.append(((right if ux>0 else left)-width*.5)/ux)
    if abs(uy)>1e-9:limits.append(((bottom if uy>0 else top)-height*.5)/uy)
    available=max(min(limits)-edge,1.)
    return float(np.clip((distance-edge)/available,0,1))


def variation(seed):
    """一次动画只采样一次；系数先量化到 GPU 使用的 float32，两端保持一致。"""
    r=random_values(5,int(seed)^0x6a09e667).astype('float64').ravel()
    values=np.array([
        1+(r[0]*2-1)*RULES['variation_stretch'],1+(r[1]*2-1)*RULES['variation_stretch'],
        (r[2]*2-1)*RULES['variation_shear'],(r[3]*2-1)*RULES['variation_shear'],
        (r[4]*2-1)*RULES['variation_offset'],(r[5]*2-1)*RULES['variation_offset']*.65,
        (.45+.55*r[6])*RULES['variation_bend'],(.45+.55*r[7])*RULES['variation_bend'],
        3.6+2.1*r[8],3.6+2.1*r[9],2*math.pi*r[10],2*math.pi*r[11],
        (r[12]*2-1)*RULES['variation_clock'],(r[13]*2-1)*RULES['variation_clock_shape'],
        (r[14]*2-1)*RULES['variation_release_delay'],(r[15]*2-1)*RULES['variation_release_delay']
    ],dtype='float64')
    # 连贯卷边需要保留其供料区域；独立起点样本可有更大的坐标与时序变化。
    coherence_sample=float(random_values(1,int(seed)^0x510e527f)[0,3])
    strength=.08+.92*float(smooth((coherence_sample-.30)/.55))
    values[:2]=1+(values[:2]-1)*strength
    values[2:8]*=strength;values[12:]*=strength
    # 对风向横轴作可选镜像，改变先从哪侧破开，总体消逝方向保持不变。
    if r[16]<.5:values[[0,2,4,6]]*=-1
    return values.astype('float32')


def warp_coordinates(x,y,v):
    a=v.astype('float64');x=x-.5;y=y-.5
    return (.5+a[0]*x+a[2]*y+a[4]+a[6]*np.sin(a[9]*y+a[10]),
            .5+a[1]*y+a[3]*x+a[5]+a[7]*np.sin(a[8]*x+a[11]))


def clock_time(t,v):
    return t+t*(1-t)*(float(v[12])+float(v[13])*(2*t-1))


def clock_rate(t,v):
    return 1+float(v[12])*(1-2*t)+float(v[13])*(-6*t*t+6*t-1)


def inverse_clock(value,v):
    t=np.asarray(value,dtype='float64').copy()
    for _ in range(4):t=np.clip(t-(clock_time(t,v)-value)/clock_rate(t,v),0,1)
    return t


def release_patches(width,height,seed):
    """错开的有限起始区域；位置属于控件，不把整个释放轮廓随风向旋转。"""
    span=min(width,height);length=max(width,height)
    count=max(2,math.ceil(length/span*1.4))
    if random_values(1,int(seed)^0x243f6a88)[0,0]>.70:count+=1
    r=random_values(count+1,int(seed)^0x243f6a88).astype('float64').ravel()
    perimeter=2*(width+height);patches=[]
    for i in range(count):
        j=4+i*4
        position=((r[0]+(i+.85*(r[j]-.5))/count)%1)*perimeter
        if position<width:ox,oy=position,0.
        elif position<width+height:ox,oy=width,position-width
        elif position<2*width+height:ox,oy=2*width+height-position,height
        else:ox,oy=0.,perimeter-position
        # 中心略在边缘之外，从局部边缘解除，避免规则的内部圆洞。
        inflate=1.015+.025*r[j+1]
        ox=width*.5+(ox-width*.5)*inflate;oy=height*.5+(oy-height*.5)*inflate
        delay=.02+.25*r[j+2]+.06*i
        angle=2*math.pi*r[j+3]
        patches.append((ox,oy,delay,math.cos(angle),math.sin(angle),.65+.50*r[j]))
    return patches


def patch_release(x,y,width,height,direction,seed,detail):
    """局部前沿平滑相遇，保留共同释放场的小尺度非规则起伏。"""
    span=min(width,height);angle=math.radians(direction)
    wind=np.array([math.cos(angle),-math.sin(angle)])
    arrival=np.full_like(x,10.,dtype='float64')
    for ox,oy,delay,c,s,scale in release_patches(width,height,seed):
        dx=(x-ox)/span;dy=(y-oy)/span
        u=c*dx+s*dy;v=-s*dx+c*dy
        distance=np.sqrt((u/scale)**2+(v/(scale*.80))**2+.0004)
        local=delay+.62*distance+.025*(dx*wind[0]+dy*wind[1])
        h=np.maximum(.055-np.abs(arrival-local),0)/.055
        arrival=np.minimum(arrival,local)-h*h*.055*.25
    arrival+=.035*np.tanh((detail-.38)/.18)
    low=float(arrival.min());high=float(arrival.max())
    return .024+.72*((arrival-low)/max(high-low,1e-6))**1.45


def release_locality(width,height,direction,seed):
    """同一连续规则覆盖连贯卷边和分离局部起点，不按素材选择运动。"""
    angle=math.radians(direction)
    ux,uy=abs(math.cos(angle))/width,abs(math.sin(angle))/height
    corner=2*min(ux,uy)/max(ux+uy,1e-8)
    sampled=random_values(1,int(seed)^0x510e527f)[0].astype('float64')
    geometry=(1-corner)**1.5*(.42+.14*sampled[0])
    random_locality=.72*float(smooth((sampled[3]-.30)/.55))
    return geometry+(1-geometry)*random_locality


def release_components(nx,ny,direction,width=None,height=None,seed=0):
    """释放与速度共用坐标变换；矩形的长宽比参与方向换算。"""
    width=nx if width is None else width;height=ny if height is None else height
    fw,fh,strength,axis=field_geometry(width,height)
    a=field_rotation(direction,fw,fh);c,s=math.cos(a),math.sin(a)
    y,x=np.mgrid[:ny,:nx].astype(float);x=((x+.5)/nx-.5)*width;y=((y+.5)/ny-.5)*height
    params=variation(seed)
    low=RULES['field_min'];size=RULES['field_size']
    anchors0,anchors1,weight=flow_anchors(x,y,width,height,direction,params)
    def local_field(anchor):
        xx=(x-anchor[0])/fw;yy=(y-anchor[1])/fh
        u=.5+c*xx-s*yy;v=.5+s*xx+c*yy
        delay=float(params[14])*np.sin(3.2*u+float(params[10]))+float(params[15])*np.sin(3.6*v+float(params[11]))
        u,v=warp_coordinates(u,v,params)
        field=map_coordinates(RELEASE,[(v-low)/size*RELEASE.shape[0]-.5,(u-low)/size*RELEASE.shape[1]-.5],order=1,mode='nearest')
        return field,delay
    field,delay=local_field(anchors0)
    if strength>0:
        second,second_delay=local_field(anchors1)
        field=field*(1-weight)+second*weight;delay=delay*(1-weight)+second_delay*weight
    original=inverse_clock(field,params)
    base=np.clip(original+delay,.008,.78)
    local=patch_release(x+width*.5,y+height*.5,width,height,direction,seed,base)
    released=base+release_locality(width,height,direction,seed)*(local-base)
    return released.astype('float32'),(released-original).astype('float32')


def release_field(nx,ny,direction,width=None,height=None,seed=0):
    return release_components(nx,ny,direction,width,height,seed)[0]

def panel_material(foreground):
    """高占比原色识别面板；并列色簇由首次出现位置决定，保持通道置换对称。"""
    h,w=foreground.shape[:2]
    stride=max(1,math.ceil(math.sqrt(w*h/65536)))
    sampled=foreground[::stride,::stride].reshape(-1,4)
    opaque=sampled[sampled[:,3]==255,:3].astype('int32')
    if not len(opaque):return np.zeros(3,dtype='float32'),0.,0.
    bins=opaque//16
    keys=bins[:,0]*256+bins[:,1]*16+bins[:,2]
    counts=np.bincount(keys,minlength=4096)
    key=keys[np.flatnonzero(counts[keys]==counts.max())[0]]
    selected=opaque[keys==key]
    color=(selected.mean(axis=0)/255).astype('float32')
    fraction=np.float32(len(selected)/len(opaque))
    weight=float(smooth(np.float32((fraction-RULES['panel_min_fraction'])/(RULES['panel_full_fraction']-RULES['panel_min_fraction']))))
    return color,weight,float(fraction)


def content_weights(rgb,panel,confidence):
    chroma=smooth((rgb.max(axis=1)-rgb.min(axis=1)-.30)/.42)
    difference=np.abs(rgb-panel).max(axis=1)
    contrast=smooth((difference-np.float32(RULES['content_distance_start']))/np.float32(RULES['content_distance_span']))
    return (chroma+(contrast-chroma)*np.float32(confidence)).astype('float32')


def refine_release(field,width,height,direction,panel_weight):
    """前沿接到背向自由边界；晚释放的内部原表面保留连贯交接。"""
    ny,nx=field.shape
    yy,xx=np.mgrid[:ny,:nx]
    x=(xx+.5)/nx*width;y=(yy+.5)/ny*height
    span=min(width,height);ex=np.minimum(x,width-x);ey=np.minimum(y,height-y)
    gy,gx=np.gradient(gaussian_filter(field,3),height/ny,width/nx)
    norm=np.maximum(np.hypot(gx,gy),1e-8)
    wind=np.array([np.cos(np.deg2rad(direction)),-np.sin(np.deg2rad(direction))])
    against=np.maximum(
        smooth(-np.where(x<width*.5,-1,1)*wind[0]/.65)*np.exp(-ex/(span*.14)),
        smooth(-np.where(y<height*.5,-1,1)*wind[1]/.65)*np.exp(-ey/(span*.14)))
    against*=smooth(((gx*wind[0]+gy*wind[1])/norm-.40)/.50)
    interior=1-np.exp(-np.minimum(ex,ey)/(span*.10))
    delta=.025*(1.-.65*panel_weight)*interior*smooth((field-.40)/.17)-.065*against*smooth((field-.23)/.25)
    # 只修正材料进入流动的时刻，原随机流场时钟保持不变。
    return (field+delta).astype('float32')


def peel_compression(normalx,normaly,cellx,celly,span,direction):
    """估计剥离场的局部压缩，平移、转动及展开不产生额外收减。"""
    wind=np.array([math.cos(math.radians(direction)),-math.sin(math.radians(direction))])
    peel=-np.stack([normalx,normaly],axis=-1)
    axial=peel@wind
    peel-=np.minimum(axial,0)[...,None]*wind
    peel-=np.maximum(axial,0)[...,None]*wind*.82
    dx=np.gradient(peel,cellx,axis=1);dy=np.gradient(peel,celly,axis=0)
    a=dx[:,:,0];b=(dx[:,:,1]+dy[:,:,0])*.5;d=dy[:,:,1]
    lowest=(a+d)*.5-np.sqrt(((a-d)*.5)**2+b*b)
    return (gaussian_filter(np.maximum(-lowest,0),1)*span).astype('float32')


def materials(width,height,foreground,direction,seed,cell_px=None):
    panel,confidence,panel_fraction=panel_material(foreground)
    copy_limit=int(RULES['content_copies'])
    budget=1+copy_limit*confidence*(1-panel_fraction)
    cell=max(RULES['cell'] if cell_px is None else cell_px,math.sqrt(width*height*budget/(RULES['max_cells']-1000)))
    nx=max(2,math.ceil(width/cell));ny=max(2,math.ceil(height/cell));n=nx*ny
    cellx=np.float32(width/nx);celly=np.float32(height/ny)
    ph,pw=foreground.shape[:2]
    stride=max(1,math.ceil(math.sqrt(pw*ph/65536)))
    sampled=foreground[::stride,::stride];opaque=sampled[:,:,3]>242
    fraction=float(np.mean(sampled[:,:,:3].min(axis=2)[opaque]>229)) if opaque.any() else 0.
    body=float(smooth(np.float32((fraction-.30)/.35)))
    field,offset_times=release_components(nx,ny,direction,width,height,seed)
    field=refine_release(field,width,height,direction,confidence)
    gy,gx=np.gradient(gaussian_filter(field,3),celly,cellx)
    length=np.maximum(np.hypot(gx,gy),1e-6)
    normalx=gaussian_filter(gx/length,7);normaly=gaussian_filter(gy/length,7)
    compression=peel_compression(normalx,normaly,cellx,celly,min(width,height),direction)
    rand=random_values(n,seed)
    born=np.maximum(field.ravel()+np.float32(RULES['release_spread']+RULES['white_spread']*body)*(rand[:,3]-.5),.001)
    values=np.zeros((n,12),dtype='float32')
    y,x=np.mgrid[:ny,:nx]
    values[:,0]=(x.ravel().astype('float32')+.5)*cellx;values[:,1]=(y.ravel().astype('float32')+.5)*celly
    values[:,2]=born;values[:,3]=np.arange(n)
    values[:,4]=normalx.ravel();values[:,5]=normaly.ravel();values[:,7]=offset_times.ravel();values[:,8:]=rand
    px=np.clip((values[:,0]/width*pw).astype(int),0,pw-1);py=np.clip((values[:,1]/height*ph).astype(int),0,ph-1)
    rgb=foreground[py,px,:3].astype('float32')/255
    content=content_weights(rgb,panel,confidence)
    content*=foreground[py,px,3].astype('float32')/np.float32(255)
    # 密铺原片不减员；只对真实不透明内容补充微片，透明边缘不复制。
    extra=[]
    all_random=random_values(n*(copy_limit+1),seed)
    amount=content*np.float32(copy_limit*confidence)
    for layer in range(1,copy_limit+1):
        ids=np.arange(n)+layer*n
        selected=(all_random[ids,0]<amount-(layer-1))&(foreground[py,px,3]==255)
        extra.extend(ids[selected].tolist())
    capacity=max(0,int(RULES['max_cells'])-n)
    if len(extra)>capacity:
        extra=sorted(extra,key=lambda i:(float(all_random[i,1]),i))[:capacity]
    if extra:
        extra=np.array(sorted(extra),dtype='int64');source=extra%n
        replicas=values[source].copy();replicas[:,3]=extra;replicas[:,8:]=all_random[extra]
        # 副片与原片同时释放，随后在同一流场中独立演化。
        values=np.concatenate([values,replicas]);content=np.concatenate([content,content[source]])
        rand=values[:,8:];born=values[:,2]
    cap=.865+.115*rand[:,2]-born
    # 早释放片不长时间滞留；后续材料仍有足够寿命维持卷边，过渡不依赖素材区域。
    early=RULES['life_early_gain']
    life_gain=early+(RULES['life_gain']-early)*smooth((born-RULES['life_birth_start'])/RULES['life_birth_span'])
    life=(.10+.22*(-np.log(np.maximum(rand[:,0],.004)))**.85+.20*born)*life_gain
    life=np.maximum(np.minimum(life,cap),.11)
    values[:,6]=np.maximum(np.minimum(life*(1+.30*content),cap),.11)
    depth=np.sin(values[:,0]*.014+values[:,1]*.021)*.6+(rand[:,1]-.5)*.15
    order=np.argsort(((depth+1)*1_000_000).astype('int64'),kind='stable')
    return dict(base=values[order],pigment=content[order],
                peel_compression=compression.ravel()[values[order,3].astype('int64')%n],
                nx=nx,ny=ny,cell=(float(cellx),float(celly)),
                body_weight=body,white_fraction=fraction,panel_color=panel.tolist(),panel_weight=confidence,
                replica_count=len(values)-n,release_spread=RULES['release_spread']+RULES['white_spread']*body,
                variation=variation(seed))

def guidance():
    return np.fromfile(SHARED/'common-flow.f16',dtype='<f2').astype('float32').reshape(int(RULES['flow_time']),int(RULES['flow_height']),int(RULES['flow_width']),2)


def flow_confidence():
    return np.fromfile(SHARED/'flow-confidence.u8',dtype='uint8').reshape(int(RULES['flow_time']),int(RULES['flow_height']),int(RULES['flow_width']))

def model_fingerprint():
    import hashlib
    h=hashlib.sha256();here=Path(__file__).resolve().parent
    for p in [here/'renderer.py',here/'pressure_grid.py',here/'unified_model.py',SHARED/'rules.properties',SHARED/'common-release.f32',SHARED/'common-flow.f16',SHARED/'flow-confidence.u8']:
        h.update(p.name.encode());h.update(p.read_bytes())
    return h.hexdigest()
