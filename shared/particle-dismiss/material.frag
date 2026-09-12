#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
uniform sampler2D foreground;
uniform float light_gain;
uniform int diagnostic;
uniform int material_pass;
in vec2 uv,local_uv;
in float age_out,life_out,light_out,shape_out;
flat in float surface_out;
flat in float trailing_out;
flat in vec4 random_out;
flat in float replica_out;
out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){
    vec4 src=texture(foreground,uv);
    if(src.a<.001)discard;
    if(replica_out>.5 && (material_pass==0 || age_out<=.001))discard;
    if(material_pass==0){
        float a=src.a*surface_out;
        if(a<.001 || diagnostic==2)discard;
        vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);
        frag=vec4(surface_color*a,a);return;
    }
    float age=age_out;
    if(age<=0. || diagnostic==3)discard;
    float life=life_out;
    float fade=1.-smoothstep(max(life-.075,life*.55),life,age);
    vec2 q=local_uv-.5;
    float radial=length(q*vec2(.85+random_out.z*.35,.87+random_out.w*.34));
    float cut=1.-smoothstep(.35,.58,radial);
    float shape=mix(1.,cut,shape_out*.92);
    // 原表面尚在淡出时，运动片按对应材料已经交出的覆盖量显现。
    float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.001,.022,age);
    if(replica_out>.5)alpha*=smoothstep(.008,.055,age);
    alpha*=mix(1.,.92,smoothstep(.018,.12,age));
    // 开放侧减少实际颗粒份额；避免保留大量半透明灰片勾出原轮廓。
    if(fract(random_out.y*13.731+random_out.z*17.371)<.64*trailing_out)discard;
    if(alpha<.001)discard;
    // 几何变细与光照变化分开，避免刚出现的颗粒同时发白。
    float optical_loosen=smoothstep(.005,.095,age);
    vec3 color=linear(src.rgb);
    // 微片转动后采用较柔和的材质明暗响应，保留源色，减轻暗部黑点聚集。
    color=mix(color,pow(color,vec3(.86)),optical_loosen*.85);
    float lighting=mix(1.,light_out,light_gain);
    lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)*(1.-smoothstep(.11,.28,age));
    float facing=.5+.5*sin(age*16.+random_out.z*6.28318);
    float glint=0.1800000*pow(facing,12.);
    color=color*lighting*(1.+0.2493839*optical_loosen*light_gain)+vec3(0.0120000+glint)*optical_loosen*light_gain;
    // 彩色材料避免被漫反射中的白色项冲淡；灰白本体和未释放纹理不变。
    float low=min(min(color.r,color.g),color.b);
    float high=max(max(color.r,color.g),color.b);
    color=max(vec3(0),color-vec3(low)*.44*smoothstep(.15,.65,high-low)*optical_loosen);
    // 白底微片保留覆盖率差异，避免过曝把密集颗粒连成平坦亮边。
    float body=smoothstep(.80,.98,min(src.r,min(src.g,src.b)));
    float facet=.80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))));
    color=mix(color,min(color,vec3(mix(1.,facet,optical_loosen))),body);
    if(diagnostic==1)color=vec3(1.,.20,.07);
    frag=vec4(color*alpha,alpha);
}
