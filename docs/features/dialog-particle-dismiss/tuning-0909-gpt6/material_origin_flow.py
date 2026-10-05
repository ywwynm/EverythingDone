"""以材料出生位置采样的共同连续位移实验，不读取场景身份或参考帧。"""
import numpy as np,moderngl,json,math
from surface_coherence import CoherenceRenderer
from shared_transport import transport_features
from learned_release import canonical_sites

def load_origin_flow(path):
    d=json.loads(path.read_text('utf-8'))
    return {**d,'layers':[(np.asarray(v['weight'],np.float32),np.asarray(v['bias'],np.float32)) for v in d['layers']]}

def origin_flow(x,y,t,width,height,direction,sites,weights):
    f=transport_features(x,y,t,width,height,direction,sites,True);z=f
    for i,(w,b) in enumerate(weights['layers']):
        z=z@w.T+b
        if i<len(weights['layers'])-1:z=np.tanh(z)
    z=np.tanh(z);angle=math.radians(direction);c,s=math.cos(angle),-math.sin(angle)
    along=z[...,0];across=z[...,1]
    move=np.stack([along*c-across*s,along*s+across*c],-1)*weights['displacement_scale']*t
    return np.concatenate([move,z[...,2:]*weights['density_scale']],-1).astype(np.float32)

class OriginFlowRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform sampler3D origin_flow_field;\nvoid main(){',1)
    vertex_source=vertex_source.replace('float count_shift=min(density_out*coverage_area,','''
    float origin_phase=(clamp(time,0.,1.)*40.+.5)/41.;
    vec3 origin_flow=texture(origin_flow_field,vec3((m.src.xy/card+vec2(.38))/1.76,origin_phase)).xyz*smoothstep(.008,.06,age);
    move+=origin_flow.xy*span;
    density_out+=origin_flow.z;
    float count_shift=min(density_out*coverage_area,''',1)
    def __init__(self,*args,origin_weights=None,**kw):
        super().__init__(*args,**kw);n=56;y,x=np.mgrid[:n,:n].astype(np.float32);x=-.38+(x+.5)/n*1.76;y=-.38+(y+.5)/n*1.76
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        value=np.zeros((41,n,n,3),np.float32) if origin_weights is None else np.array([origin_flow(x,y,t,self.cw,self.ch,self.direction,sites,origin_weights) for t in np.linspace(0,1,41)],np.float32)
        self.origin_tex=self.ctx.texture3d((n,n,41),3,value.tobytes(),dtype='f4');self.origin_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.origin_tex.repeat_x=self.origin_tex.repeat_y=self.origin_tex.repeat_z=False;self.program['origin_flow_field']=11
    def render(self,time,diagnostic=0):
        self.origin_tex.use(11);return super().render(time,diagnostic)
    def close(self):
        super().close();self.origin_tex.release()
