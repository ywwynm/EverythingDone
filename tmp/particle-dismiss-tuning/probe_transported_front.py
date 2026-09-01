"""探测“贴附接缝”和“被风输运的高密帷幔”分离后的形态。

只在桌面 OpenGL 进程内替换 canonical Shader，不修改 Android 源码。该探针用来
判断高密带是否仍被锁在未粒子化表面的边界上，以及后段是否保留连续跨度。
"""

from __future__ import annotations

import json
import os

import numpy as np

from analyze_reference_morphology import (
    MODEL_SCENARIO,
    PROGRESSES,
    FrameLayers,
    frame_metrics,
    summarise,
    to_normalized_canvas,
)
from analyze_edge_sequence import ridge_metrics
from analyze_model_activation import (
    curtain_ridge_metrics,
    fit_alpha_for_comparison,
    scaled_box,
)
from render_curtain_model import CurtainRenderer, OUT


FRONT_LINE = "float front = exp(-pow((age - 0.100) / 0.160, 2.0)) * released;"


def transported_front(center: float, width: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            FRONT_LINE,
            f"float front = exp(-pow((age - {center:.3f}) / {width:.3f}, 2.0)) * released;",
        ),
    )


def density_budget(
    probability_gain: float,
    identity_gain: float,
) -> tuple[tuple[str, str], ...]:
    return (
        (
            "0.24 + front * 0.70 + foldGain * 0.20",
            f"0.24 + front * {probability_gain:.2f} + foldGain * 0.20",
        ),
        (
            "front * (0.70 + 0.30 * frontTension) * 4.20 * life",
            f"front * (0.70 + 0.30 * frontTension) * {identity_gain:.2f} * life",
        ),
    )


def separate_density_front(
    center: float,
    width: float,
) -> tuple[tuple[str, str], ...]:
    """保留宽输运队列，只把高密副本限制到独立的窄折叠脊。"""
    return (
        (
            FRONT_LINE,
            FRONT_LINE
            + f"\n                float densityFront = "
            + f"exp(-pow((age - {center:.3f}) / {width:.3f}, 2.0)) * released;",
        ),
        (
            "front * (0.45 + 0.55 * frontTension) * 0.98",
            "densityFront * (0.45 + 0.55 * frontTension) * 0.98",
        ),
        (
            "front * (0.70 + 0.30 * frontTension) * 4.20 * life",
            "densityFront * (0.70 + 0.30 * frontTension) * 4.20 * life",
        ),
    )


def widening_density_front(
    center: float,
    width: float,
    late_start: float,
    late_end: float,
) -> tuple[tuple[str, str], ...]:
    """剥离时保持细脊，完全离面后再接入宽输运队列。"""
    return (
        (
            FRONT_LINE,
            FRONT_LINE
            + f"""
                float densityFront = exp(
                    -pow((age - {center:.3f}) / {width:.3f}, 2.0)
                ) * released;
                densityFront = max(
                    densityFront,
                    front * smoothstep({late_start:.3f}, {late_end:.3f}, uTime)
                );""",
        ),
        (
            "front * (0.45 + 0.55 * frontTension) * 0.98",
            "densityFront * (0.45 + 0.55 * frontTension) * 0.98",
        ),
        (
            "front * (0.70 + 0.30 * frontTension) * 4.20 * life",
            "densityFront * (0.70 + 0.30 * frontTension) * 4.20 * life",
        ),
    )


def detached_curtain_gate(
    end_age: float,
) -> tuple[tuple[str, str], ...]:
    return (
        (
            "float persistentCurtain = max(",
            f"""float curtainDetachment = 0.22 + 0.78 *
                    smoothstep(0.0, {end_age:.3f}, detachedAge);
                float transportedFront = front * curtainDetachment;
                float persistentCurtain = max(""",
        ),
        (
            "max(curtainWeight * 0.88, foldGain * 0.72) * released,",
            "max(curtainWeight * 0.88, foldGain * 0.72) * released * curtainDetachment,",
        ),
        (
            "front * (0.45 + 0.55 * frontTension) * 0.98",
            "transportedFront * (0.45 + 0.55 * frontTension) * 0.98",
        ),
        (
            "front * (0.70 + 0.30 * frontTension) * 4.20 * life",
            "transportedFront * (0.70 + 0.30 * frontTension) * 4.20 * life",
        ),
    )


def model_rows(replacements: tuple[tuple[str, str], ...]) -> list[dict[str, object]]:
    renderer = CurtainRenderer(replacements)
    box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    blank_rgb = np.zeros((688, 688, 3), dtype=np.uint8)
    blank_alpha = np.zeros((688, 688), dtype=np.float32)
    rows: list[dict[str, object]] = []
    for progress in PROGRESSES:
        if not (0.30 <= progress <= 0.72):
            continue
        particle = (
            renderer.render_particle_rgba(MODEL_SCENARIO, float(progress))[:, :, 3]
            .astype(np.float32)
            / 255.0
        )
        layers = FrameLayers(
            ordinary=blank_rgb,
            particle=to_normalized_canvas(particle, box),
            intact=blank_alpha,
        )
        rows.append({"progress": float(progress), **frame_metrics(layers)})
    return rows


def front_ridge(replacements: tuple[tuple[str, str], ...]) -> dict[str, float]:
    """用与正式边缘时序回归相同的定义测量 t=0.47 的高密前沿。"""
    renderer = CurtainRenderer(replacements)
    box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    alpha = (
        renderer.render_diagnostic_rgba(MODEL_SCENARIO, 0.47, "depth-band")[:, :, 3]
        .astype(np.float32)
        / 255.0
    )
    result = ridge_metrics(alpha, box)
    result["mean_thickness_fraction_w"] = (
        result["max_component_mean_thickness_px"] / box[2]
    )
    return result


def activation_ridge(replacements: tuple[tuple[str, str], ...]) -> dict[str, object]:
    """测量缩放到实际对比尺寸后的浓带峰值位置和表面重叠。"""
    renderer = CurtainRenderer(replacements)
    ox, oy = map(int, renderer.origin)
    width, height = renderer.snapshot.size
    expanded_native = (
        ox - 100,
        oy - 100,
        ox + width + 100,
        oy + height + 100,
    )
    band_alpha, scale, offset_x, offset_y = fit_alpha_for_comparison(
        renderer.render_diagnostic_rgba(
            MODEL_SCENARIO, 0.47, "depth-band"
        )[:, :, 3]
    )
    still_alpha, _, _, _ = fit_alpha_for_comparison(
        renderer.render_still_rgba(MODEL_SCENARIO, 0.47)[:, :, 3]
    )
    return curtain_ridge_metrics(
        still_alpha,
        band_alpha,
        scaled_box(expanded_native, scale, offset_x, offset_y),
    )


def key_frame(rows: list[dict[str, object]], progress: float) -> dict[str, float]:
    row = min(rows, key=lambda item: abs(float(item["progress"]) - progress))
    curve = row["curve"]
    return {
        "progress": float(row["progress"]),
        "particle_mass": float(row["particle_mass"]),
        "high_density_mass": float(row["high_density_mass"]),
        "ridge_share": float(curve["largest_high_mass_share"]),
        "centerline_span": float(curve["centerline_span_source"]),
        "arc_bend": float(curve["arc_bend_source"]),
        "arc_coherence": float(curve["arc_coherence"]),
        "component_count": float(curve["component_count"]),
    }


def main() -> None:
    variants = {
        "baseline": (),
        "widen_080_110_540_640": widening_density_front(0.080, 0.110, 0.540, 0.640),
        "widen_090_120_540_660": widening_density_front(0.090, 0.120, 0.540, 0.660),
        "widen_090_120_580_680": widening_density_front(0.090, 0.120, 0.580, 0.680),
        "widen_090_130_540_660": widening_density_front(0.090, 0.130, 0.540, 0.660),
        "density_front_080_100": separate_density_front(0.080, 0.100),
        "density_front_080_110": separate_density_front(0.080, 0.110),
        "density_front_090_110": separate_density_front(0.090, 0.110),
        "density_front_090_120": separate_density_front(0.090, 0.120),
        "density_front_100_120": separate_density_front(0.100, 0.120),
        "density_front_100_130": separate_density_front(0.100, 0.130),
        "transported_080_110": transported_front(0.080, 0.110),
        "transported_090_120": transported_front(0.090, 0.120),
        "transported_090_130": transported_front(0.090, 0.130),
        "transported_100_120": transported_front(0.100, 0.120),
        "transported_100_130": transported_front(0.100, 0.130),
        "transported_100_140": transported_front(0.100, 0.140),
        "transported_100_180": transported_front(0.100, 0.180),
        "transported_100_160": transported_front(0.100, 0.160),
        "transported_110_165": transported_front(0.110, 0.165),
        "transported_125_180": transported_front(0.125, 0.180),
        "transported_125_180_budget32": (
            *transported_front(0.125, 0.180),
            *density_budget(0.72, 3.20),
        ),
        "transported_125_180_budget28": (
            *transported_front(0.125, 0.180),
            *density_budget(0.66, 2.80),
        ),
        "transported_140_200": transported_front(0.140, 0.200),
        "transported_160_220": transported_front(0.160, 0.220),
    }
    selected = os.environ.get("PARTICLE_FRONT_PROBES", "").strip()
    if selected:
        names = {name.strip() for name in selected.split(",") if name.strip()}
        variants = {name: value for name, value in variants.items() if name in names}
    result: dict[str, object] = {}
    for name, replacements in variants.items():
        rows = model_rows(replacements)
        result[name] = {
            "summary": summarise(rows),
            "t054": key_frame(rows, 0.54),
            "t070": key_frame(rows, 0.70),
            "front_ridge": front_ridge(replacements),
            "activation_ridge": activation_ridge(replacements),
        }
        print(name, json.dumps(result[name], ensure_ascii=False))
    output = OUT / "transported-front-probes.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
