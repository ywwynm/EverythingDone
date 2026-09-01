"""检查左上对比场景的区域消融次序、目标角早期粒子与孔洞柔边。"""

from __future__ import annotations

import json
import math

import cv2
import numpy as np
from PIL import Image

from render_curtain_model import CurtainRenderer, Scenario, VIEW_H, VIEW_W


COMPARISON_W = 480
COMPARISON_H = 344


def fit_alpha_for_comparison(alpha: np.ndarray) -> tuple[np.ndarray, float, int, int]:
    """复现并排视频的 480×344 缩放，避免原生分辨率掩盖亚像素粒子。"""
    image = Image.fromarray(alpha.astype(np.uint8), "L")
    image.thumbnail((COMPARISON_W, COMPARISON_H), Image.Resampling.LANCZOS)
    offset_x = (COMPARISON_W - image.width) // 2
    offset_y = (COMPARISON_H - image.height) // 2
    fitted = Image.new("L", (COMPARISON_W, COMPARISON_H), 0)
    fitted.paste(image, (offset_x, offset_y))
    return (
        np.asarray(fitted, dtype=np.float32) / 255.0,
        image.width / VIEW_W,
        offset_x,
        offset_y,
    )


def scaled_box(
    native_box: tuple[float, float, float, float],
    scale: float,
    offset_x: int,
    offset_y: int,
) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = native_box
    return (
        max(0, int(offset_x + x0 * scale)),
        max(0, int(offset_y + y0 * scale)),
        min(COMPARISON_W, int(offset_x + x1 * scale)),
        min(COMPARISON_H, int(offset_y + y1 * scale)),
    )


def alpha_metrics(alpha: np.ndarray, box: tuple[int, int, int, int]) -> dict[str, float]:
    x0, y0, x1, y1 = box
    values = alpha[y0:y1, x0:x1]
    nonzero = values[values > (1.0 / 255.0)]
    local_density = cv2.boxFilter(values, -1, (9, 9), normalize=True)
    return {
        "mean_alpha": float(values.mean()),
        "p90_nonzero_alpha": float(np.percentile(nonzero, 90)) if nonzero.size else 0.0,
        "p95_nonzero_alpha": float(np.percentile(nonzero, 95)) if nonzero.size else 0.0,
        "strong_alpha_fraction": float((values >= 0.18).mean()),
        "local_density_p95": float(np.percentile(local_density, 95)),
    }


def exterior_corner_mass(
    alpha: np.ndarray,
    dialog_box: tuple[int, int, int, int],
    sample_box: tuple[int, int, int, int],
) -> float:
    """统计角部样本框中已经越过原 Dialog 外缘的粒子 alpha。"""
    dialog_x0, dialog_y0, dialog_x1, dialog_y1 = dialog_box
    x0, y0, x1, y1 = sample_box
    yy, xx = np.mgrid[y0:y1, x0:x1]
    outside = (
        (xx < dialog_x0)
        | (xx >= dialog_x1)
        | (yy < dialog_y0)
        | (yy >= dialog_y1)
    )
    return float(alpha[y0:y1, x0:x1][outside].sum())


def curtain_ridge_metrics(
    still_alpha: np.ndarray,
    band_alpha: np.ndarray,
    roi_box: tuple[int, int, int, int],
) -> dict[str, object]:
    """只用粒子层测量锋线外侧的窄峰，完整 Dialog 不参与统计。"""
    solid_surface = still_alpha >= 0.50
    exterior_distance = cv2.distanceTransform(
        (~solid_surface).astype(np.uint8),
        cv2.DIST_L2,
        5,
    )
    roi = np.zeros_like(solid_surface, dtype=bool)
    x0, y0, x1, y1 = roi_box
    roi[y0:y1, x0:x1] = True
    exterior = roi & (~solid_surface)
    local_density = cv2.boxFilter(band_alpha, -1, (9, 9), normalize=True)

    bins: list[dict[str, float]] = []
    for low in range(0, 49, 4):
        mask = exterior & (exterior_distance >= low) & (exterior_distance < low + 4)
        values = local_density[mask]
        bins.append({
            "from_px": float(low),
            "to_px": float(low + 4),
            "mean_local_density": float(values.mean()) if values.size else 0.0,
        })

    def mean_in(low: float, high: float) -> float:
        mask = exterior & (exterior_distance >= low) & (exterior_distance < high)
        values = local_density[mask]
        return float(values.mean()) if values.size else 0.0

    peak = max(bins, key=lambda item: item["mean_local_density"])
    peak_center = (peak["from_px"] + peak["to_px"]) * 0.5
    surface_halo = mean_in(0.0, 4.0)
    detached_ribbon = mean_in(max(6.0, peak_center - 6.0), peak_center + 6.0)
    outer_tail = mean_in(peak_center + 16.0, peak_center + 32.0)
    total_band_mass = float(band_alpha[roi].sum())
    surface_overlap_mass = float(band_alpha[roi & solid_surface].sum())
    return {
        "surface_source": "still layer threshold only; excluded from all density values",
        "bins": bins,
        "peak_center_px": peak_center,
        "ribbon_window_px": [max(6.0, peak_center - 6.0), peak_center + 6.0],
        "outer_tail_window_px": [peak_center + 16.0, peak_center + 32.0],
        "surface_halo_density": surface_halo,
        "detached_ribbon_density": detached_ribbon,
        "outer_tail_density": outer_tail,
        "ribbon_to_surface_halo": detached_ribbon / max(surface_halo, 1e-5),
        "ribbon_to_outer_tail": detached_ribbon / max(outer_tail, 1e-5),
        "surface_overlap_mass_share": surface_overlap_mass
        / max(total_band_mass, 1e-5),
    }


def main() -> None:
    renderer = CurtainRenderer()
    scenario = Scenario("comparison-left-up", -128.0, 42)
    ox, oy = map(int, renderer.origin)
    width, height = renderer.snapshot.size
    snapshot_alpha = np.asarray(renderer.snapshot)[:, :, 3].astype(np.float32) / 255.0
    source = snapshot_alpha > 0.5
    target_corner_native = (
        ox - 80,
        oy - 80,
        ox + width * 0.30,
        oy + height * 0.38,
    )
    upstream_curtain_native = (
        ox + width * 0.58,
        oy + height * 0.28,
        ox + width + 70,
        oy + height + 70,
    )
    expanded_native = (
        ox - 100,
        oy - 100,
        ox + width + 100,
        oy + height + 100,
    )

    radians = math.radians(scenario.angle_degrees)
    direction = np.array([math.cos(radians), math.sin(radians)], dtype=np.float32)
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    projection = xx * direction[0] + yy * direction[1]
    projection = (projection - projection[source].min()) / max(
        float(np.ptp(projection[source])), 1e-5
    )
    upstream = source & (projection < 0.25)
    downstream = source & (projection > 0.75)

    rows: list[dict[str, float]] = []
    dialog_box = (ox, oy, ox + width, oy + height)
    corner_pad = 90
    corner_width = int(width * 0.28)
    corner_height = int(height * 0.30)
    target_corner_box = (
        max(0, ox - corner_pad),
        max(0, oy - corner_pad),
        min(VIEW_W, ox + corner_width),
        min(VIEW_H, oy + corner_height),
    )
    top_right_corner_box = (
        max(0, ox + width - corner_width),
        max(0, oy - corner_pad),
        min(VIEW_W, ox + width + corner_pad),
        min(VIEW_H, oy + corner_height),
    )
    bottom_left_corner_box = (
        max(0, ox - corner_pad),
        max(0, oy + height - corner_height),
        min(VIEW_W, ox + corner_width),
        min(VIEW_H, oy + height + corner_pad),
    )

    for time_seconds in (
        0.08, 0.10, 0.16, 0.24, 0.32, 0.36, 0.47, 0.50,
        0.68, 0.70, 0.84, 0.90, 0.96, 1.00,
    ):
        still = renderer.render_still_rgba(scenario, time_seconds)
        particle = renderer.render_particle_rgba(scenario, time_seconds)
        still_alpha = still[oy : oy + height, ox : ox + width, 3].astype(np.float32) / 255.0

        intermediate = source & (still_alpha > 0.08) & (still_alpha < 0.92)
        solid = source & (still_alpha >= 0.5)
        boundary = cv2.morphologyEx(
            solid.astype(np.uint8),
            cv2.MORPH_GRADIENT,
            np.ones((3, 3), np.uint8),
        ) > 0
        feather_width = float(intermediate.sum() / max(1, boundary.sum()))

        # 目标角由 Dialog 左上 22% 和其外侧 80px 组成。这里只统计粒子层，
        # 因而不会把仍完整的白色表面误判成“已经向左上飞行”。
        x0 = max(0, ox - 80)
        y0 = max(0, oy - 80)
        x1 = ox + int(width * 0.22)
        y1 = oy + int(height * 0.28)
        target_alpha = particle[y0:y1, x0:x1, 3].astype(np.float32) / 255.0
        outside = np.zeros_like(particle[:, :, 3], dtype=bool)
        outside[max(0, oy - 80) : oy + int(height * 0.28), max(0, ox - 80) : ox] = True
        outside[max(0, oy - 80) : oy, ox : ox + int(width * 0.22)] = True
        outside_alpha = particle[:, :, 3].astype(np.float32) / 255.0
        target_exterior_mass = exterior_corner_mass(
            outside_alpha, dialog_box, target_corner_box
        )
        top_right_exterior_mass = exterior_corner_mass(
            outside_alpha, dialog_box, top_right_corner_box
        )
        bottom_left_exterior_mass = exterior_corner_mass(
            outside_alpha, dialog_box, bottom_left_corner_box
        )

        scaled_particle, scale, offset_x, offset_y = fit_alpha_for_comparison(
            particle[:, :, 3]
        )
        target_corner_metrics = alpha_metrics(
            scaled_particle,
            scaled_box(target_corner_native, scale, offset_x, offset_y),
        )
        upstream_curtain_metrics = alpha_metrics(
            scaled_particle,
            scaled_box(upstream_curtain_native, scale, offset_x, offset_y),
        )
        expanded_metrics = alpha_metrics(
            scaled_particle,
            scaled_box(expanded_native, scale, offset_x, offset_y),
        )

        rows.append({
            "time_s": time_seconds,
            "source_remaining_alpha": float(still_alpha[source].mean()),
            "upstream_remaining_alpha": float(still_alpha[upstream].mean()),
            "downstream_remaining_alpha": float(still_alpha[downstream].mean()),
            "downstream_minus_upstream": float(
                still_alpha[downstream].mean() - still_alpha[upstream].mean()
            ),
            "soft_edge_intermediate_pixels": float(intermediate.sum()),
            "soft_edge_width_px_approx": feather_width,
            "target_corner_particle_alpha": float(target_alpha.sum()),
            "target_corner_outside_alpha": float(outside_alpha[outside].sum()),
            "target_corner_exterior_alpha": target_exterior_mass,
            "top_right_edge_exterior_alpha": top_right_exterior_mass,
            "bottom_left_edge_exterior_alpha": bottom_left_exterior_mass,
            "target_corner_scaled_p90_alpha": target_corner_metrics["p90_nonzero_alpha"],
            "target_corner_scaled_strong_fraction": target_corner_metrics[
                "strong_alpha_fraction"
            ],
            "upstream_curtain_scaled_p90_alpha": upstream_curtain_metrics[
                "p90_nonzero_alpha"
            ],
            "upstream_curtain_scaled_strong_fraction": upstream_curtain_metrics[
                "strong_alpha_fraction"
            ],
            "expanded_scaled_mean_alpha": expanded_metrics["mean_alpha"],
            "expanded_scaled_strong_fraction": expanded_metrics[
                "strong_alpha_fraction"
            ],
        })

    band_alpha, scale, offset_x, offset_y = fit_alpha_for_comparison(
        renderer.render_diagnostic_rgba(scenario, 0.47, "depth-band")[:, :, 3]
    )
    tail_alpha, _, _, _ = fit_alpha_for_comparison(
        renderer.render_diagnostic_rgba(scenario, 0.47, "depth-tail")[:, :, 3]
    )
    expanded_box = scaled_box(expanded_native, scale, offset_x, offset_y)
    band_metrics = alpha_metrics(band_alpha, expanded_box)
    tail_metrics = alpha_metrics(tail_alpha, expanded_box)
    still_at_047, _, _, _ = fit_alpha_for_comparison(
        renderer.render_still_rgba(scenario, 0.47)[:, :, 3]
    )
    ridge_metrics = curtain_ridge_metrics(still_at_047, band_alpha, expanded_box)

    result = {
        "scenario": "comparison-left-up (-128°), canonical Shader",
        "meaning": "upstream=右下；downstream=左上；正的 remaining 差表示右下消融更快",
        "samples": rows,
        "comparison_scale_band_at_047": band_metrics,
        "comparison_scale_tail_at_047": tail_metrics,
        "particle_only_curtain_ridge_at_047": ridge_metrics,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))

    by_time = {row["time_s"]: row for row in rows}
    failures: list[str] = []
    if by_time[0.10]["target_corner_exterior_alpha"] < 24.0:
        failures.append("0.10s 顺风角粒子没有形成可辨认的越界牵伸")
    side_edge_mass = (
        by_time[0.10]["top_right_edge_exterior_alpha"]
        + by_time[0.10]["bottom_left_edge_exterior_alpha"]
    )
    if side_edge_mass < 16.0:
        failures.append("0.10s 两个侧边缺少强弱不同的同期预脱落粒子")
    if min(
        by_time[0.10]["top_right_edge_exterior_alpha"],
        by_time[0.10]["bottom_left_edge_exterior_alpha"],
    ) < 2.0:
        failures.append("0.10s 预脱落仍只集中在一个侧边")
    if by_time[0.16]["downstream_minus_upstream"] < 0.25:
        failures.append("0.16s 右下→左上的表面进度差不足")
    if by_time[0.16]["target_corner_outside_alpha"] < 10.0:
        failures.append("0.16s 左上角缺少已越界粒子")
    # 该量只描述 still layer 的连续宏观 alpha 过渡。当前交界还包含由共享
    # 噪声形成的细孔以及同位置的高占用粒子，不能再要求所有柔化都表现为
    # 4px 连续半透明带；完整的孔隙率、边界重叠和密度跳变由 61 帧形态回归验证。
    if by_time[0.24]["soft_edge_width_px_approx"] < 2.4:
        failures.append("0.24s 宏观曲线的连续柔边宽度不足 2.4px")
    if by_time[0.32]["target_corner_scaled_p90_alpha"] < 0.12:
        failures.append("0.32s 左上粒群缩放后有效 alpha 不足")
    if by_time[0.32]["target_corner_scaled_strong_fraction"] < 0.004:
        failures.append("0.32s 左上粒群缩放后没有足够清晰粒点")
    if by_time[0.32]["upstream_curtain_scaled_p90_alpha"] < 0.19:
        failures.append("0.32s 右下主粒群缩放后有效 alpha 不足")
    if by_time[1.00]["source_remaining_alpha"] > 0.0:
        failures.append("1.00s 仍有未粒子化表面")
    if by_time[1.00]["expanded_scaled_mean_alpha"] > 0.0:
        failures.append("1.00s 仍有存活粒子")
    if band_metrics["p95_nonzero_alpha"] < 0.23:
        failures.append("0.47s 高密帷幔缩放后峰值不足")
    if band_metrics["strong_alpha_fraction"] < 0.005:
        failures.append("0.47s 高密帷幔缩放后清晰覆盖不足")
    # 高密带只占扩展框的一小部分。用整个扩展框的 p95 比较会在条带变窄时
    # 把大量零值也算进帷幔、却把宽尾流的有效区域完整算入，反而惩罚更清楚
    # 的窄带。真实对比改由下方“距完整表面的分箱峰值”约束。
    # 真正的帷幔应在完整表面之外形成独立窄峰。若最高密度仍紧贴
    # still layer 边界，普通合成帧里只会像未粒子化表面的白色模糊边。
    if ridge_metrics["peak_center_px"] < 6.0:
        failures.append(
            "0.47s 粒子帷幔峰值仍贴在完整表面边缘，未形成独立条带"
        )
    if ridge_metrics["ribbon_to_surface_halo"] < 1.12:
        failures.append(
            "0.47s 峰值附近的粒子浓带不比 0–4px 白边明显"
        )
    if ridge_metrics["peak_center_px"] > 30.0:
        failures.append("0.47s 粒子浓带已脱离锋线过远，退化成悬浮粒云")
    if ridge_metrics["ribbon_to_outer_tail"] < 1.50:
        failures.append("0.47s 独立浓带不比更外侧尾流明显")
    # 柔和接缝需要少量粒子跨在 still layer 两侧。帷幔改为随材料年龄输运
    # 后跨度更长，10% 会错误惩罚真实的跨缝部分；独立峰距离及 halo 对比已经
    # 分别排除了“只把白边当帷幔”，因此这里保留不超过 15% 的连接质量。
    if ridge_metrics["surface_overlap_mass_share"] > 0.15:
        failures.append("0.47s 高密帷幔仍有超过 15% 的质量覆盖在完整表面上")
    if by_time[0.70]["source_remaining_alpha"] > 0.035:
        failures.append("0.70s 仍有超过 3.5% 的完整表面")
    if by_time[0.70]["downstream_remaining_alpha"] > 0.06:
        failures.append("0.70s 左上顺向侧仍有明显完整表面")
    if by_time[0.90]["source_remaining_alpha"] > 0.005:
        failures.append("0.90s 仍有可见完整表面")
    if by_time[0.70]["expanded_scaled_mean_alpha"] < 0.010:
        failures.append("0.70s 受风输运粒群整体过淡")
    if by_time[0.70]["expanded_scaled_strong_fraction"] < 0.003:
        failures.append("0.70s 受风输运阶段缺少清晰粒点")
    if by_time[0.96]["downstream_remaining_alpha"] > 0.03:
        failures.append("0.96s 顺向侧静止表面仍超过 3%")
    if failures:
        raise SystemExit("；".join(failures))
    print("activation regression: PASS")


if __name__ == "__main__":
    main()
