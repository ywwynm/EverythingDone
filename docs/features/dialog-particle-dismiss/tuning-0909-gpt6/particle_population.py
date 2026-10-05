"""共同颗粒数量、面积和中性反射实验；保持运动及源纹理采样不变。"""
import json
import numpy as np
import moderngl
from surface_handoff import HandoffRenderer
from shared_transport import transport_features
from learned_release import canonical_sites


def load_population(path):
    d=json.loads(path.read_text('utf-8'))
    return dict(scales=np.asarray(d.get('scales',[.35,.65]),np.float32),
                layers=[(np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in d['layers']])


def population_field(x,y,t,width,height,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,3),np.float32)
    f=transport_features(x,y,t,width,height,direction,sites,True);v=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights['layers']):
        v=v@w.T+b
        if i<len(weights['layers'])-1:v=np.tanh(v)
    value=(weights['scales']*np.tanh(v)).reshape(*x.shape,len(weights['scales'])).astype('float32')
    if value.shape[-1]==2:value=np.concatenate([value,np.zeros((*x.shape,1),np.float32)],-1)
    return value


class PopulationRenderer(HandoffRenderer):
    vertex_source=HandoffRenderer.vertex_source.replace('void main(){','''
uniform sampler3D population_field;
uniform vec3 population_delta;
flat out float population_keep;
flat out float population_reflection;
void main(){''',1)
    vertex_source=vertex_source.replace('scale*=mix(1.,conditional_size,smoothstep(.008,.055,age));','''
    vec3 population=texture(population_field,vec3(cohort_q,phase)).rgb+population_delta;
    float population_warmup=smoothstep(.008,.060,age);
    // 面积与数量成反比；可用母片数是明确的上限，不凭放大片元伪造更多细粒。
    float log_grain=max(population.x*population_warmup,.5*log(conditional_keep));
    population_keep=clamp(conditional_keep*exp(-2.*log_grain),.000001,1.);
    density_out+=population.y*population_warmup;
    population_reflection=population.z*population_warmup;
    scale*=exp(log_grain)*mix(1.,conditional_size,smoothstep(.008,.055,age));''',1)
    vertex_source=vertex_source.replace('-log(conditional_keep)','-log(population_keep)')
    fragment_source=HandoffRenderer.fragment_source.replace('void main(){','flat in float population_keep,population_reflection;\nvoid main(){',1)
    fragment_source=fragment_source.replace('-log(conditional_keep)','-log(population_keep)')
    fragment_source=fragment_source.replace('conditional_keep*exp(count_shift)','population_keep*exp(count_shift)')
    fragment_source=fragment_source.replace('step(random_keep,conditional_keep)','step(random_keep,population_keep)')
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','color=max(color+vec3(population_reflection),vec3(0.));\n    frag=vec4(color*alpha,alpha);',1)

    def __init__(self,*args,population=None,**kwargs):
        super().__init__(*args,**kwargs)
        n=56;yy,xx=np.mgrid[:n,:n].astype('float32');xx=-.38+(xx+.5)/n*1.76;yy=-.38+(yy+.5)/n*1.76
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        values=np.asarray([population_field(xx,yy,t,self.cw,self.ch,self.direction,sites,population) for t in np.linspace(0,1,41)],np.float32)
        self.population_tex=self.ctx.texture3d((n,n,41),3,values.tobytes(),dtype='f4')
        self.population_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.population_tex.repeat_x=self.population_tex.repeat_y=self.population_tex.repeat_z=False
        self.program['population_field']=11;self.program['population_delta']=(0.,0.,0.)

    def render(self,time,diagnostic=0):
        self.population_tex.use(11);return super().render(time,diagnostic)

    def close(self):
        super().close();self.population_tex.release()
