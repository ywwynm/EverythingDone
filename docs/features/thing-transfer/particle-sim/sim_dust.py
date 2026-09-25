"""接收状态的光尘带：屏幕四周一圈细小的微片，与弹窗消散同一种颗粒（约 1 dp 的圆角方片、会翻转闪烁、
没有拖尾），沿边缘缓慢流动。

传输开始后，每一片按自己的节奏持续从边缘向内扩散：越往里越淡，扩散到最深处后重新从边缘出现，
所以屏幕边缘始终有颗粒，向内的部分是一层逐渐淡去的尘，不形成一道「内边框」。扩散的深度随进度
适度增加，进度 100% 之后列表让位的那段时间里继续缓慢增加，直到颗粒流进卡片。

状态是时间的闭式函数（位置 = 初始位置 + 速度 × 时间 + 噪声在当时的取值），不逐帧积分：可以任意跳到
某一时刻，与刷新率无关。Android 端照同一套公式在 onDraw 里按时间求值即可。

坐标：舞台像素（与 TapReceiveStageView 相同，左上角为原点）。
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from sim_stage import FlowNoise

TAU = 2 * math.pi
# 越往里角越圆：圆角半径每往里 1 像素增加这么多（光尘与柔光共用）
ROUNDING = 0.6


def palette_pick(palette: list[int], positions: np.ndarray, pick: np.ndarray) -> np.ndarray:
    """按方位取调色板，但每片只取相邻两色之一（按到两色的远近随机挑），不做插值：相隔很远的两种颜色
    （比如碰上后的青与橙）之间不会混出灰褐色，交界处是两色颗粒交错。返回 (n, 3)，0–255。"""
    colors = np.array([[(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF] for c in palette], dtype=np.float64)
    n = len(palette)
    if n == 1:
        return np.repeat(colors, len(positions), axis=0)
    f = np.mod(positions, 1.0) * n
    k = np.clip(np.floor(f).astype(np.int64), 0, n - 1)
    choose = np.where(pick < f - k, (k + 1) % n, k)
    return colors[choose]


def inset_round_rect(frac: np.ndarray, width: float, height: float, inset: np.ndarray, corner: float,
                     rounding: float = 0.0):
    """逐元素：宽 [width]、高 [height] 的舞台内缩 [inset] 的圆角矩形上，按周长比例 [frac]（从上边左端起、
    顺时针）取点，返回 (x, y, 内法线 x, 内法线 y)。圆角半径 = max(corner − inset, 0) + rounding × inset：
    rounding = 0 是屏幕圆角的等距内缩；大于 0 时越往里角越圆。按周长比例取点，任意深度都不会在角上交叉。"""
    f = np.mod(frac, 1.0)
    l = inset
    t = inset
    r_ = width - inset
    b = height - inset
    rad = np.clip(corner - inset, 0.0, None) + rounding * inset
    rad = np.minimum(rad, np.minimum(r_ - l, b - t) / 2)
    sw = np.maximum(r_ - l - 2 * rad, 0.0)
    sh = np.maximum(b - t - 2 * rad, 0.0)
    arc = np.pi / 2 * rad
    total = 2 * sw + 2 * sh + 4 * arc
    d = f * total
    bounds = [np.zeros_like(d)]
    for piece in (sw, arc, sh, arc, sw, arc, sh, arc):
        bounds.append(bounds[-1] + piece)
    x = np.empty_like(d)
    y = np.empty_like(d)
    nx = np.empty_like(d)
    ny = np.empty_like(d)
    done = np.zeros(d.shape, dtype=bool)
    for k in range(8):
        m = (~done) & (d <= bounds[k + 1] + 1e-9)
        if k == 7:
            m = ~done
        local = d - bounds[k]
        if k == 0:     # 上边，向右
            x[m] = (l + rad + local)[m]; y[m] = t[m]; nx[m] = 0; ny[m] = 1
        elif k == 2:   # 右边，向下
            x[m] = r_[m]; y[m] = (t + rad + local)[m]; nx[m] = -1; ny[m] = 0
        elif k == 4:   # 下边，向左
            x[m] = (r_ - rad - local)[m]; y[m] = b[m]; nx[m] = 0; ny[m] = -1
        elif k == 6:   # 左边，向上
            x[m] = l[m]; y[m] = (b - rad - local)[m]; nx[m] = 1; ny[m] = 0
        else:
            cx = {1: r_ - rad, 3: r_ - rad, 5: l + rad, 7: l + rad}[k]
            cy = {1: t + rad, 3: b - rad, 5: b - rad, 7: t + rad}[k]
            start = {1: -np.pi / 2, 3: 0.0, 5: np.pi / 2, 7: np.pi}[k]
            a = start + np.where(rad > 0, local / np.maximum(rad, 1e-9), 0.0)
            ca, sa = np.cos(a), np.sin(a)
            x[m] = (cx + ca * rad)[m]; y[m] = (cy + sa * rad)[m]
            nx[m] = -ca[m]; ny[m] = -sa[m]
        done |= m
    return x, y, nx, ny


@dataclass
class DustFrame:
    """一帧光尘：逐片的屏幕位置、尺寸、转角、翻转压缩、颜色（0–1）与不透明度、闪光。"""
    x: np.ndarray
    y: np.ndarray
    size: np.ndarray
    angle: np.ndarray
    squash: np.ndarray
    rgb: np.ndarray
    alpha: np.ndarray
    glint: np.ndarray


class DustBand:
    """一圈光尘。参数取自 particles.properties 的 receive_dust_*。"""

    def __init__(self, width: float, height: float, density: float, corner: float, params, seed: int = 20260925):
        self.w, self.h, self.d, self.corner = float(width), float(height), float(density), float(corner)
        self.p = params
        rng = np.random.default_rng(seed)
        perimeter_dp = 2 * (width + height) / density
        n = int(perimeter_dp * params['receive_dust_per_dp'])
        self.n = n
        # 周长位置、跨带位置的初相（0 = 边缘；等待时就停在这里，扩散时从这里开始向内走）
        self.frac0 = rng.random(n)
        self.depth0 = rng.random(n)
        self.speed = 0.55 + 0.9 * rng.random(n)
        lo, hi = params['receive_dust_inward_hz']
        self.inward = lo + (hi - lo) * rng.random(n)
        lo, hi = params['receive_dust_size_dp']
        # 细粒为主，少量稍大的片（与弹窗微片的对数正态尺度相近）
        self.size = (lo + (hi - lo) * rng.random(n) ** 2.2) * density
        self.angle0 = rng.random(n) * TAU
        self.spin = (rng.random(n) - 0.5) * 2.4
        f_lo, f_hi = params['receive_dust_flip_hz']
        self.flip_rate = (f_lo + (f_hi - f_lo) * rng.random(n)) * TAU
        self.flip0 = rng.random(n) * TAU
        self.base_alpha = 0.55 + 0.45 * rng.random(n)
        self.rank = rng.random(n)          # 扩散时按它先后出场，密度随进度增加
        self.wander_seed = rng.random((n, 2)) * 64.0
        self.shade = 0.85 + 0.3 * rng.random(n)
        self.pick = rng.random(n)
        self.noise = FlowNoise(seed & 0x7FFFFFFF)

    def reach(self, spread: float) -> tuple[float, float]:
        """(带外沿离屏幕边缘的像素, 颗粒最深扩散到的像素)：等待时是带宽，扩散时加上 spread × 扩散距离。"""
        p = self.p
        inset = p['receive_border_inset_dp'] * self.d
        reach = (p['receive_dust_band_dp'] + p['receive_dust_spread_dp'] * spread) * self.d
        return inset, reach

    def evaluate(self, idx, t, spread, diffuse_t, pull=0.0, retreat=0.0):
        """逐片求值：[idx] 这几片在各自的 [t]（舞台时钟）、扩散程度 [spread]、扩散时间 [diffuse_t] 下的位置。
        各参数可以是标量，也可以是与 idx 等长的数组（交接时每一片取各自的交接时刻）。[retreat] 是整体向外
        退去的像素（交接之后没被接手的光尘）。返回 (x, y, 跨带相位 q, 出场比例)。"""
        p, d = self.p, self.d
        idx = np.asarray(idx)
        t = np.asarray(t, dtype=np.float64)
        spread = np.asarray(spread, dtype=np.float64)
        inset = p['receive_border_inset_dp'] * d
        reach = (p['receive_dust_band_dp'] + p['receive_dust_spread_dp'] * spread) * d
        # 扩散越深，出场的颗粒越多：等待时 rest 比例，扩散程度到 1 时全部
        present = p['receive_dust_rest'] + (1 - p['receive_dust_rest']) * np.minimum(spread, 1.0)
        edge_fade = np.clip((present - self.rank[idx]) / 0.08, 0, 1)
        # 跨带位置：等待时停在初相，扩散后按各自的频率向内走一轮再回到边缘（0 → 1 循环）
        q = np.mod(self.depth0[idx] + self.inward[idx] * np.maximum(diffuse_t, 0.0), 1.0)
        speed = p['receive_dust_speed_dp'] * d
        perimeter = 2 * (self.w + self.h - 4 * inset)
        frac = self.frac0[idx] + self.speed[idx] * speed * t / max(perimeter, 1.0)
        # 噪声漂移：沿边与跨带两个方向，低频、随时间缓慢变化
        wander = p['receive_dust_wander_dp'] * d
        scale = 1.0 / (90.0 * d)
        u = frac * perimeter * scale + self.wander_seed[idx, 0]
        v = q * 3.0 + self.wander_seed[idx, 1]
        cx, cy = self.noise.curl(u, v, t * 0.45)
        # 边缘密、往里疏（q^1.6）
        depth = inset + reach * q ** 1.6 + cy.astype(np.float64) * wander + pull * (0.75 + 0.5 * q) - retreat
        frac = frac + cx.astype(np.float64) * wander * 0.6 / max(perimeter, 1.0)
        depth = np.broadcast_to(depth, frac.shape)
        x, y, _, _ = inset_round_rect(frac, self.w, self.h, depth, self.corner, ROUNDING)
        return x, y, q, edge_fade

    def colors(self, idx, x, y, palette: list[int], palette_turn) -> np.ndarray:
        """按绕屏幕中心的方位取调色板（随时间转动），每片只取相邻两色之一；逐片略有明暗。0–1。"""
        turn = np.arctan2(y - self.h / 2, x - self.w / 2) / TAU
        rgb = palette_pick(palette, turn - palette_turn, self.pick[idx]) / 255.0
        return np.clip(rgb * self.shade[idx, None], 0, 1)

    @staticmethod
    def inner_fade(q):
        """越往里越淡：一轮扩散的后半段逐渐隐去，回到边缘时淡入，不在循环处闪动。"""
        return (1 - np.clip((q - 0.3) / 0.7, 0, 1) ** 1.3) * np.clip(q / 0.04, 0, 1) ** 0.5

    def at(self, t: float, spread: float, diffuse_t: float, alpha: float, palette: list[int], palette_turn: float,
           pull: float = 0.0, retreat: float = 0.0, retreat_alpha: float = 1.0, keep=None, hidden=None) \
            -> DustFrame | None:
        """[t] 秒（舞台时钟）时的一帧。[spread] 是扩散程度（0 = 等待时，进度 100% 时为 1，之后继续增加），
        [diffuse_t] 是开始扩散以来的秒数（各片向内走的相位由它推进），[alpha] 是整条带的不透明度（出现、
        退场），[palette_turn] 是调色板转过的圈数，[pull] 是整体额外向内移动的像素。

        交接（第三版第三轮）：[keep] 标出由拼卡粒子层接手的那几片，它们照常运动，[hidden] 为真之后不再画；
        其余的向外退去 [retreat] 像素，不透明度乘 [retreat_alpha]。"""
        if alpha <= 0.003 or self.n == 0:
            return None
        idx = np.arange(self.n)
        retreating = retreat if keep is None else np.where(keep, 0.0, retreat)
        x, y, q, edge_fade = self.evaluate(idx, t, spread, diffuse_t, pull, retreating)
        # 翻转：宽度按 |cos| 压缩（下限 0.35），正对时出现闪光（弹窗微片的 facing^12）
        flip = self.flip0 + self.flip_rate * t
        c = np.abs(np.cos(flip))
        squash = 0.35 + 0.65 * c
        glint = c ** 12
        angle = self.angle0 + self.spin * t
        rgb = self.colors(idx, x, y, palette, palette_turn)
        a = alpha * self.base_alpha * self.inner_fade(q) * edge_fade
        if keep is not None:
            a = np.where(keep, a, a * retreat_alpha)
        elif retreat_alpha < 1.0:
            a = a * retreat_alpha
        m = a > 0.004
        if hidden is not None:
            m &= ~hidden
        return DustFrame(x[m], y[m], self.size[m], angle[m], squash[m], rgb[m], a[m], glint[m])
