#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State state[];};
layout(std430,binding=2) readonly buffer C {float pigmentation[];};
uniform vec2 frame,card,offset,cell,wind;
uniform float time,extrapolate,span,roll_gain,body_weight;
uniform int nx;
uniform int material_pass;
out vec2 uv,local_uv;
out float age_out,life_out,light_out,shape_out;
flat out vec4 random_out;
const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
vec2 jitter(vec2 g){return (vec2(hash(g),hash(g+vec2(17.1,5.9)))-.5)*.60;}
void main(){
    Material m=particles[gl_InstanceID];
    float particle_age=max(0.,time-m.src.z);
    if((material_pass==0 && particle_age>0.) || (material_pass==1 && particle_age<=0.) || particle_age>=m.physical.z){
        gl_Position=vec4(2.,2.,2.,1.);return;
    }
    State s=state[gl_InstanceID];
    int id=int(m.src.w+.1);
    vec2 grid=vec2(id%nx,id/nx);
    vec2 c=corners[gl_VertexID];
    vec2 vertex_grid=grid+c;
    vec2 j=jitter(vertex_grid);
    if(vertex_grid.x==0. || vertex_grid.x*cell.x>=card.x-.01)j.x=0.;
    if(vertex_grid.y==0. || vertex_grid.y*cell.y>=card.y-.01)j.y=0.;
    vec2 source=(vertex_grid+j)*cell;
    uv=source/card;
    local_uv=c;
    float age=max(0.,time-m.src.z);
    age_out=age;life_out=m.physical.z;random_out=m.random;
    if(age<=0.){
        vec2 world=offset+source;
        gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
        light_out=1.;shape_out=0.;return;
    }
    float loosen=smoothstep(.018,.18,age);
    float roll_phase=age*(11.5+1.7*sin(dot(m.src.xy,vec2(.009,.015))))*(.70+.60*m.random.z);
    vec2 move=s.pos.xy+s.vel.xy*extrapolate;
    float z=s.pos.z+span*.035*(1.-cos(roll_phase))*exp(-age/.30)*roll_gain;
    float scale=mix(1.,.62+.72*m.random.y,loosen);
    // 较老的微片继续细化，密集区与末端颗粒具有不同的尺度。
    scale*=1.-.36*smoothstep(.12,.36,age);
    // 初段保留微片的实际覆盖，随后细化；白底减少增量，避免形成厚亮边。
    scale*=1.+(.08-.05*body_weight)*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));
    // 原色明显的微片在解体初段保留少量面积，再与周围材料一起细化。
    float content=pigmentation[gl_InstanceID];
    scale*=1.+content*.20*smoothstep(.025,.07,age)*(1.-smoothstep(.22,.48,age));
    // 解体只在释放之后发生，且从原始密铺几何连续变化。
    float facing_angle=atan(m.physical.y,m.physical.x);
    float angle=facing_angle*.35*smoothstep(.02,.08,age)+(m.random.z-.5)*6.28*smoothstep(.08,.28,age)+sin(age*12.+m.random.w*6.28)*.28*loosen;
    vec2 d=source-m.src.xy;
    // 释放初段保留局部姿态关联，随后转为独立的细粒转动。
    float tilt=(.5+.5*sin(roll_phase+dot(m.src.xy,vec2(.009,.014))+m.random.w*1.2));
    d.x*=mix(1.,.50+.50*tilt,loosen);
    mat2 rot=mat2(cos(angle),sin(angle),-sin(angle),cos(angle));
    vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);
    vec2 world=offset+vertex;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
    float face=sin(dot(m.src.xy,vec2(.014,.021))+age*11.);
    light_out=1.+(.17*face+.09)*smoothstep(.015,.09,age)*exp(-max(age-.25,0.)*2.);
    shape_out=loosen;
}
