"""Android「飘散」样式的输入规则：Kotlin 源码的逐项 Python 移植，供桌面模拟器的「Android 移植口径」使用。

对应 Android 源码（app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/）：
- ParticleDriftModel：seededRandom、latticeScale / latticeSize、noise / noiseRange、bornSeconds / lifeAt、count、random，
  以及 startSeconds / bodyGoneSeconds / totalSeconds；
- ParticleDriftStyles：pick（旧钢铁侠一族优先）、variantOf、resample；
- ParticleDriftContent：analyse / weight（内容强调）；
- ParticleDriftBackground：背景亮度 → 加光 / 标准 src-over；
- ParticleDriftSession：设备时刻 startSeconds → totalSeconds 压进 1 秒；BaseDialogFragment：弹窗暗层的淡出曲线。

Kotlin 的 Float 是单精度：这里逐步用 np.float32 复现（常量一律先转成 float32 再参与运算），java.util.Random 按 JDK 的
48 位线性同余逐位复现。同一个种子、同一张快照给出的输入与设备上逐位相同，由
docs/features/harmony-particle-android-port/parity/check_inputs.py 在 R5CW20BLNKL 上核对。
渲染本身由 harmony_native.NativeRenderer 完成（android_extras 接上湍流换算、内容强调与风向）。
"""
from dataclasses import dataclass, field
from pathlib import Path
import json
import math

import numpy as np

f32 = np.float32
HERE = Path(__file__).resolve().parent
STYLES_JSON = HERE.parents[3] / 'shared/particle-dismiss/drift-styles.json'

# ---------------------------------------------------------------- ParticleDriftModel 常量
HZ = f32(120.0)
RATE_PER_SECOND = f32(1.05)
SOFTNESS = f32(0.15)
BIRTH_MARGIN = f32(0.135)
LIFE_MAX = f32(0.8)
WIND_X_RANGE = f32(30000.0)
WIND_Y = f32(40000.0)
REFERENCE_CARD_COUNT = 40000.0
REFERENCE_CARD_AREA = 1.3e6
MIN_COUNT, MAX_COUNT = 10000, 50000
TURBULENCE_OFFSET_RANGE = f32(64.0)
MAX_LATTICE = 8
TOUCH_GAIN_BASE = f32(0.55)          # 风力随触点远近：0.55 + 0.90 × 远近（缺省 0.5 即 1 倍）
TOUCH_GAIN_RANGE = f32(0.90)
# ParticleDriftStyles
MATCH_DEGREES = f32(22.5)
REFERENCE_PROBABILITY = f32(0.5)
FAVORITE = 'ironman'
VARIANT_SUFFIX = '-variant'
FAVORITE_PROBABILITY = f32(0.5)
FAVORITE_ORIGINAL_SHARE = f32(0.4)
UP_FAMILY_HALF_SPAN = f32(35.0)       # 朝上一组：风向离正上方不超过 35°（参考 5、4、7、1、向上钢铁侠、参考 2）
UP_FAMILY_PROBABILITY = f32(0.75)
START_POINT_VALUE = f32(0.12)
VARIANT_JITTER = f32(0.06)
VARIANT_WIND_RANGE = f32(0.15)
OVER_ALPHA_MUL = f32(1.15)
SIZE_REFERENCE_DENSITY = f32(3.5)


def size_scale_for_display(multiplier, density):
    """与 ParticleDriftModel.sizeScaleForDisplay 相同；只缩放绘制，不改变轨迹。"""
    return f32(f32(multiplier) * f32(f32(density) / SIZE_REFERENCE_DENSITY))
# ParticleDriftContent
PANEL_MIN_FRACTION = f32(0.24)
PANEL_FULL_FRACTION = f32(0.60)
DISTANCE_START = f32(0.06)
DISTANCE_SPAN = f32(0.26)
COPIES = 2
GRID = 64
CELL_SAMPLES = 4
MAX_TOTAL = 75000
# ParticleDriftBackground
DARK_LIGHTNESS = 50.0
SAMPLE = 32
# 时长与暗层（ParticleMicroflakeModel.DURATION、DialogDimLayer.DIM_AMOUNT、BaseDialogFragment 的淡出插值）
DURATION = 1.0
DIM_AMOUNT = 0.6
BACK_KEY_DEGREES = 135.0           # 返回键关闭：触点取弹窗中心左上（BaseDialogFragment.dismissTouchAnchor）
# 背景图里已经烤进了应用暗层（截图时弹窗开着）的自有序列：更改应用语言的设置页灰是 (102, 102, 102) = 0.4 × 255，
# 即白底叠 0.6 的黑色暗层。Android 判背景亮度时扣掉自己的暗层；模拟器先把它除回去，再按 Android 的曲线让暗层淡出。
BAKED_DIM = {'language': DIM_AMOUNT}


# ---------------------------------------------------------------- java.util.Random 与 SplitMix64

class JavaRandom:
    MULT = 0x5DEECE66D
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self.MULT) & self.MASK

    def next(self, bits):
        self.seed = (self.seed * self.MULT + 0xB) & self.MASK
        r = self.seed >> (48 - bits)
        return r - (1 << 32) if r & (1 << 31) else r

    def next_float(self):
        return f32(self.next(24) / float(1 << 24))

    def next_int(self, bound):
        if bound & -bound == bound:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val


M64 = (1 << 64) - 1


def seeded_random(seed):
    """ParticleDriftModel.seededRandom：Int 种子先经 SplitMix64 打散，再交给 java.util.Random。"""
    z = (int(seed) + (-0x61c8864680b583eb)) & M64
    z = ((z ^ (z >> 30)) * (-0x40a7b892e31b1a47 & M64)) & M64
    z = ((z ^ (z >> 27)) * (-0x6b2fb644ecceee15 & M64)) & M64
    return JavaRandom(z ^ (z >> 31))


# ---------------------------------------------------------------- 格点与噪声（单精度）

def lattice_scale(width, height):
    return f32(205.0 / (float(width) * float(height)) + 0.002)


def lattice_size(width, height):
    u = lattice_scale(width, height)
    return (min(int(np.floor(f32(width) * u)) + 2, MAX_LATTICE), min(int(np.floor(f32(height) * u)) + 2, MAX_LATTICE))


def noise(lattice, w, h, x, y):
    """与 ParticleDriftModel.noise / 着色器 driftNoise 相同的平滑插值值噪声（x、y 可为 float32 数组）。"""
    lattice = np.asarray(lattice, np.float32)
    x = np.asarray(x, np.float32)
    y = np.asarray(y, np.float32)
    ix = np.floor(x)
    iy = np.floor(y)
    fx = (x - ix).astype(np.float32)
    fy = (y - iy).astype(np.float32)
    fx = fx * fx * (f32(3.0) - f32(2.0) * fx)
    fy = fy * fy * (f32(3.0) - f32(2.0) * fy)
    i0 = ix.astype(np.int64)
    j0 = iy.astype(np.int64)

    def at(i, j):
        return lattice[np.clip(j, 0, h - 1) * w + np.clip(i, 0, w - 1)]
    top = at(i0, j0) + (at(i0 + 1, j0) - at(i0, j0)) * fx
    bottom = at(i0, j0 + 1) + (at(i0 + 1, j0 + 1) - at(i0, j0 + 1)) * fx
    return (top + (bottom - top) * fy).astype(np.float32)


def cell_ends(length, u):
    """与 ParticleDriftModel.cellEnds 相同：每 8 像素一个样点，返回每个格点单元里首、末两个样点的坐标。"""
    at = (np.arange(0, length, 8, dtype=np.int64).astype(np.float32) + f32(0.5)) * f32(u)
    cells = np.floor(at).astype(np.int64)
    first = np.flatnonzero(np.r_[True, cells[1:] != cells[:-1]])
    last = np.r_[first[1:] - 1, len(at) - 1]
    return np.stack([at[first], at[last]], 1).reshape(-1)


def noise_range(lattice, w, h, width, height, u):
    """在快照上按 8 像素的格子取样，求噪声值（未乘 0.8、未加 0.2）的最小、最大值。

    与 ParticleDriftModel.noiseRange 相同：单元里噪声是双线性的，极值在单元样点子网格的四个角上，只算各单元首末行列交出的样点。
    """
    gx, gy = np.meshgrid(cell_ends(width, u), cell_ends(height, u))
    v = noise(lattice, w, h, gx, gy)
    return f32(v.min()), f32(v.max())


def born_seconds(n):
    return f32(max(f32(1.0), f32(np.ceil(HZ * (f32(n) - BIRTH_MARGIN) / RATE_PER_SECOND - f32(1e-4))))) / HZ


def life_at(born):
    return f32(1.0) - min(f32(born) * f32(0.5), f32(1.0))


def count(width, height, amount=1.0):
    """桌面 area_particle_count 截到 [1 万, 5 万] 之后再乘「粒子数量」倍率（Kotlin 的 roundToInt：floor(x + 0.5)）。"""
    area = float(width) * float(height)
    thousands = round(REFERENCE_CARD_COUNT * area / REFERENCE_CARD_AREA / 1000.0)   # Python round 与 Math.rint 都是五成双
    base = int(min(max(int(thousands * 1000.0), MIN_COUNT), MAX_COUNT))
    return max(int(math.floor(base * float(f32(amount)) + 0.5)), 1)


def emit_frame(sw, sh, ew, eh):
    emit_w = f32(sw) * f32(0.5)
    emit_h = f32(sh) * f32(0.5)
    return [f32((f32(np.floor(f32(ew))) * f32(0.5) - emit_w) * f32(0.5)),
            f32((f32(np.floor(f32(eh))) * f32(0.5) - emit_h) * f32(0.5)), emit_w, emit_h]


# ---------------------------------------------------------------- 参考样式库

@dataclass
class Style:
    name: str
    angle: np.float32
    wind: np.float32
    width: int
    height: int
    expansion_width: np.float32
    expansion_height: np.float32
    lattice: np.ndarray            # float32，行优先
    lattice_width: int
    lattice_height: int
    fade_size_power: np.float32
    fade_alpha_power: np.float32
    over_alpha_mul: np.float32


def load_styles(path=STYLES_JSON):
    out = []
    for o in json.loads(Path(path).read_text('utf-8'))['styles']:
        rows = o['lattice']
        exp = o.get('expansion')
        out.append(Style(o['name'], f32(o['angle']), f32(o['wind']), int(o['size'][0]), int(o['size'][1]),
                         f32(exp[0]) if exp else f32(o['size'][0]) * f32(1.25),
                         f32(exp[1]) if exp else f32(o['size'][1]) * f32(1.25),
                         np.asarray([v for row in rows for v in row], np.float32), len(rows[0]), len(rows),
                         f32(o.get('fadeSizePower', 0.5)), f32(o.get('fadeAlphaPower', 0.5)),
                         f32(o.get('overAlphaMul', float(OVER_ALPHA_MUL)))))
    return out


def angle_difference(a, b):
    return f32(np.fmod(f32(a) - f32(b) + f32(540.0), f32(360.0)) - f32(180.0))


def variant_of(base, rnd):
    lattice = np.empty_like(base.lattice)
    for k in range(len(base.lattice)):
        v = base.lattice[k] + (rnd.next_float() * f32(2.0) - f32(1.0)) * VARIANT_JITTER
        lattice[k] = min(max(f32(v), f32(0.0)), f32(1.0))
    spacing = f32(1.0) / lattice_scale(base.width, base.height)
    lw = base.lattice_width
    visible = [k for k in range(len(lattice))
               if f32(k % lw) * spacing <= f32(base.width) and f32(k // lw) * spacing <= f32(base.height)]
    starts = [k for k in visible if lattice[k] < START_POINT_VALUE]
    others = [k for k in visible if lattice[k] >= START_POINT_VALUE]
    all_starts = int((lattice < START_POINT_VALUE).sum())
    change = rnd.next_int(4) - 1
    # Kotlin 的 a[i] = v 先算下标、再算右边；Python 反过来，所以下标单独先取（两次随机数的先后决定结果）
    if change < 0:
        if starts and all_starts > 1:
            index = starts.pop(rnd.next_int(len(starts)))
            lattice[index] = f32(0.3) + rnd.next_float() * f32(0.3)
        else:
            change = 0
    else:
        for _ in range(change):
            if others:
                index = others.pop(rnd.next_int(len(others)))
                lattice[index] = rnd.next_float() * f32(0.06)
    wind = f32(base.wind * (f32(1.0) + (rnd.next_float() * f32(2.0) - f32(1.0)) * VARIANT_WIND_RANGE))
    label = '起点不变' if change == 0 else '起点%+d' % change
    return Style('%s%s（%s）' % (base.name, VARIANT_SUFFIX, label), base.angle, wind, base.width, base.height,
                 base.expansion_width, base.expansion_height, lattice, base.lattice_width, base.lattice_height,
                 base.fade_size_power, base.fade_alpha_power, base.over_alpha_mul)


def matches(style, angle):
    return abs(angle_difference(style.angle, angle)) <= MATCH_DEGREES


def is_up_family(style):
    return style.name != FAVORITE and abs(angle_difference(style.angle, f32(90.0))) <= UP_FAMILY_HALF_SPAN


def pick(styles, angle, rnd, forced=None):
    """ParticleDriftStyles.pick：旧钢铁侠一族（左上）与朝上一组（上方、上方偏左 / 偏右）优先，其余参考与随机各半。"""
    if forced is not None:
        if forced.endswith(VARIANT_SUFFIX):
            base = next((s for s in styles if s.name == forced[:-len(VARIANT_SUFFIX)]), None)
            return variant_of(base, rnd) if base else None
        return next((s for s in styles if s.name == forced), None)
    favorite = next((s for s in styles if s.name == FAVORITE), None)
    if favorite is not None and not matches(favorite, angle):
        favorite = None
    up = [s for s in styles if is_up_family(s) and matches(s, angle)]
    up_nearer = bool(up) and (favorite is None or
                              min(abs(angle_difference(s.angle, angle)) for s in up) <
                              abs(angle_difference(favorite.angle, angle)))
    family = up if up_nearer else ([favorite] if favorite is not None else [])
    if family and rnd.next_float() < (UP_FAMILY_PROBABILITY if up_nearer else FAVORITE_PROBABILITY):
        member = family[0] if len(family) == 1 else family[rnd.next_int(len(family))]
        return member if rnd.next_float() < FAVORITE_ORIGINAL_SHARE else variant_of(member, rnd)
    candidates = [s for s in styles if all(s is not m for m in family) and matches(s, angle)]
    if not candidates or rnd.next_float() >= REFERENCE_PROBABILITY:
        return None
    return candidates[rnd.next_int(len(candidates))]


def resample(style, width, height):
    nx, ny = lattice_size(width, height)
    u = lattice_scale(width, height)
    u_ref = lattice_scale(style.width, style.height)
    i = np.arange(nx, dtype=np.float32)[None, :].repeat(ny, 0)
    j = np.arange(ny, dtype=np.float32)[:, None].repeat(nx, 1)
    rx = i / u / f32(width) * f32(style.width) * u_ref
    ry = j / u / f32(height) * f32(style.height) * u_ref
    out = noise(style.lattice, style.lattice_width, style.lattice_height, rx, ry).reshape(-1)
    reference = noise_range(style.lattice, style.lattice_width, style.lattice_height, style.width, style.height, u_ref)
    own = noise_range(out, nx, ny, width, height, u)
    span = f32(own[1] - own[0])
    if span > f32(1e-4):
        scale = f32((reference[1] - reference[0]) / span)
        out = np.clip(reference[0] + (out - own[0]) * scale, f32(-0.5), f32(1.5)).astype(np.float32)
    return out


# ---------------------------------------------------------------- 一次动画的输入

@dataclass
class DriftModel:
    width: int
    height: int
    lattice: np.ndarray
    lattice_width: int
    lattice_height: int
    wind_x: np.float32               # aeWind（工作面单位，y 向下）
    wind_y: np.float32
    count: int
    fade_size_power: np.float32 = f32(0.5)
    fade_alpha_power: np.float32 = f32(0.5)
    over_alpha_mul: np.float32 = OVER_ALPHA_MUL
    style_name: str = None
    turbulence_offset: tuple = (f32(0.0), f32(0.0))
    turbulence_frame: list = None
    size_scale: np.float32 = f32(1.0)    # 设置里的「粒子大小」倍率
    touch_gap: np.float32 = None         # 真实触点到边缘的距离（短边为单位）；None 表示没有真实触点
    derived: dict = field(default_factory=dict)

    def __post_init__(self):
        u = lattice_scale(self.width, self.height)
        lo, hi = noise_range(self.lattice, self.lattice_width, self.lattice_height, self.width, self.height, u)
        max_mask = f32(hi * f32(0.8) + f32(0.2))
        start = max(f32(0.0), f32((lo * f32(0.8) + f32(0.2) - SOFTNESS) / RATE_PER_SECOND))
        born = born_seconds(max_mask)
        self.derived = dict(scaleU=u, startSeconds=f32(start), bodyGoneSeconds=f32(max_mask / RATE_PER_SECOND),
                            totalSeconds=f32(born + life_at(born) * LIFE_MAX))

    @property
    def start_seconds(self):
        return float(self.derived['startSeconds'])

    @property
    def total_seconds(self):
        return float(self.derived['totalSeconds'])


def random_model(width, height, direction_x, direction_y, amount, rnd, styles, forced=None, size=1.0,
                 touch_gap=None, touch_strength=None):
    """ParticleDriftModel.random（弹窗没有左滑的按下点，不含 release 那一步）。方向在叠加层像素里，y 向下。"""
    grid = lattice_size(width, height)
    dx, dy = f32(direction_x), f32(direction_y)
    length = f32(math.hypot(float(dx), float(dy)))
    ux = f32(dx / length) if length > f32(1e-6) else f32(0.0)
    uy = f32(dy / length) if length > f32(1e-6) else f32(-1.0)
    angle = f32(math.degrees(math.atan2(-float(uy), float(ux))))
    style = pick(styles, angle, rnd, forced)
    if style is not None:
        lattice = resample(style, width, height)
    else:
        lattice = np.asarray([rnd.next_float() for _ in range(grid[0] * grid[1])], np.float32)
    n = count(width, height, amount)
    gain = f32(TOUCH_GAIN_BASE + TOUCH_GAIN_RANGE * f32(0.5 if touch_strength is None else touch_strength))
    gap = None if touch_gap is None else f32(touch_gap)
    if style is not None:
        return DriftModel(width, height, lattice, grid[0], grid[1], f32(f32(ux * style.wind) * gain),
                          f32(f32(uy * style.wind) * gain), n,
                          style.fade_size_power, style.fade_alpha_power, style.over_alpha_mul, style.name,
                          turbulence_frame=emit_frame(style.width, style.height, style.expansion_width, style.expansion_height),
                          size_scale=f32(size), touch_gap=gap)
    wind_source = f32((rnd.next_float() * f32(2.0) - f32(1.0)) * WIND_X_RANGE)
    magnitude = f32(f32(0.5) * f32(math.hypot(float(wind_source), float(WIND_Y))))
    ox = f32(rnd.next_float() * TURBULENCE_OFFSET_RANGE)
    oy = f32(rnd.next_float() * TURBULENCE_OFFSET_RANGE)
    return DriftModel(width, height, lattice, grid[0], grid[1], f32(f32(ux * magnitude) * gain),
                      f32(f32(uy * magnitude) * gain), n, turbulence_offset=(ox, oy), size_scale=f32(size), touch_gap=gap)


# ---------------------------------------------------------------- 内容强调（ParticleDriftContent）

def _smooth(v):
    t = np.clip(np.asarray(v, np.float32), f32(0.0), f32(1.0)).astype(np.float32)
    return (t * t * (f32(3.0) - f32(2.0) * t)).astype(np.float32)


def content_weight(r, g, b, panel, panel_weight):
    r, g, b = (np.asarray(c, np.float32) for c in (r, g, b))
    chroma = _smooth((np.maximum(r, np.maximum(g, b)) - np.minimum(r, np.minimum(g, b)) - f32(0.30)) / f32(0.42))
    diff = np.maximum(np.abs(r - panel[0]), np.maximum(np.abs(g - panel[1]), np.abs(b - panel[2])))
    contrast = _smooth((diff - DISTANCE_START) / DISTANCE_SPAN)
    return (chroma + (contrast - chroma) * f32(panel_weight)).astype(np.float32)


def premultiply(rgba):
    """Skia 的 SkMulDiv255Round：BitmapFactory 解码出来的预乘字节。"""
    rgba = np.asarray(rgba, np.uint8)
    a = rgba[..., 3:4].astype(np.int32)
    prod = rgba[..., :3].astype(np.int32) * a + 128
    out = rgba.copy()
    out[..., :3] = ((prod + (prod >> 8)) >> 8).astype(np.uint8)
    return out


def analyse(premul, base_count):
    """ParticleDriftContent.analyse：premul 为预乘 RGBA8（H×W×4）。返回与设备 result.json 里 content 相同的字段。"""
    h, w = premul.shape[:2]
    none = dict(panel=[0.0, 0.0, 0.0], panelWeight=0.0, grid=[1, 1], replicas=0, cumulative=[])
    if w <= 0 or h <= 0:
        return none
    stride = max(1, int(math.ceil(math.sqrt(w * float(h) / 65536.0))))
    sub = premul[0:h:stride, 0:w:stride].reshape(-1, 4).astype(np.int64)
    opaque = sub[sub[:, 3] == 255]
    if len(opaque) == 0:
        return none
    key = (opaque[:, 0] // 16) * 256 + (opaque[:, 1] // 16) * 16 + opaque[:, 2] // 16
    counts = np.bincount(key, minlength=4096)
    best = int(np.argmax(counts))
    sel = opaque[key == best]
    panel = [f32(float(sel[:, c].sum()) / counts[best] / 255.0) for c in range(3)]
    fraction = f32(f32(counts[best]) / f32(len(opaque)))
    panel_weight = f32(_smooth((fraction - PANEL_MIN_FRACTION) / (PANEL_FULL_FRACTION - PANEL_MIN_FRACTION)))
    if panel_weight <= f32(0.01):
        return dict(panel=[float(v) for v in panel], panelWeight=0.0, grid=[1, 1], replicas=0, cumulative=[])
    cell_px = max(f32(1.0), f32(max(w, h)) / f32(GRID))
    gw = min(max(int(np.ceil(f32(w) / cell_px)), 1), GRID)
    gh = min(max(int(np.ceil(f32(h) / cell_px)), 1), GRID)
    sums = np.zeros((gh, gw), np.float32)
    ii = np.arange(gw, dtype=np.float32)[None, :]
    jj = np.arange(gh, dtype=np.float32)[:, None]
    for sy in range(CELL_SAMPLES):
        for sx in range(CELL_SAMPLES):
            fx = f32(f32(sx) + f32(0.5)) / f32(CELL_SAMPLES)
            fy = f32(f32(sy) + f32(0.5)) / f32(CELL_SAMPLES)
            px = np.clip(((ii + fx) / f32(gw) * f32(w)).astype(np.int64), 0, w - 1)
            py = np.clip(((jj + fy) / f32(gh) * f32(h)).astype(np.int64), 0, h - 1)
            px, py = np.broadcast_arrays(px, py)
            p = premul[py, px].astype(np.int64)
            a = p[..., 3]
            alpha = a.astype(np.float32)
            safe = np.where(a > 0, alpha, f32(1.0)).astype(np.float32)
            rr = np.minimum(p[..., 0].astype(np.float32) / safe, f32(1.0))
            gg = np.minimum(p[..., 1].astype(np.float32) / safe, f32(1.0))
            bb = np.minimum(p[..., 2].astype(np.float32) / safe, f32(1.0))
            wgt = content_weight(rr, gg, bb, panel, panel_weight) * (alpha / f32(255.0))
            sums = np.where(a > 0, (sums + wgt).astype(np.float32), sums).astype(np.float32)
    density = (f32(COPIES) * panel_weight * sums / f32(CELL_SAMPLES * CELL_SAMPLES)).astype(np.float32).reshape(-1)
    total = 0.0
    for d in density:
        total += float(d)
    cells = gw * gh
    replicas = max(min(int(math.floor(base_count * total / cells + 0.5)), MAX_TOTAL - base_count), 0)
    if replicas == 0 or total <= 0.0:
        return dict(panel=[float(v) for v in panel], panelWeight=float(panel_weight), grid=[gw, gh], replicas=0,
                    cumulative=[])
    running = 0.0
    cum = []
    for d in density:
        running += float(d)
        cum.append(float(f32(running / total)))
    cum[-1] = 1.0
    return dict(panel=[float(v) for v in panel], panelWeight=float(panel_weight), grid=[gw, gh], replicas=replicas,
                cumulative=cum)


# ---------------------------------------------------------------- 背景亮度（ParticleDriftBackground）

def relative_luminance(rgb, gain=1.0):
    c = np.minimum(np.asarray(rgb, np.float64) / 255.0 * gain, 1.0)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]


def lightness(y):
    return 116.0 * y ** (1.0 / 3.0) - 16.0 if y > 216.0 / 24389.0 else y * 24389.0 / 27.0


def background_additive(background, rect, dim=0.0):
    """快照四周（各向外扩一半、截在画面内）缩成 32 × 32，去掉快照那一块，平均相对亮度换成 L*，低于 50 为暗（加光）。
    background：画面 RGB（H×W×3，uint8），rect：快照在画面里的 (x0, y0, x1, y1)。返回 (加光, L*)。"""
    from PIL import Image
    h, w = background.shape[:2]
    x0, y0, x1, y1 = rect
    cw, ch = x1 - x0, y1 - y0
    outer = (max(int(x0 - cw * 0.5), 0), max(int(y0 - ch * 0.5), 0),
             min(int(x0 + cw * 1.5), w), min(int(y0 + ch * 1.5), h))
    ow, oh = outer[2] - outer[0], outer[3] - outer[1]
    if ow < SAMPLE or oh < SAMPLE:
        return None, float('nan')
    img = Image.fromarray(background[outer[1]:outer[3], outer[0]:outer[2]]).resize((SAMPLE, SAMPLE), Image.BILINEAR)
    px = np.asarray(img).astype(np.float64)
    sx, sy = SAMPLE / ow, SAMPLE / oh
    inner = (int((x0 - outer[0]) * sx), int((y0 - outer[1]) * sy), int((x1 - outer[0]) * sx) + 1, int((y1 - outer[1]) * sy) + 1)
    ring = SAMPLE * SAMPLE - max(inner[2] - inner[0], 0) * max(inner[3] - inner[1], 0)
    keep = np.ones((SAMPLE, SAMPLE), bool)
    if ring >= SAMPLE * SAMPLE // 8:
        keep[max(inner[1], 0):max(inner[3], 0), max(inner[0], 0):max(inner[2], 0)] = False
    gain = 1.0 / (1.0 - min(max(dim, 0.0), 0.95))
    y = relative_luminance(px[keep], gain).mean()
    l_star = float(lightness(y))
    return bool(l_star < DARK_LIGHTNESS), l_star


def dim_alpha(progress, amount=DIM_AMOUNT):
    """弹窗暗层随消散进度淡出：BaseDialogFragment 的插值 p = clamp((t − 0.18) / 0.55)，alpha = 暗度 × (1 − p²)。"""
    p = min(max((progress - 0.18) / 0.55, 0.0), 1.0)
    return amount * (1.0 - p * p)


# ---------------------------------------------------------------- 模拟器：Android 移植口径

def _frame_inputs(scene, meta):
    """设备比例、扩展画布：参考序列取原片估计的值，自有弹窗按 1260 像素宽的手机（与 harmony_native.live_inputs 相同）。"""
    import harmony_native as hn
    table = hn.reference_inputs()
    if scene in table:
        e = table[scene]
        return float(e['scale']), tuple(float(v) for v in e['expansion'])
    w, h = meta['frame']
    s = hn.OWN_SCALE_WIDTH / w
    return s, (min(w * s, 2100.0), min(h * s * 0.85, 2400.0))


def _snapshot(scene, meta, scale):
    """与 NativeRenderer 相同的快照：前景（有修过的副本就用副本）按设备比例 BICUBIC 缩放；再按 Skia 的取整预乘。"""
    from PIL import Image
    d = HERE / 'assets' / scene
    clean = d / 'foreground-clean.png'
    image = Image.open(clean if clean.exists() else d / 'foreground.png').convert('RGBA')
    x0, y0, x1, y1 = meta['rect']
    snap = (max(2, int(round((x1 - x0) * scale))), max(2, int(round((y1 - y0) * scale))))
    return premultiply(np.asarray(image.resize(snap, Image.BICUBIC)))


def build_android_renderer(scene, meta, seed, direction, ctx=None, ss=2, forced=None, amount=1.0, size=1.0,
                           touch_gap=None, touch_strength=None, display_density=3.5):
    """按 Android 的规则生成这一次动画的输入，交给逐帧推进的 NativeRenderer。

    direction：消散方向（度，0 向右、90 向上）；返回键关闭传 BACK_KEY_DEGREES。
    amount / size：设置里的「粒子数量」「粒子大小」倍率（缺省 1）。
    display_density：目标设备逻辑密度；默认取校准基准 3.5，复核具体设备时传其实际值。
    touch_gap / touch_strength：点击弹窗外时的触点远近（与 Android 的 ParticleFlowGeometry 同一公式）；返回键为 None。"""
    import harmony_native as hn
    scale, expansion = _frame_inputs(scene, meta)
    premul = _snapshot(scene, meta, scale)
    h, w = premul.shape[:2]
    a = math.radians(direction)
    model = random_model(w, h, f32(math.cos(a) * 1000.0), f32(-math.sin(a) * 1000.0), amount, seeded_random(seed),
                         load_styles(), forced, size_scale_for_display(size, display_density), touch_gap, touch_strength)
    content = analyse(premul, model.count)
    from PIL import Image
    background = np.asarray(Image.open(HERE / 'assets' / scene / 'background.png').convert('RGB'))
    baked = BAKED_DIM.get(scene, 0.0)
    if baked > 0:
        background = np.clip(background.astype(np.float64) / (1.0 - baked) + 0.5, 0, 255).astype(np.uint8)
    additive, l_star = background_additive(background, [v for v in meta['rect']])
    if additive is None:
        additive = False
    start, total = model.start_seconds, model.total_seconds
    rows = tuple(tuple(float(v) for v in model.lattice[j * model.lattice_width:(j + 1) * model.lattice_width])
                 for j in range(model.lattice_height))
    inputs = hn.NativeInputs(scale=scale, expansion=expansion, count=model.count + content['replicas'], wind_x=0.0,
                             lattice=rows, hz=120.0, span=max(total - start, 1e-3), offset=start, seed=int(seed) & 0x7fffffff)
    raster = hn.LIVE_RASTER if additive else hn.replace(hn.LIVE_RASTER, blend='over', alpha_mul=float(model.over_alpha_mul),
                                                         alpha_extra=0.0, premul_as_is=False)
    if (float(model.fade_size_power), float(model.fade_alpha_power)) != (0.5, 0.5):
        raster = hn.replace(raster, size_source='radius_alpha_split', fade_size_power=float(model.fade_size_power),
                            fade_alpha_power=float(model.fade_alpha_power))
    dim = DIM_AMOUNT if baked > 0 or float(meta.get('dim_alpha', 0.0)) > 0 else 0.0
    r = AndroidDriftLive(scene, inputs, raster=raster, ctx=ctx, ss=ss, dim_alpha=dim)
    if baked > 0:
        r.background.write(np.ascontiguousarray(background[::-1]).tobytes())
    # 快照纹理换成 Android 上传的同一份预乘字节（直通浮点，着色器里再乘回 alpha）
    pa = premul.astype(np.float64)
    alpha = pa[..., 3:4]
    straight = np.where(alpha > 0, np.minimum(pa[..., :3] / np.maximum(alpha, 1.0), 1.0), 0.0)
    r.image_tex.write(np.concatenate([straight, alpha / 255.0], axis=2).astype(np.float32).tobytes())
    frame = model.turbulence_frame
    if frame is not None:
        ex, ey = r.position
        sx, sy = float(frame[2]) / (w * 0.5), float(frame[3]) / (h * 0.5)
        turb_scale, turb_shift = (sx, sy), (float(frame[0]) - ex * sx, float(frame[1]) - ey * sy)
    else:
        turb_scale, turb_shift = (1.0, 1.0), (0.0, 0.0)
    r.android_extras(turb_scale=turb_scale, turb_shift=turb_shift,
                     turb_offset=tuple(float(v) for v in model.turbulence_offset),
                     content=content if content['panelWeight'] > 0 else None, base_count=model.count,
                     total_seconds=total, clamp_alpha=not additive, wind=(float(model.wind_x), float(model.wind_y)),
                     size_scale=float(model.size_scale),
                     touch_gap=None if model.touch_gap is None else float(model.touch_gap))
    r.android = dict(model=model, content=content, additive=additive, lightness=l_star, direction=direction)
    return r


def _live_base():
    import harmony_native as hn
    return hn.NativeLive


class AndroidDriftLive(_live_base()):
    """「Android 移植口径」：输入由 Android 的规则生成（与设备逐位相同），渲染是逐帧推进的源码复刻。
    播放时长按 Android：设备时刻 startSeconds → totalSeconds 压进 1 秒；弹窗暗层按 Android 的曲线淡出。"""

    def __init__(self, scene, inputs, raster, ctx=None, ss=2, dim_alpha=0.0):
        super().__init__(scene, inputs, raster=raster, ctx=ctx, ss=ss, dim_alpha=dim_alpha)
        self.playback_duration = DURATION
        self.android = {}

    def render(self, progress):
        progress = float(np.clip(progress, 0.0, 1.0))
        self.last_progress = progress
        self.comp_prog['dim'] = dim_alpha(progress, self.dim_alpha)
        return self.render_time(self.device_time(progress), q=progress)

    def describe(self):
        result = super().describe()
        a = self.android
        m, c = a['model'], a['content']
        style = m.style_name or '随机格点（格点值、风力与湍流偏移按种子）'
        blend = '加光' if a['additive'] else '标准 src-over'
        result.update(wind=(float(m.wind_x), -float(m.wind_y)), origins=[],
                      source=('Android 移植口径：%s；%s（背景 L* %.0f）；%d 颗粒%s；设备时刻 %.3f → %.3f 秒压进 1 秒播放'
                              % (style, blend, a['lightness'], m.count,
                                 ' + 内容强调 %d 颗（本体色置信度 %.2f）' % (c['replicas'], c['panelWeight'])
                                 if c['panelWeight'] > 0 else '', m.start_seconds, m.total_seconds)),
                      android=dict(style=m.style_name, additive=a['additive'], lightness=round(a['lightness'], 1),
                                   count=m.count, replicas=c['replicas'], panel_weight=round(c['panelWeight'], 3)))
        return result
