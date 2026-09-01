# -*- coding: utf-8 -*-
"""为连续材料阻力场选择稳定的散列相位，不改变物理公式或逐帧拟合遮罩。"""

from __future__ import annotations

import json
import math
from pathlib import Path

import cv2
import numpy as np

import physical_release_field as release_field
from analyze_particleization_topology import (
    GRID_COLUMNS,
    GRID_ROWS,
    coordinate_fields,
    correlation,
    linear_residual,
    side_lead_metrics,
)


ROOT = Path(__file__).resolve().parent
ANALYSIS = ROOT / "frames-curtain" / "particleization-topology-analysis.json"


def latest_centroid(values: np.ndarray, fraction: float = 0.15) -> tuple[float, float]:
    yy, xx = np.mgrid[0:values.shape[0], 0:values.shape[1]]
    u = (xx + 0.5) / values.shape[1]
    v = (yy + 0.5) / values.shape[0]
    cutoff = float(np.quantile(values, 1.0 - fraction))
    weights = np.clip((values - cutoff) / max(float(values.max() - cutoff), 1e-9), 0.0, 1.0)
    weights += (values >= cutoff) * 0.08
    total = max(float(weights.sum()), 1e-9)
    return float((weights * u).sum() / total), float((weights * v).sum() / total)


def main() -> None:
    analysis = json.loads(ANALYSIS.read_text(encoding="utf-8"))
    reference = np.asarray(
        analysis["reference"]["arrival_maps"]["0.52"], dtype=np.float64
    )
    valid = reference >= 0.0
    reference[~valid] = np.nan
    reference_for_centroid = np.where(
        valid, reference, float(np.nanmedian(reference[valid]))
    )
    target_u, target_v = latest_centroid(reference_for_centroid)
    _, _, nearest_edge, wind = coordinate_fields()

    candidates = []
    original_a = release_field.MATERIAL_SALT_A
    original_b = release_field.MATERIAL_SALT_B
    for index in range(128):
        salt_a = (0x9E3779B9 * (index + 3) + 0x7F4A7C15) & 0xFFFF_FFFF
        salt_b = (0x85EBCA6B * (index + 11) + 0x02E5BE93) & 0xFFFF_FFFF
        release_field.MATERIAL_SALT_A = salt_a
        release_field.MATERIAL_SALT_B = salt_b
        delay, _ = release_field.build_release_field_data(
            1.48, 0.87875, -0.6157, -0.7880, 42
        )
        grid = cv2.resize(
            delay, (GRID_COLUMNS, GRID_ROWS), interpolation=cv2.INTER_AREA
        ).astype(np.float64)
        residual = linear_residual(grid, wind, np.ones_like(grid, dtype=bool))
        edge_after_direction = correlation(
            residual, nearest_edge, np.ones_like(grid, dtype=bool)
        )
        direction = correlation(grid, wind, np.ones_like(grid, dtype=bool))
        map_correlation = correlation(grid, reference, valid)
        latest_u, latest_v = latest_centroid(grid)
        sides = side_lead_metrics(grid, np.ones_like(grid, dtype=bool))
        broad_sides = int(sides["sides_with_broad_inward_lead"])
        # 参考只用于约束总体空间统计；完整 arrival map 的相关性权重很低，
        # 防止把运行时材料阻力场变成参考遮罩的编码版本。
        score = (
            abs(direction - 0.63) * 1.8
            + abs(edge_after_direction - 0.43) * 1.2
            + math.hypot(latest_u - target_u, latest_v - target_v) * 3.2
            + max(0, broad_sides - 2) * 0.8
            + max(0.0, 0.28 - map_correlation) * 0.35
        )
        candidates.append({
            "score": score,
            "saltA": salt_a,
            "saltB": salt_b,
            "directionCorrelation": direction,
            "edgeAfterDirection": edge_after_direction,
            "mapCorrelation": map_correlation,
            "latestUv": [latest_u, latest_v],
            "broadSides": broad_sides,
        })
    release_field.MATERIAL_SALT_A = original_a
    release_field.MATERIAL_SALT_B = original_b
    candidates.sort(key=lambda item: item["score"])
    print(json.dumps({
        "referenceLatestUv": [target_u, target_v],
        "candidates": candidates[:12],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
