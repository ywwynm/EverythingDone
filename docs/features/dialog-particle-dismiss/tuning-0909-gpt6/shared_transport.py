"""共同的平滑输运残差；仅接受位置、进度、几何与起点，不读素材标识。"""
import math,json
import numpy as np
from scipy.ndimage import map_coordinates
from conditional_material import ConditionalRenderer
from learned_release import canonical_sites
import moderngl


def transport_features(x,y,t,width,height,direction,sites,multiscale=False):
    span=min(width,height);angle=math.radians(direction)
    wind=np.array([math.cos(angle),-math.sin(angle)],np.float32)
    cross=np.array([-wind[1],wind[0]],np.float32)
    q=np.stack([(x-.5)*width/span,(y-.5)*height/span],-1)
    u=q@wind;v=q@cross;one=np.ones_like(x)
    extent_u=abs(wind[0])*width/span+abs(wind[1])*height/span
    extent_v=abs(wind[1])*width/span+abs(wind[0])*height/span
    values=[u,v,u*u,v*v,u*v,one*t,one*t*t,one*extent_u,one*extent_v,one*len(sites)/4]
    dx=np.stack([(x-s.x)*width/span for s in sites],-1)
    dy=np.stack([(y-s.y)*height/span for s in sites],-1)
    st=np.array([s.start for s in sites],np.float32)
    a=dx*wind[0]+dy*wind[1];b=dx*cross[0]+dy*cross[1]
    distance=np.sqrt(a*a+b*b+.0001)
    for rate in [2.,6.,16.]:
        cost=distance+st;weight=np.exp(-rate*(cost-cost.min(-1,keepdims=True)))
        weight/=weight.sum(-1,keepdims=True)
        for f in [a,b,distance,st+np.zeros_like(a),a*a,b*b,a*b]:values.append((weight*f).sum(-1))
    if multiscale:
        # 固定的空间与起点相对尺度，不增加素材身份输入；所有布局共用。
        coordinates=[u,v,one*t,values[24],values[25],values[26],one*t-values[27]]
        for frequency in [2.*np.pi,4.*np.pi]:
            for coordinate in coordinates:values.extend([np.sin(frequency*coordinate),np.cos(frequency*coordinate)])
    return np.stack(values,-1).astype('float32')


class TransportWeights(list):
    displacement_scale=.42
    canonical_gain=0.
    multiscale_features=False
    flow_steps=0
    density_scale=1.6
    residual=None
    coupling=None
    motion_time_basis=None


def load_transport(path):
    d=json.loads(path.read_text('utf-8'))
    result=TransportWeights((np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in d['layers'])
    result.displacement_scale=float(d.get('displacement_scale',.42))
    result.canonical_gain=float(d.get('canonical_gain',0.))
    result.multiscale_features=d.get('multiscale_features',False)
    result.flow_steps=int(d.get('flow_steps',0))
    result.density_scale=float(d.get('density_scale',1.6))
    result.residual=d.get('residual')
    result.coupling=d.get('coupling')
    result.motion_time_basis=d.get('motion_time_basis')
    return result


def correction(x,y,t,width,height,direction,sites,weights):
    f=transport_features(x,y,t,width,height,direction,sites,getattr(weights,'multiscale_features',False))
    a=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights):
        a=a@w.T+b
        if i<len(weights)-1:a=np.tanh(a)
    if getattr(weights,'motion_time_basis',None)=='affine_velocity':
        # 两个共同几何函数给出速度变化的起止值；不再用周期时间特征
        # 独立摆动每个时刻的位置。密度仍使用原来的完整时序。
        ends=[]
        for endpoint in [0.,1.]:
            z=transport_features(x,y,endpoint,width,height,direction,sites,getattr(weights,'multiscale_features',False))
            z=z.reshape(-1,z.shape[-1])
            for i,(w,b) in enumerate(weights):
                z=z@w.T+b
                if i<len(weights)-1:z=np.tanh(z)
            ends.append(z[:,:2])
        a[:,:2]=ends[0]*(1.-t)+ends[1]*t
    a=np.tanh(a).reshape(*x.shape,-1)
    delta=getattr(weights,'displacement_scale',.42)*a[...,:2]*t
    r=math.radians(direction);wind=np.array([math.cos(r),-math.sin(r)])
    move=delta[...,:1]*wind+delta[...,1:]*np.array([-wind[1],wind[0]])
    steps=getattr(weights,'flow_steps',0)
    if steps:
        # 将共同速度场积分为连续形变，避免直接位移残差使宽材料带相互穿插。
        if x.ndim!=2 or min(x.shape)<2:raise ValueError('连续输运需规则二维采样网格')
        span=min(width,height);scale=np.array([span/width,span/height])
        displacement=move*scale/(2**steps)
        py,px=np.mgrid[:x.shape[0],:x.shape[1]].astype('float32')
        cell_x=float(x[0,1]-x[0,0]);cell_y=float(y[1,0]-y[0,0])
        for _ in range(steps):
            coords=[py+displacement[...,1]/cell_y,px+displacement[...,0]/cell_x]
            carried=np.stack([map_coordinates(displacement[...,k],coords,order=1,mode='nearest') for k in range(2)],-1)
            displacement=displacement+carried
        move=displacement/scale
    density=getattr(weights,'density_scale',1.6)*a[...,2:3] if a.shape[-1]>2 else np.zeros_like(a[...,:1])
    result=np.concatenate([move,density],-1).astype('float32')
    residual=getattr(weights,'residual',None)
    if residual is not None:
        # 一份共同残差速度场在已输运坐标中积分；生成后仍是原三通道场，
        # 不增加运行时贴图，不读取逐素材或逐帧控制数据。
        rw=TransportWeights((np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in residual['layers'])
        rw.displacement_scale=float(residual['displacement_scale']);rw.density_scale=float(residual['density_scale'])
        rw.flow_steps=int(residual['flow_steps']);rw.multiscale_features=bool(residual['multiscale_features'])
        extra=correction(x,y,t,width,height,direction,sites,rw)
        py,px=np.mgrid[:x.shape[0],:x.shape[1]].astype(np.float32)
        span=min(width,height);scale=np.array([span/width,span/height],np.float32)
        coords=[py+result[...,1]*scale[1]/float(y[1,0]-y[0,0]),px+result[...,0]*scale[0]/float(x[0,1]-x[0,0])]
        carried=np.stack([map_coordinates(extra[...,k],coords,order=1,mode='nearest') for k in range(3)],-1)
        result+=carried
    if getattr(weights,'coupling',None) is not None:
        from coupling_transport import compose
        result=compose(x,y,t,width,height,direction,sites,result,weights.coupling)
    return result


class SharedTransportRenderer(ConditionalRenderer):
    vertex_source=ConditionalRenderer.vertex_source.replace('void main(){','uniform sampler3D transport_field;\nout float density_out;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 move=s.pos.xy+s.vel.xy*extrapolate;',r'''
    vec2 move=s.pos.xy+s.vel.xy*extrapolate;
    vec2 q=(move/card+vec2(.38))/1.76;
    float phase=(clamp(time,0.,1.)*40.+.5)/41.;
    vec3 transport=texture(transport_field,vec3(q,phase)).xyz;
    move+=transport.xy*span*smoothstep(0.,.03,age);
    density_out=transport.z*smoothstep(0.,.03,age);
''',1)
    fragment_source=ConditionalRenderer.fragment_source.replace('void main(){','in float density_out;\nvoid main(){',1)
    fragment_source=fragment_source.replace('if(alpha<.001)discard;','if(density_out!=0.)alpha=1.-pow(max(1.-alpha,.0001),exp(density_out));\n    if(alpha<.001)discard;',1)

    def __init__(self,*args,transport_weights,**kwargs):
        super().__init__(*args,**kwargs)
        n=56;yy,xx=np.mgrid[:n,:n].astype('float32');xx=-.38+(xx+.5)/n*1.76;yy=-.38+(yy+.5)/n*1.76
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        gain=self.layout_change+(1-self.layout_change)*getattr(transport_weights,'canonical_gain',0.)
        field=np.array([correction(xx,yy,t,self.cw,self.ch,self.direction,sites,transport_weights)*gain for t in np.linspace(0,1,41)],np.float32)
        self.transport_tex=self.ctx.texture3d((n,n,41),3,field.tobytes(),dtype='f4')
        self.transport_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.transport_tex.repeat_x=self.transport_tex.repeat_y=self.transport_tex.repeat_z=False
        self.program['transport_field']=8

    def render(self,time,diagnostic=0):
        self.transport_tex.use(8)
        return super().render(time,diagnostic)

    def close(self):
        super().close();self.transport_tex.release()
