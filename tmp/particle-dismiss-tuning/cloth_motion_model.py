# -*- coding: utf-8 -*-
"""桌面离屏渲染使用的连续受风薄面；公式对应 Android 生产模型。"""

from __future__ import annotations

import math

import numba
import numpy as np

from physical_release_field import build_release_field, build_release_field_data


COLUMNS = 16
ROWS = 10
STEPS = 240
VERTEX_COLUMNS = COLUMNS + 1
VERTEX_ROWS = ROWS + 1
VERTEX_COUNT = VERTEX_COLUMNS * VERTEX_ROWS
CARD_WIDTH = 1.48
RELEASE_BLEND = 0.120


@numba.njit(cache=True)
def _clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return min(maximum, max(minimum, value))


@numba.njit(cache=True)
def _smoothstep(edge0: float, edge1: float, value: float) -> float:
    amount = _clamp((value - edge0) / max(edge1 - edge0, 1e-9))
    return amount * amount * (3.0 - 2.0 * amount)


@numba.njit(cache=True)
def _sample_release(u: float, v: float, profile: np.ndarray) -> float:
    rows, columns = profile.shape
    x = _clamp(u) * (columns - 1)
    y = _clamp(v) * (rows - 1)
    x0, y0 = min(int(math.floor(x)), columns - 1), min(int(math.floor(y)), rows - 1)
    x1, y1 = min(x0 + 1, columns - 1), min(y0 + 1, rows - 1)
    tx, ty = x - x0, y - y0
    top = profile[y0, x0] + (profile[y0, x1] - profile[y0, x0]) * tx
    bottom = profile[y1, x0] + (profile[y1, x1] - profile[y1, x0]) * tx
    return top + (bottom - top) * ty


@numba.njit(cache=True)
def _distance(positions: np.ndarray, first: int, second: int) -> float:
    dx = positions[second, 0] - positions[first, 0]
    dy = positions[second, 1] - positions[first, 1]
    dz = positions[second, 2] - positions[first, 2]
    return math.sqrt(dx * dx + dy * dy + dz * dz)


@numba.njit(cache=True)
def _compute_normals(positions: np.ndarray, normals: np.ndarray) -> None:
    for row in range(VERTEX_ROWS):
        for column in range(VERTEX_COLUMNS):
            vertex = row * VERTEX_COLUMNS + column
            left = row * VERTEX_COLUMNS + max(0, column - 1)
            right = row * VERTEX_COLUMNS + min(COLUMNS, column + 1)
            up = max(0, row - 1) * VERTEX_COLUMNS + column
            down = min(ROWS, row + 1) * VERTEX_COLUMNS + column
            ax = positions[right, 0] - positions[left, 0]
            ay = positions[right, 1] - positions[left, 1]
            az = positions[right, 2] - positions[left, 2]
            bx = positions[down, 0] - positions[up, 0]
            by = positions[down, 1] - positions[up, 1]
            bz = positions[down, 2] - positions[up, 2]
            nx = ay * bz - az * by
            ny = az * bx - ax * bz
            nz = ax * by - ay * bx
            length = max(math.sqrt(nx * nx + ny * ny + nz * nz), 1e-9)
            normals[vertex, 0] = nx / length
            normals[vertex, 1] = ny / length
            normals[vertex, 2] = nz / length


@numba.njit(cache=True)
def _store_frame(
    frames: np.ndarray,
    frame: int,
    time: float,
    positions: np.ndarray,
    normals: np.ndarray,
    delay: np.ndarray,
    step_x: float,
    step_y: float,
) -> None:
    _compute_normals(positions, normals)
    for row in range(VERTEX_ROWS):
        for column in range(VERTEX_COLUMNS):
            vertex = row * VERTEX_COLUMNS + column
            left_column, right_column = max(0, column - 1), min(COLUMNS, column + 1)
            up_row, down_row = max(0, row - 1), min(ROWS, row + 1)
            left = row * VERTEX_COLUMNS + left_column
            right = row * VERTEX_COLUMNS + right_column
            up = up_row * VERTEX_COLUMNS + column
            down = down_row * VERTEX_COLUMNS + column
            ax = positions[right, 0] - positions[left, 0]
            ay = positions[right, 1] - positions[left, 1]
            az = positions[right, 2] - positions[left, 2]
            bx = positions[down, 0] - positions[up, 0]
            by = positions[down, 1] - positions[up, 1]
            bz = positions[down, 2] - positions[up, 2]
            rest_u = max(1, right_column - left_column) * step_x
            rest_v = max(1, down_row - up_row) * step_y
            current_u = math.sqrt(ax * ax + ay * ay + az * az)
            current_v = math.sqrt(bx * bx + by * by + bz * bz)
            compression_strain = max(
                max(0.0, 1.0 - current_u / max(rest_u, 1e-9)),
                max(0.0, 1.0 - current_v / max(rest_v, 1e-9)),
            )
            projected_area = abs(ax * by - ay * bx)
            projection_compression = _clamp(
                rest_u * rest_v / max(projected_area, rest_u * rest_v * 0.20), 1.0, 5.0
            )
            curvature = 0.0
            for neighbour in (left, right, up, down):
                dot = (
                    normals[vertex, 0] * normals[neighbour, 0]
                    + normals[vertex, 1] * normals[neighbour, 1]
                    + normals[vertex, 2] * normals[neighbour, 2]
                )
                curvature = max(curvature, 1.0 - _clamp(dot, -1.0, 1.0))
            released = _smoothstep(delay[vertex], delay[vertex] + RELEASE_BLEND, time)
            front_age = time - delay[vertex]
            peel_front = math.exp(-((front_age - 0.070) / 0.066) ** 2) * released
            curved = _smoothstep(0.018, 0.11, curvature) * _smoothstep(
                1.04, 1.40, projection_compression
            )
            buckled = _smoothstep(0.010, 0.065, compression_strain) * _smoothstep(
                1.03, 1.42, projection_compression
            )
            crease = (
                peel_front
                * _smoothstep(0.003, 0.055, curvature)
                * _smoothstep(1.015, 1.55, projection_compression)
            )
            fold_density = _clamp(max(curved, buckled, crease))
            frames[frame, row, column, :3] = positions[vertex]
            frames[frame, row, column, 3] = fold_density


@numba.njit(cache=True)
def _solve_distance(
    positions: np.ndarray,
    inverse_mass: np.ndarray,
    first: int,
    second: int,
    rest_length: float,
    stretch: float,
    compression: float,
) -> None:
    dx = positions[second, 0] - positions[first, 0]
    dy = positions[second, 1] - positions[first, 1]
    dz = positions[second, 2] - positions[first, 2]
    length = math.sqrt(dx * dx + dy * dy + dz * dz)
    weight = inverse_mass[first] + inverse_mass[second]
    if length < 1e-9 or weight < 1e-9:
        return
    stiffness = compression if length < rest_length else stretch
    scale = (length - rest_length) / length * stiffness
    cx, cy, cz = dx * scale, dy * scale, dz * scale
    positions[first, 0] += cx * inverse_mass[first] / weight
    positions[first, 1] += cy * inverse_mass[first] / weight
    positions[first, 2] += cz * inverse_mass[first] / weight
    positions[second, 0] -= cx * inverse_mass[second] / weight
    positions[second, 1] -= cy * inverse_mass[second] / weight
    positions[second, 2] -= cz * inverse_mass[second] / weight


@numba.njit(cache=True)
def _build(
    snapshot_width: int,
    snapshot_height: int,
    raw_x: float,
    raw_y: float,
    release_profile: np.ndarray,
    seed: int,
    buckle_frequency_u: float,
    buckle_frequency_v: float,
    buckle_base: float,
    buckle_amplitude: float,
    peel_buckle_amplitude: float,
) -> np.ndarray:
    length = math.hypot(raw_x, raw_y)
    wind_x = raw_x / length if length > 1e-9 else 0.0
    wind_y = raw_y / length if length > 1e-9 else -1.0
    side_x, side_y = -wind_y, wind_x
    card_height = CARD_WIDTH * snapshot_height / snapshot_width
    step_x, step_y = CARD_WIDTH / COLUMNS, card_height / ROWS
    diagonal = math.hypot(step_x, step_y)
    phase = (seed & 0xFFFF) / 65535.0 * math.pi * 2.0

    uv = np.empty((VERTEX_COUNT, 2), dtype=np.float64)
    base = np.empty((VERTEX_COUNT, 2), dtype=np.float64)
    delay = np.empty(VERTEX_COUNT, dtype=np.float64)
    positions = np.empty((VERTEX_COUNT, 3), dtype=np.float64)
    old_positions = np.empty((VERTEX_COUNT, 3), dtype=np.float64)
    normals = np.zeros((VERTEX_COUNT, 3), dtype=np.float64)
    inverse_mass = np.zeros(VERTEX_COUNT, dtype=np.float64)
    compression_memory = np.zeros(VERTEX_COUNT, dtype=np.float64)

    for row in range(VERTEX_ROWS):
        for column in range(VERTEX_COLUMNS):
            vertex = row * VERTEX_COLUMNS + column
            u, v = column / COLUMNS, row / ROWS
            x, y = (u - 0.5) * CARD_WIDTH, (v - 0.5) * card_height
            uv[vertex] = (u, v)
            base[vertex] = (x, y)
            positions[vertex] = (x, y, 0.0)
            old_positions[vertex] = positions[vertex]
            delay[vertex] = _sample_release(u, v, release_profile)

    constraint_count = (
        VERTEX_ROWS * COLUMNS
        + VERTEX_COLUMNS * ROWS
        + ROWS * COLUMNS * 2
        + VERTEX_ROWS * (COLUMNS - 1)
        + VERTEX_COLUMNS * (ROWS - 1)
    )
    first = np.empty(constraint_count, dtype=np.int32)
    second = np.empty(constraint_count, dtype=np.int32)
    rest = np.empty(constraint_count, dtype=np.float64)
    stretch = np.empty(constraint_count, dtype=np.float64)
    compression = np.empty(constraint_count, dtype=np.float64)
    cursor = 0

    def equivalent(value: float) -> float:
        return 1.0 - (1.0 - value) ** 9.0

    def add(a: int, b: int, distance: float, stretch_value: float, compression_value: float) -> None:
        nonlocal cursor
        first[cursor], second[cursor], rest[cursor] = a, b, distance
        stretch[cursor], compression[cursor] = equivalent(stretch_value), equivalent(compression_value)
        cursor += 1

    for row in range(VERTEX_ROWS):
        for column in range(COLUMNS):
            add(row * VERTEX_COLUMNS + column, row * VERTEX_COLUMNS + column + 1, step_x, 0.94, 0.28)
    for column in range(VERTEX_COLUMNS):
        for row in range(ROWS):
            add(row * VERTEX_COLUMNS + column, (row + 1) * VERTEX_COLUMNS + column, step_y, 0.94, 0.28)
    for row in range(ROWS):
        for column in range(COLUMNS):
            a = row * VERTEX_COLUMNS + column
            add(a, a + VERTEX_COLUMNS + 1, diagonal, 0.67, 0.12)
            add(a + 1, a + VERTEX_COLUMNS, diagonal, 0.67, 0.12)
    for row in range(VERTEX_ROWS):
        for column in range(COLUMNS - 1):
            add(row * VERTEX_COLUMNS + column, row * VERTEX_COLUMNS + column + 2, step_x * 2, 0.085, 0.025)
    for column in range(VERTEX_COLUMNS):
        for row in range(ROWS - 1):
            add(row * VERTEX_COLUMNS + column, (row + 2) * VERTEX_COLUMNS + column, step_y * 2, 0.085, 0.025)

    frames = np.empty((STEPS + 1, VERTEX_ROWS, VERTEX_COLUMNS, 4), dtype=np.float32)
    _store_frame(frames, 0, 0.0, positions, normals, delay, step_x, step_y)
    dt = 1.0 / STEPS

    for frame in range(1, STEPS + 1):
        time = frame / STEPS
        envelope = _smoothstep(0.08, 0.95, time) ** 1.72
        wind_speed = 4.8 + 31.0 * envelope
        lift_speed = 5.2 + wind_speed * 0.23
        for row in range(VERTEX_ROWS):
            for column in range(VERTEX_COLUMNS):
                vertex = row * VERTEX_COLUMNS + column
                released = _smoothstep(delay[vertex], delay[vertex] + RELEASE_BLEND, time)
                inverse_mass[vertex] = _smoothstep(0.035, 0.94, released)
                if inverse_mass[vertex] < 1e-5:
                    positions[vertex] = (base[vertex, 0], base[vertex, 1], 0.0)
                    old_positions[vertex] = positions[vertex]
                    continue

                sx = (positions[vertex, 0] - old_positions[vertex, 0]) * 0.985
                sy = (positions[vertex, 1] - old_positions[vertex, 1]) * 0.985
                sz = (positions[vertex, 2] - old_positions[vertex, 2]) * 0.980
                pvx, pvy, pvz = sx / dt, sy / dt, sz / dt
                old_positions[vertex] = positions[vertex]
                u, v = uv[vertex]
                gust = 1.0 + 0.10 * math.sin((u * 1.22 + v * 0.84) * math.pi * 2 - time * 1.35 + phase)
                gust += 0.055 * math.sin((u * 0.56 - v * 1.43) * math.pi * 2 + time * 1.02 - phase * 0.73)
                curl = math.sin((u * 1.06 + v * 0.72) * math.pi * 2 - time * 1.46 + phase * 0.41)
                curl *= _smoothstep(0.18, 0.82, time)
                wx = wind_x * wind_speed * gust + side_x * curl * 2.35
                wy = wind_y * wind_speed * gust + side_y * curl * 2.35
                wz = lift_speed * (0.92 + 0.08 * math.cos((u - v) * math.pi * 2 + phase))
                rx, ry, rz = wx - pvx * 0.17, wy - pvy * 0.17, wz - pvz * 0.17
                nx, ny, nz = normals[vertex]
                normal_speed = rx * nx + ry * ny + rz * nz
                if normal_speed < 0:
                    nx, ny, nz, normal_speed = -nx, -ny, -nz, -normal_speed
                relative_sq = rx * rx + ry * ry + rz * rz
                drag_term = (0.92 - 0.30) * normal_speed
                ax = 0.074 * (drag_term * rx + 0.30 * relative_sq * nx)
                ay = 0.074 * (drag_term * ry + 0.30 * relative_sq * ny)
                az = 0.074 * (drag_term * rz + 0.30 * relative_sq * nz)

                left = row * VERTEX_COLUMNS + max(0, column - 1)
                right = row * VERTEX_COLUMNS + min(COLUMNS, column + 1)
                up = max(0, row - 1) * VERTEX_COLUMNS + column
                down = min(ROWS, row + 1) * VERTEX_COLUMNS + column
                rest_u = max(1, right % VERTEX_COLUMNS - left % VERTEX_COLUMNS) * step_x
                rest_v = max(1, down // VERTEX_COLUMNS - up // VERTEX_COLUMNS) * step_y
                local_compression = max(
                    max(0.0, 1.0 - _distance(positions, left, right) / max(rest_u, 1e-9)),
                    max(0.0, 1.0 - _distance(positions, up, down) / max(rest_v, 1e-9)),
                )
                compression_memory[vertex] = max(local_compression, compression_memory[vertex] * 0.965)
                front_age = time - delay[vertex]
                peel_front = math.exp(-((front_age - 0.060) / 0.062) ** 2) * released
                buckle_phase = math.sin(
                    (u * buckle_frequency_u - v * buckle_frequency_v) * math.pi * 2
                    + phase
                    + time * 0.52
                )
                buckle = compression_memory[vertex] * (
                    buckle_base + buckle_amplitude * buckle_phase
                )
                peel = peel_front * (
                    22.0 + peel_buckle_amplitude * max(0.0, buckle_phase)
                )
                ax, ay, az = ax + nx * (peel + buckle), ay + ny * (peel + buckle), az + nz * (peel + buckle)
                positions[vertex, 0] += _clamp(sx + ax * dt * dt, -0.058, 0.058) * inverse_mass[vertex]
                positions[vertex, 1] += _clamp(sy + ay * dt * dt, -0.058, 0.058) * inverse_mass[vertex]
                positions[vertex, 2] += _clamp(sz + az * dt * dt, -0.058, 0.058) * inverse_mass[vertex]

        for constraint in range(constraint_count):
            _solve_distance(
                positions,
                inverse_mass,
                first[constraint],
                second[constraint],
                rest[constraint],
                stretch[constraint],
                compression[constraint],
            )
        for vertex in range(VERTEX_COUNT):
            released = _smoothstep(delay[vertex], delay[vertex] + RELEASE_BLEND, time)
            if released < 0.015:
                positions[vertex] = (base[vertex, 0], base[vertex, 1], 0.0)
            elif released < 1.0:
                adhesion = (1.0 - released) ** 2.15 * 0.40
                positions[vertex, 0] += (base[vertex, 0] - positions[vertex, 0]) * adhesion
                positions[vertex, 1] += (base[vertex, 1] - positions[vertex, 1]) * adhesion
                positions[vertex, 2] *= 1.0 - adhesion
        _store_frame(frames, frame, time, positions, normals, delay, step_x, step_y)
    return frames


def build_cloth_trajectory(
    snapshot_width: int,
    snapshot_height: int,
    direction_x: float,
    direction_y: float,
    seed: int = 42,
    *,
    buckle_frequency_u: float = 1.42,
    buckle_frequency_v: float = 1.06,
    buckle_base: float = 16.0,
    buckle_amplitude: float = 23.0,
    peel_buckle_amplitude: float = 10.0,
    release_options: dict[str, float | int] | None = None,
) -> np.ndarray:
    card_height = CARD_WIDTH * snapshot_height / snapshot_width
    release_field = build_release_field(
        CARD_WIDTH,
        card_height,
        direction_x,
        direction_y,
        seed,
        **(release_options or {}),
    )
    return _build(
        snapshot_width,
        snapshot_height,
        direction_x,
        direction_y,
        release_field.astype(np.float64),
        seed,
        buckle_frequency_u,
        buckle_frequency_v,
        buckle_base,
        buckle_amplitude,
        peel_buckle_amplitude,
    )


def build_runtime_release_field(
    snapshot_width: int,
    snapshot_height: int,
    direction_x: float,
    direction_y: float,
    seed: int = 42,
    *,
    release_options: dict[str, float | int] | None = None,
) -> np.ndarray:
    card_height = CARD_WIDTH * snapshot_height / snapshot_width
    delay, peel = build_release_field_data(
        CARD_WIDTH,
        card_height,
        direction_x,
        direction_y,
        seed,
        **(release_options or {}),
    )
    return np.stack((delay, peel), axis=-1)
