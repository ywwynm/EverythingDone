"""独立桌面 GPU 材料微片模拟。预览与视频共享本模块。"""
from pathlib import Path
import math,json
import numpy as np
import moderngl
from PIL import Image
from scipy.ndimage import gaussian_filter
from unified_model import RULES, materials, guidance, field_rotation

HERE=Path(__file__).resolve().parent
STEP=1/240

COMPUTE=r'''#version 430
layout(local_size_x=256) in;
struct Material {vec4 src; vec4 physical; vec4 random;};
struct State {vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) buffer B {State state[];};
uniform int count;
uniform float time,dt,span,wind_gain,curl_gain,roll_gain;
uniform vec2 wind;
uniform sampler3D guide_field;
uniform vec2 card,guide_rotation;
uniform float guide_gain;

vec2 curl(vec2 p,float t) {
    vec2 v=vec2(0);
    for(int k=0;k<3;k++) {
        float scale=span*(k==0?.36:k==1?.15:.064);
        float angle=.61+float(k)*1.71;
        vec2 u=vec2(cos(angle),sin(angle)),z=vec2(-u.y,u.x);
        float a=dot(p,u)/scale*6.283+t*(1.2+float(k)*.5)+2.5;
        float b=dot(p,z)/scale*6.283-t*(.8+float(k)*.43)+.2;
        // 同一个连续势场的二维旋度；各频段幅值递减。
        vec2 grad=(cos(a)*cos(b)*u-sin(a)*sin(b)*z);
        v+=vec2(grad.y,-grad.x)*(k==0?1.:k==1?.34:.10);
    }
    return v;
}
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=count)return;
    Material m=particles[i];
    float age=time-m.src.z;
    if(age<=0.){state[i].pos=vec4(m.src.xy,0,0);state[i].vel=vec4(0);return;}
    float h=min(dt,age);
    float u=min(span,520.)*(.24+.49*smoothstep(.06,.42,time))*wind_gain;
    vec2 p=state[i].pos.xy;
    vec2 q=p-wind*(span*(.12*time+.19*time*time));
    vec2 flow=curl(q,time)*span*.125*curl_gain;
    float loose=smoothstep(.02,.16,age);
    vec2 target=wind*u*(.75+.50*m.random.x)+flow*(.35+.65*loose);
    vec2 cq=p/card-vec2(.5);
    float ca=guide_rotation.x,sa=guide_rotation.y;
    vec2 lookup=vec2(ca*cq.x-sa*cq.y,sa*cq.x+ca*cq.y)+vec2(.5);
    vec2 guide=texture(guide_field,vec3((lookup+vec2(.45))/1.9,clamp(time,0.,1.))).xy;
    guide=vec2(ca*guide.x+sa*guide.y,-sa*guide.x+ca*guide.y)*card;
    float entrained=1.+.24*smoothstep(.01,.15,age);
    target=mix(target,guide*(.9+.2*m.random.x)*entrained+flow*.85,guide_gain);
    // 共同速度表示与释放在同一坐标系中演化；不额外把整片推出或吸向周期线。
    target=guide*(guide_gain/.9)+flow*.02+wind*u*.00001;
    float random_angle=m.random.w*6.28318;
    float radius=sqrt(-2.*log(max(m.random.z,.015)));
    float separate=smoothstep(.008,.045,age)*(1.-smoothstep(.16,.32,age));
    target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate;
    float depth_target=-state[i].pos.z*3.*roll_gain;
    // 允许侧向卷动；同一颗粒不沿消逝主方向反弹。
    target+=wind*max(0.-dot(target,wind),0.);
    float response=1.-exp(-h/m.physical.w);
    vec2 old_v=state[i].vel.xy;
    if(age<=dt)old_v=target*.85;
    vec2 v=mix(old_v,target,response);
    state[i].pos.xy+=v*h;
    state[i].vel.xy=v;
    float vz=mix(state[i].vel.z,depth_target,response);
    state[i].pos.z+=vz*h;
    state[i].vel.z=vz;
}
'''

VERTEX=r'''#version 430
struct Material {vec4 src;vec4 physical;vec4 random;};
struct State {vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State state[];};
layout(std430,binding=2) readonly buffer C {float pigmentation[];};
uniform vec2 frame,card,offset,cell,wind;
uniform float time,extrapolate,span,roll_gain,body_weight;
uniform int nx,grid_count;
uniform float panel_weight;
out vec2 uv,local_uv;
out float age_out,life_out,light_out,shape_out;
flat out vec4 random_out;
flat out float replica_out;
const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
vec2 jitter(vec2 g){return (vec2(hash(g),hash(g+vec2(17.1,5.9)))-.5)*.60;}
void main(){
    Material m=particles[gl_InstanceID];State s=state[gl_InstanceID];
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
    age_out=age;life_out=m.physical.z;random_out=m.random;
    float loosen=smoothstep(.018,.18,age);
    float roll_phase=age*(11.5+1.7*sin(dot(m.src.xy,vec2(.009,.015))))*(.70+.60*m.random.z);
    vec2 move=s.pos.xy+s.vel.xy*extrapolate;
    float z=s.pos.z+span*.035*(1.-cos(roll_phase))*exp(-age/.30)*roll_gain;
    float scale=mix(1.,.62+.72*m.random.y,loosen);
    if(replica>0)scale*=.72;
    // 较老的微片继续细化，密集区与末端颗粒具有不同的尺度。
    scale*=1.-.36*smoothstep(.12,.36,age);
    // 初段保留微片的实际覆盖，随后细化；白底减少增量，避免形成厚亮边。
    scale*=1.+(.08-.05*body_weight)*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));
    // 原色明显的微片在解体初段保留少量面积，再与周围材料一起细化。
    float content=pigmentation[gl_InstanceID];
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
    vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);
    vec2 world=offset+vertex;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);
    float face=sin(dot(m.src.xy,vec2(.014,.021))+age*11.);
    light_out=1.+(.17*face+.09)*smoothstep(.015,.09,age)*exp(-max(age-.25,0.)*2.);
    shape_out=loosen;
}
'''

FRAGMENT=r'''#version 430
uniform sampler2D foreground;
uniform float light_gain;
uniform int diagnostic;
uniform int material_pass;
in vec2 uv,local_uv;
in float age_out,life_out,light_out,shape_out;
flat in vec4 random_out;
flat in float replica_out;
out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){
    vec4 src=texture(foreground,uv);
    if(src.a<.001)discard;
    if(replica_out>.5 && (material_pass==0 || age_out<=.001))discard;
    float age=age_out;
    if(material_pass==0 && age>0.)discard;
    if(material_pass==1 && age<=0.)discard;
    float life=life_out;
    float fade=1.-smoothstep(max(life-.075,life*.55),life,age);
    vec2 q=local_uv-.5;
    float radial=length(q*vec2(.85+random_out.z*.35,.87+random_out.w*.34));
    float cut=1.-smoothstep(.35,.58,radial);
    float shape=mix(1.,cut,shape_out*.92);
    float alpha=src.a*fade*shape;
    if(replica_out>.5)alpha*=smoothstep(.008,.055,age);
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
'''

FULLVERT=r'''#version 430
out vec2 uv;
const vec2 v[3]=vec2[3](vec2(0,0),vec2(2,0),vec2(0,2));
void main(){uv=v[gl_VertexID];gl_Position=vec4(uv.x*2.-1.,1.-uv.y*2.,0,1);}
'''
BACKGROUND=r'''#version 430
uniform sampler2D bg;
uniform float dim;
in vec2 uv;out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){frag=vec4(linear(texture(bg,uv).rgb*(1.-dim)),1.);}
'''
OUTPUT=r'''#version 430
uniform sampler2D screen;
in vec2 uv;out vec4 frag;
vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
void main(){frag=vec4(srgb(texture(screen,vec2(uv.x,1.-uv.y)).rgb),1);}
'''

class Renderer:
    def __init__(self,name='ironman',direction=None,cell_px=None,quality=2,settings=None,ctx=None,seed=None):
        self.ctx=ctx or moderngl.create_standalone_context(require=430)
        self.directory=HERE/'assets'/name
        self.meta=json.loads((self.directory/'scene.json').read_text(encoding='utf-8'))
        self.direction=direction if direction is not None else self.meta['direction']
        self.settings={key:RULES[key] for key in ['wind_gain','curl_gain','roll_gain','light_gain','guide_gain','life_gain']}
        if settings:raise ValueError('正式模型不接受场景参数覆盖；修改共享规则后对全部场景重新验收。')
        self.w,self.h=self.meta['frame'];x,y,x1,y1=self.meta['rect'];self.cw=x1-x;self.ch=y1-y
        self.span=min(self.cw,self.ch)
        fg=np.array(Image.open(self.directory/'foreground.png').convert('RGBA'));bg=np.array(Image.open(self.directory/'background.png').convert('RGB'))
        built=materials(self.cw,self.ch,fg,self.direction,self.meta['seed'] if seed is None else seed,cell_px)
        self.nx,self.ny=built['nx'],built['ny'];self.cell=built['cell'];self.n=len(built['base'])
        self.material_info={k:built[k] for k in ['panel_color','panel_weight','replica_count']}
        self.white_fraction=built['white_fraction'];self.body_weight=built['body_weight'];self.release_spread=built['release_spread']
        base=built['base'];content=built['pigment']
        self.base=base
        self.material=self.ctx.buffer(base.tobytes());self.state=self.ctx.buffer(reserve=self.n*8*4)
        self.pigment=self.ctx.buffer(content.astype('float32').tobytes())
        self.compute=self.ctx.compute_shader(COMPUTE)
        self.program=self.ctx.program(vertex_shader=VERTEX,fragment_shader=FRAGMENT)
        self.vao=self.ctx.vertex_array(self.program,[])
        self.bg_program=self.ctx.program(vertex_shader=FULLVERT,fragment_shader=BACKGROUND)
        self.bg_vao=self.ctx.vertex_array(self.bg_program,[])
        self.out_program=self.ctx.program(vertex_shader=FULLVERT,fragment_shader=OUTPUT)
        self.out_vao=self.ctx.vertex_array(self.out_program,[])
        self.fg_tex=self.ctx.texture((self.cw,self.ch),4,fg.tobytes());self.bg_tex=self.ctx.texture((self.w,self.h),3,bg.tobytes())
        self.fg_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.bg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST)
        self.fg_tex.repeat_x=False;self.fg_tex.repeat_y=False;self.bg_tex.repeat_x=False;self.bg_tex.repeat_y=False
        self.tex=self.ctx.texture((round(self.w*quality),round(self.h*quality)),4,dtype='f2')
        self.tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.fbo=self.ctx.framebuffer([self.tex])
        self.out_tex=self.ctx.texture((self.w,self.h),4);self.out_fbo=self.ctx.framebuffer([self.out_tex])
        self.wind=(math.cos(math.radians(self.direction)),-math.sin(math.radians(self.direction)))
        flow_data=guidance();guide_direction=RULES['guide_direction']
        self.flow_tex=self.ctx.texture3d(tuple(flow_data.shape[2::-1]),2,flow_data.tobytes(),dtype='f4')
        self.flow_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.flow_tex.repeat_x=False;self.flow_tex.repeat_y=False;self.flow_tex.repeat_z=False
        delta=field_rotation(self.direction,self.cw,self.ch)
        self.compute['guide_rotation']=(math.cos(delta),math.sin(delta));self.compute['card']=(self.cw,self.ch)
        self.compute['guide_gain']=self.settings['guide_gain'];self.compute['guide_field']=3
        self.compute['count']=self.n;self.compute['dt']=STEP;self.compute['span']=self.span;self.compute['wind']=self.wind
        for key in ['wind_gain','curl_gain','roll_gain']:self.compute[key]=self.settings[key]
        self.program['frame']=(self.w,self.h);self.program['card']=(self.cw,self.ch);self.program['offset']=(x,y)
        self.program['cell']=self.cell;self.program['span']=self.span;self.program['nx']=self.nx
        self.program['grid_count']=self.nx*self.ny
        self.program['body_weight']=self.body_weight
        self.program['panel_weight']=self.material_info['panel_weight']
        # 未使用的 shader uniform 会被编译器优化掉。
        if 'wind' in self.program:self.program['wind']=self.wind
        self.program['roll_gain']=self.settings['roll_gain'];self.program['light_gain']=self.settings['light_gain']
        self.program['foreground']=0;self.bg_program['bg']=1;self.out_program['screen']=2
        self.reset()

    def reset(self):
        a=np.zeros((self.n,8),dtype='float32');a[:,:2]=self.base[:,:2]
        self.state.write(a.tobytes());self.step=0

    def seek(self,time):
        target=int(max(time,0)/STEP+1e-6)
        if target<self.step:self.reset()
        self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
        self.pigment.bind_to_storage_buffer(2)
        self.flow_tex.use(3)
        while self.step<target:
            self.step+=1;self.compute['time']=self.step*STEP
            self.compute.run(group_x=(self.n+255)//256)
            self.ctx.memory_barrier()

    def render(self,time,diagnostic=0):
        time=float(np.clip(time,0,1.05));self.seek(time)
        self.fbo.use();self.ctx.disable(moderngl.BLEND)
        dim=self.meta['dim_alpha']*(1.-float(np.clip((time-.18)/.55,0,1))**2)
        self.bg_program['dim']=dim;self.bg_tex.use(1);self.bg_vao.render(mode=moderngl.TRIANGLES,vertices=3)
        self.ctx.enable(moderngl.BLEND);self.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
        self.program['time']=time;self.program['extrapolate']=max(0,time-self.step*STEP);self.program['diagnostic']=diagnostic
        self.fg_tex.use(0)
        for material_pass in [0,1]:
            # 静态纹理按原像素覆盖，防止超采样的双重过滤模糊文字；运动纹理仍线性过滤。
            self.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST) if material_pass==0 else (moderngl.LINEAR,moderngl.LINEAR)
            self.program['material_pass']=material_pass
            self.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=self.n)
        self.ctx.disable(moderngl.BLEND);self.out_fbo.use();self.tex.use(2);self.out_vao.render(mode=moderngl.TRIANGLES,vertices=3)
        return np.frombuffer(self.out_fbo.read(components=3,alignment=1),dtype='uint8').reshape(self.h,self.w,3)[::-1].copy()

    def close(self):
        for key in ['material','pigment','state','compute','program','vao','bg_program','bg_vao','out_program','out_vao','fg_tex','bg_tex','fbo','tex','out_fbo','out_tex','flow_tex']:
            getattr(self,key).release()

if __name__=='__main__':
    import argparse,time as clock
    p=argparse.ArgumentParser();p.add_argument('--scene',default='ironman');p.add_argument('--time',type=float,default=.4);p.add_argument('--out',default='analysis/first-render.png');a=p.parse_args()
    t=clock.perf_counter();r=Renderer(a.scene);im=r.render(a.time);Image.fromarray(im).save(HERE/a.out)
    print(a.scene,r.n,'微片',round(clock.perf_counter()-t,3),'秒');r.close()
