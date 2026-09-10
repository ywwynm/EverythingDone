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


def release_field(nx,ny,direction,width=None,height=None):
    """释放与速度共用坐标变换；矩形的长宽比参与方向换算。"""
    width=nx if width is None else width;height=ny if height is None else height
    a=field_rotation(direction,width,height);c,s=math.cos(a),math.sin(a)
    y,x=np.mgrid[:ny,:nx].astype(float);x=(x+.5)/nx-.5;y=(y+.5)/ny-.5
    u=.5+c*x-s*y;v=.5+s*x+c*y
    low=RULES['field_min'];size=RULES['field_size']
    return map_coordinates(RELEASE,[(v-low)/size*RELEASE.shape[0]-.5,(u-low)/size*RELEASE.shape[1]-.5],order=1,mode='nearest').astype('float32')

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
    field=release_field(nx,ny,direction,width,height)
    gy,gx=np.gradient(gaussian_filter(field,3),celly,cellx)
    length=np.maximum(np.hypot(gx,gy),1e-6)
    normalx=gaussian_filter(gx/length,7);normaly=gaussian_filter(gy/length,7)
    rand=random_values(n,seed)
    born=np.maximum(field.ravel()+np.float32(RULES['release_spread']+RULES['white_spread']*body)*(rand[:,3]-.5),.001)
    values=np.zeros((n,12),dtype='float32')
    y,x=np.mgrid[:ny,:nx]
    values[:,0]=(x.ravel().astype('float32')+.5)*cellx;values[:,1]=(y.ravel().astype('float32')+.5)*celly
    values[:,2]=born;values[:,3]=np.arange(n)
    values[:,4]=normalx.ravel();values[:,5]=normaly.ravel();values[:,7]=.018+.036*rand[:,2];values[:,8:]=rand
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
        replicas[:,7]=.018+.036*replicas[:,10]
        values=np.concatenate([values,replicas]);content=np.concatenate([content,content[source]])
        rand=values[:,8:];born=values[:,2]
    cap=.865+.115*rand[:,2]-born
    life=(.10+.22*(-np.log(np.maximum(rand[:,0],.004)))**.85+.20*born)*RULES['life_gain']
    life=np.maximum(np.minimum(life,cap),.11)
    values[:,6]=np.maximum(np.minimum(life*(1+.30*content),cap),.11)
    depth=np.sin(values[:,0]*.014+values[:,1]*.021)*.6+(rand[:,1]-.5)*.15
    order=np.argsort(((depth+1)*1_000_000).astype('int64'),kind='stable')
    return dict(base=values[order],pigment=content[order],nx=nx,ny=ny,cell=(float(cellx),float(celly)),
                body_weight=body,white_fraction=fraction,panel_color=panel.tolist(),panel_weight=confidence,
                replica_count=len(values)-n,release_spread=RULES['release_spread']+RULES['white_spread']*body)

def guidance():
    return np.fromfile(SHARED/'common-flow.f16',dtype='<f2').astype('float32').reshape(int(RULES['flow_time']),int(RULES['flow_height']),int(RULES['flow_width']),2)

def model_fingerprint():
    import hashlib
    h=hashlib.sha256();here=Path(__file__).resolve().parent
    for p in [here/'renderer.py',here/'unified_model.py',SHARED/'rules.properties',SHARED/'common-release.f32',SHARED/'common-flow.f16']:
        h.update(p.name.encode());h.update(p.read_bytes())
    return h.hexdigest()
