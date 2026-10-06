#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D foreground,release_times;
uniform float time;
in highp vec2 uv;
out vec4 frag;
float release_time(){
    // R32F 不依赖浮点线性过滤扩展；插值释放时间，使消失边缘不再按粗网格逐块跳变。
    ivec2 size=textureSize(release_times,0);
    vec2 p=uv*vec2(size)-.5;
    ivec2 q=ivec2(floor(p));vec2 f=fract(p);
    ivec2 hi=size-1;
    float a=texelFetch(release_times,clamp(q,ivec2(0),hi),0).r;
    float b=texelFetch(release_times,clamp(q+ivec2(1,0),ivec2(0),hi),0).r;
    float c=texelFetch(release_times,clamp(q+ivec2(0,1),ivec2(0),hi),0).r;
    float d=texelFetch(release_times,clamp(q+ivec2(1,1),ivec2(0),hi),0).r;
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
void main(){
    vec4 src=texture(foreground,uv);
    float clock=release_time();
    float surface=1.-smoothstep(max(.001,clock-.025),max(.018,clock+.060),time);
    float alpha=src.a*surface;
    if(alpha<.001)discard;
#ifdef SRGB_TARGET
    vec3 color=src.rgb;
#else
    vec3 color=mix(src.rgb/12.92,pow((src.rgb+.055)/1.055,vec3(2.4)),step(vec3(.04045),src.rgb));
#endif
    frag=vec4(color*alpha,alpha);
}
