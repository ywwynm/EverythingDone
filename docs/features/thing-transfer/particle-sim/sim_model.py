"""碰一碰粒子模拟器：参数、素材、材料与时间轴。

与 Android 同源的部分一律复用，不另写一份：
- 材料生成用 GPT6 桌面模拟器的共同模型（`tuning-0909-gpt6/unified_model.py`，与 Kotlin 逐项对齐）；
- 积分用仓库里的 `shared/particle-dismiss/step.comp` 原文（见 sim_renderer.py）；
- 参数读写 `shared/tap-transfer/particles.properties`，Android 读的也是这一份。

本文件只补碰一碰独有的三处，均逐行对照 Kotlin：
- 释放起点（`ParticleReleaseTopology` 带 origin 的分支）；
- 终点整形（`ParticleSink.shape` / `ParticleSink.target`）；
- 发送与接收的时间轴（`TapShareOverlay`、`TapParticleFlight`、`ParticleSession` 的取帧方式）。
"""
from __future__ import annotations

import contextlib
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / 'gradlew.bat').is_file())
GPT6 = ROOT / 'docs/features/dialog-particle-dismiss/tuning-0909-gpt6'
if str(GPT6) not in sys.path:
    sys.path.insert(0, str(GPT6))
import unified_model as um  # noqa: E402  共同模型：rules.properties、共同释放场、流场

PARAMS_FILE = ROOT / 'shared/tap-transfer/particles.properties'
SCENES = HERE / 'scenes'

# 720 逻辑单位对应 384 dp（ParticleMicroflakeModel.DP_UNITS）
DP_UNITS = 1.875
# 历史采样数：发送（手势会话）固定 MAX_SAMPLES，接收按 samplesFor(1.3 s, 60 Hz) 夹到同一上限
SAMPLES = 60
STEP = 1 / 240

F32 = np.float32


# ---------------------------------------------------------------- 参数

# 每个键的取值个数（'colors' 表示个数不限的颜色列表）；顺序即界面上的顺序
PARAM_SHAPE = {
    'sink_release_scale': 1, 'sink_arrival_end': 1, 'sink_flight_base': 1, 'sink_flight_per_span': 1,
    'sink_flight_jitter': 1, 'sink_min_life': 1, 'sink_swirl': 1, 'sink_homing': 2, 'sink_frame_jitter': 1,
    'sink_absorb_share': 1, 'sink_spread_dp': 1, 'sink_spiral': 1, 'sink_drift': 1, 'sink_free_life': 1,
    'sink_absorb_dp': 2, 'sink_fade_s': 1, 'sink_band_dp': 1, 'sink_band_spiral': 1, 'sink_curl': 1,
    'sink_vortex_dp': 1, 'sink_arrival': 2, 'sink_glide': 3, 'sink_near_scale': 1,
    'sink_group_stagger': 2, 'sink_group_swirl': 1,
    'send_dim_alpha': 2, 'send_open_ms': 1, 'send_close_ms': 1, 'send_lift_ms': 1, 'send_lift_ease': 4,
    'send_drop_ms': 1, 'send_drop_ease': 4, 'send_lift_fade': 1, 'send_detail_rise_dp': 1,
    'send_detail_rise_scale': 1, 'send_elevation_dp': 1, 'send_shadow_drop_ms': 1, 'send_shadow_restore_ms': 1,
    'send_pulse_rings': 1, 'send_pulse_period_s': 1, 'send_pulse_radius_dp': 2, 'send_pulse_alpha': 1,
    'send_pulse_shrink': 1,
    'send_outflow_ms': 1, 'send_outflow_ease': 4, 'send_sent_hold_ms': 1, 'send_reform_ms': 1,
    'send_reform_ease': 4, 'send_glow_curve': 4, 'send_glow_radius_dp': 2, 'send_glow_alpha': 1,
    'receive_dim_alpha': 2, 'receive_dim_fade_s': 1, 'receive_border_fade_s': 1, 'receive_border_converge_s': 1,
    'receive_border_inset_dp': 1, 'receive_border_spacing_dp': 1, 'receive_border_lane_dp': 1,
    'receive_border_speed_dp': 1, 'receive_border_wobble_dp': 1, 'receive_border_trail_s': 1,
    'receive_border_radius_dp': 2, 'receive_border_particle_fade_s': 1, 'receive_border_palette': 'colors',
    'receive_border_palette_cycle_s': 1, 'receive_border_palette_fade_s': 1, 'receive_glow_rings': 1,
    'receive_glow_ring_dp': 1, 'receive_glow_alpha': 1, 'receive_hole_close_s': 1,
    'receive_appear_s': 1, 'receive_frame_share': 1, 'receive_frame_blend_dp': 1, 'receive_handoff': 3,
    'receive_dust_per_dp': 1, 'receive_dust_band_dp': 1, 'receive_dust_size_dp': 2, 'receive_dust_speed_dp': 1,
    'receive_dust_wander_dp': 1, 'receive_dust_flip_hz': 2, 'receive_dust_rest': 1, 'receive_dust_spread_dp': 1,
    'receive_dust_inward_hz': 2, 'receive_dust_glow': 2, 'receive_dust_pull_dp': 1, 'receive_dust_retreat': 2,
    'receive_emerge_pace': 4, 'receive_emerge_sink': 4, 'receive_emerge_aspect': 1, 'receive_crossfade': 3,
    'receive_dust_join': 3,
}
# 改了要重建材料与轨迹的键；其余只影响播放
PHYSICS_KEYS = {k for k in PARAM_SHAPE if k.startswith('sink_')} | {'receive_frame_share', 'receive_frame_blend_dp'}
# 改了要重算接收舞台（边框粒子）的键
STAGE_KEYS = {k for k in PARAM_SHAPE if k.startswith('receive_') and not k.startswith('receive_dust_')
              and k not in ('receive_appear_s', 'receive_frame_share', 'receive_frame_blend_dp')}
# 光尘带的位置随进度走、流入的边框落点也跟着它：改了要重算粒子轨迹
PHYSICS_KEYS |= {'receive_dust_band_dp', 'receive_dust_spread_dp', 'receive_border_inset_dp'}
# 进度期间冒出的颗粒与光尘带：交接时拼卡粒子层的起点取它们的位置与颜色，改了同样要重算轨迹；
# 流入的时长决定各片的交接时刻
PHYSICS_KEYS |= {k for k in PARAM_SHAPE if k.startswith('receive_emerge_') or k.startswith('receive_dust_')}
PHYSICS_KEYS |= {'receive_appear_s', 'receive_border_palette', 'receive_border_palette_cycle_s', 'receive_handoff',
                 'receive_crossfade'}


def _parse_value(key: str, text) -> list:
    parts = [x.strip() for x in str(text).split(',')]
    if PARAM_SHAPE.get(key) == 'colors':
        return [int(x.lstrip('#'), 16) & 0xFFFFFF for x in parts]
    return [float(x) for x in parts]


class Params:
    """`particles.properties` 的数值视图。写回时保留原文件的注释与顺序，只替换值。"""

    def __init__(self, values: dict):
        for key, count in PARAM_SHAPE.items():
            if key not in values:
                raise ValueError(f'参数文件缺少 {key}')
            if count == 'colors':
                if not values[key]:
                    raise ValueError(f'参数 {key} 至少一个颜色')
            elif len(values[key]) != count:
                raise ValueError(f'参数 {key} 须为 {count} 个数')
        self.values = {k: (list(v) if PARAM_SHAPE.get(k) == 'colors' else [float(x) for x in v])
                       for k, v in values.items()}

    @classmethod
    def parse(cls, text: str) -> 'Params':
        values = {}
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            values[key.strip()] = _parse_value(key.strip(), value)
        return cls(values)

    @classmethod
    def load(cls, path: Path = PARAMS_FILE) -> 'Params':
        return cls.parse(path.read_text(encoding='utf-8'))

    @classmethod
    def from_scene(cls, scene) -> 'Params':
        """素材落盘那一刻手机上用的参数（重放与对照用）；缺项按当前文件补。"""
        current = cls.load()
        raw = dict(scene.meta.get('params') or {})
        # 早先的素材只有一项 receive_border_fade_s，含义是现在的 receive_border_converge_s
        if 'receive_border_converge_s' not in raw and 'receive_border_fade_s' in raw:
            raw['receive_border_converge_s'] = raw.pop('receive_border_fade_s')
        values = dict(current.values)
        for key in PARAM_SHAPE:
            if key in raw:
                values[key] = _parse_value(key, raw[key])
        return cls(values)

    def copy(self) -> 'Params':
        return Params({k: list(v) for k, v in self.values.items()})

    def __getitem__(self, key):
        v = self.values[key]
        return v[0] if len(v) == 1 and PARAM_SHAPE.get(key) != 'colors' else list(v)

    def palette(self, key: str) -> list:
        """颜色列表，换成不透明的 ARGB。"""
        return [0xFF000000 | c for c in self.values[key]]

    def set(self, key: str, value):
        count = PARAM_SHAPE[key]
        if count == 'colors':
            value = [int(x) & 0xFFFFFF for x in value]
            if not value:
                raise ValueError(f'参数 {key} 至少一个颜色')
        else:
            value = [float(value)] if count == 1 and not isinstance(value, (list, tuple)) else [float(x) for x in value]
            if len(value) != count:
                raise ValueError(f'参数 {key} 须为 {count} 个数')
        self.values[key] = value

    @staticmethod
    def format(values: list, key: str | None = None) -> str:
        if key is not None and PARAM_SHAPE.get(key) == 'colors':
            return ','.join(f'{int(c) & 0xFFFFFF:06X}' for c in values)

        def one(x: float) -> str:
            if abs(x - round(x)) < 1e-9:
                return str(int(round(x)))
            return f'{x:.6f}'.rstrip('0').rstrip('.')
        return ','.join(one(x) for x in values)

    def save(self, path: Path = PARAMS_FILE):
        lines = path.read_text(encoding='utf-8').splitlines()
        written = set()
        for i, line in enumerate(lines):
            stripped = line.strip()
            if not stripped or stripped.startswith('#') or '=' not in stripped:
                continue
            key, old = (x.strip() for x in stripped.split('=', 1))
            if key in self.values:
                # 值没变的行保持原文（例如 0.30 不改写成 0.3），写回的差异只有真正改过的那几项
                if _parse_value(key, old) != self.values[key]:
                    lines[i] = f'{key}={self.format(self.values[key], key)}'
                written.add(key)
        missing = [k for k in PARAM_SHAPE if k not in written]
        if missing:
            raise ValueError(f'参数文件缺少这些键，未写回：{missing}')
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')

    def problems(self) -> list[str]:
        """与 `TapParticleParams` 的 require 逐条对应：有任何一条不满足，手机读参数时就会抛异常。"""
        v = self.values
        one = lambda k: v[k][0]  # noqa: E731
        out = []

        def need(ok: bool, text: str):
            if not ok:
                out.append(text)
        need(0 < one('sink_release_scale') <= 1, 'sink_release_scale 须在 (0, 1]')
        need(0 < one('sink_arrival_end') <= 1, 'sink_arrival_end 须在 (0, 1]')
        need(one('sink_flight_base') > 0 and one('sink_flight_per_span') >= 0 and one('sink_min_life') > 0,
             '飞行时长须为正')
        need(0 <= one('sink_flight_jitter') <= .9, 'sink_flight_jitter 须在 [0, 0.9]')
        need(0 <= v['sink_homing'][0] < v['sink_homing'][1], 'sink_homing 须满足 0 ≤ 起 < 止')
        need(all(one(k) > 0 for k in ('send_outflow_ms', 'send_reform_ms', 'send_open_ms', 'send_close_ms',
                                      'send_lift_ms', 'send_drop_ms', 'send_shadow_drop_ms',
                                      'send_shadow_restore_ms')) and one('send_sent_hold_ms') >= 0, '发送的时长须为正')
        need(all(one(k) > 0 for k in ('receive_appear_s', 'receive_dim_fade_s', 'receive_border_fade_s',
                                      'receive_border_converge_s', 'receive_border_trail_s',
                                      'receive_border_particle_fade_s', 'receive_border_palette_cycle_s',
                                      'receive_border_palette_fade_s', 'receive_hole_close_s',
                                      'send_pulse_period_s')), '接收与扩散圈的时长须为正')
        need(0 <= one('receive_frame_share') <= 1, 'receive_frame_share 须在 [0, 1]')
        need(one('receive_frame_blend_dp') >= 0, 'receive_frame_blend_dp 不能为负')
        g = v['send_glow_curve']
        need(g[0] < g[1] and g[2] < g[3], 'send_glow_curve 的两段须各自递增')
        for k in ('send_outflow_ease', 'send_reform_ease', 'send_lift_ease', 'send_drop_ease'):
            need(0 <= v[k][0] <= 1 and 0 <= v[k][2] <= 1, f'{k} 的 x 须在 [0, 1]')
        need(all(0 <= a <= 1 for a in v['send_dim_alpha'] + v['receive_dim_alpha']), '罩层不透明度须在 [0, 1]')
        need(0 < one('send_lift_fade') <= 1, 'send_lift_fade 须在 (0, 1]')
        need(0 <= int(one('send_pulse_rings')) <= 8 and 0 <= int(one('receive_glow_rings')) <= 64, '圈数越界')
        r = v['receive_border_radius_dp']
        need(one('receive_border_spacing_dp') > 0 and 0 < r[0] <= r[1], '边框粒子尺寸不合法')
        need(len(v['receive_border_palette']) > 0, 'receive_border_palette 至少一个颜色')
        # 柔性终点与光尘带（2026-09-25 起，Android 端尚未读取，这几条是模拟器自己的检查）
        a0, a1 = v['sink_arrival']
        need(0 < a0 <= a1 <= 1, 'sink_arrival 须满足 0 < 最早 ≤ 最晚 ≤ 1')
        g0, g1, gain = v['sink_glide']
        need(0 <= g0 < g1 <= 1 and gain > 0, 'sink_glide 须满足 0 ≤ 起 < 止 ≤ 1，接近增益 > 0')
        need(0 <= v['sink_absorb_dp'][0] < v['sink_absorb_dp'][1], 'sink_absorb_dp 须满足 0 ≤ 内 < 外')
        need(0 <= one('sink_absorb_share') <= 1 and 0 <= one('receive_dust_rest') <= 1, '比例须在 [0, 1]')
        need(one('sink_vortex_dp') > 0 and one('sink_free_life') > 0 and one('sink_fade_s') > 0,
             'sink_vortex_dp、sink_free_life、sink_fade_s 须为正')
        need(0 < one('sink_near_scale') <= 1, 'sink_near_scale 须在 (0, 1]')
        s0, s1 = v['receive_dust_size_dp']
        f0, f1 = v['receive_dust_flip_hz']
        need(0 < s0 <= s1 and 0 <= f0 <= f1, '光尘的尺寸与翻转频率须满足 最小 ≤ 最大')
        need(one('receive_dust_per_dp') > 0 and one('receive_dust_band_dp') > 0, '光尘的密度与带宽须为正')
        # 第三版第六轮：进度期间天线处的颗粒（代理卡片的倒放）
        pace = v['receive_emerge_pace']
        need(pace[0] >= 0 and pace[1] > 0 and pace[0] < pace[2] < 1 and pace[3] > 0,
             'receive_emerge_pace 须满足 系数不为负、最低速度为正、系数 < 上限 < 1、指数为正')
        cf = v['receive_crossfade']
        need(cf[0] > 0 and 0 <= cf[1] < cf[2], 'receive_crossfade 须满足 淡入时长为正、淡出的起 < 止')
        dj = v['receive_dust_join']
        need(0 <= dj[0] < dj[1] < 1 and dj[2] > 0, 'receive_dust_join 须满足 0 ≤ 起 < 止 < 1、最短飞行为正')
        need(all(x >= 0 for x in v['receive_emerge_sink']) and v['receive_emerge_sink'][2] <= 1
             and v['receive_emerge_sink'][3] > 0 and one('receive_emerge_aspect') > 0,
             '天线处浮现的半径、占比与淡入须合法、预计区域的高宽比须为正')
        need(v['receive_handoff'][0] > 0, '交接的牵引渐入须为正')
        need(v['receive_dust_retreat'][1] > 0, 'receive_dust_retreat 的用时须为正')
        return out

    def physics_signature(self) -> tuple:
        return tuple((k, tuple(self.values[k])) for k in sorted(PHYSICS_KEYS))

    def stage_signature(self) -> tuple:
        return tuple((k, tuple(self.values[k])) for k in sorted(STAGE_KEYS))


# ---------------------------------------------------------------- 素材

@dataclass
class Scene:
    """一次真机飞行的输入：`scene.json` + `snapshot.png` + 底图（见 TapFixtureDump）。"""
    path: Path
    meta: dict
    snapshot: np.ndarray               # (h, w, 4) uint8，直通 alpha
    background: np.ndarray             # (H, W, 3) uint8：粒子层底下的画面
    settled: np.ndarray | None = None  # 接收：边框淡去之后的底图

    @property
    def kind(self) -> str:
        return self.meta['kind']

    @property
    def title(self) -> str:
        return self.meta.get('title') or self.path.name

    @property
    def screen(self) -> tuple[int, int]:
        w, h = self.meta['screen']
        return int(w), int(h)

    @property
    def density(self) -> float:
        return float(self.meta['density'])

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        return tuple(float(v) for v in self.meta['bounds'])

    @property
    def sink(self) -> tuple[float, float]:
        return tuple(float(v) for v in self.meta['sink'])

    @property
    def frame(self):
        f = self.meta.get('frame')
        return None if f is None else tuple(float(v) for v in f)

    @classmethod
    def load(cls, path: Path) -> 'Scene':
        path = Path(path)
        meta = json.loads((path / 'scene.json').read_text(encoding='utf-8'))
        snapshot = np.array(Image.open(path / 'snapshot.png').convert('RGBA'))
        background = np.array(Image.open(path / 'background.png').convert('RGB'))
        settled_file = path / 'background-settled.png'
        settled = np.array(Image.open(settled_file).convert('RGB')) if settled_file.is_file() else None
        scene = cls(path, meta, snapshot, background, settled)
        w, h = scene.screen
        if background.shape[:2] != (h, w):
            raise ValueError(f'{path.name}: 底图 {background.shape[1]}x{background.shape[0]} 与屏幕 {w}x{h} 不符')
        l, t, r, b = scene.bounds
        if snapshot.shape[:2] != (round(b - t), round(r - l)):
            raise ValueError(f'{path.name}: 快照 {snapshot.shape[1]}x{snapshot.shape[0]} 与范围 {r - l}x{b - t} 不符')
        return scene


def flow_mode() -> str:
    """终点模型（环境变量 TAP_FLOW，缺省 field）：field = 柔性终点；legacy = 2026-09-24 版的终点牵引
    （每片在寿命末端收进同一点）；dialog = 弹窗消散、只朝天线方向；dialog-free = 同上且不指定释放起点。"""
    import os
    return os.environ.get('TAP_FLOW', 'field')


def list_scenes() -> list[Path]:
    """素材目录：整段会话（session.json）在前，只有粒子飞行的素材（scene.json）在后。"""
    if not SCENES.is_dir():
        return []
    sessions = sorted(p for p in SCENES.iterdir() if (p / 'session.json').is_file())
    flights = sorted(p for p in SCENES.iterdir() if (p / 'scene.json').is_file())
    return sessions + flights


# ---------------------------------------------------------------- 材料

@contextlib.contextmanager
def release_origin(origin):
    """Kotlin `ParticleReleaseTopology` 在给定起点时的两处不同，桌面共同模型里没有：

    1. 第一个局部起点移到起点处（x = origin.x × 宽，y = origin.y × 高，不做外扩），延迟为 0，
       转角、缩放与其余起点不变；
    2. 局部权重至少 0.90。

    共同模型的 `release_components` 按模块全局名调用这两个函数，这里临时换掉，其余照旧。
    """
    if origin is None:
        yield
        return
    base_patches, base_locality = um.release_patches, um.release_locality

    def patches(width, height, seed):
        result = list(base_patches(width, height, seed))
        _, _, _, c, s, scale = result[0]
        result[0] = (float(origin[0]) * width, float(origin[1]) * height, 0., c, s, scale)
        return result

    def locality(width, height, direction, seed):
        return max(base_locality(width, height, direction, seed), .90)

    um.release_patches, um.release_locality = patches, locality
    try:
        yield
    finally:
        um.release_patches, um.release_locality = base_patches, base_locality


@dataclass
class SinkGeometry:
    """`ParticleSink.Geometry`：材料坐标（以快照左上角为原点）。"""
    x: float
    y: float
    left: float
    top: float
    right: float
    bottom: float
    share: float
    jitter: float
    # 着色器的两项：切向分量与转向区间（ParticleSink.Geometry 同样带着它们）
    swirl: float = .30
    homing: tuple[float, float] = (.08, .50)
    # 柔性终点（SINK_FIELD）：材料单位；见 step.comp 的 sink_flow / sink_band 与 material.* 的 sink_absorb
    flow: tuple[float, float, float, float] = (0., 0., 0., 0.)     # 螺线切向比、吸入份额、落点散布半径、自由漂移
    band: tuple[float, float, float, float] = (0., 0., 0., 0.)     # 边框带宽、边框螺线、卷流、（空）
    absorb: tuple[float, float, float, float] = (0., 1., .075, 1.)  # 渐隐内半径、外半径、寿命末端淡出、贴近时的缩放
    vortex: float = 1.                                              # 涡旋核半径
    glide: tuple[float, float, float] = (0., 0., 1.)                # 整团滑行：渐入时间窗、接近增益
    handoff: tuple[float, float, float, float] = (.14, 1., 1.37, 0.)  # 被接手的颗粒：牵引渐入、卷流倍数与取样频率
    # 按卡片分组：[(矩形 l,t,r,b, (牵引时间窗平移, 切向倍数, 流向边框的占比, 出生平移))]，材料坐标
    groups: list = field(default_factory=list)


def sink_geometry(scene: Scene, params: Params, scale: float) -> SinkGeometry:
    """`ParticleSink.toMaterial`；接收方的边框占比取参数文件（`receive_frame_share`）。"""
    ox, oy = scene.bounds[:2]
    sx, sy = scene.sink
    x, y = (sx - ox) / scale, (sy - oy) / scale
    frame = scene.frame
    if frame is not None and flow_mode() == 'field':
        # 柔性终点：流向边框的颗粒落在屏幕四周的光尘带里（贴着边缘，向内扩散到交接时的深度）
        w, h = scene.screen
        inset = params['receive_border_inset_dp'] * scene.density
        frame = (inset, inset, w - inset, h - inset)
    share = params['receive_frame_share'] if frame is not None else 0.
    swirl, homing = params['sink_swirl'], tuple(params['sink_homing'])
    unit = DP_UNITS  # 1 dp 合多少材料单位
    flow = (params['sink_spiral'], params['sink_absorb_share'], params['sink_spread_dp'] * unit, params['sink_drift'])
    # 交接时光尘带扩散到的深度：带宽 + 扩散距离 × 1.3（进度 100% 之后列表让位期间还在继续扩散）
    band_dp = params['receive_dust_band_dp'] + params['receive_dust_spread_dp'] * 1.3 if frame is not None and \
        flow_mode() == 'field' else params['sink_band_dp']
    band = (band_dp * unit, params['sink_band_spiral'], params['sink_curl'], 0.)
    absorb_fade = (params['sink_absorb_dp'][0] * unit, params['sink_absorb_dp'][1] * unit, params['sink_fade_s'],
                   params['sink_near_scale'])
    vortex = params['sink_vortex_dp'] * unit
    glide = tuple(params['sink_glide'])
    handoff = (params['receive_handoff'][0], params['receive_handoff'][1], params['receive_handoff'][2], 0.)
    groups = card_groups(scene, params, scale, share)
    if frame is None or share <= 0:
        return SinkGeometry(x, y, x, y, x, y, 0., 0., swirl, homing, flow, band, absorb_fade, vortex, glide,
                            groups=groups, handoff=handoff)
    left, top, right, bottom = ((frame[0] - ox) / scale, (frame[1] - oy) / scale,
                                (frame[2] - ox) / scale, (frame[3] - oy) / scale)
    return SinkGeometry(x, y, left, top, right, bottom, min(max(share, 0.), 1.),
                        params['sink_frame_jitter'] * min(right - left, bottom - top), swirl, homing,
                        flow, band, absorb_fade, vortex, glide, groups=groups, handoff=handoff)


def card_groups(scene: Scene, params: Params, scale: float, share: float) -> list:
    """几张卡片同时拼出时的分组。第一张出生最晚（倒放时最先拼好）、后面的依次更早离开光尘带；
    切向倍数沿卡片顺序递变，几张卡片的过程因此不对称。一张卡片时不分组。

    流向边框的占比各组相同：哪一片来自屏幕四周、哪一片来自天线按远近分配（`assign_sources`），
    几张卡片各有多少来自四周由它们离天线与屏幕边缘的远近决定，不再按卡片顺序人为错开。"""
    cards = scene.meta.get('cards') or []
    if len(cards) < 2:
        return []
    ox, oy = scene.bounds[:2]
    born, arrive = params['sink_group_stagger']
    out = []
    n = min(len(cards), 4)
    for g, (l, t, r, b) in enumerate(cards[:n]):
        f = g / (n - 1)
        rect = ((l - ox) / scale, (t - oy) / scale, (r - ox) / scale, (b - oy) / scale)
        out.append((rect, (-arrive * f, 1 + params['sink_group_swirl'] * f, min(max(share, 0.), 1.), born * (1 - f))))
    return out


def group_of(values: np.ndarray, g: SinkGeometry) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """逐片所属分组的参数：(牵引时间窗平移, 切向倍数, 流向边框的占比, 出生平移)；与 step.comp 的
    sink_group 相同，取第一个包含出生位置的矩形，都不包含时用缺省值。"""
    n = len(values)
    shift = np.zeros(n, 'float32')
    swirl = np.ones(n, 'float32')
    share = np.full(n, F32(g.share), 'float32')
    born = np.zeros(n, 'float32')
    done = np.zeros(n, dtype=bool)
    x, y = values[:, 0], values[:, 1]
    for (l, t, r, b), (s_shift, s_swirl, s_share, s_born) in g.groups:
        inside = (~done) & (x >= F32(l)) & (y >= F32(t)) & (x < F32(r)) & (y < F32(b))
        shift[inside], swirl[inside], share[inside], born[inside] = s_shift, s_swirl, s_share, s_born
        done |= inside
    return shift, swirl, share, born


def _fract(v):
    return v - np.floor(v)


def sink_targets(values: np.ndarray, g: SinkGeometry) -> np.ndarray:
    """`ParticleSink.target` / `step.comp` 的 `sink_target`：同样的随机数、同样的比较顺序。

    Kotlin 以 Float 计算，这里全程 float32，挑选与平局判定逐位一致。
    """
    x, y = values[:, 0], values[:, 1]
    r = values[:, 8:12]
    pick = _fract(r[:, 0] * F32(7.31) + r[:, 3] * F32(3.17))
    jitter = (_fract(r[:, 1] * F32(5.13) + r[:, 2] * F32(1.91)) - F32(.5)) * F32(g.jitter)
    L, T, R, B = F32(g.left), F32(g.top), F32(g.right), F32(g.bottom)
    left, right, top, bottom = x - L, R - x, y - T, B - y
    nearest = np.minimum(np.minimum(left, right), np.minimum(top, bottom))
    is_left = nearest == left
    is_right = ~is_left & (nearest == right)
    is_top = ~is_left & ~is_right & (nearest == top)
    along_y = np.clip(y + jitter, T, B)
    along_x = np.clip(x + jitter, L, R)
    tx = np.where(is_left, L, np.where(is_right, R, along_x))
    ty = np.where(is_left | is_right, along_y, np.where(is_top, T, B))
    to_frame = pick < F32(g.share)
    return np.stack([np.where(to_frame, tx, F32(g.x)), np.where(to_frame, ty, F32(g.y))], axis=1).astype('float32')


def shape_for_sink(values: np.ndarray, g: SinkGeometry, span: float, params: Params) -> np.ndarray:
    """`ParticleSink.shape`：释放时刻整体压缩，寿命改写为「从剥离到抵达终点」。原地修改并返回终点。"""
    target = sink_targets(values, g)
    unit = F32(max(span, 1.))
    born = values[:, 2] * F32(params['sink_release_scale'])
    distance = np.hypot(target[:, 0] - values[:, 0], target[:, 1] - values[:, 1]).astype('float32') / unit
    jitter = F32(1) + F32(params['sink_flight_jitter']) * (F32(2) * values[:, 8] - F32(1))
    flight = (F32(params['sink_flight_base']) + F32(params['sink_flight_per_span']) * distance) * jitter
    latest = F32(params['sink_arrival_end']) - F32(.04) * values[:, 10]
    arrival = np.minimum(born + flight, latest)
    values[:, 2] = born
    values[:, 6] = np.maximum(arrival - born, F32(params['sink_min_life']))
    return target


def field_targets(values: np.ndarray, g: SinkGeometry, card: tuple[float, float]) \
        -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """`step.comp` 的 `sink_field_target` / `sink_frame_bound` / `sink_absorbed`：同样的随机数与比较顺序。

    返回 (落点, 是否流向边框, 是否被吸入天线)。
    """
    r = values[:, 8:12]
    frame_bound = _fract(r[:, 0] * F32(7.31) + r[:, 3] * F32(3.17)) < group_of(values, g)[2]
    absorbed = _fract(r[:, 2] * F32(4.73) + r[:, 1] * F32(8.21)) < F32(g.flow[1])
    # 从快照中心沿「中心 → 这一片」射到边框上，再沿边错开、在带宽内向里散开
    L, T, R, B = F32(g.left), F32(g.top), F32(g.right), F32(g.bottom)
    cx, cy = F32(card[0] * .5), F32(card[1] * .5)
    ux, uy = values[:, 0] - cx, values[:, 1] - cy
    length = np.hypot(ux, uy)
    ok = length > F32(.0001)
    ux = np.where(ok, ux / np.maximum(length, F32(1e-9)), F32(0))
    uy = np.where(ok, uy / np.maximum(length, F32(1e-9)), F32(1))
    with np.errstate(divide='ignore', invalid='ignore'):
        tx = np.where(ux > .0001, (R - cx) / ux, np.where(ux < -.0001, (L - cx) / ux, F32(1e9)))
        ty = np.where(uy > .0001, (B - cy) / uy, np.where(uy < -.0001, (T - cy) / uy, F32(1e9)))
    side = tx < ty
    reach = np.minimum(tx, ty)
    hx, hy = cx + ux * reach, cy + uy * reach
    jitter = (_fract(r[:, 1] * F32(5.13) + r[:, 2] * F32(1.91)) - F32(.5)) * F32(g.jitter)
    hx = np.clip(hx + np.where(side, F32(0), jitter), L, R)
    hy = np.clip(hy + np.where(side, jitter, F32(0)), T, B)
    depth = F32(g.band[0]) * _fract(r[:, 1] * F32(3.93) + r[:, 0] * F32(6.11)) ** F32(1.6)
    ix = np.where(side, np.where(ux > 0, F32(-1), F32(1)), F32(0))
    iy = np.where(side, F32(0), np.where(uy > 0, F32(-1), F32(1)))
    frame_target = np.stack([hx + ix * depth, hy + iy * depth], axis=1)
    angle = _fract(r[:, 1] * F32(3.71) + r[:, 0] * F32(5.27)) * F32(6.28318)
    # 瑞利分布（二维高斯的半径）：向外逐渐变稀，没有一圈整齐的边；与 step.comp 的 sink_field_target 相同
    u = _fract(r[:, 2] * F32(9.13) + r[:, 3] * F32(2.39))
    radius = np.minimum(F32(.55) * np.sqrt(F32(-2) * np.log(np.maximum(F32(1) - u, F32(.0001)))),
                        F32(2.2)) * F32(g.flow[2])
    point_target = np.stack([F32(g.x) + np.cos(angle) * radius, F32(g.y) + np.sin(angle) * radius], axis=1)
    target = np.where(frame_bound[:, None], frame_target, point_target).astype('float32')
    return target, frame_bound, absorbed


def frame_hit(values: np.ndarray, g: SinkGeometry, card: tuple[float, float]) -> tuple[np.ndarray, np.ndarray]:
    """流向边框的颗粒各自的落点在边框上的位置：从快照中心沿「中心 → 这一片」射到边框上的那一点
    （与 field_targets 相同，不含沿边错开与向里的深度）。"""
    L, T, R, B = (np.float64(v) for v in (g.left, g.top, g.right, g.bottom))
    cx, cy = card[0] * .5, card[1] * .5
    ux, uy = values[:, 0].astype(np.float64) - cx, values[:, 1].astype(np.float64) - cy
    # 平方和开方（正确舍入），Kotlin（`ParticleSink.frameHit`）逐位复现；hypot 两边的实现不保证相同
    length = np.sqrt(ux * ux + uy * uy)
    ok = length > 1e-4
    ux = np.where(ok, ux / np.maximum(length, 1e-9), 0.)
    uy = np.where(ok, uy / np.maximum(length, 1e-9), 1.)
    with np.errstate(divide='ignore', invalid='ignore'):
        tx = np.where(ux > 1e-4, (R - cx) / ux, np.where(ux < -1e-4, (L - cx) / ux, 1e9))
        ty = np.where(uy > 1e-4, (B - cy) / uy, np.where(uy < -1e-4, (T - cy) / uy, 1e9))
    reach = np.minimum(tx, ty)
    return np.clip(cx + ux * reach, L, R), np.clip(cy + uy * reach, T, B)


def hash_units(index: np.ndarray, salt: int) -> np.ndarray:
    """逐元素的 [0, 1) 均匀数：与共同模型 `random_values` 同一个 32 位整数散列，Kotlin 端逐位复现。"""
    x = index.astype(np.uint32) + np.uint32(int(salt) & 0xFFFFFFFF)
    x = (x ^ (x >> np.uint32(16))) * np.uint32(0x7FEB352D)
    x = (x ^ (x >> np.uint32(15))) * np.uint32(0x846CA68B)
    x = x ^ (x >> np.uint32(16))
    return (x >> np.uint32(8)).astype(np.float64) / 16777216.0


# 扰动的散列盐：与随机向量（`random_values` 的盐是种子本身）错开
SOURCE_SALT = 0x5EED5EED


def assign_sources(values: np.ndarray, g: SinkGeometry, card: tuple[float, float], blend: float, seed: int,
                   visible: np.ndarray | None = None):
    """接收：按远近分配每一片的来源。离屏幕边缘近的那部分由屏幕四周的颗粒拼出，离天线近的由天线处的颗粒拼出
    （用户 2026-09-25：左侧那张卡片的右半边挨着天线，却由四周的颗粒拼出，方向反了）。

    一片是否流向边框由它的随机向量决定（`step.comp` 的 sink_frame_bound：散列 < 占比），这里不改着色器，
    只在各片之间重排随机向量：散列 < 占比的那些向量（个数不变，所以占比不变）交给「到天线的距离 − 到自己的
    边框落点的距离」最大的那几片，其余向量交给其余各片。[blend]（材料单位）是加在这个差值上的扰动的标准差：
    两部分之间是一段混在一起的过渡，没有一条分界线。随机向量整个搬动，其余由它导出的量（是否被吸入、落点角度
    与半径、抵达时刻等）的分布都不变。原地修改 values[:, 8:12]。

    [visible]：出生在快照不透明处的各片。几张卡片合成的快照里，卡片之间的空隙与较短那张下方是透明的，那里的颗粒
    看不见；只在看得见的颗粒里排，占比按它们算（看不见的分到剩下的向量），名额不浪费在看不见的颗粒上。

    每一步都能在 Kotlin（`ParticleSink.assignSources`）里逐位复现：扰动是四个整数散列均匀数之和（近似正态，
    方差为 1），距离用平方和开方，排序按「差值从大到小、下标从小到大」，向量按原来的先后依次交给按下标排好的
    接收方。"""
    n = len(values)
    if n == 0 or g.share <= 0:
        return
    r = values[:, 8:12]
    share = group_of(values, g)[2]
    frame_vector = _fract(r[:, 0] * F32(7.31) + r[:, 3] * F32(3.17)) < share
    k = int(frame_vector.sum())
    if k == 0 or k == n:
        return
    hx, hy = frame_hit(values, g, card)
    x, y = values[:, 0].astype(np.float64), values[:, 1].astype(np.float64)
    gx, gy = float(np.float32(g.x)), float(np.float32(g.y))
    score = np.sqrt((x - gx) ** 2 + (y - gy) ** 2) - np.sqrt((x - hx) ** 2 + (y - hy) ** 2)
    if blend > 0:
        i4 = np.arange(n, dtype=np.int64) * 4
        salt = int(seed) ^ SOURCE_SALT
        noise = (hash_units(i4, salt) + hash_units(i4 + 1, salt) + hash_units(i4 + 2, salt)
                 + hash_units(i4 + 3, salt) - 2.0) * math.sqrt(3.0)
        score = score + blend * noise
    seen = np.ones(n, dtype=bool) if visible is None else np.asarray(visible, dtype=bool)
    shown = np.flatnonzero(seen)
    hidden = np.flatnonzero(~seen)
    # 看得见的那些里占比 = share；剩下的流向边框的向量给看不见的（随它们去，画不出来）
    k_shown = min(k, int(math.floor(len(shown) * k / n + .5)))
    ranked = shown[np.lexsort((shown, -score[shown]))]
    to_frame = np.sort(np.concatenate([ranked[:k_shown], hidden[:k - k_shown]]))
    to_other = np.setdiff1d(np.arange(n), to_frame, assume_unique=True)
    shuffled = np.empty_like(r)
    shuffled[to_frame] = r[frame_vector]
    shuffled[to_other] = r[~frame_vector]
    values[:, 8:12] = shuffled


def shape_for_field(values: np.ndarray, g: SinkGeometry, span: float, params: Params,
                    card: tuple[float, float]) -> np.ndarray:
    """柔性终点的整形：被牵引的颗粒（流向边框的、被吸入天线的）在同一个时间窗里抵达各自的落点
    （sink_arrival，与释放先后无关，所以是整团一起到，不拉成一条流带）；其余颗粒保留弹窗的寿命
    （乘 sink_free_life），在途中消散。释放时刻同样整体压缩。"""
    target, frame_bound, absorbed = field_targets(values, g, card)
    homing = frame_bound | absorbed
    shift, _, _, born_shift = group_of(values, g)
    born = values[:, 2] * F32(params['sink_release_scale']) + born_shift
    first, last = (F32(v) for v in params['sink_arrival'])
    arrival = first + (last - first) * values[:, 10] + shift
    latest = F32(params['sink_arrival_end']) - F32(.04) * values[:, 10]
    home_life = np.maximum(arrival - born, F32(params['sink_min_life']))
    free_life = np.maximum(np.minimum(values[:, 6] * F32(params['sink_free_life']), latest - born),
                           F32(params['sink_min_life']))
    values[:, 2] = born
    values[:, 6] = np.where(homing, home_life, free_life)
    return target


@dataclass
class Build:
    """一次建材的结果：与 `ParticleMicroflakeRenderer.Input` 对应的全部量。"""
    scale: float                      # 像素 / 材料单位
    frame: tuple[float, float]        # 整个画面（材料单位）
    card: tuple[float, float]         # 快照（材料单位）
    offset: tuple[float, float]       # 快照左上角（材料单位）
    direction: float                  # 度，0 右 90 上
    seed: int
    base: np.ndarray                  # (n, 12) float32，已按终点整形
    pigment: np.ndarray
    peel_compression: np.ndarray
    nx: int
    ny: int
    cell: tuple[float, float]
    body_weight: float
    panel_weight: float
    release_spread: float
    variation: np.ndarray
    sink: SinkGeometry
    targets: np.ndarray               # (n, 2) 每片的终点（材料单位）
    visible: np.ndarray | None = None  # (n,) 出生在快照不透明处（看得见）的各片
    stats: dict = field(default_factory=dict)


def build_materials(scene: Scene, params: Params, seed: int) -> Build:
    """`ParticleMicroflakeRenderer.fromPixels` 的桌面版：同一缩放、方向、起点与终点整形。"""
    density = scene.density
    scale = max(density / DP_UNITS, .5)
    h_px, w_px = scene.snapshot.shape[:2]
    cw, ch = w_px / scale, h_px / scale
    ox, oy = scene.bounds[:2]
    sx, sy = scene.sink
    dx = sx - ox - w_px / 2.
    dy = sy - oy - h_px / 2.
    direction = float(np.float32(math.degrees(math.atan2(-dy, dx))))
    # 起点：截图上离终点最近的那一点（TapParticleFlight.releaseOriginToward）
    origin = scene.meta.get('release_origin')
    if origin is None:
        l, t, r, b = scene.bounds
        origin = ((min(max(sx, l), r) - l) / max(r - l, 1.), (min(max(sy, t), b) - t) / max(b - t, 1.))
    # 本机「微片尺寸」设置给出格子下限；0 表示不设
    cell_floor = max(um.RULES['cell'], float(scene.meta.get('cell_dp') or 0.) * DP_UNITS)
    mode = flow_mode()
    if mode == 'dialog-free':
        origin = None
    with release_origin(origin):
        built = um.materials(cw, ch, scene.snapshot, direction, seed, cell_floor)
    base = built['base']
    geometry = sink_geometry(scene, params, scale)
    # 出生在快照不透明处的各片（几张卡片合成的快照里，空隙处的颗粒看不见）
    px = np.clip((base[:, 0] * np.float32(scale)).astype(np.int64), 0, w_px - 1)
    py = np.clip((base[:, 1] * np.float32(scale)).astype(np.int64), 0, h_px - 1)
    visible = scene.snapshot[py, px, 3] > 0
    if mode.startswith('dialog'):
        targets = np.zeros((len(base), 2), 'float32')
    elif mode == 'legacy':
        targets = shape_for_sink(base, geometry, min(cw, ch), params)
    else:
        if geometry.share > 0:
            assign_sources(base, geometry, (cw, ch), params['receive_frame_blend_dp'] * DP_UNITS, seed, visible)
        targets = shape_for_field(base, geometry, min(cw, ch), params, (cw, ch))
    w, h = scene.screen
    return Build(scale=scale, frame=(w / scale, h / scale), card=(cw, ch), offset=(ox / scale, oy / scale),
                 direction=direction, seed=int(seed), base=base, pigment=built['pigment'],
                 peel_compression=built['peel_compression'], nx=built['nx'], ny=built['ny'], cell=built['cell'],
                 body_weight=float(built['body_weight']), panel_weight=float(built['panel_weight']),
                 release_spread=float(built['release_spread']), variation=built['variation'], sink=geometry,
                 targets=targets, visible=visible, stats=dict(count=len(base), replicas=int(built['replica_count'])))


# ---------------------------------------------------------------- 时间轴

def cubic_bezier(x1: float, y1: float, x2: float, y2: float):
    """`PathInterpolator(x1, y1, x2, y2)`：起点 (0,0)、终点 (1,1) 的三次贝塞尔，按 x 求 y。"""
    def solve(x: float) -> float:
        x = min(max(x, 0.), 1.)
        lo, hi = 0., 1.
        for _ in range(40):
            t = (lo + hi) / 2
            bx = 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3
            if bx < x:
                lo = t
            else:
                hi = t
        t = (lo + hi) / 2
        return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3
    return solve


def smooth(a: float, b: float, x: float) -> float:
    t = min(max((x - a) / (b - a), 0.), 1.)
    return t * t * (3 - 2 * t)


@dataclass
class Moment:
    """时间轴上一刻要画什么。"""
    position: float | None     # 历史采样的位置（0–60，可带小数）；None = 不画粒子层
    border: float = 0.         # 接收：边框的不透明度（0–1），在两张底图之间过渡
    glow: float = 0.           # 发送：天线处光的亮度（0–1）
    label: str = ''


class SendTimeline:
    """发送：碰上 → 卡片流进天线 → 「已发送」停留 → 流回拼成卡片。

    与 `TapShareOverlay` 相同：进度由 ValueAnimator 按缓动推进，粒子层按进度 × 60 取历史并在
    相邻两帧之间插值（`ParticleGestureProgress` + `history.comp` 的 fraction）；天线处的光按
    已上屏的进度取 `glowAt`。时间 0 是粒子层接管（第一帧与卡片逐像素相同）。
    """
    LEAD = .35
    TAIL = .6

    def __init__(self, params: Params):
        self.p = params
        self.out_s = params['send_outflow_ms'] / 1000
        self.hold_s = params['send_sent_hold_ms'] / 1000
        self.reform_s = params['send_reform_ms'] / 1000
        self.out_ease = cubic_bezier(*params['send_outflow_ease'])
        self.reform_ease = cubic_bezier(*params['send_reform_ease'])
        self.duration = self.LEAD + self.out_s + self.hold_s + self.reform_s + self.TAIL

    def glow(self, progress: float) -> float:
        a, b, c, d = self.p['send_glow_curve']
        return smooth(a, b, progress) * (1 - smooth(c, d, progress))

    def at(self, t: float) -> Moment:
        t -= self.LEAD
        if t < 0:
            # 接管之前是真实卡片；粒子层的第 0 帧与它逐像素相同，直接画第 0 帧
            return Moment(0., label='等待碰上')
        if t < self.out_s:
            p = self.out_ease(t / self.out_s)
            return Moment(p * SAMPLES, glow=self.glow(p), label=f'流出 {p:.3f}')
        t -= self.out_s
        if t < self.hold_s:
            return Moment(float(SAMPLES), glow=self.glow(1.), label='已发送')
        t -= self.hold_s
        if t < self.reform_s:
            p = 1 - self.reform_ease(t / self.reform_s)
            return Moment(p * SAMPLES, glow=self.glow(p), label=f'流回 {p:.3f}')
        return Moment(0., label='卡片复原')


class ReceiveTimeline:
    """接收：等待（边框）→ 颗粒从天线与边框流进空位 → 拼好。

    与 `ParticleSession` 的倒放相同：进度 = 已播时间 / 时长，采样下标 = round((1 − 进度) × 60)，
    不插值（`ParticleReversePlan.frame`）；边框按 `receive_border_fade_s` 线性淡去（舞台的 approach）。
    时间 0 是第一帧上屏。拼好之后粒子层停在末帧，与真实卡片逐像素相同。
    """
    LEAD = .5
    TAIL = .8

    def __init__(self, params: Params):
        self.appear_s = params['receive_appear_s']
        self.fade_s = params['receive_border_fade_s']
        self.duration = self.LEAD + self.appear_s + self.TAIL

    def at(self, t: float) -> Moment:
        t -= self.LEAD
        if t < 0:
            return Moment(None, border=1., label='等待碰上')
        border = max(0., 1 - t / self.fade_s)
        progress = min(t / self.appear_s, 1.)
        frame = int(math.floor((1 - progress) * SAMPLES + .5))
        return Moment(float(frame), border=border, label='拼好' if progress >= 1 else f'流入 {progress:.3f}')


def timeline_for(scene: Scene, params: Params):
    return SendTimeline(params) if scene.kind == 'send' else ReceiveTimeline(params)
