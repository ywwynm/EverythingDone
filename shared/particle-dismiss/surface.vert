#version 310 es
precision highp float;
uniform vec2 frame,card,offset;
out highp vec2 uv;
const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));
void main(){
    uv=corners[gl_VertexID];
    vec2 world=offset+uv*card;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
}
