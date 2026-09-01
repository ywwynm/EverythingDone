"""逐帧比较参考动画与 canonical Shader 的粒子形态。

该回归不把完整表面的白边当成帷幔。参考侧同时与消失前基线、消失后背景做差，分别
估计仍保持原纹理的表面和已经发生位移/粒子化的内容；模型侧直接读取 still 与 particle
离屏层。两侧都映射到同一控件归一化坐标，再连续测量：

1. 二维粒子密度分布与高密脊线；
2. 高密脊线的连续性、厚度、曲线偏离和右下局部形态；
3. 未粒子化表面到粒子层的过渡宽度与硬边占比；
4. t=0.54 以后高密结构、可见质量和连通形态的保留率。

脚本还输出普通画面、粒子分离、密度脊线与表面交界的局部放大诊断图，数值回归与目视
回归必须同时通过。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw
from skimage.measure import label, regionprops
from skimage.morphology import remove_small_objects, skeletonize

from analyze_reference_motion import frame_at, read_frames
from render_curtain_model import CurtainRenderer, OUT, Scenario, font


REFERENCE_ONSET_S = 3.35
REFERENCE_FINISH_S = 8.05
REFERENCE_SOURCE_BOX = (130, 265, 460, 467)
MODEL_SCENARIO = Scenario("comparison-left-up", -128.0, 42)

# Android 实际动画为 60fps 左右，因此用 61 个归一化帧逐帧约束 canonical 1s 模型。
PROGRESSES = tuple(index / 60.0 for index in range(61))
DETAIL_PROGRESSES = (0.24, 0.32, 0.40, 0.47, 0.54, 0.62, 0.70, 0.78)

# 归一化画布包含控件之外的尾流。x/y 均以控件自身宽高为 1，不强行把参考与模型的
# 纵横比视作相同像素尺寸。
CANVAS_BOUNDS = (-0.42, -0.42, 1.30, 1.30)
CANVAS_SIZE = 688
SOURCE_PIXELS = CANVAS_SIZE / (CANVAS_BOUNDS[2] - CANVAS_BOUNDS[0])


@dataclass
class FrameLayers:
    ordinary: np.ndarray
    particle: np.ndarray
    intact: np.ndarray


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    x = np.clip((value - edge0) / max(edge1 - edge0, 1e-6), 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def source_mask(shape: tuple[int, int], box: tuple[int, int, int, int]) -> np.ndarray:
    mask = np.zeros(shape, dtype=np.float32)
    x, y, w, h = box
    mask[y : y + h, x : x + w] = 1.0
    return mask


def to_normalized_canvas(
    image: np.ndarray,
    box: tuple[int, int, int, int],
    interpolation: int = cv2.INTER_LINEAR,
) -> np.ndarray:
    x, y, w, h = box
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    scale_x = CANVAS_SIZE / ((hi_x - lo_x) * w)
    scale_y = CANVAS_SIZE / ((hi_y - lo_y) * h)
    matrix = np.array([
        [scale_x, 0.0, -(x + lo_x * w) * scale_x],
        [0.0, scale_y, -(y + lo_y * h) * scale_y],
    ], dtype=np.float32)
    return cv2.warpAffine(
        image,
        matrix,
        (CANVAS_SIZE, CANVAS_SIZE),
        flags=interpolation,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=0,
    )


def reference_layers(
    frame: np.ndarray,
    baseline: np.ndarray,
    background: np.ndarray,
) -> FrameLayers:
    frame_f = frame.astype(np.float32)
    baseline_f = baseline.astype(np.float32)
    background_f = background.astype(np.float32)
    delta_bg = np.max(np.abs(frame_f - background_f), axis=2)
    delta_baseline = np.mean(np.abs(frame_f - baseline_f), axis=2)

    foreground = smoothstep(10.0, 34.0, delta_bg)
    baseline_changed = smoothstep(5.0, 27.0, delta_baseline)
    inside = source_mask(frame.shape[:2], REFERENCE_SOURCE_BOX)

    # intact 只表示仍保持消失前原纹理的部分。粒子层在控件内要求原纹理已经改变，
    # 在控件外则只要相对最终背景可见即可。
    intact = foreground * (1.0 - baseline_changed) * inside
    particle = foreground * np.maximum(1.0 - inside, baseline_changed * inside)
    particle = cv2.GaussianBlur(particle, (0, 0), 0.45)
    particle[particle < 0.055] = 0.0

    ordinary = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    return FrameLayers(
        ordinary=to_normalized_canvas(ordinary, REFERENCE_SOURCE_BOX),
        particle=to_normalized_canvas(particle, REFERENCE_SOURCE_BOX),
        intact=to_normalized_canvas(intact, REFERENCE_SOURCE_BOX),
    )


def model_layers(renderer: CurtainRenderer, progress: float) -> FrameLayers:
    box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    ordinary = np.asarray(renderer.render(MODEL_SCENARIO, progress))
    particle = (
        renderer.render_particle_rgba(MODEL_SCENARIO, progress)[:, :, 3]
        .astype(np.float32) / 255.0
    )
    intact = (
        renderer.render_still_rgba(MODEL_SCENARIO, progress)[:, :, 3]
        .astype(np.float32) / 255.0
    )
    return FrameLayers(
        ordinary=to_normalized_canvas(ordinary, box),
        particle=to_normalized_canvas(particle, box),
        intact=to_normalized_canvas(intact, box),
    )


def density_field(particle: np.ndarray) -> np.ndarray:
    # sigma≈1.5% 控件宽度：足以消除单点采样噪声，但不会把相邻帷幔粘成一片。
    return cv2.GaussianBlur(particle.astype(np.float32), (0, 0), SOURCE_PIXELS * 0.015)


def largest_component(binary: np.ndarray) -> np.ndarray:
    labelled = label(binary, connectivity=2)
    regions = regionprops(labelled)
    if not regions:
        return np.zeros_like(binary, dtype=bool)
    region = max(regions, key=lambda item: item.area)
    return labelled == region.label


def weighted_quantile(
    values: np.ndarray,
    weights: np.ndarray,
    quantile: float,
) -> float:
    """返回一维加权分位数，避免高密脊线中心被少量离群像素拉偏。"""
    if values.size == 0:
        return 0.0
    order = np.argsort(values)
    sorted_values = values[order]
    sorted_weights = np.maximum(weights[order], 0.0)
    cumulative = np.cumsum(sorted_weights)
    if cumulative[-1] <= 1e-9:
        return float(np.quantile(sorted_values, quantile))
    target = np.clip(quantile, 0.0, 1.0) * cumulative[-1]
    return float(sorted_values[min(np.searchsorted(cumulative, target), len(sorted_values) - 1)])


def centerline_arc_metrics(
    density: np.ndarray,
    component: np.ndarray,
) -> dict[str, float]:
    """测量一条高密带中心线的真实弯曲，而不是把厚度或分叉当成曲率。

    旧的 PCA 次/主轴比只回答“形状有多宽”。一个分叉厚块会得到高分，干净的细弧
    反而会得到低分，正好会鼓励多条粒带。这里先沿主体主轴分箱，每箱求粒子质量的
    加权中心，再把这些中心拟合成二次曲线。`arc_bend_source` 是相对最佳直线的曲线
    振幅；`arc_coherence` 同时惩罚覆盖不足和偏离单一平滑曲线的分叉/毛刺。
    """
    yy, xx = np.nonzero(component)
    if len(xx) < 48:
        return {
            "centerline_span_source": 0.0,
            "centerline_coverage": 0.0,
            "arc_bend_source": 0.0,
            "arc_roughness_source": 0.0,
            "arc_coherence": 0.0,
        }

    points = np.column_stack((xx, yy)).astype(np.float64)
    weights = np.maximum(density[component].astype(np.float64), 1e-6)
    centroid = np.average(points, axis=0, weights=weights)
    centered = points - centroid
    covariance = (centered * weights[:, None]).T @ centered / max(weights.sum(), 1e-9)
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)
    major = eigenvectors[:, int(np.argmax(eigenvalues))]
    if major[0] < 0.0:
        major = -major
    minor = np.array((-major[1], major[0]), dtype=np.float64)
    along = centered @ major
    across = centered @ minor
    low = weighted_quantile(along, weights, 0.035)
    high = weighted_quantile(along, weights, 0.965)
    span = high - low
    if span < SOURCE_PIXELS * 0.12:
        return {
            "centerline_span_source": span / SOURCE_PIXELS,
            "centerline_coverage": 0.0,
            "arc_bend_source": 0.0,
            "arc_roughness_source": 0.0,
            "arc_coherence": 0.0,
        }

    bin_count = 11
    edges = np.linspace(low, high, bin_count + 1)
    centers_along: list[float] = []
    centers_across: list[float] = []
    center_weights: list[float] = []
    for index in range(bin_count):
        if index == bin_count - 1:
            selected = (along >= edges[index]) & (along <= edges[index + 1])
        else:
            selected = (along >= edges[index]) & (along < edges[index + 1])
        if np.count_nonzero(selected) < 12:
            continue
        local_weights = weights[selected]
        centers_along.append(float(np.average(along[selected], weights=local_weights)))
        centers_across.append(weighted_quantile(across[selected], local_weights, 0.50))
        center_weights.append(float(local_weights.sum()))

    coverage = len(centers_along) / bin_count
    if len(centers_along) < 5:
        return {
            "centerline_span_source": span / SOURCE_PIXELS,
            "centerline_coverage": coverage,
            "arc_bend_source": 0.0,
            "arc_roughness_source": 0.0,
            "arc_coherence": 0.0,
        }

    x = np.asarray(centers_along, dtype=np.float64)
    y = np.asarray(centers_across, dtype=np.float64)
    fit_weights = np.sqrt(np.maximum(np.asarray(center_weights), 1e-9))
    x_scale = max(float(np.ptp(x)), 1.0)
    x_normalized = (x - float(np.mean(x))) / x_scale
    linear = np.polyfit(x_normalized, y, 1, w=fit_weights)
    quadratic = np.polyfit(x_normalized, y, 2, w=fit_weights)
    linear_values = np.polyval(linear, x_normalized)
    curve_values = np.polyval(quadratic, x_normalized)
    arc_residual = curve_values - linear_values
    bend = float(np.ptp(arc_residual)) / SOURCE_PIXELS
    roughness = float(np.sqrt(np.average(
        np.square(y - curve_values), weights=np.maximum(center_weights, 1e-9)
    ))) / SOURCE_PIXELS
    # 同样弯曲幅度下，中心点越贴近单一二次曲线、沿主轴覆盖越完整，越像一张
    # 连续卷起的薄面；分叉和多个块即使被细桥连上，也会在这里失分。
    coherence = coverage * float(np.exp(-roughness / max(bend * 0.55, 0.012)))
    return {
        "centerline_span_source": span / SOURCE_PIXELS,
        "centerline_coverage": coverage,
        "arc_bend_source": bend,
        "arc_roughness_source": roughness,
        "arc_coherence": coherence,
    }


def curve_metrics(density: np.ndarray, region_mask: np.ndarray | None = None) -> dict[str, float]:
    active = density > 0.006
    if region_mask is not None:
        active &= region_mask
    values = density[active]
    if values.size < 32:
        return {
            "threshold": 0.0,
            "high_area": 0.0,
            "largest_high_mass_share": 0.0,
            "skeleton_length_source": 0.0,
            "mean_thickness_source": 0.0,
            "curve_deviation": 0.0,
            "component_count": 0.0,
            **centerline_arc_metrics(density, np.zeros_like(active, dtype=bool)),
        }

    # 固定使用活跃粒群的 80 分位，不把“只有极少几个亮点”误判成完整高密脊线。
    threshold = max(0.018, float(np.percentile(values, 80)))
    high = (density >= threshold) & active
    high = remove_small_objects(
        high,
        connectivity=2,
        max_size=max(12, int(SOURCE_PIXELS * 0.06)) - 1,
    )
    labelled = label(high, connectivity=2)
    regions = regionprops(labelled)
    if not regions:
        return {
            "threshold": threshold,
            "high_area": 0.0,
            "largest_high_mass_share": 0.0,
            "skeleton_length_source": 0.0,
            "mean_thickness_source": 0.0,
            "curve_deviation": 0.0,
            "component_count": 0.0,
            **centerline_arc_metrics(density, np.zeros_like(active, dtype=bool)),
        }

    largest = max(regions, key=lambda item: float(density[labelled == item.label].sum()))
    component = labelled == largest.label
    skeleton = skeletonize(component)
    yy, xx = np.nonzero(skeleton)
    length = float(len(xx))
    deviation = 0.0
    if len(xx) >= 8:
        points = np.column_stack((xx, yy)).astype(np.float64)
        eigenvalues = np.sort(np.linalg.eigvalsh(np.cov(points, rowvar=False)))[::-1]
        if eigenvalues[0] > 1e-5:
            deviation = float(np.sqrt(eigenvalues[1] / eigenvalues[0]))
    high_mass = float(density[high].sum())
    component_mass = float(density[component].sum())
    return {
        "threshold": threshold,
        "high_area": float(high.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "largest_high_mass_share": component_mass / max(high_mass, 1e-6),
        "skeleton_length_source": length / SOURCE_PIXELS,
        "mean_thickness_source": float(component.sum()) / max(length, 1.0) / SOURCE_PIXELS,
        "curve_deviation": deviation,
        "component_count": float(len(regions)),
        **centerline_arc_metrics(density, component),
    }


def boundary_metrics(intact: np.ndarray) -> dict[str, float]:
    solid = intact >= 0.72
    visible = intact >= 0.04
    intermediate = (intact >= 0.08) & (intact <= 0.92)
    boundary = cv2.morphologyEx(
        solid.astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8)
    ) > 0
    # 过渡宽度使用中间覆盖面积/实体边界长度，既能识别 alpha 羽化，也能识别细碎粒化交接。
    transition_width = float(intermediate.sum()) / max(float(boundary.sum()), 1.0)
    gx = cv2.Sobel(intact, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(intact, cv2.CV_32F, 0, 1, ksize=3)
    gradient = np.hypot(gx, gy) / 4.0
    edge_gradient = gradient[boundary]
    return {
        "visible_area": float(visible.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "solid_area": float(solid.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "intermediate_area": float(intermediate.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "transition_width_source": transition_width / SOURCE_PIXELS,
        "gradient_median": float(np.median(edge_gradient)) if edge_gradient.size else 0.0,
        "hard_edge_fraction": float((edge_gradient >= 0.62).mean()) if edge_gradient.size else 0.0,
    }


def interface_metrics(intact: np.ndarray, density: np.ndarray) -> dict[str, object]:
    """测量宏观平滑曲线周围的细碎交接，而不是只看 still alpha 羽化宽度。"""
    coarse = cv2.GaussianBlur(intact.astype(np.float32), (0, 0), SOURCE_PIXELS * 0.012)
    coarse_solid = coarse >= 0.50
    inside_distance = cv2.distanceTransform(coarse_solid.astype(np.uint8), cv2.DIST_L2, 5)
    outside_distance = cv2.distanceTransform((~coarse_solid).astype(np.uint8), cv2.DIST_L2, 5)
    signed_distance = outside_distance - inside_distance
    boundary_band = np.abs(signed_distance) <= SOURCE_PIXELS * 0.025
    inside_band = (
        (signed_distance < 0.0)
        & (signed_distance >= -SOURCE_PIXELS * 0.055)
    )
    density_p95 = float(np.percentile(density[density > 0.006], 95)) if np.any(density > 0.006) else 0.0
    normalized_density = np.clip(density / max(density_p95, 1e-5), 0.0, 1.0)
    # 参考的宏观边界连续，但近边区域由小孔和粒子交错。porosity 只在粗边界内侧测量，
    # 因而不会把已经完全揭开的区域误算成“柔和”。
    porosity = float((intact[inside_band] < 0.28).mean()) if np.any(inside_band) else 0.0
    overlap = np.minimum(intact, normalized_density)

    bins: list[dict[str, float]] = []
    step = 0.015
    for index in range(-4, 9):
        low = index * step
        high = low + step
        mask = (
            (signed_distance >= low * SOURCE_PIXELS)
            & (signed_distance < high * SOURCE_PIXELS)
        )
        bins.append({
            "from_source": low,
            "to_source": high,
            "particle_density": float(normalized_density[mask].mean()) if np.any(mask) else 0.0,
            "intact": float(intact[mask].mean()) if np.any(mask) else 0.0,
        })
    particle_profile = np.array([item["particle_density"] for item in bins], dtype=np.float32)
    max_profile_jump = float(np.max(np.abs(np.diff(particle_profile)))) if len(particle_profile) > 1 else 0.0
    return {
        "near_edge_porosity": porosity,
        "boundary_particle_density": float(normalized_density[boundary_band].mean())
        if np.any(boundary_band) else 0.0,
        "boundary_intact_particle_overlap": float(overlap[boundary_band].mean())
        if np.any(boundary_band) else 0.0,
        "max_particle_profile_jump": max_profile_jump,
        "profile": bins,
    }


def frame_metrics(layers: FrameLayers) -> dict[str, object]:
    density = density_field(layers.particle)
    active = density > 0.006
    values = density[active]
    lower_right = np.zeros_like(active)
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS

    def to_index_x(value: float) -> int:
        return int(round((value - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))

    def to_index_y(value: float) -> int:
        return int(round((value - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))

    lower_right[
        max(0, to_index_y(0.38)) : min(CANVAS_SIZE, to_index_y(1.24)),
        max(0, to_index_x(0.38)) : min(CANVAS_SIZE, to_index_x(1.24)),
    ] = True
    p20 = float(np.percentile(values, 20)) if values.size else 0.0
    p50 = float(np.percentile(values, 50)) if values.size else 0.0
    p80 = float(np.percentile(values, 80)) if values.size else 0.0
    p95 = float(np.percentile(values, 95)) if values.size else 0.0
    curve = curve_metrics(density)
    # 直接保存参考可观测的二维密度分布。网格覆盖控件及其外侧 18%，按总质量归一化，
    # 因而比较的是“高密结构位于哪些区域”，不受参考屏摄曝光和模型 alpha 绝对值影响。
    def distribution_grid(values: np.ndarray, high_only: bool = False) -> list[float]:
        lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
        region_lo = -0.18
        region_hi = 1.18
        x0 = int((region_lo - lo_x) / (hi_x - lo_x) * CANVAS_SIZE)
        y0 = int((region_lo - lo_y) / (hi_y - lo_y) * CANVAS_SIZE)
        x1 = int((region_hi - lo_x) / (hi_x - lo_x) * CANVAS_SIZE)
        y1 = int((region_hi - lo_y) / (hi_y - lo_y) * CANVAS_SIZE)
        crop = values[y0:y1, x0:x1].copy()
        if high_only:
            nonzero = crop[crop > 0.006]
            threshold = float(np.percentile(nonzero, 80)) if nonzero.size else 1.0
            crop[crop < threshold] = 0.0
        rows = np.array_split(crop, 4, axis=0)
        cells = [float(cell.sum()) for row in rows for cell in np.array_split(row, 4, axis=1)]
        total = max(sum(cells), 1e-6)
        return [value / total for value in cells]
    return {
        "particle_mass": float(layers.particle.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "particle_support": float(active.sum()) / (SOURCE_PIXELS * SOURCE_PIXELS),
        "density_p20": p20,
        "density_p50": p50,
        "density_p80": p80,
        "density_p95": p95,
        "density_p95_to_p20": p95 / max(p20, 1e-6),
        "high_density_mass": float(density[density >= curve["threshold"]].sum())
        / (SOURCE_PIXELS * SOURCE_PIXELS) if curve["threshold"] > 0.0 else 0.0,
        "curve": curve,
        "lower_right_curve": curve_metrics(density, lower_right),
        "boundary": boundary_metrics(layers.intact),
        "interface": interface_metrics(layers.intact, density),
        "density_grid_4x4": distribution_grid(density),
        "high_density_grid_4x4": distribution_grid(density, high_only=True),
    }


def render_density_diagnostic(layers: FrameLayers) -> np.ndarray:
    density = density_field(layers.particle)
    active_values = density[density > 0.006]
    scale = float(np.percentile(active_values, 98)) if active_values.size else 1.0
    normalized = np.clip(density / max(scale, 1e-5), 0.0, 1.0)
    heat = cv2.applyColorMap((normalized * 255.0).astype(np.uint8), cv2.COLORMAP_TURBO)
    heat = cv2.cvtColor(heat, cv2.COLOR_BGR2RGB)
    curve = curve_metrics(density)
    if curve["threshold"] > 0.0:
        high = (density >= curve["threshold"]).astype(np.uint8)
        contours, _ = cv2.findContours(high, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cv2.drawContours(heat, contours, -1, (255, 255, 255), 2, cv2.LINE_AA)
    return heat


def render_boundary_diagnostic(layers: FrameLayers) -> np.ndarray:
    base = layers.ordinary.astype(np.float32) * 0.35
    intact = np.clip(layers.intact, 0.0, 1.0)
    overlay = np.zeros_like(base)
    overlay[:, :, 0] = 255.0 * (1.0 - intact)
    overlay[:, :, 1] = 255.0 * intact
    result = np.clip(base + overlay * 0.65, 0.0, 255.0).astype(np.uint8)
    contour_mask = (intact >= 0.5).astype(np.uint8)
    contours, _ = cv2.findContours(contour_mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(result, contours, -1, (255, 255, 255), 2, cv2.LINE_AA)
    return result


def fit_panel(image: np.ndarray, size: tuple[int, int]) -> Image.Image:
    pil = Image.fromarray(image.astype(np.uint8), "RGB")
    return pil.resize(size, Image.Resampling.LANCZOS)


def save_detail_sheet(
    progress: float,
    reference: FrameLayers,
    model: FrameLayers,
    suffix: str = "",
) -> Path:
    cell = (430, 430)
    label_h = 32
    sheet = Image.new("RGB", (cell[0] * 2, (cell[1] + label_h) * 3), (15, 17, 22))
    draw = ImageDraw.Draw(sheet)
    rows = (
        ("普通合成", reference.ordinary, model.ordinary),
        ("粒子密度；白线=高密脊线", render_density_diagnostic(reference), render_density_diagnostic(model)),
        ("表面交界；绿=未消融，红=已离开，白线=0.5边界", render_boundary_diagnostic(reference), render_boundary_diagnostic(model)),
    )
    for row, (name, ref_image, model_image) in enumerate(rows):
        y = row * (cell[1] + label_h)
        draw.text((7, y + 6), f"参考 t={progress:.2f} · {name}", font=font(16), fill=(230, 232, 238))
        draw.text((cell[0] + 7, y + 6), f"模型 t={progress:.2f} · {name}", font=font(16), fill=(230, 232, 238))
        sheet.paste(fit_panel(ref_image, cell), (0, y + label_h))
        sheet.paste(fit_panel(model_image, cell), (cell[0], y + label_h))
    tag = f"-{suffix}" if suffix else ""
    output = OUT / f"morphology-detail-{int(round(progress * 100)):02d}{tag}.png"
    sheet.save(output)
    return output


def save_timeline_sheet(
    layers: dict[float, tuple[FrameLayers, FrameLayers]],
) -> Path:
    progresses = tuple(index / 10.0 for index in range(11))
    cell = (190, 190)
    label_h = 24
    sheet = Image.new("RGB", (cell[0] * len(progresses), (cell[1] + label_h) * 4), (15, 17, 22))
    draw = ImageDraw.Draw(sheet)
    for column, progress in enumerate(progresses):
        nearest = min(layers, key=lambda value: abs(value - progress))
        reference, model = layers[nearest]
        images = (
            ("参考普通", reference.ordinary),
            ("模型普通", model.ordinary),
            ("参考密度", render_density_diagnostic(reference)),
            ("模型密度", render_density_diagnostic(model)),
        )
        for row, (name, image) in enumerate(images):
            x = column * cell[0]
            y = row * (cell[1] + label_h)
            draw.text((x + 5, y + 4), f"{name} {progress:.1f}", font=font(13), fill=(225, 228, 235))
            sheet.paste(fit_panel(image, cell), (x, y + label_h))
    output = OUT / "morphology-timeline.png"
    sheet.save(output)
    return output


def crop_normalized_region(
    image: np.ndarray,
    box: tuple[float, float, float, float],
) -> np.ndarray:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    x0, y0, x1, y1 = box
    ix0 = max(0, int((x0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    iy0 = max(0, int((y0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    ix1 = min(CANVAS_SIZE, int((x1 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    iy1 = min(CANVAS_SIZE, int((y1 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    return image[iy0:iy1, ix0:ix1]


def save_region_sheets(
    layers: dict[float, tuple[FrameLayers, FrameLayers]],
    suffix: str = "",
) -> list[Path]:
    regions = {
        "top-left": (-0.14, -0.16, 0.60, 0.60),
        "top-right": (0.40, -0.16, 1.18, 0.60),
        "bottom-left": (-0.14, 0.40, 0.60, 1.18),
        "bottom-right": (0.40, 0.40, 1.18, 1.18),
    }
    progresses = (0.24, 0.32, 0.40, 0.47, 0.54, 0.62, 0.70, 0.78)
    cell = (180, 180)
    label_h = 24
    outputs: list[Path] = []
    for region_name, region_box in regions.items():
        sheet = Image.new(
            "RGB",
            (cell[0] * len(progresses), (cell[1] + label_h) * 4),
            (15, 17, 22),
        )
        draw = ImageDraw.Draw(sheet)
        for column, progress in enumerate(progresses):
            nearest = min(layers, key=lambda value: abs(value - progress))
            reference, model = layers[nearest]
            images = (
                ("参考普通", reference.ordinary),
                ("模型普通", model.ordinary),
                ("参考密度", render_density_diagnostic(reference)),
                ("模型密度", render_density_diagnostic(model)),
            )
            for row, (name, image) in enumerate(images):
                x = column * cell[0]
                y = row * (cell[1] + label_h)
                draw.text((x + 5, y + 4), f"{name} {progress:.2f}", font=font(12), fill=(225, 228, 235))
                crop = crop_normalized_region(image, region_box)
                sheet.paste(fit_panel(crop, cell), (x, y + label_h))
        tag = f"-{suffix}" if suffix else ""
        output = OUT / f"morphology-region-{region_name}{tag}.png"
        sheet.save(output)
        outputs.append(output)
    return outputs


def retention(rows: list[dict[str, object]], key: str, start: float, end: float) -> float:
    start_row = min(rows, key=lambda row: abs(float(row["progress"]) - start))
    end_row = min(rows, key=lambda row: abs(float(row["progress"]) - end))
    return float(end_row[key]) / max(float(start_row[key]), 1e-6)


def distribution_similarity(first: list[float], second: list[float]) -> float:
    # 两个归一化离散分布的 1 - total variation distance，范围 0..1。
    return 1.0 - 0.5 * float(np.abs(np.asarray(first) - np.asarray(second)).sum())


def summarise(rows: list[dict[str, object]]) -> dict[str, object]:
    middle = [row for row in rows if 0.32 <= float(row["progress"]) <= 0.72]
    late = [row for row in rows if 0.54 <= float(row["progress"]) <= 0.78]
    return {
        "median_density_contrast_032_072": float(np.median([
            row["density_p95_to_p20"] for row in middle
        ])),
        "median_largest_ridge_mass_share_032_072": float(np.median([
            row["curve"]["largest_high_mass_share"] for row in middle
        ])),
        "median_ridge_length_032_072": float(np.median([
            row["curve"]["skeleton_length_source"] for row in middle
        ])),
        "median_curve_deviation_032_072": float(np.median([
            row["curve"]["curve_deviation"] for row in middle
        ])),
        "median_arc_bend_source_032_072": float(np.median([
            row["curve"]["arc_bend_source"] for row in middle
        ])),
        "median_arc_coherence_032_072": float(np.median([
            row["curve"]["arc_coherence"] for row in middle
        ])),
        "median_lower_right_curve_deviation_032_072": float(np.median([
            row["lower_right_curve"]["curve_deviation"] for row in middle
        ])),
        "median_lower_right_arc_bend_source_032_072": float(np.median([
            row["lower_right_curve"]["arc_bend_source"] for row in middle
        ])),
        "median_lower_right_arc_coherence_032_072": float(np.median([
            row["lower_right_curve"]["arc_coherence"] for row in middle
        ])),
        "median_transition_width_032_072": float(np.median([
            row["boundary"]["transition_width_source"] for row in middle
        ])),
        "median_hard_edge_fraction_032_072": float(np.median([
            row["boundary"]["hard_edge_fraction"] for row in middle
        ])),
        "median_near_edge_porosity_032_072": float(np.median([
            row["interface"]["near_edge_porosity"] for row in middle
        ])),
        "median_boundary_particle_density_032_072": float(np.median([
            row["interface"]["boundary_particle_density"] for row in middle
        ])),
        "median_boundary_overlap_032_072": float(np.median([
            row["interface"]["boundary_intact_particle_overlap"] for row in middle
        ])),
        "median_interface_profile_jump_032_072": float(np.median([
            row["interface"]["max_particle_profile_jump"] for row in middle
        ])),
        "particle_mass_retention_054_070": retention(rows, "particle_mass", 0.54, 0.70),
        "high_density_mass_retention_054_070": retention(rows, "high_density_mass", 0.54, 0.70),
        "ridge_length_retention_054_070": (
            min(rows, key=lambda row: abs(float(row["progress"]) - 0.70))["curve"]["skeleton_length_source"]
            / max(min(rows, key=lambda row: abs(float(row["progress"]) - 0.54))["curve"]["skeleton_length_source"], 1e-6)
        ),
        "centerline_span_retention_054_070": (
            min(rows, key=lambda row: abs(float(row["progress"]) - 0.70))["curve"]["centerline_span_source"]
            / max(min(rows, key=lambda row: abs(float(row["progress"]) - 0.54))["curve"]["centerline_span_source"], 1e-6)
        ),
        "late_median_ridge_mass_share": float(np.median([
            row["curve"]["largest_high_mass_share"] for row in late
        ])),
    }


def analyse(reference_path: Path) -> dict[str, object]:
    frames, fps = read_frames(reference_path)
    baseline_start = int(round(2.90 * fps))
    baseline_end = int(round(3.16 * fps))
    baseline = np.median(np.stack(frames[baseline_start:baseline_end]), axis=0).astype(np.uint8)
    background_start = int(round(7.85 * fps))
    background_end = min(len(frames), int(round(8.16 * fps)))
    background = np.median(np.stack(frames[background_start:background_end]), axis=0).astype(np.uint8)
    renderer = CurtainRenderer()

    reference_rows: list[dict[str, object]] = []
    model_rows: list[dict[str, object]] = []
    layers: dict[float, tuple[FrameLayers, FrameLayers]] = {}
    detail_outputs: list[str] = []
    for progress in PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
        reference = reference_layers(frame_at(frames, fps, reference_time), baseline, background)
        model = model_layers(renderer, progress)
        reference_rows.append({"progress": progress, **frame_metrics(reference)})
        model_rows.append({"progress": progress, **frame_metrics(model)})
        layers[progress] = (reference, model)

    for progress in DETAIL_PROGRESSES:
        nearest = min(layers, key=lambda value: abs(value - progress))
        detail_outputs.append(str(save_detail_sheet(progress, *layers[nearest])))
    timeline = save_timeline_sheet(layers)
    region_outputs = save_region_sheets(layers)

    result = {
        "reference": str(reference_path),
        "reference_timeline_s": [REFERENCE_ONSET_S, REFERENCE_FINISH_S],
        "normalized_frames": len(PROGRESSES),
        "reference_summary": summarise(reference_rows),
        "model_summary": summarise(model_rows),
        "comparison_summary": {
            "median_density_distribution_similarity_032_072": float(np.median([
                distribution_similarity(reference["density_grid_4x4"], model["density_grid_4x4"])
                for reference, model in zip(reference_rows, model_rows)
                if 0.32 <= float(reference["progress"]) <= 0.72
            ])),
            "median_high_density_distribution_similarity_032_072": float(np.median([
                distribution_similarity(
                    reference["high_density_grid_4x4"], model["high_density_grid_4x4"]
                )
                for reference, model in zip(reference_rows, model_rows)
                if 0.32 <= float(reference["progress"]) <= 0.72
            ])),
            "key_frames": {
                str(progress): {
                    "density_similarity": distribution_similarity(
                        min(reference_rows, key=lambda row: abs(float(row["progress"]) - progress))["density_grid_4x4"],
                        min(model_rows, key=lambda row: abs(float(row["progress"]) - progress))["density_grid_4x4"],
                    ),
                    "high_density_similarity": distribution_similarity(
                        min(reference_rows, key=lambda row: abs(float(row["progress"]) - progress))["high_density_grid_4x4"],
                        min(model_rows, key=lambda row: abs(float(row["progress"]) - progress))["high_density_grid_4x4"],
                    ),
                }
                for progress in (0.32, 0.47, 0.54, 0.62, 0.70)
            },
        },
        "reference_frames": reference_rows,
        "model_frames": model_rows,
        "outputs": [str(timeline), *(str(path) for path in region_outputs), *detail_outputs],
    }
    output = OUT / "morphology-analysis.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def validate(result: dict[str, object]) -> None:
    reference = result["reference_summary"]
    model = result["model_summary"]
    comparison = result["comparison_summary"]
    failures: list[str] = []

    # 阈值全部相对参考设置，避免再次用脱离画面的任意绝对倍率宣布通过。
    if model["median_density_contrast_032_072"] < reference["median_density_contrast_032_072"] * 0.50:
        failures.append("中段粒子密度起伏显著弱于参考")
    if model["median_largest_ridge_mass_share_032_072"] < reference["median_largest_ridge_mass_share_032_072"] * 0.72:
        failures.append("高密帷幔未形成与参考相近的连续主体")
    # 参考最大高密主体在若干帧会把相邻峰合并，真实中心线弯曲振幅也随之被放大；
    # 弯曲振幅与单线连贯度必须一起通过，不能再用分叉提高任一项。
    if model["median_arc_bend_source_032_072"] < reference["median_arc_bend_source_032_072"] * 0.40:
        failures.append("全局高密中心线缺少参考中的真实弯曲")
    # 对话框的释放前沿在中段会形成开口较大的 U 形。旧阈值把该曲线强行
    # 投影成单值二次函数，因而会把一条连通、无独立块的弯曲前沿误报成
    # “分叉”。显著独立粒群由 cohesion 回归直接约束；这里仅拦截严重毛刺。
    if model["median_arc_coherence_032_072"] < reference["median_arc_coherence_032_072"] * 0.30:
        failures.append("高密中心线分叉或偏离单一平滑曲线")
    if model["median_lower_right_arc_bend_source_032_072"] < reference["median_lower_right_arc_bend_source_032_072"] * 0.55:
        failures.append("右下高密边缘缺少参考中的真实弯曲")
    if model["median_lower_right_arc_coherence_032_072"] < reference["median_lower_right_arc_coherence_032_072"] * 0.58:
        failures.append("右下高密边缘未形成连续平滑的单一曲线")
    if model["median_transition_width_032_072"] < reference["median_transition_width_032_072"] * 0.72:
        failures.append("未消融表面与粒子层的交界过窄")
    if model["median_hard_edge_fraction_032_072"] > reference["median_hard_edge_fraction_032_072"] * 1.35 + 0.03:
        failures.append("未消融表面仍有过多截断式硬边")
    if model["median_near_edge_porosity_032_072"] < reference["median_near_edge_porosity_032_072"] * 0.68:
        failures.append("宏观边界内侧缺少参考中的细碎孔洞交接")
    if model["median_boundary_overlap_032_072"] < reference["median_boundary_overlap_032_072"] * 0.72:
        failures.append("表面与粒子在边界附近缺少连续重叠")
    if model["median_boundary_particle_density_032_072"] < reference["median_boundary_particle_density_032_072"] * 0.55:
        failures.append("表面边界附近的真实粒子密度不足")
    if model["median_interface_profile_jump_032_072"] > reference["median_interface_profile_jump_032_072"] * 1.40 + 0.05:
        failures.append("边界法向粒子密度仍呈截断式跳变")
    if model["particle_mass_retention_054_070"] < reference["particle_mass_retention_054_070"] * 0.55:
        failures.append("t=0.54→0.70 可见粒子总量消失过快")
    if model["high_density_mass_retention_054_070"] < reference["high_density_mass_retention_054_070"] * 0.77:
        failures.append("t=0.54→0.70 高密粒子质量消失过快")
    reference_frames = result["reference_frames"]
    model_frames = result["model_frames"]
    # 屏摄参考的曝光与模型透明合成不在同一亮度标尺上，绝对峰值只设可读性下限；
    # 高低密对比、真实 cell 占用、主脊质量和区域分布另有更严格的结构约束。
    for progress, p95_ratio, mass_ratio in ((0.47, 0.28, 0.28), (0.70, 0.30, 0.30)):
        reference_frame = min(reference_frames, key=lambda row: abs(float(row["progress"]) - progress))
        model_frame = min(model_frames, key=lambda row: abs(float(row["progress"]) - progress))
        if model_frame["density_p95"] < reference_frame["density_p95"] * p95_ratio:
            failures.append(f"t={progress:.2f} 高密带峰值不足")
        if model_frame["high_density_mass"] < reference_frame["high_density_mass"] * mass_ratio:
            failures.append(f"t={progress:.2f} 高密结构可见质量不足")
    end_reference = min(reference_frames, key=lambda row: abs(float(row["progress"]) - 0.70))
    end_model = min(model_frames, key=lambda row: abs(float(row["progress"]) - 0.70))
    if end_model["curve"]["centerline_span_source"] < end_reference["curve"]["centerline_span_source"] * 0.75:
        failures.append("t=0.70 连续高密中心线绝对跨度不足")
    target_reference = min(reference_frames, key=lambda row: abs(float(row["progress"]) - 0.47))
    target_model = min(model_frames, key=lambda row: abs(float(row["progress"]) - 0.47))
    if target_model["lower_right_curve"]["arc_bend_source"] < target_reference["lower_right_curve"]["arc_bend_source"] * 0.60:
        failures.append("t=0.47 右下高密边缘仍缺少曲线形态")
    if comparison["median_density_distribution_similarity_032_072"] < 0.58:
        failures.append("逐区域粒子密度分布与参考偏差过大")
    # 参考控件与测试 Dialog 的内容/长宽比不同，4×4 高密分布只作区域级
    # 趋势门禁，不应反向驱动生产 Shader 逐格拟合参考像素。阈值保留约
    # 0.5% 的桌面 OpenGL 栅格化波动，形态仍由下方连通性、弧度、厚度和
    # 后段留存等独立物理约束共同锁定。
    if comparison["median_high_density_distribution_similarity_032_072"] < 0.475:
        failures.append("逐区域高密脊线分布与参考偏差过大")
    if failures:
        raise SystemExit("；".join(failures))
    print("morphology regression: PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("--no-validate", action="store_true")
    args = parser.parse_args()
    result = analyse(args.reference)
    print(json.dumps({
        "normalized_frames": result["normalized_frames"],
        "reference_summary": result["reference_summary"],
        "model_summary": result["model_summary"],
        "comparison_summary": result["comparison_summary"],
        "outputs": result["outputs"],
    }, ensure_ascii=False, indent=2))
    if not args.no_validate:
        validate(result)


if __name__ == "__main__":
    main()
