#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
uniform sampler2D foreground;
uniform float light_gain;
uniform int diagnostic;
uniform int material_pass;
// U5：与 `material.vert` 逐字对应的精度声明。Maleoon 910 对跨阶段精度不一致直接拒绝链接。
#ifdef MEDIUMP_VARYING
#define VOUT mediump
#else
#define VOUT
#endif
in highp vec2 uv;
in VOUT vec2 local_uv;
#ifdef PARTICLE_COLOR
flat in highp vec4 particle_color;
#endif
#ifdef FLAKE_CONSTANTS
flat in VOUT float shape_out,surface_out;
flat in VOUT float alpha_scale,loosen_out,light_scale,add_term,age_beat;
flat in VOUT vec2 radial_scale;
#else
in VOUT float age_out,life_out,light_out,shape_out;
flat in VOUT float surface_out;
#ifndef DRAW_RECORDS
flat in VOUT float trailing_out;
#endif
flat in VOUT vec4 random_out;
flat in VOUT float replica_out;
#endif
#ifdef SINK_FIELD
in VOUT float absorb_out;
// .z：寿命末端的淡出时长（秒）；弹窗是 0.075
uniform vec4 sink_absorb;
#ifdef SINK_TARGETS
// 交接：接手那一片的颜色（sRGB）与方式。.a = 1：原位接手，寿命末端不淡出（倒放时以满不透明度出现，光尘接手
// 用的就是这一种）；.a = 2：与别的颗粒共用一个来源，照常淡入（现在没有用到）。两种都从来源的颜色过渡到快照上
// 的颜色
flat in VOUT vec4 origin_out;
#endif
#endif
out vec4 frag;
// U7：累积目标换成 SRGB8_ALPHA8 时前景纹理也是 sRGB 格式，采样器已经给出线性值，
// 这一段整段省掉（每片元一次 pow(vec3,2.4)）。
#ifdef SRGB_TARGET
#define linear(c) (c)
#else
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
#endif
// 「白底微片」那条判据原来比的是 sRGB 分量；线性前景下阈值按 sRGB→线性换算。
#ifdef SRGB_TARGET
#define BODY_LO 0.6038
#define BODY_HI 0.9559
#else
#define BODY_LO 0.80
#define BODY_HI 0.98
#endif
#ifdef ROUND_PARTICLES
float round_shape(){
    // 从出生起完整裁去四角，保留柔和边缘；不再按年龄从四边形渐变。
    return 1.-smoothstep(.40,.50,length(local_uv-.5));
}
#endif
void main(){
#ifdef PARTICLE_COLOR
    float alpha=particle_color.a*round_shape();
    if(alpha<.001 || diagnostic==3)discard;
    vec3 color=diagnostic==1?vec3(1.,.20,.07):particle_color.rgb;
    frag=vec4(color*alpha,alpha);
#else
    vec4 src=texture(foreground,uv);
    if(src.a<.001)discard;
#ifndef FLAKE_CONSTANTS
    if(replica_out>.5 && (material_pass==0 || age_out<=.001))discard;
#endif
    if(material_pass==0){
        float a=src.a*surface_out;
        if(a<.001 || diagnostic==2)discard;
        vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);
        frag=vec4(surface_color*a,a);return;
    }
#ifdef FLAKE_CONSTANTS
    // U4：下面用到的逐片量全部由 `draw-record.comp` 每片每帧算一次。
    // 「复制片在极早期整片丢弃」与「age<=0」两条判据都已折进 alpha_scale / alive。
    if(diagnostic==3)discard;
#ifdef ROUND_PARTICLES
    float shape=round_shape();
#else
    vec2 q=local_uv-.5;
    float radial=length(q*radial_scale);
    float cut=1.-smoothstep(.35,.58,radial);
    float shape=mix(1.,cut,shape_out*.92);
#endif
    float alpha=src.a*shape*alpha_scale;
    if(alpha<.001)discard;
    float optical_loosen=loosen_out;
    vec3 color=linear(src.rgb);
    color=mix(color,pow(color,vec3(.86)),optical_loosen*.85);
    color=color*light_scale+vec3(add_term);
#else
    float age=age_out;
    if(age<=0. || diagnostic==3)discard;
    float life=life_out;
#ifdef SINK_FIELD
    float fade=(1.-smoothstep(max(life-sink_absorb.z,life*.35),life,age))*absorb_out;
#ifdef SINK_TARGETS
    if(origin_out.a>.5&&origin_out.a<1.5)fade=1.;
#endif
#else
    float fade=1.-smoothstep(max(life-.075,life*.55),life,age);
#endif
#ifdef ROUND_PARTICLES
    float shape=round_shape();
#else
    vec2 q=local_uv-.5;
    float radial=length(q*vec2(.85+random_out.z*.35,.87+random_out.w*.34));
    float cut=1.-smoothstep(.35,.58,radial);
    float shape=mix(1.,cut,shape_out*.92);
#endif
    // 原表面尚在淡出时，运动片按对应材料已经交出的覆盖量显现。
    float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.001,.022,age);
    if(replica_out>.5)alpha*=smoothstep(.008,.055,age);
    alpha*=mix(1.,.92,smoothstep(.018,.12,age));
    // 开放侧减少实际颗粒份额；避免保留大量半透明灰片勾出原轮廓。
#ifndef DRAW_RECORDS
    // A1 之后这条判据在 `draw-record.comp` 里逐片算一次：两个操作数都是逐片量，
    // 对一片的所有像素给出同一个答案，等价于整片不进光栅化。
    if(fract(random_out.y*13.731+random_out.z*17.371)<.64*trailing_out)discard;
#endif
    if(alpha<.001)discard;
    // 几何变细与光照变化分开，避免刚出现的颗粒同时发白。
    float optical_loosen=smoothstep(.005,.095,age);
    vec3 color=linear(src.rgb);
#if defined(SINK_FIELD) && defined(SINK_TARGETS)
    // 倒放时先是接手那一片的颜色，飞到半路换成快照上的颜色
    color=mix(color,linear(origin_out.rgb),min(origin_out.a,1.)*smoothstep(life*.45,life*.85,age));
#endif
    // 微片转动后采用较柔和的材质明暗响应，保留源色，减轻暗部黑点聚集。
    color=mix(color,pow(color,vec3(.86)),optical_loosen*.85);
    float lighting=mix(1.,light_out,light_gain);
    lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)*(1.-smoothstep(.11,.28,age));
    float facing=.5+.5*sin(age*16.+random_out.z*6.28318);
    float glint=0.1800000*pow(facing,12.);
    color=color*lighting*(1.+0.2493839*optical_loosen*light_gain)+vec3(0.0120000+glint)*optical_loosen*light_gain;
#endif
    // 彩色材料避免被漫反射中的白色项冲淡；灰白本体和未释放纹理不变。
    float low=min(min(color.r,color.g),color.b);
    float high=max(max(color.r,color.g),color.b);
    color=max(vec3(0),color-vec3(low)*.44*smoothstep(.15,.65,high-low)*optical_loosen);
    // 白底微片保留覆盖率差异，避免过曝把密集颗粒连成平坦亮边。
    float body=smoothstep(BODY_LO,BODY_HI,min(src.r,min(src.g,src.b)));
#ifdef FLAKE_CONSTANTS
    float facet=.80+.20*(.5+.5*sin(age_beat+dot(uv,vec2(7.,11.))));
#else
    float facet=.80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))));
#endif
    color=mix(color,min(color,vec3(mix(1.,facet,optical_loosen))),body);
    if(diagnostic==1)color=vec3(1.,.20,.07);
    frag=vec4(color*alpha,alpha);
#endif
}
