"""接收：拼卡那一层与进度期间那一层、屏幕四周的光尘之间的交接（2026-09-25 第三版第六轮）。

拼卡仍是弹窗出现那套「消散倒放」（先按快照正向算一遍卡片 → 终点，倒着播）。

- 与进度期间那一层（代理卡片的倒放）之间不逐片配对，两层共用倒放时间轴、交叉淡化：逐片配对（按希尔伯特
  曲线排名成比例对应）会把卡片上连成一片的区域拆成几块、分别飞向云团里相隔很远的位置，半路成一块块碎片；
- 流向四边的那一份里剥离得早的颗粒，由扩散进来的光尘就近接手：在流入开始之后那一小段里，从那片光尘此刻
  的位置出现（那片光尘同时不再画），颜色从光尘的颜色过渡到卡片上的颜色。就近配对是局部的，不会拆散。
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from scipy.spatial import cKDTree

from sim_model import field_targets


def classify(build) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """逐片：(所属卡片, 是否流向四边, 是否由冒出的颗粒接手)。所属卡片按出生位置落在哪张卡片的矩形里，
    都不在（卡片之间的缝）就归最近的那张。一张卡片时全部是 0。"""
    values = build.base
    n = len(values)
    _, frame_bound, absorbed = field_targets(values, build.sink, build.card)
    cloud = (~frame_bound) & absorbed
    rects = [rect for rect, _ in build.sink.groups]
    group = np.zeros(n, dtype=np.int64)
    if len(rects) > 1:
        x, y = values[:, 0].astype(np.float64), values[:, 1].astype(np.float64)
        best = np.full(n, np.inf)
        for g, (l, t, r, b) in enumerate(rects):
            dx = np.maximum(np.maximum(l - x, x - r), 0)
            dy = np.maximum(np.maximum(t - y, y - b), 0)
            dist = dx * dx + dy * dy
            closer = dist < best
            group[closer] = g
            best[closer] = dist[closer]
    return group, frame_bound, cloud


def to_material(build, points: np.ndarray) -> np.ndarray:
    ox, oy = build.offset
    return np.stack([points[:, 0] / build.scale - ox, points[:, 1] / build.scale - oy], axis=1)


def to_screen(build, points: np.ndarray) -> np.ndarray:
    ox, oy = build.offset
    return np.stack([(points[:, 0] + ox) * build.scale, (points[:, 1] + oy) * build.scale], axis=1)


@dataclass
class Handoff:
    target: np.ndarray        # (n, 4) float32：材料坐标 xy、种类（0 照旧、2 光尘接手）、0
    origin: np.ndarray        # (n, 4) float32：接手那一片光尘的颜色（sRGB 0–1）与方式（1 = 原位接手）
    dust_until: np.ndarray    # 光尘逐片交出去的时刻（会话时间；没交出的是 inf）
    dust_keep: np.ndarray     # 光尘里被接手的那几片（它们不向外退）
    fade_in: np.ndarray       # (n, 2) float32：拼卡那一层逐片的淡入（HAND_UNTIL，见 material.vert）
    stats: dict = field(default_factory=dict)


def compute(build, plan, dust, clock, palette_at, turn_at, density: float, params, seed: int = 7) -> Handoff:
    """[clock](t) 是光尘的舞台时钟，[palette_at](t) / [turn_at](t) 是那一刻光尘的调色板与转过的圈数。

    - 拼卡那一层与进度期间那一层共用倒放时间轴，流入开始时交叉淡化：这里给出它逐片的淡入；
    - 屏幕四周扩散进来的光尘就近接手流向四边的那一份：这些颗粒改成在流入开始之后才出现（正向寿命的终点
      挪到交接之后那一小段里），出现的位置就是那片光尘此刻的位置，那片光尘同时不再画。只挑正向剥离得早的
      颗粒（飞行时间够长），否则它们要在一瞬间飞过半个屏幕。"""
    rng = np.random.default_rng(seed)
    base = build.base
    n = len(base)
    target = np.zeros((n, 4), 'float32')
    origin = np.zeros((n, 4), 'float32')
    _, frame_bound, _ = classify(build)
    t_in = plan.t_inflow
    tau_h = plan.reverse_tau(t_in)
    stats = {'tau_handoff': round(float(tau_h), 3)}

    # ---- 拼卡那一层的淡入：流入开始之后 [0, fade] 秒里
    fade = params['receive_crossfade'][0]
    x = plan.forward_time(t_in + fade)
    fade_in = np.tile(np.array([x, -max(plan.forward_time(t_in) - x, 1e-4)], 'float32'), (n, 1))

    # ---- 光尘接手：流向四边、剥离得早的那几片
    lo, hi, min_flight = params['receive_dust_join']
    d_hi, d_lo = 1 - tau_h - lo, 1 - tau_h - hi
    born = base[:, 2].astype(np.float64)
    # 只挑看得见的：出生在快照透明处（几张卡片之间的空隙）的颗粒画不出来，交给它的那片光尘会白白消失
    shown = build.visible if getattr(build, 'visible', None) is not None else np.ones(n, dtype=bool)
    eligible = np.flatnonzero(frame_bound & shown & (born <= d_lo - min_flight))
    dust_until = np.full(dust.n, np.inf)
    keep = np.zeros(dust.n, dtype=bool)
    pairs = []
    if len(eligible) and dust.n:
        all_idx = np.arange(dust.n)
        dx, dy, q, edge = dust.evaluate(all_idx, clock(t_in), plan.spread(t_in), plan.diffuse_time(t_in))
        weight = dust.base_alpha * dust.inner_fade(q) * edge
        # 扩散出去的那一部分：离开边缘最外那一层、且还看得见的
        candidates = np.flatnonzero((q ** 1.6 > .12) & (weight > .25))
        rng.shuffle(candidates)
        default, _, _ = field_targets(base, build.sink, build.card)
        tree = cKDTree(to_screen(build, default[eligible].astype(np.float64)))
        used = np.zeros(len(eligible), dtype=bool)
        if len(candidates):
            dist, near = tree.query(np.stack([dx[candidates], dy[candidates]], axis=1), k=min(24, len(eligible)),
                                    distance_upper_bound=48 * density)
            if near.ndim == 1:
                near, dist = near[:, None], dist[:, None]
            for row, j in enumerate(candidates):
                for col in range(near.shape[1]):
                    m = near[row, col]
                    if m >= len(eligible) or not np.isfinite(dist[row, col]):
                        break
                    if not used[m]:
                        used[m] = True
                        pairs.append((eligible[m], j))
                        break
        stats['dust_candidates'] = int(len(candidates))
    stats['eligible'] = int(len(eligible))
    stats['dust'] = len(pairs)
    if pairs:
        f_dust = np.array([a for a, _ in pairs])
        d_dust = np.array([b for _, b in pairs])
        death = d_lo + (d_hi - d_lo) * rng.random(len(f_dust))
        base[f_dust, 6] = (death - born[f_dust]).astype('float32')
        t_h = plan.time_of_tau(1 - death)
        px, py, _, _ = dust.evaluate(d_dust, clock(t_h), np.array([plan.spread(t) for t in t_h]),
                                     np.array([plan.diffuse_time(t) for t in t_h]))
        target[f_dust, :2] = to_material(build, np.stack([px, py], axis=1))
        target[f_dust, 2] = 2
        origin[f_dust, :3] = dust.colors(d_dust, px, py, palette_at(float(np.median(t_h))), turn_at(t_h))
        origin[f_dust, 3] = 1
        dust_until[d_dust] = t_h
        keep[d_dust] = True
        # 光尘接手的那几片以满不透明度出现，不参与淡入
        fade_in[f_dust] = (-1., 0.)
        stats['dust_after'] = (round(float(t_h.min() - t_in), 3), round(float(t_h.max() - t_in), 3))
    return Handoff(target, origin, dust_until, keep, fade_in, stats)
