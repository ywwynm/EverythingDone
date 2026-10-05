"""按已释放时长连续区分材料输运；共同几何函数，不读取场景名或参考图。

这是桌面离线实验。相同位置的新生片与已流动一段时间的片可以有不同惯性；
三个年龄权重连续交接，零权重保持现有绘制。尚未接入默认预览。
"""
import json
import numpy as np
import moderngl
from scipy.ndimage import map_coordinates
from surface_coherence import CoherenceRenderer
from shared_transport import transport_features
from learned_release import canonical_sites


def load_age_flow(path):
    d=json.loads(path.read_text('utf-8'))
    return dict(move_scale=float(d.get('move_scale',.18)),density_scale=float(d.get('density_scale',.6)),
                layers=[(np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in d['layers']])


def age_field(x,y,t,w,h,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,3,3),np.float32)
    f=transport_features(x,y,t,w,h,direction,sites,True);v=f.reshape(-1,f.shape[-1])
    for i,(a,b) in enumerate(weights['layers']):
        v=v@a.T+b
        if i<len(weights['layers'])-1:v=np.tanh(v)
    v=np.tanh(v).reshape(*x.shape,3,3)
    theta=np.radians(direction);wind=np.array([np.cos(theta),-np.sin(theta)]);cross=np.array([-wind[1],wind[0]])
    velocity=(v[...,:1]*wind+v[...,1:2]*cross)*(weights['move_scale']*t)
    scale=np.array([min(w,h)/w,min(w,h)/h]);velocity*=scale
    # 每个年龄层都先积分为连续坐标映射，而非直接叠加狭窄轮廓的吸引力。
    py,px=np.mgrid[:x.shape[0],:x.shape[1]].astype(np.float32)
    cx=float(x[0,1]-x[0,0]);cy=float(y[1,0]-y[0,0])
    for age in range(3):
        d=velocity[...,age,:]/32.
        for _ in range(5):
            coord=[py+d[...,1]/cy,px+d[...,0]/cx]
            d=d+np.stack([map_coordinates(d[...,k],coord,order=1,mode='nearest') for k in range(2)],-1)
        velocity[...,age,:]=d/scale
    return np.concatenate([velocity,v[...,2:]*weights['density_scale']],-1).astype(np.float32)


class AgeFlowRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','''
uniform sampler3D age_flow_young,age_flow_mature,age_flow_old;
uniform vec3 age_flow_probe[3];
flat out vec2 age_flow_lookup;
flat out vec3 age_flow_weights;
void main(){''',1)
    vertex_source=vertex_source.replace('float count_shift=min(density_out*coverage_area,','''
    age_flow_lookup=(move/card+vec2(.38))/1.76;
    age_flow_weights=cohort_age;
    vec3 age_delta=cohort_age.x*(texture(age_flow_young,vec3(age_flow_lookup,phase)).rgb+age_flow_probe[0]);
    age_delta+=cohort_age.y*(texture(age_flow_mature,vec3(age_flow_lookup,phase)).rgb+age_flow_probe[1]);
    age_delta+=cohort_age.z*(texture(age_flow_old,vec3(age_flow_lookup,phase)).rgb+age_flow_probe[2]);
    age_delta*=smoothstep(.008,.050,age);
    move+=age_delta.xy*span;
    density_out+=age_delta.z;
    float count_shift=min(density_out*coverage_area,''',1)
    fragment_source=CoherenceRenderer.fragment_source.replace('void main(){','''
flat in vec2 age_flow_lookup;
flat in vec3 age_flow_weights;
uniform int age_flow_diagnostic;
void main(){''',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','''
    if(age_flow_diagnostic>=0){
        color=vec3(age_flow_lookup,1.);
        alpha=1.-pow(max(1.-alpha,.00001),age_flow_weights[age_flow_diagnostic]);
    }
    frag=vec4(color*alpha,alpha);''',1)

    def __init__(self,*args,age_flow=None,**kwargs):
        super().__init__(*args,**kwargs)
        n=56;yy,xx=np.mgrid[:n,:n].astype(np.float32);xx=-.38+(xx+.5)/n*1.76;yy=-.38+(yy+.5)/n*1.76
        sites=self.origin_sites or canonical_sites(self.cw,self.ch,self.direction)
        data=np.array([age_field(xx,yy,t,self.cw,self.ch,self.direction,sites,age_flow) for t in np.linspace(0,1,41)],np.float32)
        self.age_textures=[]
        for k,name in enumerate(['young','mature','old']):
            tex=self.ctx.texture3d((n,n,41),3,np.ascontiguousarray(data[...,k,:]).tobytes(),dtype='f4')
            tex.filter=(moderngl.LINEAR,moderngl.LINEAR);tex.repeat_x=tex.repeat_y=tex.repeat_z=False
            self.program['age_flow_'+name]=11+k;self.age_textures.append(tex)
        self.program['age_flow_probe'].write(np.zeros((3,3),np.float32).tobytes())
        self.program['age_flow_diagnostic']=-1

    def render(self,time,diagnostic=0):
        for k,tex in enumerate(self.age_textures):tex.use(11+k)
        return super().render(time,diagnostic)

    def close(self):
        super().close()
        for tex in self.age_textures:tex.release()
