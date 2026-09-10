#version 310 es
precision highp float;
precision highp sampler2D;
uniform sampler2D screen;
in vec2 uv;out vec4 frag;
vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
void main(){
    vec4 p=texture(screen,vec2(uv.x,1.-uv.y));
    if(p.a<=.00001){frag=vec4(0.);return;}
    float a=clamp(p.a,0.,1.);
    frag=vec4(clamp(srgb(p.rgb/max(a,.00001)),0.,1.)*a,a);
}
