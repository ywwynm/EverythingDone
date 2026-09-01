# -*- coding: utf-8 -*-
"""测量留存表面的方向迁移与粒子包络的矩形材料锁定。"""

from __future__ import annotations

import argparse
import json
import math
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
    SOURCE_PIXELS,
    density_field,
    frame_at,
    model_layers,
    read_frames,
    reference_layers,
)
from physical_release_field import build_release_field_data
from render_curtain_model import CurtainRenderer, OUT, font


PROGRESSES = (0.24, 0.32, 0.40, 0.47, 0.54, 0.62, 0.70)


def normalized_coordinates() -> tuple[np.ndarray, np.ndarray]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    xs = np.linspace(lo_x, hi_x, CANVAS_SIZE, endpoint=False, dtype=np.float64)
    ys = np.linspace(lo_y, hi_y, CANVAS_SIZE, endpoint=False, dtype=np.float64)
    return np.meshgrid(xs, ys)


def weighted_centroid(mask: np.ndarray) -> dict[str, float]:
    weights = np.maximum(mask.astype(np.float64), 0.0)
    total = float(weights.sum())
    if total <= 1e-9:
        return {"x": 0.5, "y": 0.5, "wind_projection": 0.0, "mass": 0.0}
    xx, yy = normalized_coordinates()
    x = float((weights * xx).sum() / total)
    y = float((weights * yy).sum() / total)
    radians = math.radians(MODEL_SCENARIO.angle_degrees)
    wind_projection = (x - 0.5) * math.cos(radians) + (y - 0.5) * math.sin(radians)
    return {"x": x, "y": y, "wind_projection": wind_projection, "mass": total}


def largest_support(mask: np.ndarray) -> np.ndarray:
    binary = mask.astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    if count <= 1:
        return np.zeros_like(binary)
    label_index = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    return (labels == label_index).astype(np.uint8)


def envelope_metrics(particle: np.ndarray) -> dict[str, float]:
    density = density_field(particle)
    active = density[density > 0.006]
    if active.size < 64:
        return {
            "oriented_rectangularity": 0.0,
            "convex_fill": 0.0,
            "axis_locked_edge_share": 0.0,
            **weighted_centroid(density),
        }
    threshold = max(0.0075, float(np.percentile(active, 35)))
    radius = max(1, int(round(SOURCE_PIXELS * 0.006)))
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (radius * 2 + 1, radius * 2 + 1))
    support = cv2.morphologyEx((density >= threshold).astype(np.uint8), cv2.MORPH_CLOSE, kernel)
    support = largest_support(support)
    contours, _ = cv2.findContours(support, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return {
            "oriented_rectangularity": 0.0,
            "convex_fill": 0.0,
            "axis_locked_edge_share": 0.0,
            **weighted_centroid(density),
        }
    contour = max(contours, key=cv2.contourArea)
    area = float(cv2.contourArea(contour))
    rect = cv2.minAreaRect(contour)
    rect_area = max(float(rect[1][0] * rect[1][1]), 1.0)
    hull_area = max(float(cv2.contourArea(cv2.convexHull(contour))), 1.0)

    points = contour[:, 0, :].astype(np.float64)
    if len(points) >= 25:
        stride = max(3, len(points) // 90)
        tangent = np.roll(points, -stride, axis=0) - np.roll(points, stride, axis=0)
        angles = np.arctan2(tangent[:, 1], tangent[:, 0])
        base = math.radians(rect[2])
        relative = np.mod(angles - base, math.pi / 2.0)
        distance_to_axis = np.minimum(relative, math.pi / 2.0 - relative)
        axis_share = float(np.mean(distance_to_axis <= math.radians(12.0)))
    else:
        axis_share = 0.0
    return {
        "oriented_rectangularity": area / rect_area,
        "convex_fill": area / hull_area,
        "axis_locked_edge_share": axis_share,
        **weighted_centroid(density),
    }


def release_field_metrics(renderer: CurtainRenderer) -> dict[str, object]:
    radians = math.radians(MODEL_SCENARIO.angle_degrees)
    direction = (math.cos(radians), math.sin(radians))
    card_width = 1.48
    card_height = card_width * renderer.snapshot.height / renderer.snapshot.width
    delay, _ = build_release_field_data(card_width, card_height, *direction, MODEL_SCENARIO.seed)
    ys, xs = np.mgrid[0 : delay.shape[0], 0 : delay.shape[1]]
    u = xs / (delay.shape[1] - 1)
    v = ys / (delay.shape[0] - 1)
    projection = (u - 0.5) * direction[0] + (v - 0.5) * direction[1]

    rows: dict[str, object] = {}
    for progress in PROGRESSES:
        remaining = np.clip((delay - progress) / 0.120, 0.0, 1.0)
        total = max(float(remaining.sum()), 1e-9)
        rows[f"{progress:.2f}"] = {
            "remaining_ratio": float(np.mean(delay > progress)),
            "remaining_centroid_projection": float((remaining * projection).sum() / total),
        }
    late = delay >= float(np.quantile(delay, 0.90))
    return {
        "latest_10pct_centroid_projection": float(np.mean(projection[late])),
        "latest_sample_uv": [
            float(u.flat[int(np.argmax(delay))]),
            float(v.flat[int(np.argmax(delay))]),
        ],
        "frames": rows,
    }


def diagnostic_panel(intact: np.ndarray, particle: np.ndarray, title: str) -> Image.Image:
    density = density_field(particle)
    active = density[density > 0.006]
    scale = float(np.percentile(active, 98)) if active.size else 1.0
    image = np.zeros((CANVAS_SIZE, CANVAS_SIZE, 3), dtype=np.uint8)
    image[:, :, 0] = np.clip(density / max(scale, 1e-6) * 255.0, 0, 255).astype(np.uint8)
    image[:, :, 1] = np.clip(intact * 220.0, 0, 255).astype(np.uint8)
    pil = Image.fromarray(image).resize((310, 310), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (310, 338), (14, 16, 21))
    canvas.paste(pil, (0, 28))
    draw = ImageDraw.Draw(canvas)
    draw.text((6, 5), title, font=font(14), fill=(235, 237, 242))
    centroid = weighted_centroid(intact)
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    cx = int((centroid["x"] - lo_x) / (hi_x - lo_x) * 310)
    cy = 28 + int((centroid["y"] - lo_y) / (hi_y - lo_y) * 310)
    draw.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=(255, 220, 0))
    return canvas


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
    result: dict[str, object] = {
        "release_field": release_field_metrics(renderer),
        "frames": [],
    }
    sheet = Image.new("RGB", (310 * len(PROGRESSES), 338 * 2), (14, 16, 21))
    for column, progress in enumerate(PROGRESSES):
        reference_time = REFERENCE_ONSET_S + progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
        reference = reference_layers(frame_at(frames, fps, reference_time), baseline, background)
        model = model_layers(renderer, progress)
        result["frames"].append(
            {
                "progress": progress,
                "reference": {
                    "intact": weighted_centroid(reference.intact),
                    "envelope": envelope_metrics(reference.particle),
                },
                "model": {
                    "intact": weighted_centroid(model.intact),
                    "envelope": envelope_metrics(model.particle),
                },
            }
        )
        sheet.paste(diagnostic_panel(reference.intact, reference.particle, f"参考 t={progress:.2f}"), (column * 310, 0))
        sheet.paste(diagnostic_panel(model.intact, model.particle, f"模型 t={progress:.2f}"), (column * 310, 338))
    output_image = OUT / "directional-topology-diagnostic.png"
    output_json = OUT / "directional-topology-analysis.json"
    sheet.save(output_image)
    result["outputs"] = [str(output_image), str(output_json)]
    output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def validate(result: dict[str, object]) -> None:
    failures: list[str] = []
    release = result["release_field"]
    # 释放场只有 49×33 节点，末 10% 质心的单节点量化约为 0.01；0.13 已
    # 足以排除“最后留在几何中心”，同时不把 0.1386 这类明确顺风偏移误报。
    if release["latest_10pct_centroid_projection"] < 0.13:
        failures.append("释放场末段仍停在中心，未沿主风迁移到顺风侧")
    for row in result["frames"]:
        progress = float(row["progress"])
        if progress < 0.47 or progress > 0.62:
            continue
        reference = row["reference"]
        model = row["model"]
        if model["intact"]["wind_projection"] < reference["intact"]["wind_projection"] - 0.10:
            failures.append(f"t={progress:.2f} 留存表面沿主风迁移不足")
        if model["envelope"]["axis_locked_edge_share"] > (
            reference["envelope"]["axis_locked_edge_share"] + 0.12
        ):
            failures.append(f"t={progress:.2f} 粒子包络仍过度保持矩形材料边")
    if failures:
        raise SystemExit("；".join(failures))
    print("directional topology regression: PASS")


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
