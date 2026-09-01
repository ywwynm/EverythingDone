# -*- coding: utf-8 -*-
"""探测单一全局卷流的左上连续肩部与弧线偏置。

该脚本只通过 ``CurtainRenderer`` 的 Shader 文本替换运行候选参数，不修改
Android canonical Shader。目标是同时降低右上局部峰、补足左上连续尾部，
并确认连通性与移动端绘制预算没有回退。
"""

from __future__ import annotations

import json

import numpy as np
from PIL import Image, ImageDraw

from analyze_edge_sequence import edge_metrics, ridge_metrics as edge_ridge_metrics
from analyze_model_activation import curtain_ridge_metrics, fit_alpha_for_comparison, scaled_box
from analyze_model_cohesion import cohesion_metrics, directional_metrics, performance_proxy
from analyze_reference_morphology import (
    MODEL_SCENARIO,
    frame_metrics,
    model_layers,
    render_density_diagnostic,
)
from render_curtain_model import CurtainRenderer


MATURITY_BLOCK = """float globalSheetMaturity = globalSheetMaturityPhase
            * globalSheetMaturityPhase;
    float globalSheetWeight"""

TAIL_BLOCK = """float tailKeep = 0.06 + regionLife * 0.05
            + contentWeight * 0.18;"""


def replacements(
    linear: float,
    shoulder_keep: float,
    quadratic: float = 0.95,
    side_suppression: float = 0.0,
) -> tuple[tuple[str, str], ...]:
    values = [
        (
            "globalCross * globalCross * 0.95",
            f"globalCross * globalCross * {quadratic:.3f}",
        ),
        (
            "+ globalCross * 0.045",
            f"+ globalCross * {linear:.3f}",
        ),
        (
            MATURITY_BLOCK,
            f"""float globalSheetMaturity = globalSheetMaturityPhase
            * globalSheetMaturityPhase;
    // 与唯一高密脊线直接相连的顺向肩部。它沿前沿已通过的一侧单调衰减，
    // 不生成第二个局部峰，仅让左上已揭开的薄层仍作为一个整体可见。
    float globalFrontDelta = globalFrontField - globalFrontPosition;
    float globalAdvancedShoulder = step(0.0, globalFrontDelta)
            * (1.0 - smoothstep(0.16, 0.58, globalFrontDelta))
            * globalFrontMerge * globalFrontRelease * globalSheetMaturity;
    float globalSheetWeight""",
        ),
        (
            TAIL_BLOCK,
            f"""float tailKeep = 0.06 + regionLife * 0.05
            + contentWeight * 0.18;
    tailKeep += globalAdvancedShoulder * {shoulder_keep:.3f};""",
        ),
    ]
    if side_suppression > 0.0:
        values.append(
            (
                "+ edgeSideDrift\n            + globalSheetDrift",
                "+ edgeSideDrift * (1.0 - globalSheetWeight "
                f"* {side_suppression:.3f})\n            + globalSheetDrift",
            )
        )
    return tuple(values)


def transport_replacements(
    local_suppression: float,
    edge_suppression: float,
    shoulder_transport: float | None = None,
) -> tuple[tuple[str, str], ...]:
    transport_expression = (
        f"max(globalSheetWeight, globalAdvancedShoulder * {shoulder_transport:.3f})"
        if shoulder_transport is not None
        else "globalFrontMerge * globalFrontRelease * globalSheetMaturity"
    )
    return (
        (
            """float globalSheetWeight = globalFrontProfile
            * globalFrontMerge * globalFrontRelease
            * globalSheetMaturity * 0.82;""",
            f"""float globalSheetWeight = globalFrontProfile
            * globalFrontMerge * globalFrontRelease
            * globalSheetMaturity * 0.82;
    float globalTransportWeight = {transport_expression};""",
        ),
        (
            "globalSheetWeight * globalFrontMerge * 0.88",
            "globalTransportWeight * 0.88",
        ),
        (
            """direction + perpendicular * centerlineBend
                    * (1.0 - globalSheetWeight * 0.90)""",
            """direction + perpendicular * centerlineBend
                    * (1.0 - globalTransportWeight * 0.90)""",
        ),
        (
            "globalSheetWeight * 0.90\n            ) * speedScale",
            "globalTransportWeight * 0.90\n            ) * speedScale",
        ),
        (
            "float localStructureWeight = 1.0 - globalSheetWeight * 0.92;",
            "float localStructureWeight = 1.0 - globalTransportWeight "
            f"* {local_suppression:.3f};",
        ),
        (
            ") * uDriftPx * globalSheetWeight;",
            ") * uDriftPx * max(globalSheetWeight, globalTransportWeight * 0.30);",
        ),
        (
            "+ edgeSideDrift * (1.0 - globalSheetWeight * 0.90)",
            "+ edgeSideDrift * (1.0 - globalTransportWeight "
            f"* {edge_suppression:.3f})",
        ),
    )


def tail_suppression_replacements(strength: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            "tailKeep += globalAdvancedShoulder * 0.36;",
            f"""float detachedTailSuppression = globalFrontMerge
            * (1.0 - globalAdvancedShoulder) * {strength:.3f};
    tailKeep *= 1.0 - detachedTailSuppression;
    tailKeep += globalAdvancedShoulder * 0.36;""",
        ),
    )


def profile_replacements(inner: float, outer: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            """float globalFrontProfile = 1.0 - smoothstep(
            0.060, 0.210,""",
            f"""float globalFrontProfile = 1.0 - smoothstep(
            {inner:.3f}, {outer:.3f},""",
        ),
    )


def sheet_amplitude_replacements(amplitude: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            "* globalSheetMaturity * 0.82;",
            f"* globalSheetMaturity * {amplitude:.3f};",
        ),
    )


def catchup_replacements(amplitude: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            "* (1.0 - smoothstep(0.52, 0.68, uTime)) * 0.065;",
            "* (1.0 - smoothstep(0.52, 0.68, uTime)) "
            f"* {amplitude:.3f};",
        ),
    )


def maturity_replacements(cubic_mix: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            """float globalSheetMaturity = globalSheetMaturityPhase
            * globalSheetMaturityPhase;""",
            f"""float globalSheetMaturity = globalSheetMaturityPhase
            * globalSheetMaturityPhase
            * mix(1.0, globalSheetMaturityPhase, {cubic_mix:.3f});""",
        ),
    )


def sheet_lift_replacements(amplitude: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            """vec2 positionPx = basePx + earlyDrift + forwardDrift""",
            f"""vec2 globalSheetLift = direction * uNoiseScalePx
            * {amplitude:.3f} * globalSheetWeight;
    vec2 positionPx = basePx + earlyDrift + forwardDrift
            + globalSheetLift""",
        ),
    )


def maturity_window_replacements(start: float, end: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            """float globalSheetMaturityPhase = smoothstep(
            0.025, 0.120, activeAge
    );""",
            f"""float globalSheetMaturityPhase = smoothstep(
            {start:.3f}, {end:.3f}, activeAge
    );""",
        ),
    )


def detached_strength_replacements(strength: float) -> tuple[tuple[str, str], ...]:
    return (
        (
            "* (1.0 - globalAdvancedShoulder) * 0.62;",
            "* (1.0 - globalAdvancedShoulder) "
            f"* {strength:.3f};",
        ),
    )


def ridge(renderer: CurtainRenderer) -> dict[str, float]:
    progress = 0.47
    particle, scale, offset_x, offset_y = fit_alpha_for_comparison(
        renderer.render_diagnostic_rgba(MODEL_SCENARIO, progress, "depth-band")[:, :, 3]
    )
    still, _, _, _ = fit_alpha_for_comparison(
        renderer.render_still_rgba(MODEL_SCENARIO, progress)[:, :, 3]
    )
    ox, oy = renderer.origin
    width, height = renderer.snapshot.size
    roi = scaled_box(
        (ox - 100, oy - 100, ox + width + 100, oy + height + 100),
        scale,
        offset_x,
        offset_y,
    )
    metrics = curtain_ridge_metrics(still, particle, roi)
    return {
        "peak_center_px": float(metrics["peak_center_px"]),
        "ribbon_to_surface_halo": float(metrics["ribbon_to_surface_halo"]),
        "ribbon_to_outer_tail": float(metrics["ribbon_to_outer_tail"]),
        "surface_overlap_mass_share": float(metrics["surface_overlap_mass_share"]),
    }


def measure(name: str, shader_replacements: tuple[tuple[str, str], ...]) -> dict[str, object]:
    renderer = CurtainRenderer(shader_replacements)
    source_box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    edges = {}
    for progress in (0.32, 0.47):
        alpha = renderer.render_particle_rgba(MODEL_SCENARIO, progress)[:, :, 3]
        edges[str(progress)] = edge_metrics(alpha.astype(np.float32) / 255.0, source_box)

    frames = {}
    for progress in (0.50, 0.65, 0.70):
        layers = model_layers(renderer, progress)
        frames[str(progress)] = {
            "cohesion": cohesion_metrics(layers.particle),
            "directional": directional_metrics(layers.particle),
            "performance": performance_proxy(renderer, progress),
        }
    return {
        "name": name,
        "edge": edges,
        "ridge": ridge(renderer),
        "frames": frames,
        "morphology_047": frame_metrics(model_layers(renderer, 0.47)),
        "edge_ridge_047": edge_ridge_metrics(
            renderer.render_diagnostic_rgba(
                MODEL_SCENARIO, 0.4666666666666667, "visual-band"
            )[:, :, 3].astype(np.float32) / 255.0,
            source_box,
        ),
    }


def compact(result: dict[str, object]) -> dict[str, object]:
    edge = result["edge"]
    frames = result["frames"]
    return {
        "name": result["name"],
        "top_left_ratio_032": edge["0.32"]["top_left_to_top_right"],
        "top_left_ratio_047": edge["0.47"]["top_left_to_top_right"],
        "top_left_mass_047": edge["0.47"]["top_left_mass"],
        "top_right_mass_047": edge["0.47"]["top_right_mass"],
        "ridge": result["ridge"],
        "components_050": frames["0.5"]["cohesion"]["significant_component_count"],
        "detached_050": frames["0.5"]["cohesion"]["detached_mass_share"],
        "right_down_050": frames["0.5"]["directional"]["right_down_mass_share"],
        "active_cells_065": frames["0.65"]["performance"]["active_main_cells"],
        "alpha_mass_065": frames["0.65"]["performance"]["all_layers_alpha_mass_viewport"],
        "high_components_047": result["morphology_047"]["curve"]["component_count"],
        "largest_high_share_047": result["morphology_047"]["curve"]["largest_high_mass_share"],
        "arc_coherence_047": result["morphology_047"]["curve"]["arc_coherence"],
        "arc_bend_047": result["morphology_047"]["curve"]["arc_bend_source"],
        "edge_thickness_047": result["edge_ridge_047"]["max_component_mean_thickness_px"],
        "edge_aspect_047": result["edge_ridge_047"]["max_component_aspect"],
    }


def main() -> None:
    variants = [("baseline", ())]
    for strength in (0.40, 0.48, 0.55, 0.62):
        variants.append((
            f"profile-tail-strength-{strength:.2f}",
            profile_replacements(0.045, 0.150)
            + sheet_amplitude_replacements(0.90)
            + maturity_window_replacements(0.045, 0.145)
            + detached_strength_replacements(strength),
        ))
    results = [compact(measure(name, variant)) for name, variant in variants]
    print(json.dumps(results, ensure_ascii=False, indent=2))

    selected = (
        profile_replacements(0.045, 0.150)
        + sheet_amplitude_replacements(0.90)
        + maturity_window_replacements(0.045, 0.145)
        + detached_strength_replacements(0.48)
    )
    renderer = CurtainRenderer(selected)
    cells = []
    for progress in (0.40, 0.47, 0.54, 0.62, 0.70):
        ordinary = renderer.render(MODEL_SCENARIO, progress).resize((640, 450))
        density = Image.fromarray(
            render_density_diagnostic(model_layers(renderer, progress)), "RGB"
        ).resize((450, 450))
        cell = Image.new("RGB", (1090, 482), (17, 19, 24))
        cell.paste(ordinary, (0, 32))
        cell.paste(density, (640, 32))
        draw = ImageDraw.Draw(cell)
        draw.text((8, 6), f"t={progress:.2f} ordinary / density", fill=(230, 232, 238))
        cells.append(cell)
    sheet = Image.new("RGB", (1090, 482 * len(cells)), (17, 19, 24))
    for index, cell in enumerate(cells):
        sheet.paste(cell, (0, index * 482))
    sheet.save("tmp/particle-dismiss-tuning/frames-curtain/transport-probe.png")

    canonical = renderer
    stage_cells = []
    for progress in (0.40, 0.47, 0.54, 0.62, 0.70):
        band = Image.fromarray(
            canonical.render_diagnostic_rgba(MODEL_SCENARIO, progress, "visual-band"),
            "RGBA",
        ).convert("RGB").resize((640, 450))
        tail = Image.fromarray(
            canonical.render_diagnostic_rgba(MODEL_SCENARIO, progress, "visual-tail"),
            "RGBA",
        ).convert("RGB").resize((640, 450))
        cell = Image.new("RGB", (1280, 482), (17, 19, 24))
        cell.paste(band, (0, 32))
        cell.paste(tail, (640, 32))
        draw = ImageDraw.Draw(cell)
        draw.text((8, 6), f"t={progress:.2f} band / tail", fill=(230, 232, 238))
        stage_cells.append(cell)
    stage_sheet = Image.new("RGB", (1280, 482 * len(stage_cells)), (17, 19, 24))
    for index, cell in enumerate(stage_cells):
        stage_sheet.paste(cell, (0, index * 482))
    stage_sheet.save("tmp/particle-dismiss-tuning/frames-curtain/stage-probe.png")


if __name__ == "__main__":
    main()
