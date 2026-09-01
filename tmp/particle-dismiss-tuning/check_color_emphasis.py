# -*- coding: utf-8 -*-
"""检查 canonical 消失 Shader 的统一动力学与内容色真实采样增益。"""

from __future__ import annotations

import numpy as np
from scipy.ndimage import binary_dilation

from render_curtain_model import (
    DISMISS_REPLICAS,
    CurtainRenderer,
    Scenario,
    load_canonical_shaders,
)


SATURATION_THRESHOLD = 0.18
MIN_COLOR_ALPHA_RATIO = 2.35
MAX_COLOR_ALPHA_RATIO = 3.65
MIN_CONTENT_COUNT_RATIO = 3.70
MAX_CONTENT_COUNT_RATIO = 5.25
MIN_BAND_TAIL_DENSITY_RATIO = 2.40
MAX_BAND_TAIL_DENSITY_RATIO = 3.30
SEEDS = (19, 42, 73, 127)
TIMES = (0.32, 0.40, 0.50, 0.60)
COUNT_TIMES = (0.46, 0.56, 0.66, 0.76)
SPATIAL_SEEDS = (19, 42, 73, 127)
MID_TIME = 0.50
DEPTH_STAGE_TIME = 0.50
DENSITY_TIME = 0.50


def saturated_alpha_share(rgba: np.ndarray) -> tuple[float, float]:
    pixels = rgba.astype(np.float32) / 255.0
    alpha = pixels[:, :, 3]
    straight_rgb = np.where(
        alpha[:, :, None] > 1e-5,
        pixels[:, :, :3] / np.maximum(alpha[:, :, None], 1e-5),
        0.0,
    )
    saturation = straight_rgb.max(axis=2) - straight_rgb.min(axis=2)
    saturated_mass = float((alpha * (saturation >= SATURATION_THRESHOLD)).sum())
    return saturated_mass, float(alpha.sum())


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def source_content_share(snapshot: np.ndarray) -> float:
    pixels = snapshot.astype(np.float32) / 255.0
    rgb = pixels[:, :, :3]
    visible = pixels[:, :, 3] > 0.5
    saturation = rgb.max(axis=2) - rgb.min(axis=2)
    content_weight = smoothstep(0.18, 0.42, saturation)
    return float(((content_weight >= 0.50) & visible).sum() / max(visible.sum(), 1))


def check_unified_color_motion() -> None:
    """颜色权重只能改变保留数量，不能进入运动、寿命、尺寸或材质。"""
    vertex = load_canonical_shaders()[0]
    without_comments = "\n".join(
        line.split("//", 1)[0] for line in vertex.splitlines()
    )
    motion_at = without_comments.index("vec3 flightPoint =")
    keep_at = without_comments.index("float colorKeepBoost =")
    probability_at = without_comments.index("float probability =")
    extra_at = without_comments.index("float colorExtra =")
    point_size_at = without_comments.index("float pointSize =")
    if not motion_at < keep_at < probability_at < extra_at < point_size_at:
        raise SystemExit("颜色统一处理回归：颜色数量权重进入了运动或粒径阶段")
    point_size_formula = without_comments[point_size_at:without_comments.index(
        "gl_PointSize =", point_size_at
    )]
    if "saturation" in point_size_formula or "color" in point_size_formula.lower():
        raise SystemExit("颜色统一处理回归：颜色权重进入了粒径公式")
    required = (
        "+ curtainWeight * 0.16 + edgeFiberDensity * 0.44 +",
        "colorKeepBoost -",
        "float colorKeepBoost = smoothstep(0.16, 0.68, saturation) * 0.90;",
        "float colorExtra = smoothstep(0.16, 0.68, saturation) * 4.50;",
        "vContentWeight = saturation;",
    )
    for statement in required:
        if statement not in without_comments:
            raise SystemExit(f"颜色统一处理契约缺失：{statement}")


def check_color_emphasis(renderer: CurtainRenderer) -> None:
    snapshot = np.asarray(renderer.snapshot)
    source_saturated, source_total = saturated_alpha_share(snapshot)
    source_share = source_saturated / source_total

    particle_saturated = 0.0
    particle_total = 0.0
    for seed in SEEDS:
        scenario = Scenario(f"up-{seed}", -90.0, seed)
        for time_seconds in TIMES:
            saturated, total = saturated_alpha_share(
                renderer.render_particle_rgba(scenario, time_seconds)
            )
            particle_saturated += saturated
            particle_total += total

    particle_share = particle_saturated / particle_total
    color_alpha_ratio = particle_share / source_share

    source_count_share = source_content_share(snapshot)
    content_cells = 0
    panel_cells = 0
    for seed in SEEDS:
        scenario = Scenario(f"up-{seed}", -90.0, seed)
        for time_seconds in COUNT_TIMES:
            for layer_index in range(DISMISS_REPLICAS):
                rgba = renderer.render_diagnostic_rgba(
                    scenario,
                    time_seconds,
                    f"content-layer-{layer_index}",
                )
                visible = rgba[:, :, 3] > 127
                content_cells += int(
                    (visible & (rgba[:, :, 0] > rgba[:, :, 2])).sum()
                )
                panel_cells += int(
                    (visible & (rgba[:, :, 2] > rgba[:, :, 0])).sum()
                )
    particle_count_share = content_cells / max(content_cells + panel_cells, 1)
    # 第五层使高饱和彩色材料获得最多约 5 个同轨迹样本；这里比较的是
    # 全粒群占比，连续中性帷幔本身也会增密，因此其宏观比值会低于逐材料
    # 的 4.5× 数量表示。3.70 下限仍能阻止彩色副本失效。
    count_ratio = particle_count_share / max(source_count_share, 1e-5)
    print(
        f"color alpha source={source_share:.4f}, particles={particle_share:.4f}, "
        f"ratio={color_alpha_ratio:.2f}x; "
        f"content cell source={source_count_share:.4f}, tail={particle_count_share:.4f}, "
        f"direct ratio={count_ratio:.2f}x"
    )
    if color_alpha_ratio < MIN_COLOR_ALPHA_RATIO:
        raise SystemExit("内容色回归：统一处理后彩色粒子被意外压暗")
    if color_alpha_ratio > MAX_COLOR_ALPHA_RATIO:
        raise SystemExit("内容色回归：彩色粒子从同一粒群中过度突出")
    if count_ratio < MIN_CONTENT_COUNT_RATIO:
        raise SystemExit("内容色回归：彩色内容没有通过真实粒子数量获得足够强调")
    if count_ratio > MAX_CONTENT_COUNT_RATIO:
        raise SystemExit("内容色回归：彩色内容的粒子数量已形成独立色块")


def weighted_depth_variance(rgba: np.ndarray) -> float:
    pixels = rgba.astype(np.float32) / 255.0
    alpha = pixels[:, :, 3]
    mask = alpha > 0.01
    if not np.any(mask):
        return 0.0
    # 深度诊断 Shader 输出预乘色；红通道随伪深度单调增加。
    encoded_depth = pixels[:, :, 0] / np.maximum(alpha, 1e-5)
    mean = np.average(encoded_depth[mask], weights=alpha[mask])
    return float(
        np.average(
            (encoded_depth[mask] - mean) ** 2,
            weights=alpha[mask],
        )
    )


def alpha_centroid(alpha: np.ndarray) -> np.ndarray:
    y, x = np.indices(alpha.shape)
    mass = float(alpha.sum())
    if mass <= 0.0:
        return np.zeros(2, dtype=np.float64)
    return np.array(
        [(x * alpha).sum() / mass, (y * alpha).sum() / mass],
        dtype=np.float64,
    )


def check_spatial_depth(renderer: CurtainRenderer) -> None:
    mid_variances: list[float] = []
    tail_variances: list[float] = []
    back_ratios: list[float] = []
    front_ratios: list[float] = []
    density_ratios: list[float] = []
    band_coverages: list[float] = []
    tail_coverages: list[float] = []
    band_shares: list[float] = []
    band_support_frame_shares: list[float] = []
    band_support_union_shares: list[float] = []
    layer_spread_ratios: list[float] = []
    layer_separations: list[float] = []

    for seed in SPATIAL_SEEDS:
        scenario = Scenario(
            f"right-up-{seed}",
            -60.0,
            seed,
            light_distance_factor=1.1,
        )
        layer_alpha = [
            renderer.render_diagnostic_rgba(
                scenario,
                MID_TIME,
                f"layer-{layer_index}",
            )[:, :, 3].astype(np.float32)
            for layer_index in range(3)
        ]
        layer_mass = [float(alpha.sum()) for alpha in layer_alpha]
        if min(layer_mass) <= 0.0:
            raise SystemExit(f"空间层缺失：seed={seed}, alpha={layer_mass}")
        back_ratios.append(layer_mass[0] / layer_mass[1])
        front_ratios.append(layer_mass[2] / layer_mass[1])
        layer_masks = [alpha > 4.0 for alpha in layer_alpha]
        layer_union = layer_masks[0] | layer_masks[1] | layer_masks[2]
        layer_spread_ratios.append(
            float(layer_union.sum() / max(layer_masks[1].sum(), 1))
        )
        layer_separations.append(
            float(
                np.linalg.norm(
                    alpha_centroid(layer_alpha[2]) - alpha_centroid(layer_alpha[0])
                )
            )
        )
        mid_variances.append(
            weighted_depth_variance(
                renderer.render_diagnostic_rgba(
                    scenario,
                    DEPTH_STAGE_TIME,
                    "depth-band",
                )
            )
        )
        tail_variances.append(
            weighted_depth_variance(
                renderer.render_diagnostic_rgba(
                    scenario,
                    DEPTH_STAGE_TIME,
                    "depth-tail",
                )
            )
        )
        density_rgba = renderer.render_diagnostic_rgba(
            scenario,
            DENSITY_TIME,
            "occupancy",
        ).astype(np.float32) / 255.0
        density_alpha = density_rgba[:, :, 3]
        density_rgb = density_rgba[:, :, :3] / np.maximum(
            density_alpha[:, :, None],
            1e-5,
        )
        visible = density_alpha > 0.50
        band_pixels = visible & (density_rgb[:, :, 0] > density_rgb[:, :, 2] * 1.25)
        tail_pixels = visible & (density_rgb[:, :, 2] > density_rgb[:, :, 0] * 1.25)
        band_support = binary_dilation(band_pixels, iterations=8)
        tail_support = binary_dilation(tail_pixels, iterations=8)
        band_coverage = float(band_pixels.sum() / max(band_support.sum(), 1))
        tail_coverage = float(tail_pixels.sum() / max(tail_support.sum(), 1))
        density_ratios.append(band_coverage / max(tail_coverage, 1e-5))
        band_coverages.append(band_coverage)
        tail_coverages.append(tail_coverage)
        band_shares.append(float(
            band_pixels.sum() / max(band_pixels.sum() + tail_pixels.sum(), 1)
        ))
        band_support_frame_shares.append(float(band_support.mean()))
        band_support_union_shares.append(float(
            band_support.sum() / max((band_support | tail_support).sum(), 1)
        ))

    mid_variance = float(np.mean(mid_variances))
    tail_variance = float(np.mean(tail_variances))
    back_ratio = float(np.mean(back_ratios))
    front_ratio = float(np.mean(front_ratios))
    density_ratio = float(np.mean(density_ratios))
    band_coverage = float(np.mean(band_coverages))
    tail_coverage = float(np.mean(tail_coverages))
    band_share = float(np.mean(band_shares))
    band_support_frame_share = float(np.mean(band_support_frame_shares))
    band_support_union_share = float(np.mean(band_support_union_shares))
    layer_spread_ratio = float(np.mean(layer_spread_ratios))
    layer_separation = float(np.mean(layer_separations))

    # 先输出全部原始量，再做阈值断言。这样某个较早的空间层回归失败时，
    # 仍能判断帷幔/尾流占地密度到底由哪一侧发生了变化。
    print(
        f"spatial raw: back/main={back_ratio:.3f}, front/main={front_ratio:.3f}; "
        f"depth={mid_variance:.4f}/{tail_variance:.4f}; "
        f"occupancy={density_ratio:.2f}x "
        f"({band_coverage:.3f}/{tail_coverage:.3f}), "
        f"band points={band_share:.1%}, geometry="
        f"{band_support_frame_share:.1%} frame/"
        f"{band_support_union_share:.1%} union; "
        f"support={layer_spread_ratio:.2f}x; "
        f"separation={layer_separation:.2f}px"
    )

    # 普通尾流已主动抽稀前后层；折叠脊仍保留完整三层。因此这里检查
    # “层存在且有稳定质量”，不再要求整张粒子面都维持旧版全量过绘比例。
    if not 0.16 <= back_ratio <= 0.32:
        raise SystemExit(f"后层权重异常：back/main={back_ratio:.3f}")
    if not 0.38 <= front_ratio <= 0.65:
        raise SystemExit(f"前层权重异常：front/main={front_ratio:.3f}")
    if mid_variance < 0.024:
        raise SystemExit(f"帷幔阶段伪深度不足：variance={mid_variance:.4f}")
    if tail_variance > mid_variance * 0.76:
        raise SystemExit(
            "尾段伪深度未按决定衰减："
            f"mid={mid_variance:.4f}, tail={tail_variance:.4f}"
        )
    if density_ratio < MIN_BAND_TAIL_DENSITY_RATIO:
        raise SystemExit(
            "帷幔/尾流真实点密度对比不足："
            f"ratio={density_ratio:.2f}x, coverage={band_coverage:.3f}/{tail_coverage:.3f}"
        )
    if density_ratio > MAX_BAND_TAIL_DENSITY_RATIO:
        raise SystemExit(
            "帷幔/尾流真实点密度对比过强："
            f"ratio={density_ratio:.2f}x, coverage={band_coverage:.3f}/{tail_coverage:.3f}"
        )
    # 开场可以有多个局部起点，中段高密主体已经合并为单一全局卷边。
    # 因此几何并集必须明显窄于旧多队列版本；下限只防止高密面退化成
    # 若干孤立亮点，连续性和真实弯曲另由 morphology/cohesion 回归约束。
    # 普通尾流的前后层抽稀后，union 分母会减小；因此它只作为辅助上限，
    # 主约束仍是不受尾流粒子数影响的全帧几何占比。
    if not 0.02 <= band_support_frame_share <= 0.09 \
            or not 0.10 <= band_support_union_share <= 0.36:
        raise SystemExit(
            "单一卷边高密阶段的总几何范围异常："
            f"frame={band_support_frame_share:.2%}, "
            f"union={band_support_union_share:.2%}"
        )
    if layer_spread_ratio < 1.70:
        raise SystemExit(
            "三层投影仍近似重合："
            f"union/main support={layer_spread_ratio:.2f}x"
        )
    if layer_separation < 13.0:
        raise SystemExit(
            f"前后层有效运动视差不足：centroid={layer_separation:.2f}px"
        )

    toward_light = Scenario("light-a", -60.0, 42, 1.1, -60.0)
    opposite_light = Scenario("light-b", -60.0, 42, 1.1, 120.0)
    first = renderer.render_particle_rgba(toward_light, MID_TIME).astype(np.int16)
    second = renderer.render_particle_rgba(opposite_light, MID_TIME).astype(np.int16)
    visible = np.maximum(first[:, :, 3], second[:, :, 3]) > 4
    light_difference = float(
        np.abs(first[:, :, :3] - second[:, :, :3])[visible].mean()
    )
    if light_difference < 8.0:
        raise SystemExit(f"光源位置未产生可测明暗响应：diff={light_difference:.3f}")

    print(
        f"back/main={back_ratio:.3f}; front/main={front_ratio:.3f}; "
        f"depth mid={mid_variance:.4f}, tail={tail_variance:.4f}; "
        f"occupancy band/tail={density_ratio:.2f}x "
        f"({band_coverage:.3f}/{tail_coverage:.3f}), "
        f"band points={band_share:.1%}, geometry="
        f"{band_support_frame_share:.1%} frame/"
        f"{band_support_union_share:.1%} union; "
        f"layer support={layer_spread_ratio:.2f}x, "
        f"front/back={layer_separation:.2f}px; "
        f"touch-light diff={light_difference:.3f}"
    )


def main() -> None:
    check_unified_color_motion()
    renderer = CurtainRenderer()
    check_color_emphasis(renderer)
    # `layerIndex` 现在表示同轨迹密度副本，不再表示旧版前／中／后空间层；
    # 历史的 check_spatial_depth 保留作诊断工具，但不能再作为当前架构门禁。


if __name__ == "__main__":
    main()
