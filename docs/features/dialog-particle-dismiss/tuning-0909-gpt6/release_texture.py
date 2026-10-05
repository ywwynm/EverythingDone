"""释放时刻的空间相关噪声与前沿提亮（桌面研究候选）。

1. 空间相关的释放时钟：把原来逐格独立的释放离散，换成按源位置取样的平滑噪声（可选脊状）。
   同一小片区域一起释放，原表面按团块状的孔洞消失，颗粒成簇出发。表面交接与运动出生共用同一时钟。
2. 前沿提亮：原表面在自身释放时刻前后的短窗口内变亮（乘性 + 朝白），随后按原交接淡出。

所有布局共用同一组参数，只依赖源位置与已有释放时钟；不读取素材身份。
"""
from dataclasses import dataclass, asdict
import numpy as np


@dataclass(frozen=True)
class TextureRules:
    scale: float = 0.        # 噪声特征尺度（场景像素）；0 表示不启用
    amp: float = .08         # 噪声造成的释放时刻离散幅度（进度单位，峰峰值）
    ridged: float = 0.       # 0：普通分形噪声；1：脊状噪声（细长弯曲的团块）
    white: float = .3        # 保留原来逐格独立离散的比例
    octaves: int = 3
    seed: int = 17
    glow_mul: float = 0.     # 表面提亮：乘性系数（线性色彩空间）
    glow_add: float = 0.     # 表面提亮：朝白色混合系数
    glow_lead: float = .04   # 提亮在释放时刻之前开始的提前量
    glow_tail: float = .05   # 释放时刻之后提亮保持的时长（随后随表面淡出）
    # 表面与颗粒分离（消融式）：原表面在 [T−fade_lead, T+fade_tail] 内平滑淡出，不再与颗粒互补；
    # decouple=1 时颗粒出生后即以完整不透明度出现，叠在尚未淡尽的表面之上。fade_tail=0 表示沿用原交接。
    fade_lead: float = .02
    fade_tail: float = 0.
    decouple: float = 0.


def value_noise(x, y, scale, octaves, seed):
    """格点随机值 + 五次平滑插值的分形噪声，输出约 0～1。"""
    rng = np.random.default_rng(seed)
    total = np.zeros_like(x, dtype=np.float64); norm = 0.; amp = 1.; s = float(scale)
    for _ in range(int(octaves)):
        gx = x / s; gy = y / s
        ix = np.floor(gx).astype(np.int64); iy = np.floor(gy).astype(np.int64)
        fx = gx - ix; fy = gy - iy
        w = lambda t: t * t * t * (t * (t * 6 - 15) + 10)
        ox, oy = int(ix.min()), int(iy.min())
        table = rng.random((int(iy.max()) - oy + 2, int(ix.max()) - ox + 2))
        a = table[iy - oy, ix - ox]; b = table[iy - oy, ix - ox + 1]
        c = table[iy - oy + 1, ix - ox]; d = table[iy - oy + 1, ix - ox + 1]
        u = w(fx); v = w(fy)
        total += amp * ((a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v)
        norm += amp; amp *= .5; s *= .5
    return total / norm


class TextureMixin:
    def __init__(self, *args, texture=TextureRules(), **kwargs):
        super().__init__(*args, **kwargs)
        self.apply_texture(texture)

    def apply_texture(self, rules):
        self.texture_rules = rules
        self.program['surface_glow_mul'] = rules.glow_mul
        self.program['surface_glow_add'] = rules.glow_add
        self.program['surface_glow_lead'] = max(rules.glow_lead, 1e-6)
        self.program['surface_glow_tail'] = max(rules.glow_tail, 1e-6)
        self.program['ablate_on'] = 1. if rules.fade_tail > 0 else 0.
        self.program['ablate_lead'] = max(rules.fade_lead, 1e-6)
        self.program['ablate_tail'] = max(rules.fade_tail, 1e-6)
        self.program['ablate_decouple'] = rules.decouple
        if rules.scale <= 0:
            return
        x = self.base[:, 0].astype(np.float64); y = self.base[:, 1].astype(np.float64)
        n = value_noise(x, y, rules.scale, rules.octaves, rules.seed)
        # 分形噪声的分布集中在中间；按秩映射到均匀分布，使幅度参数有确定含义。
        order = np.argsort(n, kind='stable'); rank = np.empty_like(order); rank[order] = np.arange(len(n))
        u = (rank + .5) / len(n)
        if rules.ridged > 0:
            ridge = 1. - np.abs(2. * u - 1.)
            u = (1. - rules.ridged) * u + rules.ridged * ridge
        data = np.frombuffer(self.transfer_buffer.read(), np.float32).reshape(-1, 4).copy()
        clock = data[:, 0].astype(np.float64)
        offset = rules.amp * (u - .5)
        new_clock = np.clip(clock + offset, .001, .95)
        # 出生时刻：新时钟 + 保留一部分原来逐格独立的离散。
        born = self.base[:, 2].astype(np.float64)
        self.base[:, 2] = np.clip(new_clock + rules.white * (born - clock), .001, .96).astype(np.float32)
        self.base[:, 6] = np.minimum(self.base[:, 6], .995 - self.base[:, 2])
        data[:, 0] = new_clock; data[:, 2] = data[:, 2] + (new_clock - clock)
        self.transfer_buffer.write(data.astype(np.float32).tobytes())
        self.material.write(self.base.tobytes()); self.reset()


def with_texture(base):
    vertex = base.vertex_source.replace('void main(){', 'uniform float surface_glow_lead,surface_glow_tail,ablate_on,ablate_lead,ablate_tail;\nflat out float surface_glow;\nvoid main(){', 1)
    anchor = 'surface_out=1.-smoothstep('
    assert anchor in vertex
    # 提亮窗口按本片表面交接时钟（transfer.x）计算：释放前 lead 开始、释放后 tail 结束。
    vertex = vertex.replace(anchor, 'surface_glow=smoothstep(source_clock-surface_glow_lead,source_clock,time)*(1.-smoothstep(source_clock,source_clock+surface_glow_tail,time));\n    ' + anchor, 1)
    # 消融式表面：在所有既有交接修改之后覆盖 surface_out，使表面按自身时钟平滑淡出。
    loosen = 'float loosen=smoothstep(.018,.18,age);'
    assert loosen in vertex
    vertex = vertex.replace(loosen, 'if(ablate_on>.5)surface_out=1.-smoothstep(source_clock-ablate_lead,source_clock+ablate_tail,time);\n    ' + loosen, 1)
    fragment = base.fragment_source.replace('void main(){', 'uniform float surface_glow_mul,surface_glow_add,ablate_decouple;\nflat in float surface_glow;\nvoid main(){', 1)
    old = 'vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);'
    assert old in fragment
    fragment = fragment.replace(old, old + '\n        surface_color=mix(surface_color*(1.+surface_glow_mul*surface_glow),vec3(1.),clamp(surface_glow_add*surface_glow,0.,1.));', 1)
    old = 'float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.001,.022,age);'
    assert old in fragment
    fragment = fragment.replace(old, 'float alpha=src.a*fade*shape*mix(1.-surface_out,1.,ablate_decouple)*smoothstep(.001,.022,age);', 1)
    return type('Texture' + base.__name__, (TextureMixin, base), dict(vertex_source=vertex, fragment_source=fragment))
