"""GLES 语法转换与等价绘制裁剪；不修改桌面运动模型。"""
def convert(name, source):
    source=source.replace('#version 430','#version 310 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler3D;')
    source=source.replace('if(i>=count)', 'if(i>=uint(count))')
    if name=='material.vert':
        source=source.replace('const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));',
            'const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));')
        source=source.replace('Material m=particles[gl_InstanceID];State s=state[gl_InstanceID];', '''Material m=particles[gl_InstanceID];
    float particle_age=max(0.,time-m.src.z);
    // 表面与颗粒在交接时可以同时存在；不能按出生时刻提前裁掉表面。
    if(material_pass==1 && (particle_age<=0. || particle_age>=m.physical.z)){
        gl_Position=vec4(2.,2.,2.,1.);return;
    }
    State s=state[gl_InstanceID];''')
        source=source.replace('float loosen=smoothstep(.018,.18,age);', '''if(material_pass==0){
        if(surface_out<.001 || replica>0){gl_Position=vec4(2.,2.,2.,1.);return;}
        vec2 world=offset+source;
        gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
        light_out=1.;shape_out=0.;return;
    }
    float loosen=smoothstep(.018,.18,age);''')
    return source
