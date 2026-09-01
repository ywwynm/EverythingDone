# -*- coding: utf-8 -*-
"""运行时物理释放场；公式对应 ``ParticleDismissReleaseField.kt``。"""

from __future__ import annotations

import math

import numpy as np


COLUMNS = 49
ROWS = 33
MIN_DELAY = 0.018
MAX_DELAY = 0.585
TIMING_X = np.array((0.0, 0.221, 0.400, 0.443, 0.559, 0.776, 0.868, 1.0), dtype=np.float64)
TIMING_Y = np.array((0.0, 0.020, 0.050, 0.075, 0.280, 0.640, 0.840, 1.0), dtype=np.float64)
TIMING_SLOPE = np.array(
    (0.04789997, 0.11877366, 0.29327752, 0.81218801, 1.71688798, 1.91658062, 1.58318805, 0.64535103),
    dtype=np.float64,
)
MATERIAL_SALT_A = 0x51ED270B
MATERIAL_SALT_B = 0x68BC21EB


def _clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return min(maximum, max(minimum, value))


def _smoothstep(edge0: float, edge1: float, value: float) -> float:
    amount = _clamp((value - edge0) / max(edge1 - edge0, 1e-9))
    return amount * amount * (3.0 - 2.0 * amount)


def _smooth_min(first: float, second: float, radius: float) -> float:
    amount = _clamp(0.5 + 0.5 * (second - first) / max(radius, 1e-9))
    return second + (first - second) * amount - radius * amount * (1.0 - amount)


def _material_hash(x: int, y: int, seed: int, salt: int) -> float:
    value = (
        x * 374_761_393
        + y * 668_265_263
        + seed * 1_442_695_041
        + salt
    ) & 0xFFFF_FFFF
    value = ((value ^ (value >> 13)) * 1_274_126_177) & 0xFFFF_FFFF
    value ^= value >> 16
    return (value & 0xFFFF_FFFF) / 4_294_967_295.0


def _material_noise(x: float, y: float, seed: int, salt: int) -> float:
    x0 = math.floor(x)
    y0 = math.floor(y)
    tx = x - x0
    ty = y - y0
    blend_x = tx * tx * (3.0 - 2.0 * tx)
    blend_y = ty * ty * (3.0 - 2.0 * ty)
    top = (
        _material_hash(x0, y0, seed, salt) * (1.0 - blend_x)
        + _material_hash(x0 + 1, y0, seed, salt) * blend_x
    )
    bottom = (
        _material_hash(x0, y0 + 1, seed, salt) * (1.0 - blend_x)
        + _material_hash(x0 + 1, y0 + 1, seed, salt) * blend_x
    )
    return top * (1.0 - blend_y) + bottom * blend_y


def _timing_curve(values: np.ndarray) -> np.ndarray:
    values = np.clip(values.astype(np.float64), 0.0, 1.0)
    output = np.empty_like(values)
    for segment in range(len(TIMING_X) - 1):
        mask = (values >= TIMING_X[segment]) & (
            (values <= TIMING_X[segment + 1]) if segment == len(TIMING_X) - 2
            else (values < TIMING_X[segment + 1])
        )
        width = TIMING_X[segment + 1] - TIMING_X[segment]
        t = np.clip((values[mask] - TIMING_X[segment]) / width, 0.0, 1.0)
        t2 = t * t
        t3 = t2 * t
        output[mask] = (
            (2.0 * t3 - 3.0 * t2 + 1.0) * TIMING_Y[segment]
            + (t3 - 2.0 * t2 + t) * width * TIMING_SLOPE[segment]
            + (-2.0 * t3 + 3.0 * t2) * TIMING_Y[segment + 1]
            + (t3 - t2) * width * TIMING_SLOPE[segment + 1]
        )
    return np.clip(output, 0.0, 1.0)


def build_release_field_data(
    card_width: float,
    card_height: float,
    direction_x: float,
    direction_y: float,
    seed: int = 42,
    *,
    front_warp_scale: float = 1.0,
    broad_resistance_scale: float = 1.0,
    detail_resistance_scale: float = 1.0,
    load_scale: float = 1.0,
    smoothing_passes: int = 5,
    smoothing_amount: float = 0.28,
) -> tuple[np.ndarray, np.ndarray]:
    raw_length = math.hypot(direction_x, direction_y)
    wind_x = direction_x / raw_length if raw_length > 1e-9 else 0.0
    wind_y = direction_y / raw_length if raw_length > 1e-9 else -1.0
    side_x, side_y = -wind_y, wind_x
    phase = (seed & 0xFFFF) / 65535.0 * math.pi * 2.0
    diagonal = math.hypot(card_width, card_height)
    step_x = card_width / (COLUMNS - 1)
    step_y = card_height / (ROWS - 1)

    # 主剥离不是若干圆形波纹的并集，而是一张沿风向连续推进的 phase field。
    # 横向低频韧性只弯曲同一条前沿；反向边界上的有限压差区只让局部稍早起步。
    # 因而留存区域从反向侧迁往顺风侧，不会向几何中心收缩，也不存在多圆相交尖角。
    along_extent = max(
        (abs(wind_x) * card_width + abs(wind_y) * card_height) * 0.5,
        1e-9,
    )
    side_extent = max(
        (abs(side_x) * card_width + abs(side_y) * card_height) * 0.5,
        1e-9,
    )
    arrival = np.empty((ROWS, COLUMNS), dtype=np.float64)
    for row in range(ROWS):
        v = row / (ROWS - 1)
        for column in range(COLUMNS):
            u = column / (COLUMNS - 1)
            px = (u - 0.5) * card_width
            py = (v - 0.5) * card_height
            along = (px * wind_x + py * wind_y) / along_extent
            across = (px * side_x + py * side_y) / side_extent
            directional_progress = _clamp((along + 1.0) * 0.5)
            # 风压从反向侧向内部增加，但最后解除的位置不是顺风外边缘。
            # 薄面的外缘少一侧约束，越过内部附着脊后会重新较早脱附；
            # 因而沿主风坐标的到达时刻具有一个内部峰值，而非单调扫描。
            attachment_peak = 0.18
            rise = _clamp((along + 1.0) / (attachment_peak + 1.0)) ** 0.84
            forward_release = _smoothstep(attachment_peak, 1.0, along)
            directional = rise - forward_release * 0.22
            interior_wave = math.sin(directional_progress * math.pi)
            interior_envelope = 0.16 + 0.84 * interior_wave * interior_wave
            # 每条等时线都保持为沿主方向的单值曲线。横向形变只保留
            # 低于一个周期的宽波形，避免多个凹口相遇后夹出尖嘴。
            broad_bend = 0.142 * math.sin(
                across * math.pi * 0.58 + phase * 0.23
            )
            shoulder_bend = 0.046 * math.sin(
                across * math.pi * 0.92 - 0.72 + phase * 0.11
            )
            material_bias = 0.028 * math.sin(
                across * math.pi * 0.43 + 1.08 - phase * 0.17
            )
            front_warp = front_warp_scale * interior_envelope * (
                broad_bend + shoulder_bend + material_bias
            )
            # 解除贴附不是“到最近边缘的距离”。薄面有一张空间连续的材料
            # 阻力场；风压随主方向增加，并在反向侧的两个宽区域形成压力峰。
            # 两个压力峰分别对应反向边界承受法向载荷与横向剪切的位置，中心
            # 由主风坐标和薄面纵横比自然决定，不依赖屏幕的上/下/左/右。
            broad_resistance = _material_noise(
                u * 2.25 + 0.31,
                v * 1.72 - 0.18,
                seed,
                MATERIAL_SALT_A,
            )
            detail_resistance = _material_noise(
                u * 4.05 - 0.72,
                v * 3.10 + 0.44,
                seed,
                MATERIAL_SALT_B,
            )
            material_resistance = (
                (broad_resistance - 0.5) * 0.180 * broad_resistance_scale
                + (detail_resistance - 0.5) * 0.060 * detail_resistance_scale
            ) * (0.30 + 0.70 * interior_envelope)

            reverse_normal_load = math.exp(
                -((along + 0.52) / 0.40) ** 2
                -((across - 0.62) / 0.42) ** 2
            )
            reverse_shear_load = math.exp(
                -((along + 0.18) / 0.48) ** 2
                -((across + 0.34) / 0.30) ** 2
            )
            # 顺风角从开场就有可辨认的局部解除，但它是受张力集中的宽材料
            # 区域，不是沿两条自由边向内传播的 L 形距离场。随着阈值提高，
            # 该区域会与主释放区平滑汇合，而不会把完整层从四周围向中心。
            forward_tension = math.exp(
                -((along - 0.97) / 0.25) ** 2
                -((across + 0.36) / 0.28) ** 2
            )
            forward_shear_load = math.exp(
                -((along - 0.48) / 0.42) ** 2
                -((across - 0.26) / 0.50) ** 2
            )
            # 中部仍受基底附着和四周布面张力共同约束，形成一块偏顺风侧的
            # 宽附着脊。它使最后留存区域随风迁移但不固定在几何中心，也让
            # 解除等时线成为弯曲区域而不是一条贯穿整卡的机械对角线。
            adhesion_ridge_center = 0.04 + 0.075 * math.sin(
                across * math.pi * 0.68 + 0.42 + phase * 0.13
            )
            central_adhesion = math.exp(
                -((along - adhesion_ridge_center) / 0.49) ** 2
            ) * (
                0.84 + 0.16 * math.sin(
                    across * math.pi * 0.55 + 1.32 - phase * 0.09
                )
            )
            main_coordinate = (
                directional
                + front_warp
                + material_resistance
                + central_adhesion * 0.080
                - reverse_normal_load * 0.250 * load_scale
                - reverse_shear_load * 0.380 * load_scale
                - forward_tension * 1.045 * load_scale
                - forward_shear_load * 0.320 * load_scale
            )
            # 保留完整的连续材料相位，不能在空间平滑和归一化之前截断。
            # 预截断会把一大片高阻力区域压成完全相同的最晚时刻，视觉上
            # 就会重新出现轮廓规整、整块同步消失的“剩余 Dialog”。
            arrival[row, column] = main_coordinate

    # 轻微曲率流只消除采样网格的二阶锯齿；无需再把边界点钉回原始最小值
    #（钉回会重新制造截断）。
    offsets = (-1, 0, 1)
    smoothed = arrival
    for _ in range(smoothing_passes):
        next_values = smoothed.copy()
        for row in range(ROWS):
            for column in range(COLUMNS):
                total = 0.0
                weight = 0.0
                for dy in offsets:
                    for dx in offsets:
                        sample_column, sample_row = column + dx, row + dy
                        if not (0 <= sample_column < COLUMNS and 0 <= sample_row < ROWS):
                            continue
                        sample_weight = 4.0 if dx == dy == 0 else 2.0 if dx == 0 or dy == 0 else 1.0
                        total += smoothed[sample_row, sample_column] * sample_weight
                        weight += sample_weight
                next_values[row, column] = (
                    smoothed[row, column]
                    + (total / weight - smoothed[row, column]) * smoothing_amount
                )
        smoothed = next_values

    minimum = float(smoothed.min())
    maximum = float(smoothed.max())
    # 单调 Hermite 曲线只改变推进速度，不改变 phase field 的空间拓扑。
    normalized = _timing_curve(
        np.clip((smoothed - minimum) / max(maximum - minimum, 1e-9), 0.0, 1.0)
    )
    values = (MIN_DELAY + (MAX_DELAY - MIN_DELAY) * normalized).astype(np.float32)
    peel = np.empty_like(values)
    for row in range(ROWS):
        for column in range(COLUMNS):
            left, right = max(0, column - 1), min(COLUMNS - 1, column + 1)
            up, down = max(0, row - 1), min(ROWS - 1, row + 1)
            gx = (values[row, right] - values[row, left]) / max((right - left) * step_x, 1e-9)
            gy = (values[down, column] - values[up, column]) / max((down - up) * step_y, 1e-9)
            gradient_length = max(math.hypot(gx, gy), 1e-9)
            nx, ny = gx / gradient_length, gy / gradient_length
            wind_tension = _smoothstep(-0.12, 0.72, nx * wind_x + ny * wind_y)
            side_shear = _smoothstep(0.55, 0.98, abs(nx * side_x + ny * side_y)) * 0.22
            gradient_strength = _smoothstep(0.20, 0.85, gradient_length)
            peel[row, column] = _clamp(
                (0.08 + 0.92 * wind_tension + side_shear)
                * (0.72 + 0.28 * gradient_strength)
            )
    return values, peel


def build_release_field(
    card_width: float,
    card_height: float,
    direction_x: float,
    direction_y: float,
    seed: int = 42,
    **options: float | int,
) -> np.ndarray:
    return build_release_field_data(
        card_width, card_height, direction_x, direction_y, seed, **options
    )[0]
