"""HarmonyOS 7 粒子消蚀公式的独立桌面实现。

依据：research-2026-10-04-emulator.md 中已验证的着色器与宿主常量。
这里不加载参考视频、人工起点、学习权重或旧 Android 输运场。
Skia 模拟器缺失最终粒子绘制后端，桌面用预乘透明圆片替代，不能声称栅格等价。
"""
from dataclasses import asdict, dataclass, replace
from pathlib import Path
import json
import math

import moderngl
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent


@dataclass(frozen=True)
class CoreConfig:
    # 调用方的卡片默认值；数量为 2×2 卡片预设，不等于一帧可见数量。
    count: int = 20000
    duration: float = 2.0
    stop_time: float = 1.17
    rate: float = 2.1
    radius: float = 7.0
    radius_range: float = 1.0
    lifetime: tuple = (0.5, 0.8)
    wind_y: float = 40000.0
    turb_scale: float = 300000.0
    turb_evo: float = 20.0
    turb_amp: float = 400.0
    expansion: tuple = (2100.0, 2400.0)
    # 录像快照没有物理像素信息。首版共同取 2；不为每段拟合尺度。
    native_scale: float = 2.0
    simulation_hz: int = 120
    # 绘图后端未恢复，这两项只用于核对替代栅格化，不参与运动状态。
    # 默认将最终 scale 解释为圆片直径；这是视觉核对后的替代绘制假设。
    sprite_radius_factor: float = 0.5
    additive: bool = False


DEFAULT = CoreConfig()

FULL_VERTEX = r'''#version 430
out vec2 uv;
void main(){
    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
    uv=vec2(p.x,1.-p.y);gl_Position=vec4(p*2.-1.,0.,1.);
}
'''

RANDOM = r'''
uint hashIndex(uint v){
    v^=v>>15;v*=0x85ebca6bu;v^=v>>13;v*=0xc2b2ae35u;return v^(v>>16);
}
float randomIndex(uint i){return float(hashIndex(i))/float(0xffffffffu);}
'''

MASK_FUNCTIONS = r'''
uniform vec2 native_card;
uniform float mask_random;
uniform float rate;
uniform float native_progress;
float lattice(vec2 p){
    return fract(sin(dot(p+1.,vec2(127.1,311.7)))*43758.5453123*(mask_random+1.));
}
float erosionNoise(vec2 p){
    vec2 q=floor(p),f=fract(p);f=f*f*(3.-2.*f);
    return mix(mix(lattice(q),lattice(q+vec2(1,0)),f.x),
               mix(lattice(q+vec2(0,1)),lattice(q+1.),f.x),f.y);
}
float remaining(vec2 coord){
    float frequency=205./(native_card.x*native_card.y)+.002;
    float threshold=.2+.8*erosionNoise(coord*frequency);
    return clamp((threshold-native_progress*rate)/.15,0.,1.);
}
'''

MASK_FRAGMENT = '#version 430\nin vec2 uv;out vec4 result;\nuniform sampler2D source;\n' + MASK_FUNCTIONS + r'''
void main(){vec4 c=texture(source,uv);c.rgb*=c.a;result=c*remaining(uv*native_card);}
'''

# 与提取公式相同的 Ashima simplex 家族；采用公开 MIT 实现并保留许可证。
# 上游当前采用 +10／0.5／105；本镜像使用较早的 +1／0.6／42 形式。
SIMPLEX = (HERE / 'shaders/webgl-noise/noise3D.glsl').read_text('utf-8').replace(
    '((x*34.0)+10.0)*x', '((x*34.0)+1.0)*x').replace(
    'max(0.5 - vec4', 'max(0.6 - vec4').replace('return 105.0 * dot', 'return 42.0 * dot')

COMPUTE = '#version 430\n' + RANDOM + SIMPLEX + r'''
layout(local_size_x=256) in;
struct Particle{vec4 position_age;vec4 origin_life;vec4 color;vec4 radius_scale;};
layout(std430,binding=0) buffer Particles{Particle particles[];};
uniform uint count;
uniform sampler2D source;
uniform sampler2D mask_image;
uniform vec2 emission_origin,emission_size;
uniform vec2 wind,lifetime,radius;
uniform vec3 turbulence;
uniform float dt;

float turbulenceValue(vec3 p){return snoise(p)+.5*snoise(p*1.5);}
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=count)return;
    Particle p=particles[i];
    if(p.position_age.z==0. && all(equal(p.position_age.xy,vec2(0)))){
        vec2 at=emission_origin+vec2(randomIndex(i+1u),randomIndex(i+3u))*emission_size;
        vec2 uv=(at-emission_origin)/emission_size;
        if(textureLod(mask_image,vec2(uv.x,1.-uv.y),0).a>.9)return;
        p.position_age.xy=at;p.origin_life.xy=at;
        p.origin_life.z=max(mix(lifetime.x,lifetime.y,randomIndex(i+8u)),.001);
        p.color=textureLod(source,uv,0);
        // 原实现的透明快照拒绝条件；边缘不会重新生成。
        if(p.color.a<1.){p.position_age.xy=vec2(-1);particles[i]=p;return;}
        p.color.a=mix(.6,1.,randomIndex(i+13u));
        if(all(lessThan(p.color.rgb,vec3(.1961))))p.color.rgb=vec3(.1961);
        p.radius_scale.x=mix(radius.x-radius.y,radius.x+radius.y,randomIndex(i));
        p.radius_scale.y=0.;p.position_age.z+=dt;particles[i]=p;return;
    }
    if(p.position_age.z<0.)return;
    p.position_age.z+=dt;
    float t=p.position_age.z;
    if(t>=p.origin_life.z){p.position_age=vec4(-1,-1,-1,0);particles[i]=p;return;}
    float r=randomIndex(i+11u);
    vec2 shift=.5*t*t*wind*.05*(.7+.6*r);
    float phase=mod(randomIndex(i+12u)*10000.,100.)/1000.+r*.5;
    vec3 coord=vec3(p.origin_life.xy*(500./(turbulence.x+50.)),
                    t*turbulence.y*.005+phase);
    vec2 flow=vec2(turbulenceValue(coord),turbulenceValue(coord+vec3(100,100,0)));
    p.position_age.xy=p.origin_life.xy+shift+flow*turbulence.z*t;
    float life=t/p.origin_life.z;
    p.radius_scale.y=p.radius_scale.x*clamp(life/.2,0.,1.)*clamp((1.-life)/.4,0.,1.);
    particles[i]=p;
}
'''

PARTICLE_VERTEX = r'''#version 430
struct Particle{vec4 position_age;vec4 origin_life;vec4 color;vec4 radius_scale;};
layout(std430,binding=0) readonly buffer Particles{Particle particles[];};
uniform vec2 work_size;
uniform float sprite_radius_factor;
out vec2 local;
out vec4 color;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(-1,1),
                             vec2(-1,1),vec2(1,-1),vec2(1,1));
void main(){
    Particle p=particles[gl_InstanceID];local=corners[gl_VertexID];color=p.color;
    vec2 pos=p.position_age.xy+local*p.radius_scale.y*sprite_radius_factor;
    if(p.position_age.z<=0. || p.radius_scale.y<=0.)pos=vec2(-10000);
    gl_Position=vec4(pos.x/work_size.x*2.-1.,1.-pos.y/work_size.y*2.,0.,1.);
}
'''

PARTICLE_FRAGMENT = r'''#version 430
in vec2 local;in vec4 color;out vec4 result;
void main(){
    float distance=length(local);float aa=max(fwidth(distance),.001);
    float coverage=1.-smoothstep(1.-aa,1.,distance);
    if(coverage<=0.)discard;
    float alpha=color.a*coverage;result=vec4(color.rgb*alpha,alpha);
}
'''

COMPOSITE_FRAGMENT = r'''#version 430
in vec2 uv;out vec4 result;
uniform sampler2D background,particle_image,mask_image;
uniform vec2 frame,card_offset,card_size,expansion;
uniform float dim;
vec4 over(vec4 top,vec4 bottom){return top+bottom*(1.-top.a);}
void main(){
    vec2 pixel=uv*frame;
    vec4 c=vec4(texture(background,uv).rgb*(1.-dim),1.);
    vec2 expanded_origin=card_offset+(card_size-expansion)*.5;
    vec2 part=(pixel-expanded_origin)/expansion;
    if(all(greaterThanEqual(part,vec2(0)))&&all(lessThanEqual(part,vec2(1))))
        c=over(texture(particle_image,vec2(part.x,1.-part.y)),c);
    vec2 st=(pixel-card_offset)/card_size;
    if(all(greaterThanEqual(st,vec2(0)))&&all(lessThanEqual(st,vec2(1))))
        c=over(texture(mask_image,vec2(st.x,1.-st.y)),c);
    result=c;
}
'''


def index_hash(indices):
    """便于独立校验 GPU 的 32 位整数运算。"""
    x = np.asarray(indices, dtype=np.uint32).copy()
    x ^= x >> np.uint32(15)
    x *= np.uint32(0x85ebca6b)
    x ^= x >> np.uint32(13)
    x *= np.uint32(0xc2b2ae35)
    return x ^ (x >> np.uint32(16))


def mask_random(seed):
    # RandomState 使用标准 MT19937 的单个 uint32；不使用 53 位 random_sample。
    bits = np.random.RandomState(int(seed) & 0xffffffff).randint(0, 2**32, dtype=np.uint32)
    return float(np.float32(bits) * np.float32(2.0**-32))


class HarmonyCoreRenderer:
    # 派生实验使用自己的着色器；原生公式对照不受进程内全局替换影响。
    compute_source = COMPUTE
    mask_source = MASK_FRAGMENT
    particle_vertex_source = PARTICLE_VERTEX
    particle_fragment_source = PARTICLE_FRAGMENT
    composite_source = COMPOSITE_FRAGMENT

    def __init__(self, name='ironman', ctx=None, quality=2, seed=None, direction=None,
                 touch_gap=None, config=None, wind_x=None, random_value=None):
        self.config = config or DEFAULT
        c = self.config
        self.playback_duration = c.stop_time
        if c.native_scale <= 0 or c.count < 1 or c.simulation_hz < 1:
            raise ValueError('核心模型的尺度、数量与模拟频率必须为正')
        self.owns_context = ctx is None
        self.ctx = ctx or moderngl.create_standalone_context(require=430)
        self.directory = HERE / 'assets' / name
        self.meta = json.loads((self.directory / 'scene.json').read_text('utf-8'))
        self.name = name
        self.seed = int(self.meta['seed'] if seed is None else seed)
        self.w, self.h = self.meta['frame']
        x, y, x1, y1 = self.meta['rect']
        self.cw, self.ch = x1-x, y1-y
        self.n = c.count
        self.random = mask_random(self.seed) if random_value is None else float(random_value)
        # 核心验证默认使用调用方的随机横风。触点扩展仅旋转风向，不改变释放规则。
        if wind_x is None:
            rng = np.random.RandomState((self.seed ^ 0x69042) & 0xffffffff)
            wind_x = float(rng.randint(-30000, 30001))
        self.native_wind = (float(wind_x), c.wind_y)
        if touch_gap is not None and direction is not None:
            a = math.radians(direction)
            self.native_wind = (c.wind_y*math.cos(a), c.wind_y*math.sin(a))
        s = c.native_scale
        self.native_card = tuple(max(1, round(v*s)) for v in (self.cw, self.ch))
        self.expansion = tuple(max(k, min(m, f*s)) for k,m,f in zip(self.native_card,c.expansion,(self.w,self.h)))
        self.work_size = tuple(max(1, round(v*.5)) for v in self.expansion)
        self.emission_origin = tuple((e-k)*.25 for e,k in zip(self.expansion,self.native_card))
        self.objects = []

        def own(obj):
            self.objects.append(obj)
            return obj

        fg = np.asarray(Image.open(self.directory/'foreground.png').convert('RGBA'))
        bg = np.asarray(Image.open(self.directory/'background.png').convert('RGB'))
        self.source = own(self.ctx.texture((self.cw,self.ch),4,fg.tobytes()))
        self.background = own(self.ctx.texture((self.w,self.h),3,bg.tobytes()))
        self.mask = own(self.ctx.texture(self.native_card,4))
        self.particle_image = own(self.ctx.texture(self.work_size,4))
        self.output = own(self.ctx.texture((self.w,self.h),4))
        for tex in (self.source,self.background,self.mask,self.particle_image):
            tex.repeat_x = tex.repeat_y = False
            tex.filter = (moderngl.LINEAR,moderngl.LINEAR)
        self.mask_fbo = own(self.ctx.framebuffer([self.mask]))
        self.particle_fbo = own(self.ctx.framebuffer([self.particle_image]))
        self.output_fbo = own(self.ctx.framebuffer([self.output]))
        self.state = own(self.ctx.buffer(reserve=self.n*64))
        self.compute = own(self.ctx.compute_shader(self.compute_source))
        self.mask_program = own(self.ctx.program(vertex_shader=FULL_VERTEX,fragment_shader=self.mask_source))
        self.mask_vao = own(self.ctx.vertex_array(self.mask_program,[]))
        self.particle_program = own(self.ctx.program(vertex_shader=self.particle_vertex_source,fragment_shader=self.particle_fragment_source))
        self.particle_vao = own(self.ctx.vertex_array(self.particle_program,[]))
        self.composite = own(self.ctx.program(vertex_shader=FULL_VERTEX,fragment_shader=self.composite_source))
        self.composite_vao = own(self.ctx.vertex_array(self.composite,[]))
        for key,value in dict(count=self.n,source=0,mask_image=1,
                              emission_origin=self.emission_origin,
                              emission_size=tuple(v*.5 for v in self.native_card),
                              wind=(self.native_wind[0]*.5,-self.native_wind[1]*.5),
                              radius=(c.radius*.5,c.radius_range*.5),
                              turbulence=(c.turb_scale*.5,c.turb_evo,c.turb_amp*.5),
                              dt=1/c.simulation_hz).items():
            self.compute[key] = value
        for key,value in dict(source=0,native_card=self.native_card,mask_random=self.random,rate=c.rate).items():
            self.mask_program[key] = value
        self.particle_program['work_size'] = self.work_size
        self.particle_program['sprite_radius_factor'] = c.sprite_radius_factor
        for key,value in dict(background=2,particle_image=3,mask_image=1,frame=(self.w,self.h),
                              card_offset=(x,y),card_size=(self.cw,self.ch),
                              expansion=tuple(v/s for v in self.expansion)).items():
            self.composite[key] = value
        self.reset()

    def reset(self):
        self.state.clear()
        self.step = 0
        self.last_progress = 0.

    def draw_mask(self, seconds):
        self.mask_fbo.use()
        self.ctx.disable(moderngl.BLEND)
        self.source.use(0)
        self.mask_program['native_progress'] = min(1., seconds/self.config.duration)
        self.mask_vao.render(mode=moderngl.TRIANGLES,vertices=3)

    def seek(self, progress):
        c = self.config
        target = math.floor(self.timeline_seconds(float(np.clip(progress,0,1)))*c.simulation_hz+1e-7)
        if target < self.step:
            self.reset()
        self.state.bind_to_storage_buffer(0)
        while self.step < target:
            self.step += 1
            seconds = self.step/c.simulation_hz
            self.draw_mask(seconds)
            self.source.use(0)
            self.mask.use(1)
            self.compute['lifetime'] = tuple(v*(1-seconds/c.duration) for v in c.lifetime)
            self.compute.run(group_x=(self.n+255)//256)
            self.ctx.memory_barrier()
        self.last_progress = progress

    def timeline_seconds(self,progress):
        """原生对照从零开始；显式事件派生类可以预演首帧之前的已知起点。"""
        return progress*self.config.stop_time

    def render(self, progress):
        progress = float(np.clip(progress,0,1))
        self.seek(progress)
        self.particle_fbo.use()
        self.particle_fbo.clear(0,0,0,0)
        if progress < 1.:
            self.ctx.enable(moderngl.BLEND)
            self.ctx.blend_func = ((moderngl.ONE,moderngl.ONE) if self.config.additive else
                                   (moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA))
            self.state.bind_to_storage_buffer(0)
            self.particle_vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=self.n)
        self.draw_mask(self.timeline_seconds(progress))
        if progress >= 1.:
            self.mask_fbo.clear(0,0,0,0)
        self.output_fbo.use()
        self.ctx.disable(moderngl.BLEND)
        self.background.use(2)
        self.mask.use(1)
        self.particle_image.use(3)
        self.composite['dim'] = self.meta['dim_alpha']*(1.-min(1.,progress/.75)**2)
        self.composite_vao.render(mode=moderngl.TRIANGLES,vertices=3)
        return np.frombuffer(self.output_fbo.read(components=3,alignment=1),np.uint8).reshape(self.h,self.w,3)[::-1].copy()

    def diagnostics(self):
        data = np.frombuffer(self.state.read(),np.float32).reshape(self.n,16)
        visible = (data[:,2]>0)&(data[:,13]>0)&(data[:,11]>0)
        return dict(candidate_count=self.n,active_count=int(visible.sum()) if self.last_progress<1 else 0,
                    state_active_count=int(visible.sum()),
                    max_radius=float(data[visible,13].max(initial=0)),
                    finite=bool(np.isfinite(data).all()),step=self.step)

    def describe(self):
        return dict(model='harmony-core',config=asdict(self.config),seed=self.seed,
                    mask_random=self.random,wind=self.native_wind,native_card=self.native_card,
                    expansion=self.expansion,emission_origin=self.emission_origin,
                    source='镜像中的粒子消蚀公式；共同卡片默认参数，未拟合原片随机输入',
                    limitations=['原片的物理分辨率、随机输入及具体系统构建未知',
                                 '粒子绘制后端缺失，使用预乘透明圆片替代',
                                 '触点只作为桌面风向扩展，不是原生卡片调用规则'])

    def close(self):
        if self.ctx.fbo in (self.mask_fbo,self.particle_fbo,self.output_fbo):
            self.ctx.detect_framebuffer(0).use()
        for obj in reversed(self.objects):
            obj.release()
        self.objects.clear()
        if self.owns_context:
            self.ctx.release()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scene',default='ironman')
    parser.add_argument('--progress',type=float,default=.4)
    parser.add_argument('--out',required=True)
    args = parser.parse_args()
    renderer = HarmonyCoreRenderer(args.scene)
    Image.fromarray(renderer.render(args.progress)).save(args.out)
    print(json.dumps(renderer.describe()|renderer.diagnostics(),ensure_ascii=False))
    renderer.close()
