"""由实际材料分布计算边界，并生成非周期、连续演化的局部卷动场。"""
SIZE=128

CLEAR=r'''#version 430
layout(local_size_x=256) in;
layout(std430,binding=3) buffer Density {uint density[];};
void main(){uint i=gl_GlobalInvocationID.x;if(i<16384u)density[i]=0u;}
'''

SCATTER=r'''#version 430
layout(local_size_x=256) in;
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State state[];};
layout(std430,binding=3) buffer Density {uint density[];};
uniform int count,grid_count;
uniform float time,span;
uniform vec2 card;
uniform sampler2D foreground;
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    Material m=particles[i];
    // 副片是颜色表现配额，不重复增加驱动运动的物质密度。
    if(m.src.w>=float(grid_count) || time<=m.src.z)return;
    float age=max(0.,time-m.src.z),life=m.physical.z;
    float fade=1.-smoothstep(max(life-.075,life*.55),life,age);
    float mass=texture(foreground,m.src.xy/card).a*fade;
    vec2 g=(state[i].pos.xy+span*.85)/(card+span*1.7)*128.;
    ivec2 p=ivec2(floor(g));
    if(any(lessThan(p,ivec2(0))) || any(greaterThanEqual(p,ivec2(128))))return;
    atomicAdd(density[p.y*128+p.x],uint(mass*1024.+.5));
}
'''

RESOLVE=r'''#version 430
layout(local_size_x=16,local_size_y=16) in;
layout(std430,binding=3) readonly buffer Density {uint density[];};
struct CloudCell {vec4 shape;vec4 flow;};
layout(std430,binding=4) writeonly buffer Cloud {CloudCell cloud[];};
uniform float time,span;
uniform vec2 card,cell,wind;
uint mixBits(uint x){x^=x>>16;x*=0x7feb352du;x^=x>>15;x*=0x846ca68bu;return x^(x>>16);}
vec4 randomCell(ivec2 p,uint layer){
    uint h=mixBits(uint(p.x)*0x9e3779b9u^uint(p.y)*0x85ebca6bu^layer);
    return vec4(mixBits(h),mixBits(h+1u),mixBits(h+2u),mixBits(h+3u))/4294967296.;
}
// 位置、半径、椭圆率、旋向与局部拉伸均不同；没有共同吸引线或条纹相位。
vec3 eddies(vec2 p,float spacing,uint layer){
    ivec2 tile=ivec2(floor(p/spacing));vec3 result=vec3(0.);
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        ivec2 index=tile+ivec2(x,y);vec4 r=randomCell(index,layer);
        vec2 center=(vec2(index)+.12+.76*r.xy)*spacing;
        float radius=spacing*(.20+.20*r.z);
        float angle=r.w*6.2831853+time*(r.y-.5)*.55;
        vec2 axis=vec2(cos(angle),sin(angle)),side=vec2(-axis.y,axis.x);
        vec2 delta=p-center;
        vec2 axes=radius*vec2(.74+.56*r.x,1.25-.40*r.x);
        vec2 q=vec2(dot(delta,axis),dot(delta,side))/axes;
        float square=dot(q,q),gauss=exp(-.5*square)*(1.-smoothstep(9.,16.,square));
        float spin=r.w<.5?-1.:1.;
        float strain=(r.z-.5)*1.25;
        vec2 gradient=radius*gauss*(spin*q+strain*vec2(q.y-q.x*q.x*q.y,q.x-q.y*q.y*q.x))/axes;
        gradient=axis*gradient.x+side*gradient.y;
        float energy=.68+.32*sin(time*(1.1+r.x)+r.y*6.2831853);
        result.xy+=vec2(-gradient.y,gradient.x)*energy;
        result.z+=spin*q.y*gauss*energy;
    }
    return result;
}
void main(){
    ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,ivec2(128))))return;
    vec2 voxel=(card+span*1.7)/128.;
    float norm=1024.*voxel.x*voxel.y/(cell.x*cell.y);
    float mass=0.,total=0.;vec2 gradient=vec2(0.);
    for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++){
        ivec2 samplep=p+ivec2(x,y);
        if(any(lessThan(samplep,ivec2(0))) || any(greaterThanEqual(samplep,ivec2(128))))continue;
        vec2 d=vec2(x,y);float weight=exp(-dot(d,d)*.16);
        float value=float(density[samplep.y*128+samplep.x])/norm;
        mass+=value*weight;gradient+=d*.32*value*weight/voxel;total+=weight;
    }
    mass/=total;gradient/=total;
    float contrast=length(gradient)*span*.022/(mass+.16);
    float edge=smoothstep(.07,.55,contrast)*smoothstep(.018,.12,mass);
    vec2 world=(vec2(p)+.5)*voxel-span*.85;
    vec2 side=vec2(-wind.y,wind.x);
    vec2 q=(world-card*.5)/span-wind*(.20*time+.28*time*time);
    q=vec2(dot(q,wind),dot(q,side));
    vec3 broad=eddies(q,.50,859u),fine=eddies(q,.14,2189u);
    // 中心保留较宽卷动，真实密度边界增加较小尺度的拉伸与卷吸。
    vec3 flow=broad*1.10+fine*(.04+.38*edge);
    flow.xy=flow.xy/(1.+length(flow.xy)*.55);
    flow.xy=wind*flow.x+side*flow.y;
    cloud[p.y*128+p.x].shape=vec4(mass,gradient*span,edge);
    cloud[p.y*128+p.x].flow=vec4(flow.xy,flow.z*.16,0.);
}
'''

SAMPLE=r'''
struct CloudCell {vec4 shape;vec4 flow;};
layout(std430,binding=4) readonly buffer Cloud {CloudCell cloud[];};
CloudCell sampleCloud(vec2 p){
    vec2 g=clamp((p+span*.85)/(card+span*1.7)*128.-.5,vec2(0.),vec2(126.999));
    ivec2 c=ivec2(floor(g));vec2 f=fract(g);int index=c.y*128+c.x;
    CloudCell result;
    result.shape=mix(mix(cloud[index].shape,cloud[index+1].shape,f.x),mix(cloud[index+128].shape,cloud[index+129].shape,f.x),f.y);
    result.flow=mix(mix(cloud[index].flow,cloud[index+1].flow,f.x),mix(cloud[index+128].flow,cloud[index+129].flow,f.x),f.y);
    return result;
}
'''
