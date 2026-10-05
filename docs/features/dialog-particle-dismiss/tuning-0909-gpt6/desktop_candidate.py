"""10 月 4 日桌面候选：在正式运动、材质上检验稀疏采样与多起点释放。

仅用于桌面研究。模型不接收场景名或参考帧；起点是一次动画的显式输入。
正式 Android 共享资源、Renderer 默认行为均不改变。
"""
from dataclasses import dataclass
import math
import numpy as np
from scipy.ndimage import gaussian_filter

from renderer import Renderer, VERTEX
from unified_model import materials, release_components, refine_release, peel_compression, RULES, smooth


@dataclass(frozen=True)
class Origin:
    x: float
    y: float
    start: float = 0.


@dataclass(frozen=True)
class CandidateRules:
    cell: float = 2.7
    front_duration: float = 2.15
    release_limit: float = .68
    early_life: float = RULES['life_early_gain']
    edge_spread: float = 1.4


def sample_origins(seed, width, height):
    """种子只在启动时采样；位置属于材料，终点只控制运动方向与距离。"""
    rng = np.random.default_rng(seed)
    count = int(rng.choice([1, 2, 3, 4], p=[.10, .40, .40, .10]))
    origins = []
    edges = rng.permutation(4)
    for i in range(count):
        t = float(rng.uniform(.12, .88))
        x, y = [(t, 0.), (1., t), (t, 1.), (0., t)][edges[i]]
        if i == 0 and rng.random() < .30:
            x, y = rng.uniform(.28, .72, 2)
        origins.append(Origin(float(x), float(y), 0. if i == 0 else float(rng.uniform(.025, .14))))
    return tuple(origins)


def arrival_field(nx, ny, width, height, origins, seed, rules=CandidateRules(), direction=90.):
    """同一短边尺度的幂律前沿；软最小值连接各起点，低频起伏固定在材料上。"""
    span = min(width, height)
    yy, xx = np.mgrid[:ny, :nx]
    x = (xx + .5) / nx * width / span
    y = (yy + .5) / ny * height / span
    stretch = 1 + .60 * (max(width, height) / span - 1)
    times = []
    wind = np.array([math.cos(math.radians(direction)), -math.sin(math.radians(direction))])
    for o in origins:
        dx, dy = x - o.x * width / span, y - o.y * height / span
        # 传播沿相邻自由边界更快；迎风边减弱加速，内部起点连续退为径向传播。
        distances = [o.y * height / span, (1 - o.x) * width / span, (1 - o.y) * height / span, o.x * width / span]
        edge = int(np.argmin(distances))
        normal = [(0., -1.), (1., 0.), (0., 1.), (-1., 0.)][edge]
        along, across = (dx, dy) if edge % 2 == 0 else (dy, dx)
        gain = rules.edge_spread * np.exp(-max(0., distances[edge]) / .06) * (1 - .90 * max(0., np.dot(normal, wind)))
        d = np.hypot(along / (1 + gain * np.exp(-abs(across) / .18)), across) / stretch
        times.append(np.maximum(.006 + o.start + rules.front_duration * d ** 1.85, .001))
    times = np.stack(times)
    field = np.sum(times ** -6., axis=0) ** (-1. / 6.)
    rng = np.random.default_rng(int(seed) ^ 0x318AD)
    phase = rng.uniform(0, 2 * np.pi, 4)
    noise = .012 * (np.sin(9 * x + 5 * y + phase[0]) + np.sin(4 * x - 11 * y + phase[1]))
    # 接近起点时衰减起伏，不产生提前释放的孤立孔洞。
    field += noise * smooth(field / .12)
    # 有限时长关闭；软饱和保持连续前沿，不把晚区同帧截断。
    field = rules.release_limit * np.tanh(field / rules.release_limit)
    return np.maximum(field, .001).astype('float32')


class DesktopCandidate(Renderer):
    """沿用正式速度积分与压力投影；只更改材料采样和可选释放输入。"""
    # 保留随年龄细化，但幅度比正式版小；与少量较大的采样共同验证。
    vertex_source = VERTEX.replace('scale*=1.-0.3900000*smoothstep', 'scale*=1.-0.2500000*smoothstep')
    def __init__(self, *args, origins=None, local_clock=True, rules=CandidateRules(), **kwargs):
        self.origins = tuple(origins) if origins is not None else None
        self.local_clock = local_clock
        self.candidate_rules = rules
        kwargs.setdefault('cell_px', rules.cell)
        super().__init__(*args, **kwargs)

    def material_builder(self, width, height, foreground, direction, seed, cell_px):
        built = materials(width, height, foreground, direction, seed, cell_px)
        nx, ny = built['nx'], built['ny']
        cx, cy = built['cell']
        native, offset = release_components(nx, ny, direction, width, height, seed)
        native = refine_release(native, width, height, direction, built['panel_weight'])
        field = native if self.origins is None else arrival_field(nx, ny, width, height, self.origins, seed, self.candidate_rules, direction)
        # 采样间距不改变法向平滑的实际像素尺度。
        gy, gx = np.gradient(gaussian_filter(field, (4.95 / cy, 4.95 / cx)), cy, cx)
        length = np.maximum(np.hypot(gx, gy), 1e-6)
        normalx = gaussian_filter(gx / length, (11.55 / cy, 11.55 / cx))
        normaly = gaussian_filter(gy / length, (11.55 / cy, 11.55 / cx))
        base = built['base']
        ids = base[:, 3].astype('int64') % (nx * ny)
        rand = base[:, 8:]
        base[:, 2] = np.maximum(field.ravel()[ids] + built['release_spread'] * (rand[:, 3] - .5), .001)
        base[:, 4] = normalx.ravel()[ids]
        base[:, 5] = normaly.ravel()[ids]
        if self.origins is not None and self.local_clock:
            # 同一材料的新出生时刻对应原运动的出生相位，避免换前沿后错用全局时钟。
            base[:, 7] = (field - native + offset).ravel()[ids]
        cap = .865 + .115 * rand[:, 2] - base[:, 2]
        early = self.candidate_rules.early_life
        gain = early + (RULES['life_gain'] - early) * smooth((base[:, 2] - RULES['life_birth_start']) / RULES['life_birth_span'])
        life = (.10 + .22 * (-np.log(np.maximum(rand[:, 0], .004))) ** .85 + .20 * base[:, 2]) * gain
        life = np.maximum(np.minimum(life, cap), .11)
        base[:, 6] = np.maximum(np.minimum(life * (1 + .30 * built['pigment']), cap), .11)
        built['peel_compression'] = peel_compression(normalx, normaly, cx, cy, min(width, height), direction).ravel()[ids]
        return built


class SparseRenderer(Renderer):
    """严格消融：仅减少粒子，释放、运动、材质与正式基准一致。"""
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('cell_px', 2.7)
        super().__init__(*args, **kwargs)
