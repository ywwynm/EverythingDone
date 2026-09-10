#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
out vec2 uv;
const vec2 v[3]=vec2[3](vec2(0,0),vec2(2,0),vec2(0,2));
void main(){uv=v[gl_VertexID];gl_Position=vec4(uv.x*2.-1.,1.-uv.y*2.,0,1);}
