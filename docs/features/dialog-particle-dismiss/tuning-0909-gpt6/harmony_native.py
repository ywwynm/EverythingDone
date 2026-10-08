"""HarmonyOS 7 粒子消蚀的逐项等价桌面实现（按宿主机器码与两段着色器逐项对照）。

与 harmony_core.py 的区别：harmony_core 只复刻了两段着色器的公式，这里把宿主
GEParticleAblationFilter::OnProcessImage / DrawParticleImage 的处理也逐项补上：

- 消蚀遮罩在快照原分辨率计算（imageShader 最近邻取样、预乘输出）；
- 粒子取色纹理 inTexture 与出生判据 ablationMaskTexture 都是先用最近邻缩到一半分辨率的快照，
  粒子更新着色器再以最近邻取样（源码 SamplingOptions 全零 = NEAREST）；
- 粒子画在扩展尺寸一半的工作面上，再以最近邻放大两倍画到扩展尺寸的画布，剩余快照最后居中叠在上面；
- 每帧 deltaT 取系统时钟的毫秒差 / 1000，首帧固定 0.0167；
- 新粒子寿命取 (1 - progress) × centers1，半径取 centers0 / 2，风、湍流尺度与幅度取一半后传入，
  WindConfig/TurbConfig 的其余常量取宿主写死的值（1.0/0.7/0.6/0.05；2/0.5/1.5/0.005/100/100/0.5）；
- 遮罩随机数 random 在首帧之前为 0.1，之后为 MT19937 首个输出 × 2^-32（每次动画一个）。

宿主没有读取 centers3、glowLevel、glowBrightness。模拟器镜像缺少 DrawParticle 的绘制后端，
粒子的栅格化形状是唯一需要按画面推断的部分，作为可选项保留。

动画的调用输入（卡片尺寸与扩展尺寸的物理像素、粒子数量、横风随机值、遮罩随机数、设备帧率、
录像时间与设备时间的对应）由 NativeInputs 给出；它们是一次动画的输入，不是算法参数。
"""
from dataclasses import dataclass, field, asdict, replace
from pathlib import Path
import json
import math

import moderngl
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent

# ---------------------------------------------------------------- 参数

@dataclass(frozen=True)
class FormParams:
    """桌面卡片 FormParticleConfig 的默认值（7.0.0.107）。"""
    ablation_rate: float = 2.1
    centers0: tuple = (7.0, 1.0)      # 粒径：radius = a/2, radiusRange = b/2（工作面像素）
    centers1: tuple = (0.5, 0.8)      # 寿命：(1-progress) * (a, b) 秒
    centers2: tuple = (0.0, 0.0)      # u_mode, u_dir
    wind_y: float = 40000.0           # wind = [randomInteger(-30000, 30000), 40000]
    turb_scale: float = 300000.0
    turb_evo: float = 20.0
    turb_amp: float = 400.0
    duration: float = 2.0             # HDS 线性进度动画时长（秒）
    # FORCE_STOP_PARTICLE_MS：静态默认 1170，setParticleParams 的缺省入参 1500。十二段原片的颗粒都是自然淡出
    # （钢铁侠到设备约 1.25 秒仍有颗粒），没有 1.17 秒处整体消失，按 1500 取。
    force_stop: float = 1.5
    expansion_cap: tuple = (2100.0, 2400.0)
    # 宿主写死的常量
    density: float = 1.0
    drag_base: float = 0.7
    drag_range: float = 0.6
    accel: float = 0.05
    turb_comp: float = 2.0
    turb_omult: float = 0.5
    turb_oscale: float = 1.5
    turb_evo_mult: float = 0.005
    noise_offset: tuple = (100.0, 100.0)
    particle_phase: float = 0.5
    first_dt: float = 0.0167
    dark_clamp: float = 0.1961        # 暗色颗粒的下限（三个通道都低于它时整体抬到它）
    size_min: float = 0.7             # 只在 UpdatePosition 的尺寸里出现，随后被 UpdateScale 覆盖


@dataclass(frozen=True)
class Raster:
    """缺失的 DrawParticle 后端的替代栅格化（唯一需要按画面推断的部分）。"""
    shape: str = 'disc'               # disc / square / soft / sprite（系统镜像的 9×9 软点 dot01.png，画满半宽方块）
    size_is: str = 'diameter'         # scale 作为直径或半径
    size_mul: float = 1.0
    feather: float = 1.0              # 圆片边缘抗锯齿宽度（工作面像素）
    blend: str = 'over'               # over：预乘 src-over；add：加光（工作面为 8 位，逐通道截到 1）
    gain: float = 1.0                 # 粒子颜色乘子（只用于核对后端是否提亮）
    opacity: float = 1.0              # 不透明度乘子（只用于核对后端是否更透明）
    linear: bool = False              # 颗粒累加与合成在线性光里做（sRGB 解码 → 混合 → 编码）
    premul_as_is: bool = False        # 颗粒颜色按「已预乘」直接累加（只乘覆盖率，不再乘随机 alpha）：
                                      # 计算着色器取的是预乘颜色、随后把 alpha 换成 0.6～1.0 的随机数，后端若把它当预乘颜色画，
                                      # 每颗颗粒相当于提亮 1/alpha
    alpha_mul: float = 1.0            # 只乘颗粒层的 alpha（不改颜色）：决定颗粒在浅色背景上遮住多少背景，深色背景上几乎不起作用
    alpha_extra: float = 0.0          # 只乘在 alpha 上的额外寿命衰减 g^alpha_extra，g = 寿命末段的衰减系数（1−寿命比例）/fade_window：
                                      # 末段颗粒更像加光、少遮挡（生长段不受影响）
    alpha_window: float = 0.4         # alpha_extra 的末段窗口（寿命比例）
    fade_size_power: float = 0.5      # size_source='radius_alpha_split'：衰减段尺寸 g^fade_size_power、不透明度 g^fade_alpha_power
    fade_alpha_power: float = 0.5     # （g = 衰减段系数；两者都取 0.5 时与 radius_alpha 相同）
    size_source: str = 'scale'        # 颗粒尺寸取 scale（随出生长大、寿命末段缩小）或 radius（固定，诊断用）；
                                      # radius_alpha：按 radius 画，scale/radius 乘到不透明度上（生长与衰减表现为淡入淡出）；
                                      # radius_alpha_fade：同上，但只取衰减段（出生即满尺寸）；
                                      # radius_alpha_win：同 radius_alpha，生长 / 衰减按 grow_window / fade_window 重算（源码 0.2 / 0.4 时与 radius_alpha 相同）
    alpha_power: float = 1.0          # radius_alpha 时不透明度取 (scale/radius)^alpha_power，尺寸取 radius·(scale/radius)^(1−alpha_power)
    grow_window: float = 0.2          # size_source='window'：按寿命比例重算尺寸，前 grow_window 长大、后 fade_window 缩小（源码 0.2 / 0.4）
    fade_window: float = 0.4
    glow_sigma: float = 0.0           # 颗粒辉光（假设检验）：卡片调用把 glowLevel 8、glowBrightness 2 交给渲染服务，滤镜宿主不读，
    glow_gain: float = 0.0            # 缺失的绘制后端可能用它。颗粒层贡献在线性光里按 σ（场景像素）模糊、乘 glow_gain 后加回
    min_scale: float = 0.0            # 绘制时的最小 scale（工作面像素）：ParticleSystemConfig.u_sizeMin = 0.7 交给了绘制后端，
                                      # 运动着色器里没有用到，后端若以它为下限，寿命末段缩小的颗粒会保持可见直到消失


@dataclass(frozen=True)
class NativeInputs:
    """一次动画的输入。"""
    scale: float = 2.0                # 设备物理像素 / 场景像素
    expansion: tuple = None           # 设备物理像素；None 时取 min(画面 × scale, 上限)
    count: int = 20000                # maxParticleCount（卡片尺寸档：10000/20000/30000/40000/50000）
    wind_x: float = 0.0               # randomInteger(-30000, 30000)
    random: float = None              # 遮罩随机数；None 时按 seed 用 MT19937
    seed: int = 1
    lattice: tuple = None             # 直接给定消蚀噪声格点值（按行，形状 (ny, nx)）；覆盖 random
    hz: float = 120.0                 # 设备刷新率
    span: float = 1.17                # 场景进度 1 对应的设备秒数
    offset: float = 0.0               # 场景进度 0 对应的设备秒数（动画首帧为 0）
    # 录像的时间轴可能不均匀（慢放、补帧、丢帧）：进度 knee 之后改用 span2；为 None 时整段线性
    span2: float = None
    knee: float = 0.5
    # 逐帧测得的时间映射 ((参考进度, 设备秒), ...)，单调不减；给定时取代 offset/span/span2，两端按端段斜率外推。
    # 原片帧库按独立帧序号换算进度，手机卡顿时录下的重复帧被去掉，这段时间在进度轴上被压缩，线性映射表达不了
    time_knots: tuple = None
    # 按段覆盖的栅格化字段 ((字段, 值), ...)：只用于与主体录屏不是同一类设备的参考（参考 5），见 replica 文档第 40 节
    raster_override: tuple = None
    ms_clock: bool = True             # deltaT 按毫秒取整（宿主用 system_clock 毫秒差）
    # 参考画面开头的暗色背景层（长按菜单退场时的遮罩）：((参考进度, 背景亮度倍数), ...)，按进度线性插值；为 None 时不处理
    backdrop: tuple = None
    # 手持屏摄的相机自动曝光：((参考进度, 背景 A×3, 背景 O×3, 卡片 A×3, 卡片 O×3), ...)，按进度插值；
    # 背景按 A·x+O、卡片与颗粒（预乘）按 A·c+O·α 变换后再合成。为 None 时不处理
    exposure: tuple = None
    # 手持屏摄的相机口径：(亮度模糊 σ, 色度模糊 σ（场景像素）, 高光上限（编码值，可为 None）, 颗粒增益,
    # 眩光 σ, 眩光权重, 锐化量, 锐化 σ)，见 apply_capture；只作用于颗粒层的贡献；屏幕录制与自有弹窗为 None
    capture: tuple = None


def mt19937_first(seed):
    """std::mt19937(seed)() —— 与宿主 random_device 播种后的首个输出同一生成器。"""
    return int(np.random.RandomState(int(seed) & 0xffffffff).randint(0, 2**32, dtype=np.uint64)) & 0xffffffff


def mask_random(seed):
    return float(np.float32(np.float32(mt19937_first(seed)) * np.float32(2.0**-32)))


# ---------------------------------------------------------------- 着色器

FULL_VERTEX = r'''#version 430
out vec2 v_uv;
void main(){
    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
    v_uv=p;gl_Position=vec4(p*2.-1.,0.,1.);
}
'''

# 遮罩：片元坐标即快照像素中心（SkSL main(vec2 uv)），输出预乘颜色。
MASK_FRAGMENT = r'''#version 430
layout(location=0) out vec4 result;
uniform sampler2D image_tex;     // 快照（原分辨率，straight alpha）
uniform vec2 iResolution;
uniform float iTime, rate, random_value, u_mode, u_dir;
uniform int use_lattice;
uniform ivec2 lattice_size;
uniform sampler2D lattice_tex;   // R32F，格点值
float hash(vec2 p){
    if(use_lattice==1){
        ivec2 q=clamp(ivec2(p),ivec2(0),lattice_size-1);
        return texelFetch(lattice_tex,q,0).r;
    }
    return fract(sin(dot(p+vec2(1.0,1.0),vec2(127.1,311.7)))*43758.5453123*(random_value+1.0));
}
float noise(vec2 p){
    vec2 i=floor(p);vec2 f=fract(p);
    f=f*f*(3.0-2.0*f);
    return mix(mix(hash(i+vec2(0.0,0.0)),hash(i+vec2(1.0,0.0)),f.x),
               mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x),f.y);
}
void main(){
    // 纹理行号一律按自上而下存放：帧缓冲第 r 行即快照第 r 行，片元坐标就是快照像素中心。
    vec2 uv=gl_FragCoord.xy;
    vec2 uv1=uv/iResolution.xy;
    float u_Softness=0.15;
    float diff;
    if(u_mode<0.5){
        float L=sqrt(iResolution.x*iResolution.y);
        float u_Scale=205.0/(L*L)+0.002;
        vec2 noiseUV=uv*u_Scale;
        float n=noise(noiseUV)*0.8+0.2;
        float threshold=iTime*rate;
        diff=n-threshold;
    }else{
        float u_Warp=0.20,u_Tiles=3.0,s;
        if(u_dir<0.5)s=uv1.x;else if(u_dir<1.5)s=1.0-uv1.x;else if(u_dir<2.5)s=uv1.y;else s=1.0-uv1.y;
        float progress=iTime*rate;
        float minThresh=-u_Warp-u_Softness,maxThresh=1.0+u_Warp;
        float threshold=mix(minThresh,maxThresh,progress);
        float n=noise(uv1*u_Tiles);
        diff=(s-threshold)+(n-0.5)*u_Warp;
    }
    float alpha=0.0;
    if(diff<=0.0)alpha=0.0;else if(diff<u_Softness)alpha=diff/u_Softness;else alpha=1.0;
    vec4 c=texelFetch(image_tex,ivec2(uv),0);
    c.rgb*=c.a;                         // Skia 着色器求值为预乘颜色
    result=vec4(c.rgb*alpha,c.a*alpha);
}
'''

SIMPLEX = (HERE / 'shaders/webgl-noise/noise3D.glsl').read_text('utf-8').replace(
    '((x*34.0)+10.0)*x', '((x*34.0)+1.0)*x').replace(
    'max(0.5 - vec4', 'max(0.6 - vec4').replace('return 105.0 * dot', 'return 42.0 * dot')

COMPUTE = r'''#version 430
layout(local_size_x=256) in;
struct Particle{vec2 position;vec2 initPosition;vec4 color;float lifeTime;float activeTime;float radius;float scale;};
layout(std430,binding=0) buffer Particles{Particle v[];} particles;
uniform sampler2D mask_full;    // 原分辨率遮罩（预乘），宿主先最近邻缩半
uniform sampler2D image_full;   // 原分辨率快照（straight）
uniform ivec2 half_size;        // trunc(快照尺寸 / 2)
uniform ivec2 full_size;
// ParticleSystemConfig
uniform vec2 position_cfg, emitSize, lifeTime_cfg;
uniform uint maxParticleCount;
uniform float radius_cfg, radiusRange, deltaT, u_aspect, u_sizeGrowTime, u_sizeShrinkTime, u_sizeMin;
// WindConfig
uniform vec2 u_wind;
uniform float u_density, u_dragBase, u_dragRange, u_accel;
// TurbConfig
uniform float u_scale, u_evo, u_amp, u_comp, u_omult, u_oscale, u_evoMult, u_noiseOffsetX, u_noiseOffsetY, u_particlePhase;
// 暗色下限：源码在快照颜色空间里取 0.1961；快照若为线性格式，换成 sRGB 编码值约为 0.4823（诊断用）
uniform float u_darkClamp;
// Android 移植口径（缺省与源码相同）：湍流取样位置 = (初始位置 × u_turbScale + u_turbShift) × 频率 + u_turbOffset
uniform vec2 u_turbScale, u_turbShift, u_turbOffset;
// Android 移植口径的内容强调（ParticleDriftContent / drift-update.comp）：use_content = 0 时不起作用
uniform int use_content;
uniform int neutral_content;     // Android 现行口径：黑白灰不追加强调；参考复刻缺省为 0
uniform uint content_base;        // 前 content_base 颗是源码的颗粒，其后是按分布表追加的副本
uniform ivec2 content_grid;
uniform vec3 content_panel;
uniform float content_panel_weight;
uniform float content_total;      // 整段动画的设备时长（Android totalSeconds）：寿命加长不越过它
uniform float frame_time;         // 本帧的设备时刻（出生帧的时刻）
layout(std430,binding=1) readonly buffer Cells{float cumulative[];} cells;
layout(std430,binding=2) buffer Emphasis{vec2 e[];} emphasis_buf;   // 每颗：强调量、本体色份额
uniform float u_touchGap;         // Android 口径：真实触点到边缘的距离（短边为单位）；< 0 时不偏转（源码）

uint hash(uint x){
    x^=x>>15;x*=0x85ebca6bu;x^=x>>13;x*=0xc2b2ae35u;x^=x>>16;return x;
}
float getRandomValue(float mn,float mx,uint seed){
    uint hashed=hash(seed);
    float randFloat=float(hashed)/float(0xFFFFFFFFu);
    return mn+randFloat*(mx-mn);
}
// 半分辨率快照的最近邻取样：半分辨率纹素 (i,j) = 原图像素 (2i+1, 2j+1)
ivec2 halfTexel(vec2 uv){
    ivec2 t=ivec2(floor(uv*vec2(half_size)));
    t=clamp(t,ivec2(0),half_size-1);
    return min(2*t+1,full_size-1);
}
vec4 sampleMask(vec2 uv){return texelFetch(mask_full,halfTexel(uv),0);}
vec4 sampleImage(vec2 uv){vec4 c=texelFetch(image_full,halfTexel(uv),0);c.rgb*=c.a;return c;}

// 诊断：出生位置的坐标约定与哈希种子。pos_mode 0 = 源码；1 x 翻转、2 y 翻转、3 都翻；4～7 先交换两个随机数再同上。
// pos_seed 为 x、y 两个随机数的种子偏移（源码 1、3）。
uniform int pos_mode;
uniform uvec2 pos_seed;
int pickCell(float r){
    int lo=0,hi=content_grid.x*content_grid.y-1;
    while(lo<hi){int mid=(lo+hi)/2;if(cells.cumulative[mid]<r)lo=mid+1;else hi=mid;}
    return lo;
}
float smoothUnit(float x){float t=clamp(x,0.0,1.0);return t*t*(3.0-2.0*t);}
float contentWeight(vec3 c){
    float chroma=smoothUnit((max(c.r,max(c.g,c.b))-min(c.r,min(c.g,c.b))-0.30)/0.42);
    vec3 d=abs(c-content_panel);
    float contrast=smoothUnit((max(d.r,max(d.g,d.b))-0.06)/0.26);
    return chroma+(contrast-chroma)*content_panel_weight;
}
vec2 calculateParticlePosition(uint index){
    vec2 pos=vec2(0.0);
    float minX=position_cfg.x,maxX=position_cfg.x+emitSize.x;
    float minY=position_cfg.y,maxY=position_cfg.y+emitSize.y;
    if(use_content==1&&index>=content_base){
        int cell=pickCell(getRandomValue(0.0,1.0,index*7u+5u));
        vec2 inCell=vec2(getRandomValue(0.0,1.0,index+1u),getRandomValue(0.0,1.0,index+3u));
        vec2 cellUv=(vec2(float(cell%content_grid.x),float(cell/content_grid.x))+inCell)/vec2(content_grid);
        return position_cfg+min(cellUv,vec2(0.99999))*emitSize;
    }
    if(pos_mode==0){
        pos.x=getRandomValue(minX,maxX,index+pos_seed.x);
        pos.y=getRandomValue(minY,maxY,index+pos_seed.y);
        return pos;
    }
    float hx=getRandomValue(0.0,1.0,index+pos_seed.x),hy=getRandomValue(0.0,1.0,index+pos_seed.y);
    if(pos_mode>=4){float t=hx;hx=hy;hy=t;}
    int m=pos_mode&3;
    if(m==1||m==3)hx=1.0-hx;
    if(m==2||m==3)hy=1.0-hy;
    return position_cfg+vec2(hx,hy)*emitSize;
}
void createNewParticles(uint index){
    vec2 pos=calculateParticlePosition(index);
    vec2 uv=(pos-position_cfg)/emitSize;
    vec4 ablationMask=sampleMask(uv);
    if(ablationMask.a>0.9)return;
    particles.v[index].position=pos;
    particles.v[index].initPosition=particles.v[index].position;
    particles.v[index].lifeTime=max(getRandomValue(lifeTime_cfg.x,lifeTime_cfg.y,index+8u),0.001);
    uv=(particles.v[index].position-position_cfg)/emitSize;
    particles.v[index].color=sampleImage(uv);
    if(particles.v[index].color.a<1.0){
        particles.v[index].position=vec2(-1.0);
        return;
    }
    if(use_content==1){
        // 内容权重取暗色下限之前的颜色（与 Android 相同）
        float w=contentWeight(particles.v[index].color.rgb);
        float em=content_panel_weight*w;
        if(neutral_content==1){
            vec3 rgb=particles.v[index].color.rgb;
            float neutral=1.0-smoothstep(0.04,0.20,max(rgb.r,max(rgb.g,rgb.b))-min(rgb.r,min(rgb.g,rgb.b)));
            if(index>=content_base&&getRandomValue(0.0,1.0,index+81u)<neutral){
                particles.v[index].position=vec2(-1.0);
                return;
            }
            em*=1.0-neutral;
        }
        emphasis_buf.e[index]=vec2(em,content_panel_weight*(1.0-w));
        float life=particles.v[index].lifeTime;
        particles.v[index].lifeTime=min(life*(1.0+0.30*em),max(content_total-frame_time,life));
    }
    float randomAlpha=getRandomValue(0.6,1.0,index+13u);
    particles.v[index].color.a=randomAlpha;
    if(particles.v[index].color.r<u_darkClamp&&particles.v[index].color.g<u_darkClamp&&particles.v[index].color.b<u_darkClamp){
        particles.v[index].color.r=u_darkClamp;particles.v[index].color.g=u_darkClamp;particles.v[index].color.b=u_darkClamp;
    }
    particles.v[index].radius=getRandomValue(radius_cfg-radiusRange,radius_cfg+radiusRange,index);
    particles.v[index].scale=0.0;
    particles.v[index].activeTime+=deltaT;
}
''' + SIMPLEX + r'''
float fbm(vec3 p){
    float total=0.0,amp=1.0,freq=1.0;
    total+=snoise(p*freq)*amp;
    if(u_comp>=2.0){amp*=u_omult;freq*=u_oscale;total+=snoise(p*freq)*amp;}
    if(u_comp>=3.0){amp*=u_omult;freq*=u_oscale;total+=snoise(p*freq)*amp;}
    if(u_comp>=4.0){amp*=u_omult;freq*=u_oscale;total+=snoise(p*freq)*amp;}
    return total;
}
void UpdatePosition(int index,float dT){
    Particle p=particles.v[index];
    vec2 pos=p.initPosition;
    float dt=p.activeTime;
    vec2 aeWind=vec2(u_wind.x,-u_wind.y);
    float accel=u_accel*u_density;
    float a_rand=getRandomValue(0.0,1.0,uint(index)+11u);
    float randFactor=u_dragBase+a_rand*u_dragRange;
    float aspectScale=min(u_aspect,1.0);
    vec2 windVec=aeWind*accel*dt*dt*0.5*randFactor;
    windVec.x*=aspectScale;
    if(u_touchGap>=0.0){
        // Android 口径：风向按「出生位置 → 终点」的方位偏转 0.13（与 drift-update.comp 相同）
        vec2 dir=normalize(aeWind);
        vec2 center=position_cfg+emitSize*0.5;
        float edge=min(emitSize.x/(2.0*max(abs(dir.x),1e-6)),emitSize.y/(2.0*max(abs(dir.y),1e-6)));
        vec2 destination=center+dir*(edge+min(emitSize.x,emitSize.y)*u_touchGap);
        vec2 bearing=normalize(destination-p.initPosition);
        float turn=0.13*atan(dot(bearing,vec2(-dir.y,dir.x)),dot(bearing,dir));
        windVec=mat2(cos(turn),sin(turn),-sin(turn),cos(turn))*windVec;
    }
    float turbFreq=500.0/(u_scale+50.0);
    float turbEvoTime=dt*u_evo*u_evoMult;
    float timeOffset=mod(getRandomValue(0.0,1.0,uint(index)+12u)*10000.0,100.0)/1000.0;
    float particlePhase=a_rand*u_particlePhase;
    vec3 samplePos=vec3((p.initPosition*u_turbScale+u_turbShift)*turbFreq+u_turbOffset,turbEvoTime+timeOffset+particlePhase);
    float noiseX=fbm(samplePos);
    float noiseY=fbm(samplePos+vec3(u_noiseOffsetX,u_noiseOffsetY,0.0));
    vec2 turbVec=vec2(noiseX,noiseY)*u_amp*dt;
    pos.xy+=turbVec+windVec;
    pos.x/=u_aspect;
    p.position=pos;
    particles.v[index]=p;
}
void UpdateScale(uint index){
    Particle p=particles.v[index];
    float lifeRatio=p.activeTime/p.lifeTime;
    float grow=clamp(lifeRatio/0.2,0.0,1.0);
    float fade=clamp((1.0-lifeRatio)/0.4,0.0,1.0);
    p.scale=p.radius*grow*fade;
    particles.v[index]=p;
}
bool UpdateActiveTime(int index,float dT){
    if(particles.v[index].activeTime<0.0)return false;
    particles.v[index].activeTime+=dT;
    if(particles.v[index].activeTime>=particles.v[index].lifeTime){
        particles.v[index].activeTime=-1.0;
        particles.v[index].position=vec2(-1.0);
        return false;
    }
    return true;
}
void main(){
    uint index=gl_GlobalInvocationID.x;
    if(index>=maxParticleCount)return;
    if(particles.v[index].activeTime==0.0&&particles.v[index].position==vec2(0.0)){
        createNewParticles(index);
    }else{
        if(!UpdateActiveTime(int(index),deltaT))return;
        UpdatePosition(int(index),deltaT);
        UpdateScale(index);
    }
}
'''

PARTICLE_VERTEX = r'''#version 430
struct Particle{vec2 position;vec2 initPosition;vec4 color;float lifeTime;float activeTime;float radius;float scale;};
layout(std430,binding=0) readonly buffer Particles{Particle v[];} particles;
layout(std430,binding=2) readonly buffer Emphasis{vec2 e[];} emphasis_buf;
uniform int use_content;
uniform vec2 work_size;
uniform int idx_lo, idx_hi;   // 只画索引在 [idx_lo, idx_hi) 的颗粒（诊断用，默认全部）
uniform float age_lo, age_hi; // 只画活动时间在 [age_lo, age_hi) 秒的颗粒（诊断用，默认全部）
uniform float size_factor;     // 半宽 = scale * size_factor
uniform float body_size_cut;
uniform float min_scale;       // 绘制时的最小 scale
uniform int use_radius;        // 1：尺寸取 radius（诊断用）；2：按 radius 画、scale/radius 进不透明度；3：按窗口重算；
                               // 4：同 2，但只取衰减段（出生即满尺寸，生长段不进尺寸与不透明度）；5：同 2，生长衰减按窗口重算
uniform float alpha_power;
uniform float grow_window, fade_window;
uniform float alpha_window;
uniform float fade_size_power, fade_alpha_power;
uniform float feather;
out vec2 local;
out vec4 color;
out float half_extent;
out float fade_alpha;
out float fade_f;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(-1,1),vec2(-1,1),vec2(1,-1),vec2(1,1));
void main(){
    Particle p=particles.v[gl_InstanceID];
    float f=clamp(p.scale/max(p.radius,1e-4),0.0,1.0);
    float lr=p.activeTime/max(p.lifeTime,1e-4);
    float wf=clamp(lr/max(grow_window,1e-4),0.0,1.0)*clamp((1.0-lr)/max(fade_window,1e-4),0.0,1.0);
    if(use_radius==4)f=clamp((1.0-lr)/max(fade_window,1e-4),0.0,1.0);
    if(use_radius==5)f=wf;
    bool split=use_radius==2||use_radius==4||use_radius==5;
    float sz=use_radius==1?p.radius:(split?p.radius*pow(f,1.0-alpha_power):(use_radius==3?p.radius*wf:p.scale));
    fade_alpha=split?pow(f,alpha_power):1.0;
    if(use_radius==6){
        // 生长段与衰减段分开：生长段同 radius_alpha（√ 分配），衰减段尺寸取 g^fade_size_power、不透明度取 g^fade_alpha_power
        float gr=clamp(lr/0.2,0.0,1.0), fd=clamp((1.0-lr)/0.4,0.0,1.0);
        sz=p.radius*pow(gr,1.0-alpha_power)*pow(fd,fade_size_power);
        fade_alpha=pow(gr,alpha_power)*pow(fd,fade_alpha_power);
        if(p.scale<=0.0)sz=0.0;
    }
    if(use_content==1){
        vec2 g=emphasis_buf.e[gl_InstanceID];
        sz*=(1.0+0.20*g.x)*(1.0-body_size_cut*g.y);
        fade_alpha*=(1.0+0.25*g.x)*(1.0-0.35*g.y);
    }
    fade_f=clamp((1.0-lr)/max(alpha_window,1e-4),0.0,1.0);   // 只取寿命末段 alpha_window 内的衰减（生长段不算）
    float h=max(sz,min_scale)*size_factor;
    float pad=h+feather;
    local=corners[gl_VertexID]*pad;half_extent=h;color=p.color;
    vec2 pos=p.position+local;
    if(p.activeTime<=0.0||p.scale<=0.0||gl_InstanceID<idx_lo||gl_InstanceID>=idx_hi||p.activeTime<age_lo||p.activeTime>=age_hi)pos=vec2(-1e5);
    // 工作面纹理同样按自上而下的行号存放（第 r 行 = 工作面 y∈[r,r+1)）
    gl_Position=vec4(pos.x/work_size.x*2.0-1.0,pos.y/work_size.y*2.0-1.0,0.0,1.0);
}
'''

PARTICLE_FRAGMENT = r'''#version 430
in vec2 local;in vec4 color;in float half_extent;in float fade_alpha;in float fade_f;
out vec4 result;
uniform int shape;      // 0 圆片 1 方块 2 软点 3 点纹理
uniform sampler2D sprite_tex;
uniform float feather;
uniform float gain;
uniform float opacity;
uniform int linear_light;
uniform int premul_as_is;
uniform float alpha_mul;
uniform float alpha_extra;   // 只乘在 alpha（遮挡背景的程度）上的额外衰减 f^alpha_extra，颜色不变
uniform int clamp_alpha;     // 1：颗粒 alpha（随机 alpha × 生长衰减）先截到 1 再乘点纹理覆盖率（Android 移植口径，强调可使它超过 1）
#include "drift-coverage.glsl"
vec3 s2l(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(0.04045,c));}
void main(){
    float cover;
    if(shape==1){
        vec2 d=abs(local)-vec2(half_extent);
        float e=max(d.x,d.y);
        cover=clamp(0.5-e/max(feather,1e-3),0.0,1.0);
    }else if(shape==2){
        float r=length(local)/max(half_extent,1e-3);
        cover=exp(-2.5*r*r);
    }else if(shape==4){
        vec2 q=local/max(half_extent,1e-6);
        cover=driftParticleCoverage(q,dFdx(q),dFdy(q));
    }else if(shape==3){
        vec2 q=local/max(half_extent,1e-3);
        cover=(abs(q.x)<=1.0&&abs(q.y)<=1.0)?texture(sprite_tex,q*0.5+0.5).a:0.0;
    }else{
        float e=length(local)-half_extent;
        cover=clamp(0.5-e/max(feather,1e-3),0.0,1.0);
    }
    if(cover<=0.0)discard;
    float sprite=cover;
    cover*=fade_alpha;
    float a=color.a*cover*opacity;
    if(clamp_alpha==1)a=min(color.a*fade_alpha,1.0)*sprite*opacity;
    vec3 rgb=min(color.rgb*gain,vec3(1.0));
    if(linear_light==1)rgb=s2l(rgb);
    float ax=alpha_extra>0.0?pow(max(fade_f,0.0),alpha_extra):1.0;
    result=vec4(rgb*(premul_as_is==1?cover*opacity:a),a*alpha_mul*ax);      // 预乘；alpha_mul、alpha_extra 只改遮挡背景的程度
}
'''

PARTICLE_FRAGMENT = PARTICLE_FRAGMENT.replace('#include "drift-coverage.glsl"',
    (HERE.parents[3] / 'shared/particle-dismiss/drift-coverage.glsl').read_text('utf-8'))

# 每个场景像素在设备像素里超采样合成：粒子工作面 ×2 最近邻放大，剩余快照 1:1 居中叠在上面。
COMPOSITE_FRAGMENT = r'''#version 430
in vec2 v_uv;
layout(location=0) out vec4 result;
uniform sampler2D background;    // 场景分辨率
uniform sampler2D particle_tex;  // 工作面（预乘）
uniform sampler2D mask_tex;      // 原分辨率剩余快照（预乘）
uniform vec2 frame;              // 场景像素
uniform vec2 card_center;        // 场景像素
uniform float scale;             // 设备像素 / 场景像素
uniform vec2 canvas;             // 扩展画布（设备像素，整数）
uniform vec2 snap;               // 快照尺寸（设备像素）
uniform ivec2 work_size;
uniform int ss;
uniform int show_particles;
uniform float dim;
uniform vec3 expo_bg_a, expo_bg_o, expo_c_a, expo_c_o;   // 相机自动曝光（背景、卡片与颗粒分开）
uniform sampler2D screen_tex;    // 手持拍摄的参考：手机屏幕以外是实景，飞出屏幕的颗粒不可见
uniform int use_screen;
uniform sampler2D overlay_tex;   // 盖在颗粒上面的叠加层（状态栏图标、灵动胶囊、视频字幕与水印），straight alpha
uniform int use_overlay;
uniform int linear_light;        // 颗粒层是线性光：设备像素上的混合在线性光里做，再编码后按场景像素平均
vec4 over(vec4 top,vec4 bottom){return top+bottom*(1.0-top.a);}
vec3 s2l(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(0.04045,c));}
vec3 l2s(vec3 c){c=clamp(c,0.0,1.0);return mix(c*12.92,1.055*pow(c,vec3(1.0/2.4))-0.055,step(0.0031308,c));}
vec4 lin_premult(vec4 p){if(p.a<=0.0)return vec4(0.0);return vec4(s2l(p.rgb/p.a)*p.a,p.a);}
void main(){
    vec2 pix=vec2(v_uv.x,1.0-v_uv.y)*frame;          // 场景像素坐标（自上而下），像素左上角为整数
    vec2 base=floor(pix);
    vec3 bg=texture(background,vec2(v_uv.x,v_uv.y)).rgb*(1.0-dim);
    bg=expo_bg_a*bg+expo_bg_o/255.0;
    vec4 acc=vec4(0.0);
    vec2 origin=(canvas-snap)*0.5;
    for(int j=0;j<ss;j++)for(int i=0;i<ss;i++){
        vec2 q=base+(vec2(i,j)+0.5)/float(ss);
        vec2 X=(q-card_center)*scale+canvas*0.5;          // 扩展画布中的设备像素坐标
        vec4 c=vec4(0.0);
        bool on_screen=use_screen==0||texelFetch(screen_tex,ivec2(clamp(floor(q),vec2(0.0),frame-1.0)),0).r>0.5;
        if(show_particles==1&&on_screen&&all(greaterThanEqual(X,vec2(0.0)))&&all(lessThan(X,canvas))){
            ivec2 w=ivec2(floor(X*0.5));
            if(all(lessThan(w,work_size)))c=min(texelFetch(particle_tex,w,0),vec4(1.0));   // 8 位工作面
        }
        vec2 M=X-origin;
        vec4 snapv=vec4(0.0);
        if(all(greaterThanEqual(M,vec2(0.0)))&&all(lessThan(M,snap)))snapv=texelFetch(mask_tex,ivec2(floor(M)),0);
        if(linear_light==1){
            vec4 top=over(lin_premult(snapv),c);
            acc+=vec4(l2s(top.rgb+s2l(bg)*(1.0-top.a)),1.0);
        }else{
            c=over(snapv,c);
            c.rgb=expo_c_a*c.rgb+expo_c_o/255.0*c.a;
            acc+=over(c,vec4(bg,1.0));
        }
    }
    vec3 rgb=acc.rgb/float(ss*ss);
    if(use_overlay==1){vec4 ov=texture(overlay_tex,v_uv);rgb=mix(rgb,ov.rgb,ov.a);}
    result=vec4(rgb,1.0);
}
'''


# ---------------------------------------------------------------- 渲染器

def screen_mask(scene, frame):
    """手持参考的手机屏幕区域（场景分辨率，行自上而下）；屏幕录制与自有弹窗返回 None。"""
    path = HERE / 'assets' / scene / 'screen-mask.png'
    if path.exists():
        a = np.asarray(Image.open(path).convert('L'))
        if a.shape == (frame[1], frame[0]):
            return np.ascontiguousarray((a > 127).astype(np.uint8) * 255)
    rects = {'reference-0926-3': (80, 120, 640, 1080)}   # 与 analysis/match_observe.SCREEN 一致
    if scene in rects:
        x0, y0, x1, y1 = rects[scene]
        a = np.zeros((frame[1], frame[0]), np.uint8)
        a[y0:y1, x0:x1] = 255
        return a
    return None


def _srgb_to_linear(a):
    a = a.astype(np.float32) / 255.0
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def _linear_to_srgb(x):
    x = np.clip(x, 0.0, 1.0)
    return (np.where(x <= 0.0031308, x * 12.92, 1.055 * x ** (1 / 2.4) - 0.055) * 255 + 0.5).astype(np.uint8)


def apply_capture(with_particles, bare, luma_sigma, chroma_sigma, white=None, particle_gain=1.0,
                  halo_sigma=0.0, halo_weight=0.0, sharpen=0.0, sharpen_sigma=1.5, white_luma=False, inc_gamma=1.0):
    """手持屏摄：卡片与背景取自原片，本来就带着相机的模糊；颗粒是新画上去的，要补上同一台相机的点扩散。
    颗粒贡献（含颗粒的画面 − 不含颗粒的画面）在线性光里做亮度模糊，再只对颗粒贡献做色度模糊
    （小颗粒的颜色被相机的去马赛克与 4:2:0 抹掉）。
    white：相机的高光上限（拍摄画面的编码值）。卡片与背景已经是相机拍下的值，颗粒却在这个空间里加光叠加，
    叠到 255 也不受相机曝光的约束；颗粒把像素提亮的部分按软拐点压到这个上限以内。
    sharpen：相机的锐化（反锐化掩模，在点扩散之后）：颗粒贡献加上 sharpen ×（贡献 − σ=sharpen_sigma 的模糊），
    只增加细节、不改变区域平均亮度。
    inc_gamma：颗粒在编码值里的提亮量按 255·(增量/255)^inc_gamma 重映射（<1 时淡的颗粒提得更多）。
    对应「颗粒合成之后整段视频又过了一道色调曲线」：画面里的卡片与背景已经带着这道曲线，颗粒的半透明部分却没有。
    white_luma：高光上限按亮度压（颗粒增量整体按比例缩小，保持增量的色相）。逐通道压时，背景某一通道已高于拐点
    （参考 1 的浅蓝背景 B≈232）的地方颗粒加不进这一通道，白颗粒被压成黄绿。"""
    import cv2
    out = with_particles
    if inc_gamma != 1.0:
        x = with_particles.astype(np.float32); b = bare.astype(np.float32)
        inc = x - b
        pos = np.clip(inc, 0.0, None)
        boosted = 255.0 * (pos / 255.0) ** float(inc_gamma)
        x = np.where(inc > 0, b + boosted, x)
        with_particles = np.clip(x + 0.5, 0, 255).astype(np.uint8)
        out = with_particles
    if luma_sigma > 0 or particle_gain != 1.0 or halo_weight > 0 or sharpen > 0:
        # particle_gain：相机对小面积亮细节的响应（局部色调映射、锐化），按线性光乘在颗粒贡献上
        # halo：镜头眩光——点扩散函数除了 σL 的核心，还有一圈很宽的晕（能量守恒地分出 halo_weight）
        lb = _srgb_to_linear(bare)
        d = _srgb_to_linear(with_particles) - lb
        core = cv2.GaussianBlur(d, (0, 0), float(luma_sigma)) if luma_sigma > 0 else d
        if halo_weight > 0 and halo_sigma > 0:
            core = (1.0 - halo_weight) * core + halo_weight * cv2.GaussianBlur(d, (0, 0), float(halo_sigma))
        if sharpen > 0:
            core = core + float(sharpen) * (core - cv2.GaussianBlur(core, (0, 0), float(sharpen_sigma)))
        out = _linear_to_srgb(lb + core * float(particle_gain))
    if chroma_sigma > 0:
        ya = cv2.cvtColor(out, cv2.COLOR_RGB2YCrCb).astype(np.float32)
        yb = cv2.cvtColor(bare, cv2.COLOR_RGB2YCrCb).astype(np.float32)
        ya[:, :, 1:] = yb[:, :, 1:] + cv2.GaussianBlur(ya[:, :, 1:] - yb[:, :, 1:], (0, 0), float(chroma_sigma))
        out = cv2.cvtColor(np.clip(ya + 0.5, 0, 255).astype(np.uint8), cv2.COLOR_YCrCb2RGB)
    if white and white_luma:
        w = float(white)
        k = w - 48.0
        lw = np.float32([.299, .587, .114])
        x = out.astype(np.float32)
        b = bare.astype(np.float32)
        lx, lb = x @ lw, b @ lw
        knee = np.where(lx > k, k + (w - k) * np.tanh((lx - k) / (w - k)), lx)
        target = np.maximum(knee, np.minimum(lx, lb))
        inc = lx - lb
        sc = np.where(inc > 1e-3, np.clip((target - lb) / np.maximum(inc, 1e-3), 0.0, 1.0), 1.0)
        x = b + (x - b) * sc[..., None]
        out = np.clip(x + 0.5, 0, 255).astype(np.uint8)
    elif white:
        w = float(white)
        k = w - 48.0
        x = out.astype(np.float32)
        knee = np.where(x > k, k + (w - k) * np.tanh((x - k) / (w - k)), x)
        b = bare.astype(np.float32)
        x = np.where(x > b, np.maximum(knee, np.minimum(x, b)), x)
        out = np.clip(x + 0.5, 0, 255).astype(np.uint8)
    return out


class _Uniforms:
    """编译器会删掉没用到的 uniform（例如被 UpdateScale 覆盖的尺寸参数），写入时跳过。"""
    def __init__(self, program):
        self.program = program

    def __setitem__(self, key, value):
        try:
            self.program[key] = value
        except KeyError:
            pass


class NativeRenderer:
    def __init__(self, scene, inputs=None, params=None, raster=None, ctx=None, ss=4,
                 image=None, background=None, rect=None, frame=None):
        self.inputs = inputs or NativeInputs()
        self.params = params or FormParams()
        self.raster = raster or Raster()
        if self.inputs.raster_override:
            self.raster = replace(self.raster, **dict(self.inputs.raster_override))
        self.ss = ss
        self.owns_context = ctx is None
        self.ctx = ctx or moderngl.create_standalone_context(require=430)
        self.name = scene
        image_given = image is not None
        if image is None:
            d = HERE / 'assets' / scene
            self.meta = json.loads((d / 'scene.json').read_text('utf-8'))
            # 原片抠出的卡片图若混入了与卡片无关的叠加物（录屏的触摸指示点等），用修过的副本
            clean = d / 'foreground-clean.png'
            image = Image.open(clean if clean.exists() else d / 'foreground.png').convert('RGBA')
            # 重建背景里若残留了原片的颗粒（参考 3 顶部），用修过的副本
            clean_bg = d / 'background-clean.png'
            background = Image.open(clean_bg if clean_bg.exists() else d / 'background.png').convert('RGB')
            rect = self.meta['rect']
            frame = self.meta['frame']
        else:
            self.meta = dict(rect=list(rect), frame=list(frame), dim_alpha=0.0)
        self.frame = tuple(frame)
        self.rect = tuple(rect)
        x0, y0, x1, y1 = self.rect
        cw, ch = x1 - x0, y1 - y0
        i, q = self.inputs, self.params
        s = i.scale
        # 快照尺寸（设备像素，整数）
        self.snap = (max(2, int(round(cw * s))), max(2, int(round(ch * s))))
        if i.expansion is None:
            ex = (min(frame[0] * s, q.expansion_cap[0]), min(frame[1] * s, q.expansion_cap[1]))
        else:
            ex = tuple(float(v) for v in i.expansion)
        self.expansion = ex
        self.canvas = (int(ex[0]), int(ex[1]))                     # cvttps2dq
        self.work = (int(ex[0] * 0.5), int(ex[1] * 0.5))           # cvttps2dq(exp*0.5)
        self.half = (int(self.snap[0] * 0.5), int(self.snap[1] * 0.5))
        self.emit_size = (self.snap[0] * 0.5, self.snap[1] * 0.5)
        self.position = ((self.canvas[0] * 0.5 - self.emit_size[0]) * 0.5,
                         (self.canvas[1] * 0.5 - self.emit_size[1]) * 0.5)
        self.card_center = ((x0 + x1) * 0.5, (y0 + y1) * 0.5)
        self.random_value = (mask_random(i.seed) if i.random is None else float(i.random))
        self.objects = []
        own = self._own
        img = image.resize(self.snap, Image.BICUBIC)
        a = np.asarray(img).astype(np.float32) / 255.0
        self.image_tex = own(self.ctx.texture(self.snap, 4, a.astype('f4').tobytes(), dtype='f4'))
        self.image_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        bg = np.asarray(background.convert('RGB'))[::-1].copy()
        self.background = own(self.ctx.texture(self.frame, 3, bg.tobytes()))
        self.background.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.mask_tex = own(self.ctx.texture(self.snap, 4, dtype='f4'))
        self.mask_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.mask_fbo = own(self.ctx.framebuffer([self.mask_tex]))
        self.work_tex = own(self.ctx.texture(self.work, 4, dtype='f4'))
        self.work_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.work_fbo = own(self.ctx.framebuffer([self.work_tex]))
        self.out_tex = own(self.ctx.texture(self.frame, 4))
        # 手持拍摄的参考：按手机屏幕遮罩裁掉屏幕外的颗粒（屏幕录制没有遮罩，整幅都是屏幕）
        screen = screen_mask(scene, self.frame)
        if screen is not None:
            self.screen_tex = own(self.ctx.texture(self.frame, 1, screen.tobytes()))
            self.screen_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        else:
            self.screen_tex = own(self.ctx.texture((1, 1), 1, bytes([255])))
        # 盖在颗粒上面的叠加层（analysis/build_overlays.py 从原片提取），自有弹窗没有
        overlay = HERE / 'assets' / scene / 'overlay.png'
        if image_given is False and overlay.exists():
            ov = np.asarray(Image.open(overlay).convert('RGBA'))[::-1].copy()
            self.overlay_tex = own(self.ctx.texture(self.frame, 4, ov.tobytes()))
            self.overlay_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            use_overlay = 1
        else:
            self.overlay_tex = own(self.ctx.texture((1, 1), 4, bytes(4)))
            use_overlay = 0
        # 按参考进度显示的叠加层：顶部界面随时间的变化（录屏计时、状态栏与水印切换，analysis/build_timed_overlays.py）、
        # 卡片移除后桌面重排出现的元素（analysis/rebuild_late_background.py）。都在颗粒之上，合成之后（含相机口径）再叠。
        self.timed = []
        timed = HERE / 'assets' / scene / 'timed-overlays.json'
        if image_given is False and timed.exists():
            for e in json.loads(timed.read_text('utf-8')):
                a = np.asarray(Image.open(HERE / 'assets' / scene / e['file']).convert('RGBA')).astype(np.float32) / 255.0
                ys, xs = np.nonzero(a[:, :, 3] > 0)
                if not len(ys):
                    continue
                y0_, y1_, x0_, x1_ = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
                self.timed.append((float(e['from']), float(e['to']), (slice(y0_, y1_), slice(x0_, x1_)),
                                   a[y0_:y1_, x0_:x1_, :3] * 255.0, a[y0_:y1_, x0_:x1_, 3:]))
        # 手持屏摄里随手机晃动的屏幕反光（analysis/build_glare.py）：按参考进度插值的低频加性场，
        # 只加在屏幕内、卡片与叠加层以外，在逐帧遮挡与按进度叠加层之前（它们取自原片，本来就带着反光）
        self.glare = None
        self.glare_screen = None
        gl = HERE / 'assets' / scene / 'glare.npz'
        if image_given is False and gl.exists():
            z = np.load(gl)
            self.glare = (z['progress'].astype(np.float64), z['field'].astype(np.float32),
                          z['card'].astype(np.float32) if 'card' in z else None)
            if screen is not None:
                self.glare_screen = screen.astype(np.float32) / 255.0
        # 开场确认框淡出的尾段（analysis/build_dialog_fade.py）：逐帧遮挡层截止之后，按淡出系数加上确认框的加性图案
        self.dialog_fade = None
        df = HERE / 'assets' / scene / 'dialog-fade.npz'
        if image_given is False and df.exists():
            z = np.load(df)
            by0_, by1_, bx0_, bx1_ = (int(v) for v in z['box'])
            self.dialog_fade = (float(z['p_start']), z['progress'].astype(np.float64), z['f'].astype(np.float64),
                                (slice(by0_, by1_), slice(bx0_, bx1_)), z['K'].astype(np.float32))
        # 手持屏摄里入镜的手指等逐帧遮挡（analysis/build_occluder_frames.py）：按参考进度取最近的原片帧，在容差内才叠，最后一层
        self.occluders = []
        self.occluder_tol = 0.0
        occ = HERE / 'assets' / scene / 'occluder-frames.json'
        if image_given is False and occ.exists():
            o = json.loads(occ.read_text('utf-8'))
            self.occluder_tol = float(o.get('tolerance', 0.005))
            for e in o['frames']:
                a = np.asarray(Image.open(HERE / 'assets' / scene / e['file']).convert('RGBA')).astype(np.float32) / 255.0
                bx0, by0, bx1, by1 = e['box']
                self.occluders.append((float(e['progress']), (slice(by0, by1), slice(bx0, bx1)), a[..., :3] * 255.0, a[..., 3:]))
        self.out_fbo = own(self.ctx.framebuffer([self.out_tex]))
        n = int(i.count)
        self.n = n
        self.state = own(self.ctx.buffer(reserve=n * 48))
        # Android 移植口径的内容强调：每颗两个 float；分布表至少一项（着色器声明的缓冲始终有东西绑着）
        self.emphasis = own(self.ctx.buffer(reserve=n * 8))
        self.cells = own(self.ctx.buffer(np.ones(1, 'f4').tobytes()))
        self.compute = own(self.ctx.compute_shader(COMPUTE))
        self.mask_prog = own(self.ctx.program(vertex_shader=FULL_VERTEX, fragment_shader=MASK_FRAGMENT))
        self.mask_vao = own(self.ctx.vertex_array(self.mask_prog, []))
        self.part_prog = own(self.ctx.program(vertex_shader=PARTICLE_VERTEX, fragment_shader=PARTICLE_FRAGMENT))
        self.part_vao = own(self.ctx.vertex_array(self.part_prog, []))
        self.comp_prog = own(self.ctx.program(vertex_shader=FULL_VERTEX, fragment_shader=COMPOSITE_FRAGMENT))
        self.comp_vao = own(self.ctx.vertex_array(self.comp_prog, []))
        # 遮罩格点覆盖
        if i.lattice is not None:
            L = np.asarray(i.lattice, np.float32)
            self.lattice_tex = own(self.ctx.texture((L.shape[1], L.shape[0]), 1, L.tobytes(), dtype='f4'))
            self.lattice_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            self.mask_prog['use_lattice'] = 1
            self.mask_prog['lattice_size'] = (L.shape[1], L.shape[0])
        else:
            self.lattice_tex = own(self.ctx.texture((1, 1), 1, np.zeros(1, 'f4').tobytes(), dtype='f4'))
            self.mask_prog['use_lattice'] = 0
            self.mask_prog['lattice_size'] = (1, 1)
        self.mask_prog['image_tex'] = 0
        self.mask_prog['lattice_tex'] = 5
        self.mask_prog['iResolution'] = self.snap
        self.mask_prog['rate'] = q.ablation_rate
        self.mask_prog['u_mode'] = q.centers2[0]
        self.mask_prog['u_dir'] = q.centers2[1]
        c = _Uniforms(self.compute)
        c['mask_full'] = 1
        c['image_full'] = 0
        c['half_size'] = self.half
        c['full_size'] = self.snap
        c['position_cfg'] = self.position
        c['emitSize'] = self.emit_size
        c['maxParticleCount'] = n
        c['radius_cfg'] = q.centers0[0] * 0.5
        c['radiusRange'] = q.centers0[1] * 0.5
        c['u_aspect'] = 1.0
        c['u_sizeGrowTime'] = 0.0
        c['u_sizeShrinkTime'] = 5.0
        c['u_sizeMin'] = q.size_min
        c['u_wind'] = (i.wind_x * 0.5, q.wind_y * 0.5)
        c['u_density'] = q.density
        c['u_dragBase'] = q.drag_base
        c['u_dragRange'] = q.drag_range
        c['u_accel'] = q.accel
        c['u_scale'] = q.turb_scale * 0.5
        c['u_evo'] = q.turb_evo
        c['u_amp'] = q.turb_amp * 0.5
        c['u_comp'] = q.turb_comp
        c['u_omult'] = q.turb_omult
        c['u_oscale'] = q.turb_oscale
        c['u_evoMult'] = q.turb_evo_mult
        c['u_noiseOffsetX'] = q.noise_offset[0]
        c['u_noiseOffsetY'] = q.noise_offset[1]
        c['u_particlePhase'] = q.particle_phase
        c['u_darkClamp'] = q.dark_clamp
        c['pos_mode'] = 0
        c['pos_seed'] = (1, 3)
        c['u_turbScale'] = (1.0, 1.0)
        c['u_turbShift'] = (0.0, 0.0)
        c['u_turbOffset'] = (0.0, 0.0)
        c['use_content'] = 0
        c['content_base'] = n
        c['content_grid'] = (1, 1)
        c['content_panel'] = (0.0, 0.0, 0.0)
        c['content_panel_weight'] = 0.0
        c['content_total'] = 0.0
        c['u_touchGap'] = -1.0
        self.cu = c
        r = self.raster
        self.part_prog['work_size'] = self.work
        self.part_prog['idx_lo'] = 0
        self.part_prog['idx_hi'] = n
        self.part_prog['age_lo'] = 0.0
        self.part_prog['age_hi'] = 1e9
        self.part_prog['size_factor'] = (0.5 if r.size_is == 'diameter' else 1.0) * r.size_mul
        self.part_prog['body_size_cut'] = 0.26
        self.part_prog['feather'] = r.feather
        self.part_prog['shape'] = dict(disc=0, square=1, soft=2, sprite=3)[r.shape]
        # 系统镜像 scene_board/particle/dot01.png（9×9 白色软点，只用 alpha），双线性取样
        dot = np.asarray(Image.open(HERE / 'assets/system/dot01.png').convert('RGBA'))[::-1].copy()
        self.sprite_tex = own(self.ctx.texture((dot.shape[1], dot.shape[0]), 4, dot.tobytes()))
        self.sprite_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.sprite_tex.repeat_x = False
        self.sprite_tex.repeat_y = False
        self.part_prog['sprite_tex'] = 8
        self.part_prog['gain'] = r.gain
        self.part_prog['opacity'] = r.opacity
        self.part_prog['linear_light'] = int(r.linear)
        self.part_prog['premul_as_is'] = int(r.premul_as_is)
        self.part_prog['min_scale'] = r.min_scale
        self.part_prog['alpha_mul'] = r.alpha_mul
        self.part_prog['alpha_extra'] = r.alpha_extra
        _Uniforms(self.part_prog)['clamp_alpha'] = 0
        _Uniforms(self.part_prog)['use_content'] = 0
        self.part_prog['alpha_window'] = r.alpha_window
        self.part_prog['use_radius'] = dict(scale=0, radius=1, radius_alpha=2, window=3, radius_alpha_fade=4,
                                            radius_alpha_win=5, radius_alpha_split=6)[r.size_source]
        self.part_prog['fade_size_power'] = r.fade_size_power
        self.part_prog['fade_alpha_power'] = r.fade_alpha_power
        self.part_prog['grow_window'] = r.grow_window
        self.part_prog['fade_window'] = r.fade_window
        self.part_prog['alpha_power'] = r.alpha_power
        p = self.comp_prog
        p['background'] = 2
        p['particle_tex'] = 3
        p['mask_tex'] = 4
        p['frame'] = self.frame
        p['card_center'] = self.card_center
        p['scale'] = s
        p['canvas'] = self.canvas
        p['snap'] = self.snap
        p['work_size'] = self.work
        p['ss'] = ss
        p['show_particles'] = 1
        p['dim'] = 0.0
        for k_, v_ in (('expo_bg_a', (1.0, 1.0, 1.0)), ('expo_bg_o', (0.0, 0.0, 0.0)),
                       ('expo_c_a', (1.0, 1.0, 1.0)), ('expo_c_o', (0.0, 0.0, 0.0))):
            p[k_] = v_
        p['screen_tex'] = 6
        p['use_screen'] = 1 if screen is not None else 0
        p['overlay_tex'] = 7
        p['use_overlay'] = use_overlay
        p['linear_light'] = int(r.linear)
        self.reset()

    def _own(self, obj):
        self.objects.append(obj)
        return obj

    # 设备帧序列：第 k 帧（k≥1）在 T_k = k / hz；首帧 deltaT = first_dt，其后为毫秒差。
    def frame_time(self, k):
        return k / self.inputs.hz

    def frame_dt(self, k):
        if k <= 1:
            return self.params.first_dt
        t1, t0 = self.frame_time(k), self.frame_time(k - 1)
        if self.inputs.ms_clock:
            return (math.floor(t1 * 1000 + 1e-9) - math.floor(t0 * 1000 + 1e-9)) / 1000.0
        return t1 - t0

    def android_extras(self, turb_scale=(1.0, 1.0), turb_shift=(0.0, 0.0), turb_offset=(0.0, 0.0),
                       content=None, base_count=None, total_seconds=0.0, clamp_alpha=False, wind=None, size_scale=1.0,
                       touch_gap=None, current_visuals=True):
        """Android 移植口径的可选输入（缺省与源码相同）。

        wind：Android 的 aeWind（工作面单位，y 向下）；宿主 aeWind = (u_wind.x, −u_wind.y)。
        content：ParticleDriftContent 的结果 dict(panel, panelWeight, grid, cumulative, replicas)；
        粒子数 n 须已含副本，base_count 为其中源码颗粒的个数。
        size_scale：Android 设置里的「粒子大小」倍率，乘在颗粒半宽上（1 时与源码相同）。
        touch_gap：真实触点到边缘的距离（短边为单位）；给出时风向按出生位置到终点的方位偏转。"""
        c = self.cu
        c['u_turbScale'] = tuple(float(v) for v in turb_scale)
        c['u_turbShift'] = tuple(float(v) for v in turb_shift)
        c['u_turbOffset'] = tuple(float(v) for v in turb_offset)
        c['u_touchGap'] = -1.0 if touch_gap is None else float(touch_gap)
        if wind is not None:
            c['u_wind'] = (float(wind[0]), -float(wind[1]))
        use = content is not None and float(content.get('panelWeight', 0.0)) > 0.0
        c['use_content'] = int(use)
        _Uniforms(self.part_prog)['use_content'] = int(use)
        _Uniforms(self.part_prog)['clamp_alpha'] = int(bool(clamp_alpha))
        c['neutral_content'] = int(current_visuals)
        self.part_prog['body_size_cut'] = 0.16 if current_visuals else 0.26
        self.part_prog['shape'] = 4 if current_visuals else dict(disc=0, square=1, soft=2, sprite=3)[self.raster.shape]
        r = self.raster
        self.part_prog['size_factor'] = (0.5 if r.size_is == 'diameter' else 1.0) * r.size_mul * float(size_scale)
        if use:
            cum = np.asarray(content['cumulative'] or [1.0], np.float32)
            self.cells.orphan(cum.nbytes)
            self.cells.write(cum.tobytes())
            c['content_base'] = int(base_count)
            c['content_grid'] = tuple(int(v) for v in content['grid'])
            c['content_panel'] = tuple(float(v) for v in content['panel'])
            c['content_panel_weight'] = float(content['panelWeight'])
            c['content_total'] = float(total_seconds)
        self.reset()

    def reset(self):
        self.state.clear()
        self.emphasis.clear()
        self.k = 0
        self.mask_seconds = None

    def draw_mask(self, progress, random_value):
        self.mask_fbo.use()
        self.ctx.disable(moderngl.BLEND)
        self.image_tex.use(0)
        self.lattice_tex.use(5)
        self.mask_prog['iTime'] = float(progress)
        self.mask_prog['random_value'] = float(random_value)
        self.mask_vao.render(mode=moderngl.TRIANGLES, vertices=3)

    def step(self):
        """推进一个设备帧：OnProcessImage → DrawParticleImage。"""
        self.k += 1
        k = self.k
        T = self.frame_time(k)
        prog = min(1.0, T / self.params.duration)
        # 首帧之前缓存为空，random = 0.1；首帧起为本次动画的随机数
        rnd = 0.1 if k == 1 else self.random_value
        self.draw_mask(prog, rnd)
        q = self.params
        c = self.cu
        c['deltaT'] = float(self.frame_dt(k))
        c['frame_time'] = float(T)
        c['lifeTime_cfg'] = ((1.0 - prog) * q.centers1[0], (1.0 - prog) * q.centers1[1])
        self.image_tex.use(0)
        self.mask_tex.use(1)
        self.state.bind_to_storage_buffer(0)
        self.cells.bind_to_storage_buffer(1)
        self.emphasis.bind_to_storage_buffer(2)
        self.compute.run(group_x=(self.n + 255) // 256)
        self.ctx.memory_barrier()
        self.mask_seconds = T

    def device_time(self, p):
        i = self.inputs
        if i.time_knots:
            k = np.asarray(i.time_knots, np.float64)
            if p <= k[0, 0]:
                s = (k[1, 1] - k[0, 1]) / max(k[1, 0] - k[0, 0], 1e-9)
                return float(k[0, 1] + (p - k[0, 0]) * s)
            if p >= k[-1, 0]:
                s = (k[-1, 1] - k[-2, 1]) / max(k[-1, 0] - k[-2, 0], 1e-9)
                return float(k[-1, 1] + (p - k[-1, 0]) * s)
            return float(np.interp(p, k[:, 0], k[:, 1]))
        if i.span2 is None or p <= i.knee:
            return i.offset + p * i.span
        return i.offset + i.knee * i.span + (p - i.knee) * i.span2

    def ref_progress(self, T):
        """device_time 的反函数：设备时刻 → 参考进度。"""
        i = self.inputs
        if i.time_knots:
            k = np.asarray(i.time_knots, np.float64)
            if T <= k[0, 1]:
                s = (k[1, 1] - k[0, 1]) / max(k[1, 0] - k[0, 0], 1e-9)
                return float(k[0, 0] + (T - k[0, 1]) / max(s, 1e-9))
            if T >= k[-1, 1]:
                s = (k[-1, 1] - k[-2, 1]) / max(k[-1, 0] - k[-2, 0], 1e-9)
                return float(k[-1, 0] + (T - k[-1, 1]) / max(s, 1e-9))
            return float(np.interp(T, k[:, 1], k[:, 0]))
        Tk = i.offset + i.knee * i.span
        if i.span2 is None or T <= Tk:
            return (T - i.offset) / i.span
        return i.knee + (T - Tk) / i.span2

    def seek_time(self, T):
        target = int(math.floor(T * self.inputs.hz + 1e-6))
        if target < self.k:
            self.reset()
        while self.k < target:
            self.step()

    def render_time(self, T, particles=True, background=None, q=None):
        """设备时刻 T 的画面（场景分辨率 RGB）。q：对应的参考进度（按参考进度渲染时直接给出；
        逐帧时间表里相邻原片帧可能落在同一设备帧，反函数在这些点上有歧义），缺省按 ref_progress(T) 反算。"""
        qq = q
        stopped = T >= self.params.force_stop
        self.seek_time(min(T, self.params.force_stop))
        self.work_fbo.use()
        self.work_fbo.clear(0, 0, 0, 0)
        if particles and self.k >= 1 and not stopped:
            self.ctx.enable(moderngl.BLEND)
            self.ctx.blend_func = ((moderngl.ONE, moderngl.ONE) if self.raster.blend == 'add'
                                   else (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA))
            self.state.bind_to_storage_buffer(0)
            self.emphasis.bind_to_storage_buffer(2)
            self.sprite_tex.use(8)
            self.part_vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=self.n)
            self.ctx.disable(moderngl.BLEND)
        if self.k >= 1 and not stopped:
            prog = min(1.0, self.frame_time(self.k) / self.params.duration)
            self.draw_mask(prog, 0.1 if self.k == 1 else self.random_value)
        else:
            self.mask_fbo.use()
            if stopped:
                self.mask_fbo.clear(0, 0, 0, 0)
            else:
                self.draw_mask(0.0, 0.1)
        self.out_fbo.use()
        self.background.use(2)
        self.work_tex.use(3)
        self.mask_tex.use(4)
        self.screen_tex.use(6)
        self.overlay_tex.use(7)
        if self.inputs.backdrop and self.inputs.span > 0:
            q = self.ref_progress(T) if qq is None else qq
            pts = np.asarray(self.inputs.backdrop, np.float64)
            self.comp_prog['dim'] = float(1.0 - np.interp(q, pts[:, 0], pts[:, 1]))
        if self.inputs.exposure and self.inputs.span > 0:
            q = self.ref_progress(T) if qq is None else qq
            pts = np.asarray(self.inputs.exposure, np.float64)
            v = [float(np.interp(q, pts[:, 0], pts[:, k])) for k in range(1, 13)]
            self.comp_prog['expo_bg_a'] = tuple(v[0:3]); self.comp_prog['expo_bg_o'] = tuple(v[3:6])
            self.comp_prog['expo_c_a'] = tuple(v[6:9]); self.comp_prog['expo_c_o'] = tuple(v[9:12])
        out = self._composite()
        if self.raster.glow_gain > 0 and self.raster.glow_sigma > 0 and particles and self.k >= 1 and not stopped:
            import cv2
            self.comp_prog['show_particles'] = 0
            bare_g = self._composite()
            self.comp_prog['show_particles'] = 1
            lb = _srgb_to_linear(bare_g)
            d = _srgb_to_linear(out) - lb
            g = cv2.GaussianBlur(np.maximum(d, 0.0), (0, 0), float(self.raster.glow_sigma))
            out = _linear_to_srgb(lb + d + float(self.raster.glow_gain) * g)
        cap = self.inputs.capture
        if cap and particles and self.k >= 1 and not stopped:
            # 相机只拍到了颗粒的模糊像：同一时刻再合成一张不含颗粒的画面，颗粒贡献按相机口径模糊后再叠回去
            self.comp_prog['show_particles'] = 0
            bare = self._composite()
            self.comp_prog['show_particles'] = 1
            out = apply_capture(out, bare, *cap)
        if self.glare is not None and self.inputs.span > 0:
            out = self._apply_glare(out, self.ref_progress(T) if qq is None else qq)
        if self.dialog_fade is not None and self.inputs.span > 0:
            q = self.ref_progress(T) if qq is None else qq
            p_start, dp, df_, box, K = self.dialog_fade
            if q > p_start:
                f = float(np.interp(q, dp, df_, left=df_[0], right=0.0))
                if f > 0.003:
                    out[box] = np.clip(out[box].astype(np.float32) + f * K + 0.5, 0, 255).astype(np.uint8)
        if self.timed and self.inputs.span > 0:
            q = self.ref_progress(T) if qq is None else qq          # 参考进度
            for lo, hi, box, rgb, alpha in self.timed:
                if lo <= q < hi:
                    region = out[box].astype(np.float32)
                    out[box] = (region * (1.0 - alpha) + rgb * alpha + 0.5).clip(0, 255).astype(np.uint8)
        if self.occluders and self.inputs.span > 0:
            q = self.ref_progress(T) if qq is None else qq
            k = min(range(len(self.occluders)), key=lambda j: abs(self.occluders[j][0] - q))
            p_, box, rgb, alpha = self.occluders[k]
            if abs(p_ - q) <= self.occluder_tol:
                region = out[box].astype(np.float32)
                out[box] = (region * (1.0 - alpha) + rgb * alpha + 0.5).clip(0, 255).astype(np.uint8)
        return out

    def card_alpha(self):
        """当前合成状态下卡片（剩余快照）与叠加层的覆盖率：不画颗粒，背景分别换成黑与白各合成一次，(白 − 黑) = (1 − 覆盖率) × 255。"""
        p = self.comp_prog
        keep = (p['show_particles'].value, p['dim'].value, p['expo_bg_a'].value, p['expo_bg_o'].value)
        p['show_particles'] = 0
        p['dim'] = 0.0
        p['expo_bg_a'] = (0.0, 0.0, 0.0)
        p['expo_bg_o'] = (0.0, 0.0, 0.0)
        black = self._composite().astype(np.float32)
        p['expo_bg_o'] = (255.0, 255.0, 255.0)
        white = self._composite().astype(np.float32)
        p['show_particles'], p['dim'], p['expo_bg_a'], p['expo_bg_o'] = keep
        return np.clip(1.0 - (white - black).mean(2) / 255.0, 0.0, 1.0)

    def glare_field(self, q, which=1):
        """参考进度 q 处的残差场（场景分辨率，加性，编码值）：which=1 为卡片以外（反光、壁纸重建），2 为卡片本身。"""
        import cv2
        prog, field = self.glare[0], self.glare[which]
        j = int(np.searchsorted(prog, q))
        if j <= 0:
            f = field[0]
        elif j >= len(prog):
            f = field[-1]
        else:
            w = (q - prog[j - 1]) / max(prog[j] - prog[j - 1], 1e-9)
            f = field[j - 1] * (1.0 - w) + field[j] * w
        return cv2.resize(f, self.frame, interpolation=cv2.INTER_CUBIC)

    def _apply_glare(self, out, q):
        cov = self.card_alpha()
        wgt = 1.0 - cov
        if self.glare_screen is not None:
            wgt = wgt * self.glare_screen
        g = self.glare_field(q, 1) * wgt[..., None]
        if self.glare[2] is not None:
            # 卡片场按卡片覆盖率加（叠加层也算在覆盖率里，但叠加层取自原片，拟合时已排除，这里乘 (1 − 叠加层) 不必再细分）
            g = g + self.glare_field(q, 2) * cov[..., None]
        return np.clip(out.astype(np.float32) + g + 0.5, 0, 255).astype(np.uint8)

    def _composite(self):
        self.comp_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        w, h = self.frame
        return np.frombuffer(self.out_fbo.read(components=3, alignment=1), np.uint8).reshape(h, w, 3)[::-1].copy()

    def render(self, p):
        return self.render_time(self.device_time(p), q=float(p))

    def particles(self):
        d = np.frombuffer(self.state.read(), np.float32).reshape(self.n, 12)
        return dict(position=d[:, 0:2], init=d[:, 2:4], color=d[:, 4:8], life=d[:, 8], age=d[:, 9],
                    radius=d[:, 10], scale=d[:, 11])

    def describe(self):
        return dict(model='harmony-native', scene=self.name, inputs=asdict(self.inputs), params=asdict(self.params),
                    raster=asdict(self.raster), snap=self.snap, canvas=self.canvas, work=self.work,
                    position=self.position, emit_size=self.emit_size, random=self.random_value)

    def close(self):
        for obj in reversed(self.objects):
            obj.release()
        self.objects.clear()
        if self.owns_context:
            self.ctx.release()



# ---------------------------------------------------------------- 实时预览与预渲染的共同入口

REFERENCE_INPUTS = HERE / 'native_reference_inputs.json'
# 预览里的自有弹窗没有设备信息：按 1260 像素宽的手机取输入。
OWN_SCALE_WIDTH = 1260.0
# 粒子数：源码按卡片尺寸查表（1×2 1 万 … 4×4 4 万 … 6×4 5 万）。十二段参考都是 4×4 档，按索引区间逐颗重合实测为 4 万
# （native-replica-2026-10-04.md 第 29 节）。弹窗没有对应的档位：预览里按设备像素面积保持与 4×4 卡片相同的密度
# （4 万 / 1.3M 设备像素²），截到源码档位的范围 [1 万, 5 万]。是否用于 Android 实现另定。
PARTICLE_COUNT = 20000                # 无卡片尺寸信息时的 FormParticleConfig 默认值
REFERENCE_CARD_COUNT = 40000
REFERENCE_CARD_AREA = 1.3e6           # 4×4 卡片的设备像素面积（灭霸 1.36M、参考 6 1.43M、钢铁侠 1.11M）


def area_particle_count(rect, scale):
    x0, y0, x1, y1 = rect
    area = (x1 - x0) * scale * (y1 - y0) * scale
    return int(np.clip(round(REFERENCE_CARD_COUNT * area / REFERENCE_CARD_AREA / 1000.0) * 1000, 10000, 50000))


def reference_inputs():
    """十二段参考按原片估计的一次动画输入（格点值、横风、几何、时间口径）。"""
    if not REFERENCE_INPUTS.exists():
        return {}
    return json.loads(REFERENCE_INPUTS.read_text('utf-8'))


# 镜像的 setParticleParam 取 randomInteger(-30000, 30000)；十二段原片按每次动画估计的横风在 −55000～+30000，
# 超出镜像范围（参考视频所用版本更宽），随机播放按 ±60000 取。
WIND_X_RANGE = 60000


def wind_for_direction(direction):
    """把触点方向（度，0 向右、90 向上）换成横风：wind = [wx, 40000]，wx ∈ [-WIND_X_RANGE, WIND_X_RANGE]。"""
    a = math.radians(direction)
    dx, dy = math.cos(a), math.sin(a)
    if dy <= 1e-6:
        return float(WIND_X_RANGE if dx > 0 else -WIND_X_RANGE)
    return float(np.clip(40000.0 * dx / dy, -WIND_X_RANGE, WIND_X_RANGE))


def inputs_from_entry(e, seed=1, **override):
    """参考输入表的一条 → NativeInputs（含相机口径、开场遮罩、自动曝光）。分析脚本与预览共用。"""
    cap = e.get('capture')
    kw = dict(scale=e['scale'], expansion=tuple(e['expansion']), count=int(e['count']), wind_x=e['wind_x'],
              lattice=tuple(map(tuple, e['lattice'])), hz=e.get('hz', 120.0), span=e['span'], offset=e['offset'],
              span2=e.get('span2'), knee=float(e.get('knee', 0.5)),
              time_knots=tuple(map(tuple, e['time_knots'])) if e.get('time_knots') else None,
              raster_override=tuple(sorted(e['raster_override'].items())) if e.get('raster_override') else None,
              seed=int(seed),
              capture=(float(cap['luma_sigma']), float(cap['chroma_sigma']), cap.get('white'),
                       float(cap.get('particle_gain', 1.0)), float(cap.get('halo_sigma', 0.0)),
                       float(cap.get('halo_weight', 0.0)), float(cap.get('sharpen', 0.0)),
                       float(cap.get('sharpen_sigma', 1.5)), bool(cap.get('white_luma', False)),
                       float(cap.get('inc_gamma', 1.0))) if cap else None,
              backdrop=tuple(map(tuple, e['backdrop'])) if e.get('backdrop') else None,
              exposure=tuple(map(tuple, e['exposure']['points'])) if e.get('exposure') else None)
    kw.update(override)
    return NativeInputs(**kw)


def live_inputs(scene, meta, kind, seed, direction=None):
    """kind：ref 用原片估计的输入；back / tap 按种子生成随机输入（横风、遮罩随机数）。"""
    table = reference_inputs()
    if kind == 'ref' and scene in table:
        e = table[scene]
        return inputs_from_entry(e, seed), e
    w, h = meta['frame']
    if scene in table:
        e = table[scene]
        scale, expansion, count = e['scale'], tuple(e['expansion']), e['count']
    else:
        scale = OWN_SCALE_WIDTH / w
        expansion = (min(w * scale, 2100.0), min(h * scale * 0.85, 2400.0))
        count = area_particle_count(meta['rect'], scale)
    rng = np.random.RandomState(int(seed) & 0x7fffffff)
    wx = float(rng.randint(-WIND_X_RANGE, WIND_X_RANGE + 1)) if direction is None else wind_for_direction(direction)
    # 随机播放从动画首帧开始，按宿主的强制停止时刻结束。
    return NativeInputs(scale=scale, expansion=expansion, count=count, wind_x=wx, seed=int(seed),
                        span=FormParams().force_stop, offset=0.0), None


class NativeLive(NativeRenderer):
    """预览接口：progress ∈ [0,1] → 设备时刻 offset + progress × span；1× 播放即真实时长。"""

    def __init__(self, scene, inputs, entry=None, ctx=None, raster=None, ss=2, dim_alpha=0.0):
        super().__init__(scene, inputs, raster=raster or LIVE_RASTER, ctx=ctx, ss=ss)
        self.entry = entry
        self.playback_duration = inputs.span
        self.dim_alpha = dim_alpha
        self.last_progress = 0.0

    def render(self, progress):
        progress = float(np.clip(progress, 0.0, 1.0))
        self.last_progress = progress
        self.comp_prog['dim'] = self.dim_alpha * (1.0 - min(1.0, progress / 0.75) ** 2)
        return self.render_time(self.device_time(progress), q=progress)

    def diagnostics(self):
        d = np.frombuffer(self.state.read(), np.float32).reshape(self.n, 12)
        visible = (d[:, 9] > 0) & (d[:, 11] > 0)
        return dict(candidate_count=self.n, active_count=int(visible.sum()), step=self.k,
                    device_time=round(self.device_time(self.last_progress), 4), finite=bool(np.isfinite(d).all()))

    def describe(self):
        i = self.inputs
        result = super().describe()
        result.update(wind=(i.wind_x, self.params.wind_y), origins=[],
                      source=('原片估计的一次动画输入：消蚀格点值、横风 %+d、设备比例 %.2f、扩展 %d×%d、%d 粒子、进度 1 = %.2f 秒'
                              % (i.wind_x, i.scale, self.canvas[0], self.canvas[1], i.count, i.span)) if self.entry else
                             ('随机输入：横风 %+d、遮罩随机数 %.4f；按宿主时序从首帧播放到强制停止' % (i.wind_x, self.random_value)))
        return result


# 缺失后端的替代栅格化：按录屏参考逐像素对照选定（见 native-replica-2026-10-04.md 第 20、28～30 节）。
# 系统镜像 scene_board/particle/dot01.png 的 9×9 软点画在半宽 0.7 × scale 的方块上（scale 即运动着色器里的 radius）；
# 颗粒颜色按「已预乘」直接累加（计算着色器取预乘颜色后把 alpha 换成随机数，后端按预乘颜色画）。
# 4 万粒子、按逐颗重合定出的输入下，四段录屏的逐颗重合在所有圆片变体之上，粗尺度画面差与最好的圆片相差约 2%。
# 颗粒的生长与衰减（scale/radius）一半体现为尺寸、一半体现为不透明度：按 radius·√f 画、不透明度乘 √f（f = scale/radius）。
# 依据（replica 文档第 36 节）：末段原片颗粒点宽不缩小、亮度明显高于「只缩尺寸」，过期颗粒检验证实寿命正确；
# 四段可逐颗验证的录屏两个判据都改善，十一段 σ4 / σ10 粗尺度合计都最低。纯属对缺失后端的经验推断，不是源码。
# 寿命最后 20% 里颗粒遮挡背景的程度（alpha）再按剩余寿命线性减弱、颜色不变（alpha_extra=1、alpha_window=0.2，第 47 节）：
# 卡片框外扩 120 像素内的颗粒总光量（不受颗粒位置对不上影响）在钢铁侠、参考 6（深色背景）末段偏暗、参考 7 / 4 / 1（浅色背景）
# 末段遮挡偏多，六段同时更接近原片；粗尺度合计略降、逐颗重合不变。
LIVE_RASTER = Raster(shape='sprite', size_is='radius', blend='add', size_mul=0.7, premul_as_is=True,
                     size_source='radius_alpha', alpha_power=0.5,
                     alpha_extra=1.0, alpha_window=0.2)

if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--p', type=float, nargs='+', default=[0.3])
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    r = NativeRenderer(a.scene)
    for p in a.p:
        Image.fromarray(r.render(p)).save(a.out.replace('.png', f'-{p:.2f}.png'))
    print(json.dumps(r.describe(), ensure_ascii=False))
    r.close()
