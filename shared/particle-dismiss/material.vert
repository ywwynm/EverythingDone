#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
// 材料、状态与色素按实例读入。**顶点阶段不能用 SSBO**：Mali 系（含华为 Maleoon 910）
// 的 GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS 是 0——这是规范允许的最小值，不是驱动缺陷，
// 在那些设备上 SSBO 版本链接不过、整个动画每次都回退（2026-09-15）。
// 读的仍是 compute 写的同几个缓冲对象本身，只是换成 divisor=1 的实例化顶点属性，
// 布局与 std430 的 stride 一一对应，没有任何数据拷贝。
#ifdef DRAW_RECORDS
// A1：位置只依赖前两个属性（32 字节），外观在后面——正是 Adreno 分箱遍与 Mali IDVS
// 要求的「位置流与非位置流分开」。全部由 `draw-record.comp` 每片每帧写一次。
layout(location=0) in vec4 record_xform;
layout(location=1) in vec4 record_place;
layout(location=2) in vec4 record_shade;
layout(location=3) in vec4 record_mask;
#ifdef FLAKE_CONSTANTS
// U4 之后片元一个 random 分量都不用了，位置 5 因此空出来；记录本身仍是四段。
// U10a 打开时复制片的采样中心也并进记录（第五段），一次绘制只剩一个实例化缓冲。
#ifdef SINGLE_INSTANCE
layout(location=4) in vec4 record_extra;
#else
layout(location=4) in vec4 material_src;
#endif
#else
layout(location=4) in vec4 material_src;
layout(location=5) in vec4 material_random;
#endif
#else
layout(location=0) in vec4 material_src;
layout(location=1) in vec4 material_physical;
layout(location=2) in vec4 material_random;
layout(location=3) in vec4 state_pos;
layout(location=4) in vec4 state_vel;
layout(location=5) in float pigment;
#endif
uniform vec2 frame,card,offset,cell,wind;
uniform float time,extrapolate,span,roll_gain,body_weight;
uniform int nx,grid_count;
uniform int material_pass;
uniform float panel_weight,release_spread;
// U5：varying 精度。`uv` 必须留 highp——前景纹理最大到卡片像素尺寸（全屏 3192 px），
// fp16 在 [0,1] 上的间隔乘回去是 1.6 px，直接可见。其余分量值域都很小。
// **两侧必须同时改**：Maleoon 910 对跨阶段精度不一致直接拒绝链接。
#ifdef MEDIUMP_VARYING
#define VOUT mediump
#else
#define VOUT
#endif
out highp vec2 uv;
out VOUT vec2 local_uv;
#ifdef FLAKE_CONSTANTS
flat out VOUT float shape_out,surface_out;
flat out VOUT float alpha_scale,loosen_out,light_scale,add_term,age_beat;
flat out VOUT vec2 radial_scale;
#else
out VOUT float age_out,life_out,light_out,shape_out;
flat out VOUT float surface_out;
#ifndef DRAW_RECORDS
flat out VOUT float trailing_out;
#endif
flat out VOUT vec4 random_out;
flat out VOUT float replica_out;
#endif
const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));
// 相邻微片共用顶点偏移；整数散列避免 GPU 三角函数近似改变颗粒覆盖。
uint grid_hash(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float grid_unit(uint x){return float(grid_hash(x)>>8u)*(1./16777216.);}
vec2 jitter(vec2 g){uint s=uint(g.x)*0x9e3779b9u^uint(g.y)*0x85ebca6bu;
    return (vec2(grid_unit(s),grid_unit(s^0x68bc21ebu))-.5)*.60;}
#ifdef DRAW_RECORDS
void main(){
    float surface=record_mask.x;
#ifdef FLAKE_CONSTANTS
    // U4 把「是不是复制片」与「这一帧活不活着」合成一个标志位（取值 0/1/2/3，浮点精确），
    // 空出来的两个槽给打包好的 radial / loosen / age_beat。
    float flags=record_mask.y;
    float replica=flags>=1.5?1.:0.;
    bool flake_alive=fract(flags*.5)>.25;
#else
    float replica=record_mask.y;
    bool flake_alive=record_mask.z>=.5;
#endif
    // 表面与颗粒在交接时可以同时存在；不能按出生时刻提前裁掉表面。
    if(material_pass==0){
        if(surface<.001||replica>.5){gl_Position=vec4(2.,2.,2.,1.);return;}
    } else if(!flake_alive){
        gl_Position=vec4(2.,2.,2.,1.);return;
    }
    vec2 grid=record_place.zw;
    vec2 c=corners[gl_VertexID];
    vec2 vertex_grid=grid+c;
    vec2 j=jitter(vertex_grid);
    if(vertex_grid.x==0. || vertex_grid.x*cell.x>=card.x-.01)j.x=0.;
    if(vertex_grid.y==0. || vertex_grid.y*cell.y>=card.y-.01)j.y=0.;
    vec2 source=(vertex_grid+j)*cell;
    uv=source/card;
#ifdef SINGLE_INSTANCE
    // U10a：复制片的采样中心来自绘制记录，不再需要第二个实例化缓冲。
    if(replica>.5)uv=mix(source,record_extra.xy,.80)/card;
#else
    if(replica>.5)uv=mix(source,material_src.xy,.80)/card;
#endif

    local_uv=c;
    surface_out=surface;
#ifdef FLAKE_CONSTANTS
    alpha_scale=record_shade.x;shape_out=record_shade.y;
    light_scale=record_shade.z;add_term=record_shade.w;
    radial_scale=unpackHalf2x16(floatBitsToUint(record_mask.z));
    vec2 packed_pair=unpackHalf2x16(floatBitsToUint(record_mask.w));
    loosen_out=packed_pair.x;age_beat=packed_pair.y;
#else
    age_out=record_shade.x;life_out=record_shade.y;
    random_out=material_random;replica_out=replica;
#endif
    if(material_pass==0){
        vec2 world=offset+source;
        gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
#ifndef FLAKE_CONSTANTS
        light_out=1.;shape_out=0.;
#endif
        return;
    }
    // 记录里存的是预乘好的 2×2 矩阵（已折进格子边长）与它作用在 d0 上之后的原点。
    mat2 expand=mat2(record_xform.x,record_xform.y,record_xform.z,record_xform.w);
    vec2 vertex=record_place.xy+expand*(c+j);
    vec2 world=offset+vertex;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
#ifndef FLAKE_CONSTANTS
    light_out=record_shade.z;shape_out=record_shade.w;
#endif
}
#else
void main(){
    Material m=Material(material_src,material_physical,material_random);
    float particle_age=max(0.,time-m.src.z);
    // 表面与颗粒在交接时可以同时存在；不能按出生时刻提前裁掉表面。
    if(material_pass==1 && (particle_age<=0. || particle_age>=m.physical.z)){
        gl_Position=vec4(2.,2.,2.,1.);return;
    }
    State s=State(state_pos,state_vel);
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
    float content=pigment;
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
#endif
