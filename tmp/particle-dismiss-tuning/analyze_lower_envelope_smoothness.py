# -*- coding: utf-8 -*-
"""检查中段右下高密粒群外包络的突出幅度与曲率连续性。

用户看到的缺陷位于 `visual-band` 层，而不是仍未粒子化的表面。这里先把高密粒子按
控件坐标归一化，在右下区域逐列求加权下包络，再分别测量：

1. 相对首尾弦线的最大下凸深度；
2. 去除单点粒子噪声后，相对单一低频曲线的残差；
3. 曲率变化的 P95，识别多段曲线机械拼接形成的折点。

参考侧使用相同的密度、连通域和包络提取，不把屏摄噪声或内容纹理当成形态优势。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw
from skimage.measure import label, regionprops

from analyze_reference_morphology import (
    CANVAS_BOUNDS,
    CANVAS_SIZE,
    MODEL_SCENARIO,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    REFERENCE_SOURCE_BOX,
    SOURCE_PIXELS,
    density_field,
    frame_at,
    read_frames,
    reference_layers,
    to_normalized_canvas,
)
from render_curtain_model import CurtainRenderer, OUT, font


PROGRESSES = (0.40, 0.47, 0.54)
ROI_SOURCE = (0.40, 0.43, 1.08, 1.13)


def source_index_x(value: float) -> int:
    lo_x, _, hi_x, _ = CANVAS_BOUNDS
    return int(round((value - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))


def source_index_y(value: float) -> int:
    _, lo_y, _, hi_y = CANVAS_BOUNDS
    return int(round((value - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))


def gaussian_1d(values: np.ndarray, sigma: float) -> np.ndarray:
    if len(values) < 3:
        return values.copy()
    column = values.astype(np.float32)[:, None]
    return cv2.GaussianBlur(
        column, (1, 0), sigmaX=0.0, sigmaY=sigma
    )[:, 0].astype(np.float64)


def weighted_quantile_y(weights: np.ndarray, y_indices: np.ndarray, quantile: float) -> float:
    total = float(weights.sum())
    if total <= 1e-8:
        return float("nan")
    cumulative = np.cumsum(weights)
    position = int(np.searchsorted(cumulative, total * quantile))
    return float(y_indices[min(position, len(y_indices) - 1)])


def largest_component(binary: np.ndarray) -> np.ndarray:
    labelled = label(binary, connectivity=2)
    regions = regionprops(labelled)
    if not regions:
        return np.zeros_like(binary, dtype=bool)
    largest = max(regions, key=lambda item: item.area)
    return labelled == largest.label


def extract_envelope(particle: np.ndarray) -> tuple[dict[str, float], np.ndarray, np.ndarray, np.ndarray]:
    density = density_field(particle)
    x0, y0, x1, y1 = ROI_SOURCE
    ix0, ix1 = source_index_x(x0), source_index_x(x1)
    iy0, iy1 = source_index_y(y0), source_index_y(y1)
    roi = density[iy0:iy1, ix0:ix1]
    active_values = roi[roi > 0.004]
    if active_values.size < 64:
        raise RuntimeError("右下高密区域不足，无法提取包络")

    # 用中高密主体而非最亮 20% 提取外边缘。闭运算尺寸约为控件宽度的 2%，
    # 只连接粒子采样孔隙，不会抹掉用户指出的宏观鼓包。
    threshold = max(0.010, float(np.percentile(active_values, 58)))
    support = (density >= threshold).astype(np.uint8)
    support[:iy0, :] = 0
    support[iy1:, :] = 0
    support[:, :ix0] = 0
    support[:, ix1:] = 0
    kernel_size = max(5, int(round(SOURCE_PIXELS * 0.020)) | 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    support = cv2.morphologyEx(support, cv2.MORPH_CLOSE, kernel) > 0
    component = largest_component(support)

    xs: list[int] = []
    ys: list[float] = []
    y_indices = np.arange(iy0, iy1, dtype=np.float64)
    for x in range(ix0, ix1):
        weights = density[iy0:iy1, x] * component[iy0:iy1, x]
        y = weighted_quantile_y(weights, y_indices, 0.90)
        if np.isfinite(y):
            xs.append(x)
            ys.append(y)
    if len(xs) < int(SOURCE_PIXELS * 0.28):
        raise RuntimeError("右下高密包络跨度不足")

    x_values = np.asarray(xs, dtype=np.float64)
    y_values = np.asarray(ys, dtype=np.float64)
    full_x = np.arange(xs[0], xs[-1] + 1, dtype=np.float64)
    interpolated = np.interp(full_x, x_values, y_values)
    local_curve = gaussian_1d(interpolated, SOURCE_PIXELS * 0.010)
    global_curve = gaussian_1d(local_curve, SOURCE_PIXELS * 0.055)

    endpoint_count = max(3, int(round(len(full_x) * 0.08)))
    start_y = float(np.median(local_curve[:endpoint_count]))
    end_y = float(np.median(local_curve[-endpoint_count:]))
    chord = np.linspace(start_y, end_y, len(local_curve))
    protrusion = float(np.max(local_curve - chord)) / SOURCE_PIXELS
    roughness = float(np.sqrt(np.mean(np.square(local_curve - global_curve)))) / SOURCE_PIXELS

    # 在控件归一化坐标中计算曲率变化；P95 对单个像素残差不敏感，但会对
    # 连续几个网格段的切线突变做出响应。
    x_source = full_x / SOURCE_PIXELS
    y_source = local_curve / SOURCE_PIXELS
    slope = np.gradient(y_source, x_source)
    curvature = np.gradient(slope, x_source) / np.power(1.0 + slope * slope, 1.5)
    curvature = gaussian_1d(curvature, SOURCE_PIXELS * 0.008)
    curvature_step = np.abs(np.diff(curvature))
    curvature_step_p95 = float(np.percentile(curvature_step, 95)) if curvature_step.size else 0.0
    curvature_total_variation = float(curvature_step.sum()) / max(len(curvature_step), 1)

    metrics = {
        "threshold": threshold,
        "span_source": float(full_x[-1] - full_x[0]) / SOURCE_PIXELS,
        "protrusion_source": protrusion,
        "roughness_source": roughness,
        "curvature_step_p95": curvature_step_p95,
        "curvature_total_variation": curvature_total_variation,
    }
    points = np.column_stack((full_x, local_curve))
    return metrics, density, component, points


def model_band(renderer: CurtainRenderer, progress: float) -> np.ndarray:
    box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    alpha = renderer.render_diagnostic_rgba(
        MODEL_SCENARIO, progress, "visual-band"
    )[:, :, 3].astype(np.float32) / 255.0
    return to_normalized_canvas(alpha, box)


def diagnostic_panel(
    particle: np.ndarray,
    component: np.ndarray,
    points: np.ndarray,
    title: str,
) -> Image.Image:
    density = density_field(particle)
    scale_values = density[density > 0.004]
    scale = float(np.percentile(scale_values, 98)) if scale_values.size else 1.0
    heat = cv2.applyColorMap(
        (np.clip(density / max(scale, 1e-6), 0.0, 1.0) * 255).astype(np.uint8),
        cv2.COLORMAP_TURBO,
    )
    heat = cv2.cvtColor(heat, cv2.COLOR_BGR2RGB)
    contours, _ = cv2.findContours(
        component.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE
    )
    cv2.drawContours(heat, contours, -1, (255, 255, 255), 1, cv2.LINE_AA)
    image = Image.fromarray(heat)
    draw = ImageDraw.Draw(image)
    draw.line([(float(x), float(y)) for x, y in points], fill=(255, 48, 48), width=3)
    draw.rectangle(
        (
            source_index_x(ROI_SOURCE[0]),
            source_index_y(ROI_SOURCE[1]),
            source_index_x(ROI_SOURCE[2]),
            source_index_y(ROI_SOURCE[3]),
        ),
        outline=(255, 255, 255),
        width=1,
    )
    draw.rectangle((0, 0, CANVAS_SIZE, 34), fill=(10, 12, 17))
    draw.text((8, 7), title, font=font(18), fill=(238, 240, 245))
    return image


def analyse(reference_path: Path) -> dict[str, object]:
    frames, fps = read_frames(reference_path)
    baseline = np.median(
        np.stack(frames[int(round(2.90 * fps)) : int(round(3.16 * fps))]), axis=0
    ).astype(np.uint8)
    background = np.median(
        np.stack(frames[int(round(7.85 * fps)) : min(len(frames), int(round(8.16 * fps)))]),
        axis=0,
    ).astype(np.uint8)
    renderer = CurtainRenderer()
    rows: list[dict[str, object]] = []
    panels: list[Image.Image] = []
    for progress in PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (
            REFERENCE_FINISH_S - REFERENCE_ONSET_S
        )
        reference = reference_layers(
            frame_at(frames, fps, reference_time), baseline, background
        ).particle
        model = model_band(renderer, progress)
        reference_metrics, _, reference_component, reference_points = extract_envelope(reference)
        model_metrics, _, model_component, model_points = extract_envelope(model)
        rows.append({
            "progress": progress,
            "reference": reference_metrics,
            "model": model_metrics,
        })
        panels.extend((
            diagnostic_panel(reference, reference_component, reference_points, f"参考 t={progress:.2f}"),
            diagnostic_panel(model, model_component, model_points, f"模型 t={progress:.2f}"),
        ))

    sheet = Image.new("RGB", (CANVAS_SIZE * 2, CANVAS_SIZE * len(PROGRESSES)), (10, 12, 17))
    for index, panel in enumerate(panels):
        row = index // 2
        column = index % 2
        sheet.paste(panel, (column * CANVAS_SIZE, row * CANVAS_SIZE))
    output_image = OUT / "lower-envelope-smoothness.png"
    sheet.save(output_image)
    result = {"frames": rows, "output": str(output_image)}
    (OUT / "lower-envelope-smoothness.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return result


def validate(result: dict[str, object]) -> None:
    failures: list[str] = []
    for row in result["frames"]:
        progress = float(row["progress"])
        reference = row["reference"]
        model = row["model"]
        if model["protrusion_source"] > reference["protrusion_source"] * 1.65 + 0.030:
            failures.append(f"t={progress:.2f} 下凸包突出过深")
        if model["roughness_source"] > reference["roughness_source"] * 1.45 + 0.004:
            failures.append(f"t={progress:.2f} 下凸包偏离单一低频曲线")
        if model["curvature_step_p95"] > reference["curvature_step_p95"] * 1.55 + 0.08:
            failures.append(f"t={progress:.2f} 下凸包存在拼接式曲率跳变")
        # 参考侧屏摄噪声会抬高局部曲率指标，因此另对 deterministic 模型的
        # 关键中段施加绝对上限：避免重新叠加多个脱离年龄层或截断弓形。
        if abs(progress - 0.47) < 1e-6:
            if model["protrusion_source"] > 0.110:
                failures.append("t=0.47 下凸包超过中段最大允许深度")
            if model["roughness_source"] > 0.016:
                failures.append("t=0.47 下缘偏离单一连续低频曲线")
            if model["curvature_total_variation"] > 0.82:
                failures.append("t=0.47 下缘仍由多个曲率段拼接")
    if failures:
        raise SystemExit("；".join(failures))
    print("lower envelope smoothness regression: PASS")


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
