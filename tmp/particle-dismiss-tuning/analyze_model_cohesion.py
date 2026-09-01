"""约束 Dialog 被感知为一个整体，并记录真机片元负载代理。

这个回归专门捕捉三类失败：

1. 同一时刻出现多条彼此分离的持久粒子带；
2. 左上输运已进入中段后，右下反向侧仍残留独立高密粒群；
3. 为维持多条带而让过多 cell、点覆盖和全部实际副本同时存活。

参考与模型沿用 61 帧形态回归的同一归一化坐标和粒子分离方法。性能部分不把桌面 GPU
耗时冒充手机帧耗时，而是统计 canonical Shader 实际保留的主体 cell、全部副本可见覆盖、
alpha 质量与覆盖重叠；真正的帧预算仍由 R5 实机录制确认。
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np
from skimage.measure import label, regionprops
from skimage.morphology import remove_small_objects

from analyze_reference_morphology import (
    CANVAS_BOUNDS,
    CANVAS_SIZE,
    MODEL_SCENARIO,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    SOURCE_PIXELS,
    density_field,
    frame_at,
    model_layers,
    read_frames,
    reference_layers,
)
from render_curtain_model import DISMISS_REPLICAS, CurtainRenderer


KEY_PROGRESSES = (0.50, 0.65, 0.70)


def normalized_coordinates() -> tuple[np.ndarray, np.ndarray]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    xs = np.linspace(lo_x, hi_x, CANVAS_SIZE, endpoint=False, dtype=np.float32)
    ys = np.linspace(lo_y, hi_y, CANVAS_SIZE, endpoint=False, dtype=np.float32)
    return np.meshgrid(xs, ys)


def cohesion_metrics(particle: np.ndarray) -> dict[str, float]:
    density = density_field(particle)
    values = density[density > 0.006]
    if values.size < 64:
        return {
            "support_threshold": 0.0,
            "significant_component_count": 0.0,
            "largest_component_mass_share": 0.0,
            "detached_mass_share": 0.0,
            "component_mass_entropy": 0.0,
        }

    # 使用中低密支撑判断“是否属于同一粒群”，高密峰仍由 morphology 回归单独约束。
    # 约 0.8% 控件宽的 closing 只跨越粒点间隙，不能跨越截图中成片的黑色断口。
    threshold = max(0.008, float(np.percentile(values, 42)))
    support = density >= threshold
    radius = max(1, int(round(SOURCE_PIXELS * 0.008)))
    kernel = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE, (radius * 2 + 1, radius * 2 + 1)
    )
    support = cv2.morphologyEx(support.astype(np.uint8), cv2.MORPH_CLOSE, kernel) > 0
    support = remove_small_objects(
        support,
        max_size=max(24, int(SOURCE_PIXELS * SOURCE_PIXELS * 0.0012)) - 1,
        connectivity=2,
    )
    labelled = label(support, connectivity=2)
    regions = regionprops(labelled)
    masses = np.array(
        [float(density[labelled == region.label].sum()) for region in regions],
        dtype=np.float64,
    )
    total_mass = max(float(masses.sum()), 1e-9)
    significant = masses[masses >= total_mass * 0.025]
    if significant.size == 0:
        significant = masses
    significant_total = max(float(significant.sum()), 1e-9)
    shares = significant / significant_total
    entropy = -float(np.sum(shares * np.log(np.maximum(shares, 1e-9))))
    entropy /= max(math.log(max(len(shares), 2)), 1e-9)
    largest_share = float(np.max(shares)) if shares.size else 0.0
    return {
        "support_threshold": threshold,
        "significant_component_count": float(len(significant)),
        "largest_component_mass_share": largest_share,
        "detached_mass_share": 1.0 - largest_share,
        "component_mass_entropy": entropy,
    }


def directional_metrics(particle: np.ndarray) -> dict[str, float]:
    density = density_field(particle).astype(np.float64)
    xx, yy = normalized_coordinates()
    radians = math.radians(MODEL_SCENARIO.angle_degrees)
    direction = np.array([math.cos(radians), math.sin(radians)], dtype=np.float64)
    projection = (xx - 0.5) * direction[0] + (yy - 0.5) * direction[1]
    total = max(float(density.sum()), 1e-9)
    trailing = projection <= -0.10
    advanced = projection >= 0.22
    # 再单独测右下实体区域，避免一条向右上越界的粒带在投影上被误算成已前进。
    right_down = (xx >= 0.48) & (yy >= 0.48)
    return {
        "sweep_centroid": float((density * projection).sum() / total),
        "trailing_mass_share": float(density[trailing].sum() / total),
        "advanced_mass_share": float(density[advanced].sum() / total),
        "right_down_mass_share": float(density[right_down].sum() / total),
    }


def performance_proxy(renderer: CurtainRenderer, progress: float) -> dict[str, float]:
    occupancy = renderer.render_diagnostic_rgba(MODEL_SCENARIO, progress, "occupancy")
    red = occupancy[:, :, 0] > 180
    blue = occupancy[:, :, 2] > 180
    active_cells = int(np.count_nonzero(red | blue))

    layer_alpha = [
        renderer.render_diagnostic_rgba(MODEL_SCENARIO, progress, f"layer-{index}")[:, :, 3]
        .astype(np.float32) / 255.0
        for index in range(DISMISS_REPLICAS)
    ]
    supports = [alpha >= 0.012 for alpha in layer_alpha]
    union = np.logical_or.reduce(supports)
    viewport_area = float(union.size)
    summed_support = float(sum(np.count_nonzero(item) for item in supports))
    return {
        "active_main_cells": float(active_cells),
        "estimated_active_vertices": float(active_cells * DISMISS_REPLICAS),
        "submitted_vertices": float(renderer.cols * renderer.rows * DISMISS_REPLICAS),
        "band_main_cells": float(np.count_nonzero(red)),
        "tail_main_cells": float(np.count_nonzero(blue)),
        "all_layers_support_viewport": summed_support / viewport_area,
        "all_layers_alpha_mass_viewport": float(sum(alpha.sum() for alpha in layer_alpha))
        / viewport_area,
        "overdraw_per_visible_pixel": summed_support / max(float(np.count_nonzero(union)), 1.0),
    }


def analyse(reference_path: Path) -> dict[str, object]:
    frames, fps = read_frames(reference_path)
    baseline = np.median(
        np.stack(frames[int(round(2.90 * fps)) : int(round(3.16 * fps))]), axis=0
    ).astype(np.uint8)
    background = np.median(
        np.stack(
            frames[
                int(round(7.85 * fps)) : min(len(frames), int(round(8.16 * fps)))
            ]
        ),
        axis=0,
    ).astype(np.uint8)
    renderer = CurtainRenderer()
    rows: list[dict[str, object]] = []
    for progress in KEY_PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (
            REFERENCE_FINISH_S - REFERENCE_ONSET_S
        )
        reference = reference_layers(
            frame_at(frames, fps, reference_time), baseline, background
        )
        model = model_layers(renderer, progress)
        rows.append(
            {
                "progress": progress,
                "reference": {
                    "cohesion": cohesion_metrics(reference.particle),
                    "directional": directional_metrics(reference.particle),
                },
                "model": {
                    "cohesion": cohesion_metrics(model.particle),
                    "directional": directional_metrics(model.particle),
                    "performance": performance_proxy(renderer, progress),
                },
            }
        )
    return {"frames": rows}


def validate(result: dict[str, object]) -> None:
    failures: list[str] = []
    sweep_centroids: list[tuple[float, float]] = []
    for row in result["frames"]:
        progress = float(row["progress"])
        reference = row["reference"]
        model = row["model"]
        ref_cohesion = reference["cohesion"]
        model_cohesion = model["cohesion"]
        ref_directional = reference["directional"]
        model_directional = model["directional"]
        sweep_centroids.append((progress, model_directional["sweep_centroid"]))
        if model_cohesion["significant_component_count"] > max(
            2.0, ref_cohesion["significant_component_count"] + 1.0
        ):
            failures.append(f"t={progress:.2f} 存在过多彼此独立的显著粒子群")
        if model_cohesion["detached_mass_share"] > max(
            0.16, ref_cohesion["detached_mass_share"] * 1.35 + 0.04
        ):
            failures.append(f"t={progress:.2f} 主粒群之外的分离质量过高")
        if model_directional["right_down_mass_share"] > (
            ref_directional["right_down_mass_share"] + 0.05
        ):
            failures.append(f"t={progress:.2f} 右下区域残留粒子过多")
        # 参考控件的长宽比、构图和粒子颜色与测试 Dialog 不同，不能把质心
        # 绝对位置逐帧硬拟合。这里只约束主风输运已建立且随时间单调推进。
        if progress == 0.50 and model_directional["sweep_centroid"] < 0.075:
            failures.append("t=0.50 尚未建立可见的整体左上输运")

    if any(
        later[1] <= earlier[1]
        for earlier, later in zip(sweep_centroids, sweep_centroids[1:])
    ):
        failures.append("t=0.50–0.70 的整体左上输运没有持续推进")

    # 第五层已改为仅彩色材料，并在 PBD 纹理读取前提前退出。旧门禁只统计
    # 前三层且把 alpha 质量当成 GPU 成本，会漏掉真实副本并错误惩罚高密
    # 细粒。这里统计全部副本，主要约束提交顶点、片元覆盖和覆盖重叠；最终
    # 120 Hz 帧预算仍由 R5 实机录制确认。
    middle = min(result["frames"], key=lambda row: abs(float(row["progress"]) - 0.65))
    cost = middle["model"]["performance"]
    if cost["active_main_cells"] > 13000:
        failures.append("t=0.65 同时存活的主体 cell 超出移动端预算")
    if cost["submitted_vertices"] > 70000:
        failures.append("单帧提交顶点数超出移动端预算")
    if cost["all_layers_support_viewport"] > 0.14:
        failures.append("t=0.65 全部粒子层覆盖超出移动端片元预算")
    if cost["all_layers_alpha_mass_viewport"] > 0.08:
        failures.append("t=0.65 全部粒子层 alpha 质量异常")
    if cost["overdraw_per_visible_pixel"] > 1.65:
        failures.append("t=0.65 粒子层覆盖重叠超出移动端预算")
    if failures:
        raise SystemExit("；".join(failures))
    print("cohesion/performance regression: PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("--no-validate", action="store_true")
    args = parser.parse_args()
    result = analyse(args.reference)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not args.no_validate:
        validate(result)


if __name__ == "__main__":
    main()
