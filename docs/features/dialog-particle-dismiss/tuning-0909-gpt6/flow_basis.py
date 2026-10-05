"""按方向和起点构造共同的平滑形变基；系数离线拟合，运行时只作解析计算。"""
import math
import numpy as np


def basis(x,y,t,width,height,direction,sites):
    """输出 [...,54,2]，单位为控件短边；不读取素材或参考图。"""
    span=min(width,height);a=math.radians(direction)
    wind=np.array([math.cos(a),-math.sin(a)],np.float32)
    cross=np.array([-wind[1],wind[0]],np.float32)
    q=np.stack([(x-.5)*width/span,(y-.5)*height/span],-1)
    u=q@wind;v=q@cross;one=np.ones_like(x)
    base=[one[...,None]*wind,one[...,None]*cross,u[...,None]*wind,
          v[...,None]*cross,v[...,None]*wind,u[...,None]*cross]
    for radius in [.20,.43,.85]:
        terms=[np.zeros_like(q) for _ in range(4)]
        for s in sites:
            age=max(0.,t-s.start)
            center=np.array([(s.x-.5)*width/span,(s.y-.5)*height/span])+wind*(.20*age)
            delta=q-center;du=delta@wind;dv=delta@cross
            g=np.exp(-(du*du+dv*dv)/(2*radius*radius))
            sign=math.tanh(float(center@cross)*4.)
            terms[0]+=((-dv)[...,None]*wind+du[...,None]*cross)*(g*sign/radius)[...,None]
            terms[1]+=delta*(g/radius)[...,None]
            terms[2]+=wind*g[...,None]
            terms[3]+=cross*(g*sign)[...,None]
        base.extend(q/max(len(sites),1) for q in terms)
    spatial=np.stack(base,-2)
    # 无额外起始停顿；三个时间基在 t=0 均为零。
    return np.concatenate([spatial*f for f in [t,t*(1-t),t*t*(1-t)]],-2).astype('float32')


def displacement(x,y,t,width,height,direction,sites,coefficients):
    b=basis(x,y,t,width,height,direction,sites)
    raw=np.einsum('...ki,k->...i',b,np.asarray(coefficients,np.float32))
    return (.32*np.tanh(raw/.32)).astype('float32')
