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
flat in vec4 random_out;
out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){
    vec4 src=texture(foreground,uv);
    if(src.a<.001)discard;
    float age=age_out;
    if(material_pass==0){frag=vec4(linear(src.rgb)*src.a,src.a);return;}
    if(material_pass==0 && age>0.)discard;
    if(material_pass==1 && age<=0.)discard;
    float life=life_out;
    float fade=1.-smoothstep(max(life-.075,life*.55),life,age);
    vec2 q=local_uv-.5;
    float radial=length(q*vec2(.85+random_out.z*.35,.87+random_out.w*.34));
    float cut=1.-smoothstep(.35,.58,radial);
    float shape=mix(1.,cut,shape_out*.92);
    float alpha=src.a*fade*shape;
    if(alpha<.001)discard;
    vec3 color=linear(src.rgb);
    // 微片转动后采用较柔和的材质明暗响应，保留源色，减轻暗部黑点聚集。
    color=mix(color,pow(color,vec3(.70)),shape_out*.85);
    float lighting=mix(1.,light_out,light_gain);
    float facing=.5+.5*sin(age*16.+random_out.z*6.28318);
    float glint=.22*pow(facing,12.);
    color=color*lighting*(1.+.70*shape_out*light_gain)+vec3(.026+glint)*shape_out*light_gain;
    // 彩色材料避免被漫反射中的白色项冲淡；灰白本体和未释放纹理不变。
    float low=min(min(color.r,color.g),color.b);
    float high=max(max(color.r,color.g),color.b);
    color=max(vec3(0),color-vec3(low)*.44*smoothstep(.15,.65,high-low)*shape_out);
    // 白底微片保留覆盖率差异，避免过曝把密集颗粒连成平坦亮边。
    float body=smoothstep(.80,.98,min(src.r,min(src.g,src.b)));
    float facet=.80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))));
    color=mix(color,min(color,vec3(mix(1.,facet,shape_out))),body);
    if(diagnostic==1)color=age<=0.?vec3(.05,.45,.95):vec3(1.,.20,.07);
    if(diagnostic==2 && age<=0.)discard;
    frag=vec4(color*alpha,alpha);
}
