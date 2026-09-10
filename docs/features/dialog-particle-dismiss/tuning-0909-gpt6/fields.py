"""连续传播场。参数描述少量起点，不读取原片的逐帧遮罩。"""
import numpy as np

def smooth_min(a,b,k=.047):
    h=np.clip(.5+.5*(b-a)/k,0,1)
    return b*(1-h)+a*h-k*h*(1-h)

def warp(x,y):
    wx=.035*np.sin(5.7*y+1.2)+.019*np.sin(11*x+7.3*y+2.4)
    wy=.027*np.sin(6.3*x-1.8)+.017*np.sin(7.6*x-10.2*y+.6)
    return x+wx,y+wy

def propagation(x,y,params):
    x,y=warp(x,y)
    parts=[]
    for ox,oy,delay,sx,sy,theta in np.array(params).reshape(-1,6):
        c,s=np.cos(theta),np.sin(theta)
        dx=x-ox;dy=y-oy
        d=np.sqrt(((c*dx+s*dy)*sx)**2+((-s*dx+c*dy)*sy)**2+1e-8)
        parts.append(delay+d**.88)
    out=parts[0]
    for p in parts[1:]:out=smooth_min(out,p)
    return out

def residual_basis(x,y,order=4):
    return np.stack([np.cos(np.pi*i*x)*np.cos(np.pi*j*y) for i in range(order) for j in range(order)],axis=-1)

def initial_params(meta):
    ori=meta.get('origins') or [[.60,1.02,0.0],[0.0,.06,.15],[1.04,.20,.28]]
    return np.array([[x,y,d,.66,.67,0.] for x,y,d in ori],dtype=float).ravel()

def rotate_uv(x,y,delta):
    a=np.deg2rad(delta);c,s=np.cos(a),np.sin(a)
    return .5+c*(x-.5)-s*(y-.5),.5+s*(x-.5)+c*(y-.5)

def field_grid(meta,nx,ny,direction=None,profile=None):
    y,x=np.mgrid[:ny,:nx].astype(float)
    x=(x+.5)/nx;y=(y+.5)/ny
    default=meta['direction']
    # 屏幕坐标 y 向下；反向变换输入可连续旋转释放形态。
    x,y=rotate_uv(x,y,(direction if direction is not None else default)-default)
    params=np.array(profile['params']) if profile else initial_params(meta)
    T=propagation(x,y,params)
    if profile and 'residual_coefficients' in profile:
        order=profile.get('residual_order',4)
        T+=residual_basis(x,y,order)@np.array(profile['residual_coefficients'])
        # 参考中的纹理开始变化不等于离开原位；少量人工时间标定单独记录。
        T+=profile.get('detach_delay',0.)
        for ox,oy,sx,sy,delay in profile.get('origin_time_corrections',[]):
            T+=delay*np.exp(-((x-ox)/sx)**2-((y-oy)/sy)**2)
    if not profile:
        T=(T-T.min())/(np.quantile(T,.997)-T.min())*.59+.018
    else:
        T=np.maximum(T,0.)
    T=np.clip(T,0.,.78)
    return T.astype('float32')
