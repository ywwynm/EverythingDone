"""检查桌面模拟器的缩放栅格与内容权重是否会低估 Android 粒子密度。"""

from __future__ import annotations

import json

import cv2
import numpy as np
from PIL import Image, ImageDraw

import render_curtain_model as model


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def content_weights(snapshot: Image.Image, panel_color: tuple[float, float, float]) -> dict[str, float]:
    rgba = np.asarray(snapshot).astype(np.float32) / 255.0
    rgb = rgba[:, :, :3]
    visible = rgba[:, :, 3] >= 0.5
    panel = np.asarray(panel_color, dtype=np.float32)
    color_distance = np.linalg.norm(rgb - panel, axis=2) * 0.5774
    saturation = rgb.max(axis=2) - rgb.min(axis=2)
    saturation_weight = smoothstep(0.18, 0.42, saturation)
    legacy = np.minimum(
        smoothstep(0.08, 0.42, color_distance) * 0.58 + saturation_weight * 0.52,
        1.0,
    )
    deployed = saturation_weight
    neutral = visible & (saturation < 0.08)
    return {
        "visible_mean_deployed": float(deployed[visible].mean()),
        "neutral_mean_deployed": float(deployed[neutral].mean()),
        "neutral_boosted_fraction_deployed": float((deployed[neutral] >= 0.50).mean()),
        "visible_mean_legacy": float(legacy[visible].mean()),
        "neutral_mean_legacy": float(legacy[neutral].mean()),
        "neutral_boosted_fraction_legacy": float((legacy[neutral] >= 0.50).mean()),
    }


def grayscale_stress_snapshot() -> Image.Image:
    width, height = 720, 420
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((1, 1, width - 2, height - 2), radius=38, fill=(245, 246, 248, 255))
    for index in range(9):
        y = 34 + index * 38
        shade = 48 + index * 13
        draw.rounded_rectangle((38, y, 682, y + 24), radius=10, fill=(shade, shade, shade, 255))
        draw.rectangle((58, y + 7, 420 + (index % 3) * 70, y + 13), fill=(205, 205, 205, 255))
    return image


def render_scale_profile(scale: int, base_snapshot: Image.Image) -> dict[str, float]:
    old = {
        "VIEW_W": model.VIEW_W,
        "VIEW_H": model.VIEW_H,
        "DENSITY": model.DENSITY,
        "CELL_PX": model.CELL_PX,
        "DRIFT_PX": model.DRIFT_PX,
        "NOISE_SCALE_PX": model.NOISE_SCALE_PX,
        "make_snapshot": model.make_snapshot,
    }
    try:
        model.VIEW_W = 640 * scale
        model.VIEW_H = 450 * scale
        model.DENSITY = 1.2 * scale
        model.CELL_PX = 1.1 * model.DENSITY
        model.DRIFT_PX = 210.0 * model.DENSITY
        model.NOISE_SCALE_PX = 120.0 * model.DENSITY
        target_snapshot = base_snapshot.resize((360 * scale, 210 * scale), Image.Resampling.LANCZOS)
        model.make_snapshot = lambda: target_snapshot
        renderer = model.CurtainRenderer()
        scenario = model.Scenario(f"scale-{scale}", -128.0, 42)
        rgba = renderer.render_particle_rgba(scenario, 0.47)
        native_values = rgba[:, :, 3].astype(np.float32) / 255.0
        alpha = Image.fromarray(rgba[:, :, 3], "L")
        if scale != 1:
            alpha = alpha.resize((640, 450), Image.Resampling.LANCZOS)
        values = np.asarray(alpha, dtype=np.float32) / 255.0
        local = cv2.boxFilter(values, -1, (9, 9), normalize=True)
        return {
            "scale": float(scale),
            "cell_px_uniform": float(renderer.program["uCellPx"].value),
            "grid": [renderer.cols, renderer.rows],
            "native_mean_alpha": float(native_values.mean()),
            "native_strong_fraction": float((native_values >= 0.18).mean()),
            "mean_alpha": float(values.mean()),
            "strong_fraction": float((values >= 0.18).mean()),
            "local_density_p95": float(np.percentile(local, 95)),
            "nonzero_p95": float(np.percentile(values[values > 0], 95)),
        }
    finally:
        for name, value in old.items():
            setattr(model, name, value)


def relative_range(values: list[float]) -> float:
    mean = float(np.mean(values))
    return (max(values) - min(values)) / max(mean, 1e-8)


def validate(result: dict[str, object]) -> None:
    # scale=2 是桌面蓝本默认 2.4 density，scale=3 覆盖常见高密度 Android。
    # scale=1 会受到 OpenGL 最小 1px 点径限制，只作为降级信息保留，不纳入
    # Android 一致性断言。
    comparable = result["raster_profiles"][1:]
    for key in ("native_mean_alpha", "native_strong_fraction", "local_density_p95"):
        spread = relative_range([float(profile[key]) for profile in comparable])
        if spread > 0.03:
            raise SystemExit(f"桌面/Android 尺度回归：{key} 相对极差={spread:.2%}")

    classifiers = result["content_classifier"]
    grayscale = classifiers["grayscale_stress"]
    if float(grayscale["neutral_boosted_fraction_deployed"]) > 1e-6:
        raise SystemExit("灰色内容被误判为彩色强调区域")
    desktop = classifiers["desktop_mock"]
    if float(desktop["visible_mean_deployed"]) < 0.08:
        raise SystemExit("真实彩色内容没有获得粒子数量强调")


def main() -> None:
    base_snapshot = model.make_snapshot()
    panel = model.PANEL_COLOR
    result = {
        "raster_profiles": [render_scale_profile(scale, base_snapshot) for scale in (1, 2, 3)],
        "content_classifier": {
            "desktop_mock": content_weights(base_snapshot, panel),
            "grayscale_stress": content_weights(grayscale_stress_snapshot(), panel),
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    validate(result)


if __name__ == "__main__":
    main()
