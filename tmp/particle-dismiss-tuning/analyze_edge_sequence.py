"""逐帧比较参考与 canonical 模型在四条边缘附近的粒子形态。

参考侧用消失完成后的静态桌面作背景差分，并排除局部占用率很高的完整表面；模型侧直接
渲染粒子 alpha。所有距离先除以各自控件宽高，避免手机画面与桌面 Dialog 尺寸不同。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

from analyze_reference_motion import foreground_mask, frame_at, read_frames
from render_curtain_model import CurtainRenderer, FPS, OUT, Scenario, font


REFERENCE_ONSET_S = 3.35
REFERENCE_FINISH_S = 8.05
REFERENCE_SOURCE_BOX = (130, 265, 460, 467)
PROGRESSES = tuple(index / FPS for index in range(FPS + 1))
SHEET_PROGRESSES = tuple(index / 10.0 for index in range(11))


def particle_mask_from_reference(
    frame: np.ndarray,
    background: np.ndarray,
    analysis_roi: tuple[int, int, int, int],
) -> np.ndarray:
    mask = foreground_mask(frame, background, analysis_roi)
    occupancy = cv2.boxFilter(mask.astype(np.float32), -1, (19, 19), normalize=True)
    return (mask > 0) & (occupancy < 0.72)


def edge_metrics(
    weight: np.ndarray,
    source_box: tuple[int, int, int, int],
) -> dict[str, float]:
    x, y, w, h = source_box
    height, width = weight.shape
    visible = weight > 0.035

    def weighted_mass(x0: float, y0: float, x1: float, y1: float) -> float:
        ix0 = max(0, int(round(x0)))
        iy0 = max(0, int(round(y0)))
        ix1 = min(width, int(round(x1)))
        iy1 = min(height, int(round(y1)))
        return float(weight[iy0:iy1, ix0:ix1].sum())

    def percentile_distance(axis: str) -> float:
        yy, xx = np.nonzero(visible)
        if axis == "top":
            selected = (xx >= x) & (xx < x + w) & (yy < y)
            values = (y - yy[selected]) / max(h, 1)
        elif axis == "bottom":
            selected = (xx >= x) & (xx < x + w) & (yy >= y + h)
            values = (yy[selected] - (y + h)) / max(h, 1)
        elif axis == "left":
            selected = (yy >= y) & (yy < y + h) & (xx < x)
            values = (x - xx[selected]) / max(w, 1)
        else:
            selected = (yy >= y) & (yy < y + h) & (xx >= x + w)
            values = (xx[selected] - (x + w)) / max(w, 1)
        return float(np.percentile(values, 95)) if values.size else 0.0

    # 左上边缘的宽粒云与右上突兀尖端分开统计。区域都包含边界内外，避免仅用
    # 越界距离把仍贴着边缘的有效粒群漏掉。
    top_left_mass = weighted_mass(x - 0.10 * w, y - 0.18 * h, x + 0.38 * w, y + 0.20 * h)
    top_right_mass = weighted_mass(x + 0.62 * w, y - 0.25 * h, x + 1.10 * w, y + 0.20 * h)
    lower_left_mass = weighted_mass(x - 0.12 * w, y + 0.56 * h, x + 0.52 * w, y + 1.18 * h)

    return {
        "top_protrusion_p95_h": percentile_distance("top"),
        "bottom_protrusion_p95_h": percentile_distance("bottom"),
        "left_protrusion_p95_w": percentile_distance("left"),
        "right_protrusion_p95_w": percentile_distance("right"),
        "top_left_mass": top_left_mass,
        "top_right_mass": top_right_mass,
        "lower_left_mass": lower_left_mass,
        "top_left_to_top_right": top_left_mass / max(top_right_mass, 1e-5),
    }


def ridge_metrics(alpha: np.ndarray, source_box: tuple[int, int, int, int]) -> dict[str, float]:
    """检测高亮层是否收缩成细长连通脊线，而不是有厚度的粒云。"""
    x, y, w, h = source_box
    x0 = max(0, int(x - 0.20 * w))
    y0 = max(0, int(y - 0.25 * h))
    x1 = min(alpha.shape[1], int(x + 1.20 * w))
    y1 = min(alpha.shape[0], int(y + 1.22 * h))
    roi = alpha[y0:y1, x0:x1]
    local = cv2.boxFilter(roi.astype(np.float32), -1, (9, 9), normalize=True)
    threshold = max(0.035, float(np.percentile(local[local > 0.002], 72))) if np.any(local > 0.002) else 1.0
    binary = (local >= threshold).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    best_aspect = 0.0
    best_area = 0.0
    best_thickness = 0.0
    for component in range(1, count):
        area = int(stats[component, cv2.CC_STAT_AREA])
        if area < 24:
            continue
        yy, xx = np.nonzero(labels == component)
        points = np.column_stack((xx, yy)).astype(np.float64)
        covariance = np.cov(points, rowvar=False)
        eigenvalues = np.sort(np.linalg.eigvalsh(covariance))[::-1]
        if eigenvalues[1] <= 1e-5:
            continue
        aspect = float(np.sqrt(eigenvalues[0] / eigenvalues[1]))
        major_span = max(1.0, 4.0 * float(np.sqrt(eigenvalues[0])))
        thickness = area / major_span
        if aspect > best_aspect:
            best_aspect = aspect
            best_area = float(area)
            best_thickness = float(thickness)
    return {
        "threshold": threshold,
        "max_component_aspect": best_aspect,
        "max_component_area": best_area,
        "max_component_mean_thickness_px": best_thickness,
    }


def crop_box(
    source_box: tuple[int, int, int, int],
    side: str,
) -> tuple[float, float, float, float]:
    x, y, w, h = source_box
    if side == "top":
        return x - 0.12 * w, y - 0.25 * h, x + 1.12 * w, y + 0.32 * h
    if side == "bottom":
        return x - 0.12 * w, y + 0.68 * h, x + 1.12 * w, y + 1.25 * h
    if side == "left":
        return x - 0.25 * w, y - 0.10 * h, x + 0.34 * w, y + 1.10 * h
    return x + 0.66 * w, y - 0.10 * h, x + 1.25 * w, y + 1.10 * h


def crop_and_fit(image: Image.Image, box: tuple[float, float, float, float], size: tuple[int, int]) -> Image.Image:
    cropped = image.crop(tuple(map(int, box)))
    return cropped.resize(size, Image.Resampling.LANCZOS)


def save_edge_sheets(
    reference_frames: list[np.ndarray],
    reference_fps: float,
    renderer: CurtainRenderer,
    scenario: Scenario,
) -> None:
    model_box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    for side in ("top", "bottom", "left", "right"):
        cell_size = (210, 132) if side in ("top", "bottom") else (150, 210)
        label_h = 24
        sheet = Image.new(
            "RGB",
            (cell_size[0] * len(SHEET_PROGRESSES), (cell_size[1] + label_h) * 2),
            (17, 19, 24),
        )
        draw = ImageDraw.Draw(sheet)
        for column, progress in enumerate(SHEET_PROGRESSES):
            reference_time = REFERENCE_ONSET_S + progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
            reference_bgr = frame_at(reference_frames, reference_fps, reference_time)
            reference_rgb = Image.fromarray(cv2.cvtColor(reference_bgr, cv2.COLOR_BGR2RGB))
            model = renderer.render(scenario, progress)
            for row, (image, source_box) in enumerate((
                (reference_rgb, REFERENCE_SOURCE_BOX),
                (model, model_box),
            )):
                x = column * cell_size[0]
                y = row * (cell_size[1] + label_h)
                sheet.paste(crop_and_fit(image, crop_box(source_box, side), cell_size), (x, y + label_h))
                prefix = "参考" if row == 0 else "模型"
                draw.text((x + 5, y + 3), f"{prefix} {progress:.1f}", font=font(14), fill=(225, 228, 235))
        sheet.save(OUT / f"edge-sequence-{side}.png")


def analyse(reference_path: Path) -> dict[str, object]:
    reference_frames, reference_fps = read_frames(reference_path)
    bg_start = int(round(7.85 * reference_fps))
    bg_end = min(len(reference_frames), int(round(8.15 * reference_fps)) + 1)
    background = np.median(np.stack(reference_frames[bg_start:bg_end]), axis=0).astype(np.uint8)
    height, width = reference_frames[0].shape[:2]
    analysis_roi = (55, 105, min(610, width - 55), min(800, height - 105))

    renderer = CurtainRenderer()
    scenario = Scenario("comparison-left-up", -128.0, 42)
    model_box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    rows: list[dict[str, object]] = []
    for progress in PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
        reference_frame = frame_at(reference_frames, reference_fps, reference_time)
        reference_mask = particle_mask_from_reference(reference_frame, background, analysis_roi)
        model_alpha = renderer.render_particle_rgba(scenario, progress)[:, :, 3].astype(np.float32) / 255.0
        rows.append({
            "progress": round(progress, 4),
            "reference": edge_metrics(reference_mask.astype(np.float32), REFERENCE_SOURCE_BOX),
            "model": edge_metrics(model_alpha, model_box),
        })

    target_progress = min(PROGRESSES, key=lambda value: abs(value - 0.47))
    reference_time = REFERENCE_ONSET_S + target_progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
    reference_frame = frame_at(reference_frames, reference_fps, reference_time)
    reference_mask = particle_mask_from_reference(reference_frame, background, analysis_roi).astype(np.float32)
    model_band = renderer.render_diagnostic_rgba(
        scenario, target_progress, "visual-band"
    )[:, :, 3].astype(np.float32) / 255.0

    save_edge_sheets(reference_frames, reference_fps, renderer, scenario)
    reference_ridge = ridge_metrics(reference_mask, REFERENCE_SOURCE_BOX)
    model_ridge = ridge_metrics(model_band, model_box)
    reference_ridge["mean_thickness_fraction_w"] = (
        reference_ridge["max_component_mean_thickness_px"] / REFERENCE_SOURCE_BOX[2]
    )
    model_ridge["mean_thickness_fraction_w"] = (
        model_ridge["max_component_mean_thickness_px"] / model_box[2]
    )
    result = {
        "reference": str(reference_path),
        "model": "canonical Shader, comparison-left-up seed=42",
        "target_progress": target_progress,
        "ridge_at_target": {
            "reference_particle_front": reference_ridge,
            "model_band_particles": model_ridge,
        },
        "frames": rows,
    }
    output = OUT / "edge-sequence-metrics.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def validate(result: dict[str, object]) -> None:
    target = min(result["frames"], key=lambda row: abs(row["progress"] - 0.47))
    early = min(result["frames"], key=lambda row: abs(row["progress"] - 0.32))
    reference_ridge = result["ridge_at_target"]["reference_particle_front"]
    ridge = result["ridge_at_target"]["model_band_particles"]
    thickness_fraction = float(ridge["mean_thickness_fraction_w"])
    reference_fraction = float(reference_ridge["mean_thickness_fraction_w"])
    if not reference_fraction * 0.65 <= thickness_fraction <= reference_fraction * 1.55:
        raise SystemExit(
            "帷幔边缘归一化厚度回归："
            f"model={thickness_fraction:.4f}w, reference={reference_fraction:.4f}w"
        )

    model = target["model"]
    if float(model["right_protrusion_p95_w"]) > 0.02:
        raise SystemExit(
            "右上局部粒群再次突兀越界："
            f"{float(model['right_protrusion_p95_w']):.3f}w"
        )
    if not 0.05 <= float(model["top_protrusion_p95_h"]) <= 0.30:
        raise SystemExit("顶部粒群外移距离偏离参考范围")
    # 物理模型的主前沿在 t≈0.47 正经过右上区域，不能用参考素材的
    # 左上/右上质量比强行拟合；这里只锁住左上早发粒群本身的可见质量。
    if float(model["top_left_mass"]) < 50.0:
        raise SystemExit("t=0.47 左上早发粒群不可辨认")
    if float(early["model"]["top_left_mass"]) < 100.0:
        raise SystemExit("t=0.32 左上起始粒群不可辨认")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()
    result = analyse(args.reference)
    target = min(result["frames"], key=lambda row: abs(row["progress"] - 0.47))
    print(json.dumps({
        "target_progress": result["target_progress"],
        "ridge_at_target": result["ridge_at_target"],
        "edge_at_target": target,
        "outputs": [str(OUT / f"edge-sequence-{side}.png") for side in ("top", "bottom", "left", "right")],
    }, ensure_ascii=False, indent=2))
    validate(result)


if __name__ == "__main__":
    main()
