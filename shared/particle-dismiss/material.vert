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
uniform int nx,grid_count;
uniform int material_pass;
uniform float panel_weight,release_spread;
out vec2 uv,local_uv;
out float age_out,life_out,light_out,shape_out;
flat out float surface_out;
flat out float trailing_out;
flat out vec4 random_out;
flat out float replica_out;
const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));
// 相邻微片共用顶点偏移；整数散列避免 GPU 三角函数近似改变颗粒覆盖。
uint grid_hash(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float grid_unit(uint x){return float(grid_hash(x)>>8u)*(1./16777216.);}
vec2 jitter(vec2 g){uint s=uint(g.x)*0x9e3779b9u^uint(g.y)*0x85ebca6bu;
    return (vec2(grid_unit(s),grid_unit(s^0x68bc21ebu))-.5)*.60;}
void main(){
    Material m=particles[gl_InstanceID];
    float particle_age=max(0.,time-m.src.z);
    // 表面与颗粒在交接时可以同时存在；不能按出生时刻提前裁掉表面。
    if(material_pass==1 && (particle_age<=0. || particle_age>=m.physical.z)){
        gl_Position=vec4(2.,2.,2.,1.);return;
    }
    State s=state[gl_InstanceID];
    int id=int(m.src.w+.1);
    int replica=id/grid_count;
    id=id%grid_count;
    replica_out=float(replica);
    vec2 grid=vec2(id%nx,id/nx);
    vec2 c=corners[gl_VertexID];
    vec2 vertex_grid=grid+c;
    vec2 j=jitter(vertex_grid);
    if(vertex_grid.x==0. || vertex_grid.x*cell.x>=card.x-.01)j.x=0.;
    if(vertex_grid.y==0. || vertex_grid.y*cell.y>=card.y-.01)j.y=0.;
    vec2 source=(vertex_grid+j)*cell;
    uv=source/card;
    if(replica>0)uv=mix(source,m.src.xy,.80)/card;
    local_uv=c;
    float age=max(0.,time-m.src.z);
    age_out=time-m.src.z;
    // 同一释放规则的平均时序用于表面交接；运动仍按每片实际出生时间开始。
    float source_clock=m.src.z-release_spread*(m.random.w-.5);
    vec2 edge_d=min(m.src.xy,card-m.src.xy);
    vec2 edge_n=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.)*exp(-edge_d/(span*.15));
    float trailing=smoothstep(0.,.6,dot(normalize(edge_n+vec2(.000001)),m.physical.xy))*max(exp(-edge_d.x/(span*.15)),exp(-edge_d.y/(span*.15)));
    trailing_out=trailing;source_clock-=.065*trailing;
    surface_out=1.-smoothstep(max(.001,source_clock-.025),max(.018,source_clock+.060),time);
    life_out=m.physical.z;random_out=m.random;
    // 原边缘最后释放的一侧更多采用渐隐，面板内部保留颗粒层次。
    surface_out=mix(time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));
    if(material_pass==0){
        if(surface_out<.001 || replica>0){gl_Position=vec4(2.,2.,2.,1.);return;}
        vec2 world=offset+source;
        gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
        light_out=1.;shape_out=0.;return;
    }
    float loosen=smoothstep(.018,.18,age);
    float roll_phase=age*(11.5+1.7*sin(dot(m.src.xy,vec2(.009,.015))))*(.70+.60*m.random.z);
    vec2 move=s.pos.xy+s.vel.xy*extrapolate;
    float z=s.pos.z+span*.035*(1.-cos(roll_phase))*exp(-age/.30)*roll_gain;
    // 平均平方尺度接近 1；细粒与少量较大片共存，保留颗粒层次而不追加亮带。
    float scale=mix(1.,exp(0.4000000*sqrt(-2.*log(max(m.random.y,.004)))*cos(m.random.z*6.28318)-0.1600000),loosen);
    if(replica>0)scale*=.72;
    // 较老的微片继续细化，密集区与末端颗粒具有不同的尺度。
    scale*=1.-0.3900000*smoothstep(.12,.36,age);
    // 初段保留微片的实际覆盖，随后细化；白底减少增量，避免形成厚亮边。
    scale*=1.+(.08-.05*body_weight)*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));
    // 原色明显的微片在解体初段保留少量面积，再与周围材料一起细化。
    float content=pigmentation[gl_InstanceID];
    // 面板色微片更细，实际内容仍保留份额，静态纹理覆盖不变。
    scale*=1.-.26*panel_weight*(1.-content)*smoothstep(.006,.040,age);
    scale*=1.+content*.20*smoothstep(.025,.07,age)*(1.-smoothstep(.22,.48,age));
    // 解体只在释放之后发生，且从原始密铺几何连续变化。
    float facing_angle=atan(m.physical.y,m.physical.x);
    float angle=facing_angle*.35*smoothstep(.02,.08,age)+(m.random.z-.5)*6.28*smoothstep(.08,.28,age)+sin(age*12.+m.random.w*6.28)*.28*loosen;
    vec2 d=source-m.src.xy;
    // 释放初段保留局部姿态关联，随后转为独立的细粒转动。
    float tilt=(.5+.5*sin(roll_phase+dot(m.src.xy,vec2(.009,.014))+m.random.w*1.2));
    d.x*=mix(1.,.50+.50*tilt,loosen);
    mat2 rot=mat2(cos(angle),sin(angle),-sin(angle),cos(angle));
    scale*=mix(1.,mix(.84,.98,panel_weight),smoothstep(.0,.018,age));
    // 少量、宽时间范围的覆盖变化；亮度来自源色和运动颗粒，不添加轮廓线。
    scale*=1.+.20*(1.-panel_weight)*smoothstep(.010,.050,age)*(1.-smoothstep(.10,.24,age));
    // 迎风区域新生片覆盖稍小，避免角部过密；随后平滑并入既有细化过程。
    float forward_front=smoothstep(.20,.85,dot(-m.physical.xy,wind));
    scale*=1.-.18*forward_front*smoothstep(.002,.022,age)*(1.-smoothstep(.10,.24,age));
    scale*=1.+(0.9700000-1.)*smoothstep(.002,.025,age)*(1.-smoothstep(.10,.25,age));
    scale*=mix(1.,1.0012984,smoothstep(.0,.06,age));
    vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);
    if(material_pass==0)vertex=source;
    vec2 world=offset+vertex;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
    float face=sin(dot(m.src.xy,vec2(.014,.021))+age*11.);
    light_out=1.+(.17*face+.09)*smoothstep(.015,.09,age)*exp(-max(age-.25,0.)*2.);
    shape_out=mix(smoothstep(.004,.060,age),loosen,.85*panel_weight);
}
