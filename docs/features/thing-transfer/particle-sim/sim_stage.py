"""接收舞台（`TapReceiveStageView`）的逐行移植：罩层、屏幕边框粒子、边框底下那圈光、空洞。

边框粒子系统来自 `taptransfer/ui/particles`：
- `JavaRandom`：`java.util.Random` 的 48 位线性同余，与 Kotlin 端逐位相同的随机序列；
- `FlowNoise`：带解析导数的二维梯度噪声与旋度场（排列表按同一个 64 位线性同余打乱）；
- `RoundRectPerimeter`：圆角矩形按弧长取点与切线；
- `PerimeterParticles`：沿周长流动的一圈粒子（车道、速度起伏、横向摆动、拖尾、按角度取调色板）。

舞台按设备上的做法逐帧推进（`onDraw` 里的 approach 与粒子更新），帧间隔取模拟的屏幕刷新率。
整段时间线一次算好存起来，拖动时间轴时直接取帧。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

MASK48 = (1 << 48) - 1
MASK64 = (1 << 64) - 1


class JavaRandom:
    """`java.util.Random`。"""

    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & MASK48

    def next_bits(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & MASK48
        return self.seed >> (48 - bits)

    def next_float(self) -> float:
        return self.next_bits(24) / float(1 << 24)


class FlowNoise:
    """`FlowNoise`：16 个梯度方向的二维梯度噪声，五次平滑，带解析导数。"""
    GRADIENTS = 16

    def __init__(self, seed: int = 1):
        p = list(range(256))
        s = (seed * 6364136223846793005 + 1442695040888963407) & MASK64
        for i in range(255, 0, -1):
            s = (s * 6364136223846793005 + 1442695040888963407) & MASK64
            j = (s >> 33) % (i + 1)
            p[i], p[j] = p[j], p[i]
        self.perm = np.array([p[i & 255] for i in range(512)], dtype=np.int64)
        a = (np.arange(self.GRADIENTS) + 0.5) * 2.0 * math.pi / self.GRADIENTS
        self.gx = np.cos(a).astype(np.float32)
        self.gy = np.sin(a).astype(np.float32)

    @staticmethod
    def _fade(t):
        return t * t * t * (t * (t * 6 - 15) + 10)

    @staticmethod
    def _slope(t):
        return 30 * t * t * (t * (t - 2) + 1)

    def sample(self, x, y):
        """返回 (值, ∂/∂x, ∂/∂y)，逐元素。"""
        x = np.asarray(x, dtype=np.float32)
        y = np.asarray(y, dtype=np.float32)
        fx0 = np.floor(x)
        fy0 = np.floor(y)
        ix = fx0.astype(np.int64) & 255
        iy = fy0.astype(np.int64) & 255
        fx = x - fx0
        fy = y - fy0
        perm, G = self.perm, self.GRADIENTS
        g00 = perm[perm[ix] + iy] % G
        g10 = perm[perm[ix + 1] + iy] % G
        g01 = perm[perm[ix] + iy + 1] % G
        g11 = perm[perm[ix + 1] + iy + 1] % G
        gx, gy = self.gx, self.gy
        a = gx[g00] * fx + gy[g00] * fy
        b = gx[g10] * (fx - 1) + gy[g10] * fy
        c = gx[g01] * fx + gy[g01] * (fy - 1)
        d = gx[g11] * (fx - 1) + gy[g11] * (fy - 1)
        u, v = self._fade(fx), self._fade(fy)
        du, dv = self._slope(fx), self._slope(fy)
        k = a - b - c + d
        value = a + u * (b - a) + v * (c - a) + u * v * k
        kx = gx[g00] - gx[g10] - gx[g01] + gx[g11]
        ky = gy[g00] - gy[g10] - gy[g01] + gy[g11]
        dx = gx[g00] + u * (gx[g10] - gx[g00]) + v * (gx[g01] - gx[g00]) + u * v * kx + du * ((b - a) + v * k)
        dy = gy[g00] + u * (gy[g10] - gy[g00]) + v * (gy[g01] - gy[g00]) + u * v * ky + dv * ((c - a) + u * k)
        return value, dx, dy

    def curl(self, x, y, time: float):
        _, dx1, dy1 = self.sample(x + time * 0.31, y - time * 0.23)
        _, dx2, dy2 = self.sample(x * 2.03 - time * 0.19 + 17.1, y * 2.03 + time * 0.27 + 3.7)
        dx = dx1 + dx2 * 0.5 * 2.03
        dy = dy1 + dy2 * 0.5 * 2.03
        return dy, -dx


class RoundRectPerimeter:
    """弧长从上边左端开始、顺时针；向内的法线是切线顺时针转 90°：(−ty, tx)。"""

    def set(self, left, top, right, bottom, radius):
        self.left, self.top, self.right, self.bottom = left, top, right, bottom
        r = min(max(radius, 0.0), min(right - left, bottom - top) / 2)
        self.radius = r
        self.sw = max(right - left - 2 * r, 0.0)
        self.sh = max(bottom - top - 2 * r, 0.0)
        self.arc = math.pi / 2 * r
        self.length = 2 * self.sw + 2 * self.sh + 4 * self.arc

    def point_at(self, s):
        """逐元素：返回 (x, y, tx, ty)。"""
        s = np.asarray(s, dtype=np.float64)
        L = self.length
        d = np.fmod(s, L)
        d = np.where(d < 0, d + L, d)
        r, sw, sh, arc = self.radius, self.sw, self.sh, self.arc
        x = np.empty_like(d); y = np.empty_like(d); tx = np.empty_like(d); ty = np.empty_like(d)
        bounds = np.cumsum([0, sw, arc, sh, arc, sw, arc, sh, arc])
        seg = np.clip(np.searchsorted(bounds, d, side='right') - 1, 0, 7)
        local = d - bounds[seg]
        l, t, rr, b = self.left, self.top, self.right, self.bottom

        def line(mask, px, py, dx, dy):
            x[mask] = px[mask] if isinstance(px, np.ndarray) else px
            y[mask] = py[mask] if isinstance(py, np.ndarray) else py
            tx[mask] = dx
            ty[mask] = dy

        def corner(mask, cx, cy, start):
            if r <= 0:
                x[mask] = cx; y[mask] = cy
                tx[mask] = math.cos(start + math.pi / 2); ty[mask] = math.sin(start + math.pi / 2)
                return
            a = start + local[mask] / r
            ca, sa = np.cos(a), np.sin(a)
            x[mask] = cx + ca * r; y[mask] = cy + sa * r
            tx[mask] = -sa; ty[mask] = ca

        line(seg == 0, l + r + local, t, 1.0, 0.0)
        corner(seg == 1, rr - r, t + r, -math.pi / 2)
        line(seg == 2, rr, t + r + local, 0.0, 1.0)
        corner(seg == 3, rr - r, b - r, 0.0)
        line(seg == 4, rr - r - local, b, -1.0, 0.0)
        corner(seg == 5, l + r, b - r, math.pi / 2)
        line(seg == 6, l, b - r - local, 0.0, -1.0)
        m7 = seg == 7
        local[m7] = np.minimum(local[m7], arc)
        corner(m7, l + r, t + r, math.pi)
        return x, y, tx, ty


def lerp_color(a: int, b: int, t: float) -> int:
    """`PerimeterParticles.lerpColor`：ARGB 逐通道插值，四舍五入。"""
    u = min(max(t, 0.0), 1.0)
    out = 0
    for shift in (24, 16, 8, 0):
        ca = (a >> shift) & 0xFF
        cb = (b >> shift) & 0xFF
        out |= (int(ca + (cb - ca) * u + 0.5) & 0xFF) << shift
    return out


def palette_colors(palette: list[int], positions: np.ndarray) -> np.ndarray:
    """`PerimeterParticles.paletteColor` 逐元素：返回 (n, 3) 的 RGB（0–255 浮点，已按设备的整数取整）。"""
    colors = np.array([[(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF] for c in palette], dtype=np.float64)
    n = len(palette)
    if n == 1:
        return np.repeat(colors, len(positions), axis=0)
    p = np.fmod(positions, 1.0)
    p = np.where(p < 0, p + 1, p)
    f = p * n
    k = np.clip(f.astype(np.int64), 0, n - 1)
    t = np.clip(f - k, 0, 1)[:, None]
    a, b = colors[k], colors[(k + 1) % n]
    return np.floor(a + (b - a) * t + 0.5)


class PerimeterParticles:
    """`PerimeterParticles`，按列向量化；随机数的抽取顺序与 Kotlin 端一致。"""

    def __init__(self, capacity: int, noise: FlowNoise):
        self.capacity = capacity
        self.noise = noise
        self.shape = RoundRectPerimeter()
        self.palette = [0xFF4FC3F7]
        self.palette_cycle_s = 24.0
        self.alpha = 1.0
        self.center = None
        self.count = 0
        self.clear()

    def clear(self):
        self.count = 0
        self.arc = np.zeros(0); self.speed = np.zeros(0); self.lane = np.zeros(0); self.radius = np.zeros(0)
        self.twinkle = np.zeros(0); self.base_alpha = np.zeros(0); self.born = np.zeros(0)
        self.prev = np.zeros((0, 2)); self.cur = np.zeros((0, 2)); self.placed = np.zeros(0, dtype=bool)

    def populate(self, n, lane_width, speed_px, min_radius, max_radius, random: JavaRandom, now_s):
        total = min(n, self.capacity)
        L = self.shape.length
        arc, lane, speed, radius, opacity, twinkle = [], [], [], [], [], []
        for k in range(total):
            arc.append(L * (k + random.next_float()) / total)
            r = random.next_float(); lane.append(lane_width * r * r)
            speed.append(speed_px * (0.7 + 0.6 * random.next_float()))
            r = random.next_float(); radius.append(min_radius + (max_radius - min_radius) * r * r)
            opacity.append(0.45 + 0.55 * random.next_float())
            twinkle.append(random.next_float() * 6.2832)
        self.arc = np.array(arc, dtype=np.float64)
        self.lane = np.array(lane, dtype=np.float64)
        self.speed = np.array(speed, dtype=np.float64)
        self.radius = np.array(radius, dtype=np.float64)
        self.base_alpha = np.array(opacity, dtype=np.float64)
        self.twinkle = np.array(twinkle, dtype=np.float64)
        self.born = np.full(total, float(now_s))
        self.prev = np.zeros((total, 2)); self.cur = np.zeros((total, 2))
        self.placed = np.zeros(total, dtype=bool)
        self.count = total

    def update(self, dt, now_s, wobble_px, noise_scale):
        L = self.shape.length
        if L <= 0 or self.count == 0:
            return
        cx, cy = self.noise.curl(self.arc * noise_scale, self.lane * 0.05, now_s * 0.35)
        surge = 1.0 + 0.35 * cx.astype(np.float64)
        self.arc = np.fmod(self.arc + self.speed * surge * dt, L)
        self.arc = np.where(self.arc < 0, self.arc + L, self.arc)
        px, py, tx, ty = self.shape.point_at(self.arc)
        nx, ny = -ty, tx
        offset = self.lane + cy.astype(np.float64) * wobble_px
        x = px + nx * offset
        y = py + ny * offset
        new = np.stack([x, y], axis=1)
        self.prev = np.where(self.placed[:, None], self.cur, new)
        self.placed[:] = True
        self.cur = new

    def draw(self, now_s, trail_scale, fade_in_s):
        """返回 (head (n,2), tail (n,2), radius (n,), rgba (n,4) 0–1，alpha 已乘好)。"""
        if self.alpha <= 0.001 or self.count == 0:
            return None
        m = self.placed
        flicker = 0.78 + 0.22 * np.sin(now_s * 2.4 + self.twinkle)
        born = np.clip((now_s - self.born) / fade_in_s, 0, 1)
        a = np.clip(self.alpha * self.base_alpha * flicker * born, 0, 1)
        # scaleAlpha：整数的 alpha 通道
        a = np.floor(255 * a + 0.5) / 255
        head = self.cur
        tail = head + (self.prev - head) * trail_scale
        if self.center is not None:
            ang = np.arctan2(self.cur[:, 1] - self.center[1], self.cur[:, 0] - self.center[0])
            turn = ang / (2 * math.pi)
            turn = np.where(turn < 0, turn + 1, turn)
            rgb = palette_colors(self.palette, turn - now_s / self.palette_cycle_s)
        else:
            rgb = palette_colors(self.palette, self.arc / self.shape.length + now_s / self.palette_cycle_s)
        rgba = np.concatenate([rgb / 255.0, a[:, None]], axis=1)
        return head[m], tail[m], self.radius[m], rgba[m]


# ---------------------------------------------------------------- 舞台

@dataclass
class StageFrame:
    """一帧舞台的绘制状态。"""
    dim_alpha: float
    border_alpha: float
    hole_open: float
    holes: list
    palette: list
    now: float
    particles: tuple | None
    trail_scale: float


@dataclass
class StageConfig:
    width: float
    height: float
    density: float
    corner: float
    dim_color: int
    dim_alpha: float
    seed: int = 20260925
    capacity: int = 2000


class ReceiveStage:
    """`TapReceiveStageView` 的状态机，逐帧推进（`onDraw` 的顺序），参数取自 particles.properties。"""

    def __init__(self, config: StageConfig, params):
        self.c = config
        self.p = params
        self.random = JavaRandom(config.seed)
        self.border = PerimeterParticles(config.capacity, FlowNoise(config.seed))
        self.border.palette = list(params.palette('receive_border_palette'))
        self.border.palette_cycle_s = params['receive_border_palette_cycle_s']
        self.phase = 'IDLE'
        self.border_alpha = 0.0
        self.border_target = 0.0
        self.border_fade_s = params['receive_border_fade_s']
        self.dim_alpha = 0.0
        self.dim_target = 0.0
        self.palette_from = list(self.border.palette)
        self.palette_to = list(self.border.palette)
        self.palette_t = 1.0
        self.holes: list = []
        self.hole_radius = 0.0
        self.hole_open = 0.0
        self.hole_target = 0.0
        self.now = 0.0

    # ---- 动作（与 Kotlin 同名方法对应）
    def _inset_border(self):
        d = self.c.density
        inset = self.p['receive_border_inset_dp'] * d
        self.border.shape.set(inset, inset, self.c.width - inset, self.c.height - inset,
                              max(self.c.corner - inset, 8 * d))

    def _populate(self):
        d = self.c.density
        self._inset_border()
        self.border.center = (self.c.width / 2, self.c.height / 2)
        n = int(self.border.shape.length / (self.p['receive_border_spacing_dp'] * d))
        n = min(max(n, 200), self.c.capacity)
        rmin, rmax = self.p['receive_border_radius_dp']
        self.border.populate(n, self.p['receive_border_lane_dp'] * d, self.p['receive_border_speed_dp'] * d,
                             rmin * d, rmax * d, self.random, self.now)

    def enter(self):
        self._populate()
        self.border_target = 1.0
        self.border_fade_s = self.p['receive_border_fade_s']
        self.dim_target = self.c.dim_alpha
        self.phase = 'WAITING'

    def touched(self, colors: list[int]):
        self.clear_highlights()
        usable = [c for c in colors if c != 0]
        if usable:
            size = len(self.p.palette('receive_border_palette'))
            opaque = [c | 0xFF000000 for c in usable]
            self._fade_palette_to([opaque[k % len(opaque)] for k in range(size)])
        self.border_target = 1.0
        self.border_fade_s = self.p['receive_border_fade_s']
        self.phase = 'BUSY'

    def converge(self):
        self.border_target = 0.0
        self.border_fade_s = self.p['receive_border_converge_s']

    def resume(self):
        if self.phase != 'BUSY':
            return
        if self.border_alpha <= 0.01:
            self._populate()
        self._fade_palette_to(list(self.p.palette('receive_border_palette')))
        self.border_target = 1.0
        self.border_fade_s = self.p['receive_border_fade_s']
        self.phase = 'WAITING'

    def highlight(self, rects, radius):
        self.holes = [tuple(r) for r in rects]
        self.hole_radius = radius
        self.hole_open = 1.0
        self.hole_target = 1.0

    def clear_highlights(self):
        self.hole_target = 0.0

    def exit(self):
        self.border_target = 0.0
        self.border_fade_s = self.p['receive_border_fade_s']
        self.dim_target = 0.0
        self.phase = 'EXITING'

    def _fade_palette_to(self, target):
        self.palette_from = self._current_palette()
        self.palette_to = list(target)
        self.palette_t = 0.0

    def _current_palette(self):
        if self.palette_t >= 1:
            return list(self.palette_to)
        return [lerp_color(a, b, self.palette_t) for a, b in zip(self.palette_from, self.palette_to)]

    @staticmethod
    def _approach(value, target, step):
        return min(target, value + step) if value < target else max(target, value - step)

    # ---- 一帧
    def frame(self, dt: float) -> StageFrame | None:
        """`onDraw`：推进一帧并返回绘制状态；IDLE 时返回 None。"""
        if self.phase == 'IDLE':
            return None
        self.now += dt
        self.border_alpha = self._approach(self.border_alpha, self.border_target, dt / self.border_fade_s)
        self.dim_alpha = self._approach(self.dim_alpha, self.dim_target, dt / self.p['receive_dim_fade_s'])
        self.hole_open = self._approach(self.hole_open, self.hole_target, dt / self.p['receive_hole_close_s'])
        if self.palette_t < 1:
            self.palette_t = min(1.0, self.palette_t + dt / self.p['receive_border_palette_fade_s'])
            self.border.palette = self._current_palette()
        if self.phase == 'EXITING' and self.border_alpha <= 0.01 and self.dim_alpha <= 0.005:
            self.border.clear()
            self.phase = 'IDLE'
            return None
        particles = None
        trail = min(max(self.p['receive_border_trail_s'] / dt, 1.0), 6.0) if dt > 1e-4 else 1.0
        if self.border_alpha > 0.01 and self.border.count > 0:
            self.border.alpha = self.border_alpha
            d = self.c.density
            self.border.update(dt, self.now, self.p['receive_border_wobble_dp'] * d, 1.0 / (140.0 * d))
            particles = self.border.draw(self.now, trail, self.p['receive_border_particle_fade_s'])
        holes = list(self.holes) if (self.holes and self.hole_open > 0.001) else []
        if self.hole_target <= 0 and self.hole_open <= 0.001:
            self.holes = []
        return StageFrame(self.dim_alpha, self.border_alpha, self.hole_open,
                          holes, list(self.border.palette), self.now, particles, trail)
