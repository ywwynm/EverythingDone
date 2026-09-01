# -*- coding: utf-8 -*-
"""诊断参考与 canonical 模型的粒子化拓扑。

重点不是逐帧拟合像素，而是区分两种不同机制：

1. 外边缘距离主导的向心侵蚀；
2. 方向压力与连续材料阻力共同决定的、位置不局限于外边缘的解除贴附。

脚本将完整层按材料坐标分箱，测量每个区域首次明显失去本体的归一化时刻，随后比较
最近边缘距离、主风方向坐标、四条边的持续领先范围以及留存区域质心。所有阈值都会在
多个完整层保留率上重复计算，避免单一 alpha 阈值把合成噪声误判为拓扑。
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

from analyze_reference_morphology import (
    CANVAS_BOUNDS,
    CANVAS_SIZE,
    MODEL_SCENARIO,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    frame_at,
    model_layers,
    read_frames,
    reference_layers,
)
from physical_release_field import build_release_field_data
from render_curtain_model import CurtainRenderer, OUT, font


GRID_COLUMNS = 15
GRID_ROWS = 11
PROGRESSES = np.linspace(0.0, 0.72, 73, dtype=np.float64)
ARRIVAL_THRESHOLDS = (0.78, 0.52, 0.24)
DETAIL_PROGRESSES = (0.10, 0.16, 0.24, 0.32, 0.40, 0.47, 0.54, 0.62)


@dataclass
class GridSeries:
    retention: np.ndarray
    observable: np.ndarray


def source_canvas_slice() -> tuple[slice, slice]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    x0 = int(round((0.0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    y0 = int(round((0.0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    x1 = int(round((1.0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    y1 = int(round((1.0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    return slice(y0, y1), slice(x0, x1)


def grid_series(intact_frames: list[np.ndarray], baseline: np.ndarray) -> GridSeries:
    ys, xs = source_canvas_slice()
    baseline = baseline[ys, xs].astype(np.float64)
    frames = [frame[ys, xs].astype(np.float64) for frame in intact_frames]
    height, width = baseline.shape
    retention = np.full(
        (len(frames), GRID_ROWS, GRID_COLUMNS), np.nan, dtype=np.float64
    )
    observable = np.zeros((GRID_ROWS, GRID_COLUMNS), dtype=bool)
    for row in range(GRID_ROWS):
        y0 = round(row * height / GRID_ROWS)
        y1 = round((row + 1) * height / GRID_ROWS)
        for column in range(GRID_COLUMNS):
            x0 = round(column * width / GRID_COLUMNS)
            x1 = round((column + 1) * width / GRID_COLUMNS)
            weight = baseline[y0:y1, x0:x1]
            visible = weight >= 0.12
            if np.count_nonzero(visible) < max(12, visible.size * 0.12):
                continue
            observable[row, column] = True
            baseline_mass = max(float(weight[visible].sum()), 1e-9)
            for index, frame in enumerate(frames):
                retention[index, row, column] = np.clip(
                    float(frame[y0:y1, x0:x1][visible].sum()) / baseline_mass,
                    0.0,
                    1.15,
                )
    # 屏摄压缩会造成单帧小幅回升。粒子化是不可逆过程，使用累计最小值恢复物理约束。
    retention = np.fmin.accumulate(retention, axis=0)
    return GridSeries(retention=retention, observable=observable)


def arrival_map(series: GridSeries, threshold: float) -> np.ndarray:
    output = np.full((GRID_ROWS, GRID_COLUMNS), np.nan, dtype=np.float64)
    for row in range(GRID_ROWS):
        for column in range(GRID_COLUMNS):
            if not series.observable[row, column]:
                continue
            values = series.retention[:, row, column]
            hits = np.flatnonzero(values <= threshold)
            if hits.size == 0:
                output[row, column] = float(PROGRESSES[-1])
                continue
            index = int(hits[0])
            if index == 0:
                output[row, column] = float(PROGRESSES[0])
                continue
            previous = values[index - 1]
            current = values[index]
            amount = np.clip(
                (previous - threshold) / max(previous - current, 1e-9), 0.0, 1.0
            )
            output[row, column] = float(
                PROGRESSES[index - 1]
                + (PROGRESSES[index] - PROGRESSES[index - 1]) * amount
            )
    return output


def coordinate_fields() -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    yy, xx = np.mgrid[0:GRID_ROWS, 0:GRID_COLUMNS]
    u = (xx + 0.5) / GRID_COLUMNS
    v = (yy + 0.5) / GRID_ROWS
    nearest_edge = np.minimum.reduce((u, 1.0 - u, v, 1.0 - v))
    radians = math.radians(MODEL_SCENARIO.angle_degrees)
    wind = (u - 0.5) * math.cos(radians) + (v - 0.5) * math.sin(radians)
    return u, v, nearest_edge, wind


def correlation(first: np.ndarray, second: np.ndarray, mask: np.ndarray) -> float:
    valid = mask & np.isfinite(first) & np.isfinite(second)
    if np.count_nonzero(valid) < 8:
        return 0.0
    a = first[valid]
    b = second[valid]
    if float(np.std(a)) < 1e-9 or float(np.std(b)) < 1e-9:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def linear_residual(values: np.ndarray, predictor: np.ndarray, mask: np.ndarray) -> np.ndarray:
    valid = mask & np.isfinite(values) & np.isfinite(predictor)
    design = np.column_stack((np.ones(np.count_nonzero(valid)), predictor[valid]))
    coefficients, *_ = np.linalg.lstsq(design, values[valid], rcond=None)
    output = np.full_like(values, np.nan)
    output[valid] = values[valid] - design @ coefficients
    return output


def side_lead_metrics(arrival: np.ndarray, observable: np.ndarray) -> dict[str, object]:
    # 比较最外层与距边约 18% 的平行内层。连续整边领先意味着边界距离场正在向内传播；
    # 参考中的局部早发区只会让边的一部分领先。
    pairs = {
        "top": ((0, slice(None)), (2, slice(None))),
        "bottom": ((GRID_ROWS - 1, slice(None)), (GRID_ROWS - 3, slice(None))),
        "left": ((slice(None), 0), (slice(None), 2)),
        "right": ((slice(None), GRID_COLUMNS - 1), (slice(None), GRID_COLUMNS - 3)),
    }
    rows: dict[str, object] = {}
    for name, (edge_index, inner_index) in pairs.items():
        edge = arrival[edge_index]
        inner = arrival[inner_index]
        valid = observable[edge_index] & observable[inner_index]
        delta = inner - edge
        lead = valid & np.isfinite(delta) & (delta >= 0.045)
        rows[name] = {
            "lead_fraction": float(np.count_nonzero(lead) / max(np.count_nonzero(valid), 1)),
            "median_inner_minus_edge": float(np.nanmedian(delta[valid]))
            if np.any(valid)
            else 0.0,
        }
    rows["sides_with_broad_inward_lead"] = int(
        sum(float(value["lead_fraction"]) >= 0.55 for key, value in rows.items() if key != "sides_with_broad_inward_lead")
    )
    return rows


def topology_metrics(series: GridSeries) -> dict[str, object]:
    _, _, nearest_edge, wind = coordinate_fields()
    threshold_rows: dict[str, object] = {}
    arrival_maps: dict[str, np.ndarray] = {}
    for threshold in ARRIVAL_THRESHOLDS:
        arrival = arrival_map(series, threshold)
        arrival_maps[f"{threshold:.2f}"] = arrival
        residual = linear_residual(arrival, wind, series.observable)
        threshold_rows[f"{threshold:.2f}"] = {
            "nearest_edge_correlation": correlation(arrival, nearest_edge, series.observable),
            "direction_correlation": correlation(arrival, wind, series.observable),
            "edge_correlation_after_direction": correlation(
                residual, nearest_edge, series.observable
            ),
            "side_lead": side_lead_metrics(arrival, series.observable),
        }

    centroid_rows = []
    for progress in DETAIL_PROGRESSES:
        index = int(np.argmin(np.abs(PROGRESSES - progress)))
        retention = np.nan_to_num(series.retention[index], nan=0.0)
        total = max(float(retention.sum()), 1e-9)
        yy, xx = np.mgrid[0:GRID_ROWS, 0:GRID_COLUMNS]
        u = (xx + 0.5) / GRID_COLUMNS
        v = (yy + 0.5) / GRID_ROWS
        radians = math.radians(MODEL_SCENARIO.angle_degrees)
        projection = (u - 0.5) * math.cos(radians) + (v - 0.5) * math.sin(radians)
        centroid_rows.append({
            "progress": progress,
            "retained_mass": float(retention.sum() / max(series.observable.sum(), 1)),
            "centroid_u": float((retention * u).sum() / total),
            "centroid_v": float((retention * v).sum() / total),
            "centroid_wind_projection": float((retention * projection).sum() / total),
        })
    return {
        "thresholds": threshold_rows,
        "centroids": centroid_rows,
        "arrival_maps": arrival_maps,
    }


def field_grid(renderer: CurtainRenderer) -> np.ndarray:
    radians = math.radians(MODEL_SCENARIO.angle_degrees)
    direction = (math.cos(radians), math.sin(radians))
    card_width = 1.48
    card_height = card_width * renderer.snapshot.height / renderer.snapshot.width
    delay, _ = build_release_field_data(
        card_width, card_height, *direction, MODEL_SCENARIO.seed
    )
    return cv2.resize(
        delay.astype(np.float32),
        (GRID_COLUMNS, GRID_ROWS),
        interpolation=cv2.INTER_AREA,
    ).astype(np.float64)


def heatmap(values: np.ndarray, title: str, observable: np.ndarray | None = None) -> Image.Image:
    valid = np.isfinite(values) if observable is None else observable & np.isfinite(values)
    low = float(np.nanpercentile(values[valid], 4)) if np.any(valid) else 0.0
    high = float(np.nanpercentile(values[valid], 96)) if np.any(valid) else 1.0
    normalized = np.clip((values - low) / max(high - low, 1e-9), 0.0, 1.0)
    pixels = (np.nan_to_num(normalized, nan=0.0) * 255.0).astype(np.uint8)
    colored = cv2.applyColorMap(pixels, cv2.COLORMAP_TURBO)
    colored = cv2.cvtColor(colored, cv2.COLOR_BGR2RGB)
    if observable is not None:
        colored[~observable] = (18, 20, 26)
    image = Image.fromarray(colored).resize((450, 330), Image.Resampling.NEAREST)
    canvas = Image.new("RGB", (450, 366), (14, 16, 21))
    canvas.paste(image, (0, 36))
    draw = ImageDraw.Draw(canvas)
    draw.text((8, 8), f"{title}  蓝=早 红=晚", font=font(17), fill=(240, 242, 246))
    return canvas


def mask_panel(series: GridSeries, progress: float, title: str) -> Image.Image:
    index = int(np.argmin(np.abs(PROGRESSES - progress)))
    values = np.nan_to_num(series.retention[index], nan=0.0)
    pixels = (np.clip(values, 0.0, 1.0) * 255.0).astype(np.uint8)
    colored = np.zeros((GRID_ROWS, GRID_COLUMNS, 3), dtype=np.uint8)
    colored[:, :, 1] = pixels
    colored[:, :, 2] = 255 - pixels
    colored[~series.observable] = (18, 20, 26)
    image = Image.fromarray(colored).resize((225, 165), Image.Resampling.NEAREST)
    canvas = Image.new("RGB", (225, 192), (14, 16, 21))
    canvas.paste(image, (0, 27))
    ImageDraw.Draw(canvas).text((6, 5), title, font=font(13), fill=(235, 237, 242))
    return canvas


def serializable_metrics(metrics: dict[str, object]) -> dict[str, object]:
    output = dict(metrics)
    output["arrival_maps"] = {
        key: np.where(np.isfinite(value), value, -1.0).round(4).tolist()
        for key, value in metrics["arrival_maps"].items()
    }
    return output


def analyse(reference_path: Path) -> dict[str, object]:
    frames, fps = read_frames(reference_path)
    baseline = np.median(
        np.stack(frames[int(round(2.90 * fps)) : int(round(3.16 * fps))]), axis=0
    ).astype(np.uint8)
    background = np.median(
        np.stack(
            frames[int(round(7.85 * fps)) : min(len(frames), int(round(8.16 * fps)))]
        ),
        axis=0,
    ).astype(np.uint8)
    renderer = CurtainRenderer()

    reference_baseline = reference_layers(baseline, baseline, background).intact
    model_baseline = model_layers(renderer, 0.0).intact
    reference_frames = []
    model_frames = []
    for progress in PROGRESSES:
        reference_time = REFERENCE_ONSET_S + float(progress) * (
            REFERENCE_FINISH_S - REFERENCE_ONSET_S
        )
        reference_frames.append(
            reference_layers(frame_at(frames, fps, reference_time), baseline, background).intact
        )
        model_frames.append(model_layers(renderer, float(progress)).intact)

    reference_series = grid_series(reference_frames, reference_baseline)
    model_series = grid_series(model_frames, model_baseline)
    reference_metrics = topology_metrics(reference_series)
    model_metrics = topology_metrics(model_series)
    raw_field = field_grid(renderer)
    _, _, nearest_edge, wind = coordinate_fields()
    raw_residual = linear_residual(raw_field, wind, np.ones_like(raw_field, dtype=bool))
    raw_metrics = {
        "nearest_edge_correlation": correlation(
            raw_field, nearest_edge, np.ones_like(raw_field, dtype=bool)
        ),
        "direction_correlation": correlation(
            raw_field, wind, np.ones_like(raw_field, dtype=bool)
        ),
        "edge_correlation_after_direction": correlation(
            raw_residual, nearest_edge, np.ones_like(raw_field, dtype=bool)
        ),
        "side_lead": side_lead_metrics(
            raw_field, np.ones_like(raw_field, dtype=bool)
        ),
    }

    sheet = Image.new("RGB", (450 * 4, 366 + 192 * 4), (14, 16, 21))
    reference_arrival = reference_metrics["arrival_maps"]["0.52"]
    model_arrival = model_metrics["arrival_maps"]["0.52"]
    panels = (
        heatmap(reference_arrival, "参考：本体保留率降至 52%", reference_series.observable),
        heatmap(model_arrival, "当前模型：本体保留率降至 52%", model_series.observable),
        heatmap(raw_field, "当前原始 release delay"),
        heatmap(model_arrival - reference_arrival, "模型 - 参考（仅作诊断）", reference_series.observable & model_series.observable),
    )
    for column, panel in enumerate(panels):
        sheet.paste(panel, (column * 450, 0))
    for row, progress in enumerate(DETAIL_PROGRESSES[::2]):
        items = (
            mask_panel(reference_series, progress, f"参考 t={progress:.2f}"),
            mask_panel(model_series, progress, f"模型 t={progress:.2f}"),
            mask_panel(reference_series, DETAIL_PROGRESSES[row * 2 + 1], f"参考 t={DETAIL_PROGRESSES[row * 2 + 1]:.2f}"),
            mask_panel(model_series, DETAIL_PROGRESSES[row * 2 + 1], f"模型 t={DETAIL_PROGRESSES[row * 2 + 1]:.2f}"),
        )
        for column, panel in enumerate(items):
            sheet.paste(panel, (column * 225 + 450, 366 + row * 192))

    output_image = OUT / "particleization-topology-diagnostic.png"
    output_json = OUT / "particleization-topology-analysis.json"
    sheet.save(output_image)
    result = {
        "reference": serializable_metrics(reference_metrics),
        "model": serializable_metrics(model_metrics),
        "raw_release_field": raw_metrics,
        "outputs": [str(output_image), str(output_json)],
    }
    output_json.write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return result


def validate(result: dict[str, object]) -> None:
    reference = result["reference"]["thresholds"]["0.52"]
    model = result["model"]["thresholds"]["0.52"]
    raw = result["raw_release_field"]
    failures = []
    # 这是针对当前缺陷的预修复回归：模型不能比参考多出明显的“去除方向趋势后，
    # 越靠外边缘越早”相关性；四条边中也不能有三条以上形成大范围持续领先。
    if model["edge_correlation_after_direction"] > (
        reference["edge_correlation_after_direction"] + 0.16
    ):
        failures.append("渲染后的完整层仍受最近边缘距离显著支配")
    if raw["side_lead"]["sides_with_broad_inward_lead"] >= 3:
        failures.append("原始释放场仍有至少三条边持续向内领先")
    if failures:
        raise SystemExit("；".join(failures))
    print("particleization topology regression: PASS")


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
