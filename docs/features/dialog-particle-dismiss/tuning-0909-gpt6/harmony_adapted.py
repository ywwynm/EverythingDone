"""在华为核心上验证共同运动和材质改进；尚未通过参考验收。

保留原生候选采样、消蚀和年龄驱动框架。运动相位关联、线性光合成、
片面明暗属于桌面实验，不能表述为已经恢复出的华为实现。
不读取参考视频、观测控制图或按素材切换参数。
"""
from dataclasses import dataclass, asdict, replace

from harmony_core import (
    HarmonyCoreRenderer, DEFAULT, COMPUTE, MASK_FRAGMENT,
    PARTICLE_VERTEX, PARTICLE_FRAGMENT, COMPOSITE_FRAGMENT,
)


@dataclass(frozen=True)
class AdaptedRules:
    phase_spread: float = .08
    linear_light: bool = True
    facet_light: float = .65
    linear_dark_floor: bool = False
    growth_seconds: float | None = None
    fade_seconds: float | None = None
    motion_time_scale: float = 1.


DECODE = '''vec3 decode(vec3 c){return mix(c/12.92,
    pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}\n'''
ENCODE = '''c.rgb=mix(c.rgb*12.92,1.055*pow(max(c.rgb,vec3(0)),
    vec3(1./2.4))-.055,step(vec3(.0031308),c.rgb));result=c;'''


class HarmonyAdaptedRenderer(HarmonyCoreRenderer):
    def __init__(self, *args, rules=None, config=None, motion_seed=None, **kwargs):
        self.adapted_rules = rules or AdaptedRules()
        q = self.adapted_rules
        self.compute_source = COMPUTE.replace(
            'mod(randomIndex(i+12u)*10000.,100.)/1000.+r*.5',
            f'mod(randomIndex(i+12u)*10000.,100.)/1000.*{q.phase_spread/.5:.7f}'
            f'+r*{q.phase_spread:.7f}')
        self.compute_source = self.compute_source.replace(
            'uniform float dt;', 'uniform float dt;uniform vec3 flow_offset;').replace(
            'vec2 flow=vec2(turbulenceValue(coord)',
            'coord+=flow_offset;vec2 flow=vec2(turbulenceValue(coord)')
        # 隔离寿命与尺寸包络：延长寿命时不必同时推迟颗粒长到完整尺寸。
        # 默认仍用原生归一化年龄，固定秒数只用于显式请求的离线实验。
        if q.growth_seconds is not None:
            if q.growth_seconds <= 0:
                raise ValueError('生长时间必须为正')
            self.compute_source = self.compute_source.replace(
                'clamp(life/.2,0.,1.)', f'clamp(t/{q.growth_seconds:.7f},0.,1.)')
        if q.fade_seconds is not None:
            if q.fade_seconds <= 0:
                raise ValueError('消退时间必须为正')
            self.compute_source = self.compute_source.replace(
                'clamp((1.-life)/.4,0.,1.)',
                f'clamp((p.origin_life.z-t)/{q.fade_seconds:.7f},0.,1.)')
        if q.motion_time_scale <= 0:
            raise ValueError('运动时间倍率必须为正')
        if q.motion_time_scale != 1.:
            self.compute_source = self.compute_source.replace(
                'float r=randomIndex(i+11u);',
                f'float mt=t*{q.motion_time_scale:.7f};float r=randomIndex(i+11u);').replace(
                '.5*t*t*wind', '.5*mt*mt*wind').replace(
                't*turbulence.y*.005', 'mt*turbulence.y*.005').replace(
                'flow*turbulence.z*t;', 'flow*turbulence.z*mt;')
        self.mask_source = MASK_FRAGMENT
        self.composite_source = COMPOSITE_FRAGMENT
        if q.linear_light:
            self.compute_source = self.compute_source.replace('void main(){',DECODE+'void main(){',1)
            if q.linear_dark_floor:
                self.compute_source = self.compute_source.replace(
                    'p.color=textureLod(source,uv,0);',
                    'p.color=textureLod(source,uv,0);p.color.rgb=decode(p.color.rgb);')
            else:
                self.compute_source = self.compute_source.replace(
                    'p.radius_scale.x=mix', 'p.color.rgb=decode(p.color.rgb);p.radius_scale.x=mix')
            self.mask_source = MASK_FRAGMENT.replace('void main(){',DECODE+'void main(){',1).replace(
                'c.rgb*=c.a;', 'c.rgb=decode(c.rgb)*c.a;')
            self.composite_source = COMPOSITE_FRAGMENT.replace('void main(){',DECODE+'void main(){',1).replace(
                'texture(background,uv).rgb*(1.-dim)',
                'decode(texture(background,uv).rgb)*(1.-dim)').replace('result=c;',ENCODE)
        self.particle_vertex_source = PARTICLE_VERTEX.replace(
            'out vec4 color;', 'out vec4 color;out float age;flat out vec2 origin;').replace(
            'color=p.color;', 'color=p.color;age=p.position_age.z;origin=p.origin_life.xy;')
        self.particle_fragment_source = PARTICLE_FRAGMENT.replace(
            'in vec4 color;', 'in vec4 color;in float age;flat in vec2 origin;').replace(
            'float alpha=color.a*coverage;result=vec4(color.rgb*alpha,alpha);',
            f'''float loosen=smoothstep(.01,.12,age);
    float face=sin(dot(origin,vec2(.014,.021))+age*11.);
    float facing=.5+.5*sin(age*16.+dot(origin,vec2(.021,.032)));
    float glint=.12*pow(facing,12.);
    float gain={q.facet_light:.7f};
    float diffuse=1.+gain*(.09+.17*face)*loosen*exp(-max(age-.25,0.)*2.);
    vec3 rgb=color.rgb*diffuse*(1.+.25*gain*loosen)+vec3(.012+glint)*gain*loosen;
    float alpha=color.a*coverage;result=vec4(rgb*alpha,alpha);''')
        super().__init__(*args,config=config or replace(DEFAULT,count=40000),**kwargs)
        self.motion_seed = int(self.seed if motion_seed is None else motion_seed)
        self.flow_offset = motion_offset(self.motion_seed)
        self.compute['flow_offset'] = self.flow_offset

    def describe(self):
        result = super().describe()
        result.update(model='harmony-adapted',adapted_rules=asdict(self.adapted_rules),
                      motion_seed=self.motion_seed,flow_offset=self.flow_offset,
                      source='华为核心框架上的共同运动／材质实验，尚未通过视觉验收')
        return result


def motion_offset(seed):
    """每次动画一次采样，所有素材共用；使连续流形状也随种子变化。"""
    import numpy as np
    from harmony_core import index_hash
    ids = (np.arange(3,dtype=np.uint32)+np.uint32(int(seed)&0xffffffff)) ^ np.uint32(0x72d53a1)
    values = (index_hash(ids)>>np.uint32(8)).astype(np.float32)/np.float32(16777216)
    return tuple(float(v) for v in (values-.5)*6.)
