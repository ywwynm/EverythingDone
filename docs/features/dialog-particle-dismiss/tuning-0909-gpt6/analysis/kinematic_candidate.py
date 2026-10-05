"""共同输运的连续速度约束实验；不改变当前默认或逐素材输入。"""
import numpy as np
from scatter_material import ScatterRenderer
from renderer import STEP

KINEMATIC=r'''#version 430
layout(local_size_x=256) in;
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
struct Kinematic {vec4 pos;vec4 vel;vec4 desired;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State state[];};
layout(std430,binding=12) buffer K {Kinematic carried[];};
uniform sampler3D transport_field;
uniform vec2 card,wind;
uniform float time,dt,span,response,acceleration;
uniform uint count;
void main(){
 uint i=gl_GlobalInvocationID.x;if(i>=count)return;
 Material m=particles[i];State b=state[i];float age=time-m.src.z;
 if(age<=0.){carried[i].pos=vec4(m.src.xy,0,0);carried[i].vel=vec4(0);carried[i].desired=vec4(m.src.xy,0,0);return;}
 float h=min(dt,age);
 vec3 q=vec3((b.pos.xy/card+vec2(.38))/1.76,(clamp(time,0.,1.)*40.+.5)/41.);
 vec2 desired=b.pos.xy+texture(transport_field,q).xy*span*smoothstep(0.,.03,age);
 vec2 wanted=(desired-carried[i].desired.xy)/h;
 // 以真实材料编号连续积累；位置修正不能越过速度规则再把颗粒拉回去。
 vec2 previous=carried[i].vel.xy-carried[i].desired.zw;
 if(age<=dt)previous=vec2(0.);
 vec2 correction=mix(previous,wanted-b.vel.xy,1.-exp(-h/response));
 vec2 dv=correction-previous;float magnitude=length(dv);
 vec2 v=b.vel.xy+previous+dv/(1.+magnitude/max(acceleration*span*h,.00001));
 // 只校正主方向反行，侧向卷曲仍然由共同场决定。
 float axial=dot(v,wind);float floor_speed=.045*span;
 if(axial<floor_speed)v+=wind*(floor_speed-axial);
 carried[i].pos.xy+=v*h;
 carried[i].vel=vec4(v,0,0);carried[i].desired=vec4(desired,b.vel.xy);
}
'''


class KinematicRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){','layout(std430,binding=12) readonly buffer K {vec4 carried[];};\nvoid main(){',1)
    vertex_source=vertex_source.replace('move+=transport.xy*span*smoothstep(0.,.03,age);','move=carried[parent_instance*3].xy+carried[parent_instance*3+1].xy*extrapolate;',1)

    def __init__(self,*a,response=.032,acceleration=18.,**kw):
        super().__init__(*a,**kw)
        self.kinematic=self.ctx.compute_shader(KINEMATIC);self.carried=self.ctx.buffer(reserve=self.n*12*4)
        self.kinematic['transport_field']=8;self.kinematic['count']=self.n
        self.kinematic['dt']=STEP;self.kinematic['span']=self.span;self.kinematic['card']=(self.cw,self.ch)
        self.kinematic['wind']=self.program['wind'].value
        self.kinematic['response']=response;self.kinematic['acceleration']=acceleration
        self.reset()

    def reset(self):
        super().reset()
        if hasattr(self,'carried'):
            a=np.zeros((self.n,12),np.float32);a[:,:2]=self.base[:,:2];a[:,8:10]=self.base[:,:2]
            self.carried.write(a.tobytes())

    def seek(self,time):
        target=int(max(time,0)/STEP+1e-6)
        if target<self.step:self.reset()
        self.transport_tex.use(8);self.carried.bind_to_storage_buffer(12)
        while self.step<target:
            super().seek((self.step+1)*STEP)
            self.kinematic['time']=self.step*STEP
            self.kinematic.run(group_x=(self.n+255)//256);self.ctx.memory_barrier()

    def close(self):
        super().close();self.kinematic.release();self.carried.release()
