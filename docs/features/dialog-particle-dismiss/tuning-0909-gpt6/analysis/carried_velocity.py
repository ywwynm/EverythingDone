"""未采用研究：沿材料经过的位置积累静态速度修正，无公共时间周期输入。"""
import json, math
import numpy as np, moderngl
from scatter_material import ScatterRenderer
from shared_transport import transport_features
from learned_release import canonical_sites
from renderer import STEP

FIELD=56
CADENCE=1/120
SPEED=.55
RAMP=.06
LO,SIZE=-.38,1.76

COMPUTE=r'''#version 430
layout(local_size_x=256) in;
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State states[];};
layout(std430,binding=7) buffer C {vec4 carried[];};
uniform sampler3D transport_field;
uniform sampler3D carried_field;
uniform int count;
uniform float time,span,dt;
uniform float carried_samples;
uniform vec2 card;
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    float age=time-particles[i].src.z;
    vec2 p=states[i].pos.xy;
    float phase=(clamp(time,0.,1.)*40.+.5)/41.;
    p+=texture(transport_field,vec3((p/card+vec2(.38))/1.76,phase)).xy*span*smoothstep(0.,.03,age);
    float particle_phase=(clamp(age,0.,1.)*(carried_samples-1.)+.5)/carried_samples;
    vec2 velocity=texture(carried_field,vec3((p/card+vec2(.38))/1.76,particle_phase)).xy*smoothstep(0.,.06,age);
    carried[i].xy+=(carried[i].zw+velocity)*(.5*dt);
    carried[i].zw=velocity;
}
'''


class Weights(list):
    age_slices=1


def load(path):
    q=json.loads(path.read_text('utf-8'))
    weights=Weights((np.asarray(v['weight'],np.float32),np.asarray(v['bias'],np.float32)) for v in q['layers'])
    weights.age_slices=int(q.get('age_slices',1))
    return weights


def features(width,height,direction,sites,age_slices=1):
    y,x=np.mgrid[:FIELD,:FIELD].astype(np.float32)
    x=LO+(x+.5)/FIELD*SIZE;y=LO+(y+.5)/FIELD*SIZE
    static=transport_features(x,y,0.,width,height,direction,sites,True)
    if age_slices==1:return static[None]
    # 年龄只采用非周期输入；几何部分不注入公共进度。
    return np.asarray([np.concatenate([static,np.full((*x.shape,1),age,np.float32),np.full((*x.shape,1),age*age,np.float32)],-1)
                       for age in np.linspace(0,1,age_slices)],np.float32)


def evaluate(width,height,direction,sites,weights):
    if weights is None:return np.zeros((1,FIELD,FIELD,2),np.float32)
    value=features(width,height,direction,sites,weights.age_slices)
    for i,(w,b) in enumerate(weights):
        value=value@w.T+b
        if i<len(weights)-1:value=np.tanh(value)
    angle=math.radians(direction);c,z=math.cos(angle),-math.sin(angle)
    return (np.tanh(value)*SPEED)@np.array([[c,z],[-z,c]],np.float32)


class CarriedVelocityRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){','''
layout(std430,binding=7) readonly buffer CarriedVelocity {vec4 carried[];};
uniform float carried_extrapolate;
void main(){''',1)
    vertex_source=vertex_source.replace('density_out=transport.z*smoothstep(0.,.03,age);','''
    density_out=transport.z*smoothstep(0.,.03,age);
    move+=(carried[parent_instance].xy+carried[parent_instance].zw*carried_extrapolate)*span;
''',1)

    def __init__(self,*args,carried_weights=None,**kwargs):
        super().__init__(*args,**kwargs)
        field=evaluate(self.cw,self.ch,self.direction,self.origin_sites or canonical_sites(self.cw,self.ch,self.direction),carried_weights)
        self.carried_tex=self.ctx.texture3d((FIELD,FIELD,len(field)),2,field.astype(np.float32).tobytes(),dtype='f4')
        self.carried_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.carried_tex.repeat_x=self.carried_tex.repeat_y=self.carried_tex.repeat_z=False
        self.carried=self.ctx.buffer(reserve=self.n*16);self.carried.clear()
        self.carried_compute=self.ctx.compute_shader(COMPUTE)
        for key,value in dict(count=self.n,span=self.span,dt=CADENCE,card=(self.cw,self.ch),transport_field=8,carried_field=12,carried_samples=float(len(field))).items():
            self.carried_compute[key]=value
        self.carried_step=0

    def reset(self):
        super().reset()
        if hasattr(self,'carried'):self.carried.clear();self.carried_step=0

    def seek(self,time):
        if not hasattr(self,'carried'):return super().seek(time)
        target=int(max(time,0)/STEP+1e-6)
        if target<self.step:self.reset()
        self.carried.bind_to_storage_buffer(7);self.carried_tex.use(12);self.transport_tex.use(8)
        end=int(max(time,0)/CADENCE+1e-6)
        while self.carried_step<end:
            self.carried_step+=1
            now=self.carried_step*CADENCE
            super().seek(now)
            self.carried_compute['time']=now
            self.carried_compute.run(group_x=(self.n+255)//256)
            self.ctx.memory_barrier()
        super().seek(time)
        self.program['carried_extrapolate']=max(0.,time-self.carried_step*CADENCE)

    def close(self):
        super().close()
        for v in [self.carried_tex,self.carried,self.carried_compute]:v.release()
