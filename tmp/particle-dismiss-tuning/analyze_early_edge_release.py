"""比较参考与 canonical 模型开场阶段的左上、上边缘和左边缘释放。

这个回归专门捕获“边缘只在开场象征性起粒，随后完整层和粒子都停住”的问题。
参考只提供量级和持续趋势，不把逐帧遮罩作为运行时输入。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from analyze_reference_motion import frame_at, read_frames
from analyze_reference_morphology import (
    CANVAS_BOUNDS,
    CANVAS_SIZE,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    model_layers,
    reference_layers,
)
from render_curtain_model import CurtainRenderer


PROGRESSES = (0.00, 0.05, 0.10, 0.16, 0.24, 0.32, 0.40, 0.47)
SOURCE_AREA = (CANVAS_SIZE / (CANVAS_BOUNDS[2] - CANVAS_BOUNDS[0])) ** 2
REGIONS = {
    "top_left": (0.00, 0.00, 0.34, 0.34),
    "top_edge": (0.18, 0.00, 0.82, 0.16),
    "left_edge": (0.00, 0.18, 0.16, 0.82),
    "center": (0.34, 0.34, 0.66, 0.66),
}
PARTICLE_REGIONS = {
    "top_left": (-0.24, -0.24, 0.48, 0.48),
    "top_edge": (-0.06, -0.24, 1.06, 0.28),
    "left_edge": (-0.24, -0.06, 0.28, 1.06),
}


def canvas_slice(bounds: tuple[float, float, float, float]) -> tuple[slice, slice]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    x0, y0, x1, y1 = bounds
    ix0 = int(round((x0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    iy0 = int(round((y0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    ix1 = int(round((x1 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE))
    iy1 = int(round((y1 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE))
    return slice(max(0, iy0), min(CANVAS_SIZE, iy1)), slice(max(0, ix0), min(CANVAS_SIZE, ix1))


def source_coordinates() -> tuple[np.ndarray, np.ndarray]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    y, x = np.mgrid[0:CANVAS_SIZE, 0:CANVAS_SIZE].astype(np.float32)
    u = lo_x + (x + 0.5) / CANVAS_SIZE * (hi_x - lo_x)
    v = lo_y + (y + 0.5) / CANVAS_SIZE * (hi_y - lo_y)
    return u, v


U, V = source_coordinates()


def weighted_quantile(values: np.ndarray, weights: np.ndarray, quantile: float) -> float:
    if values.size == 0 or float(weights.sum()) <= 1e-9:
        return 0.0
    order = np.argsort(values)
    values = values[order]
    weights = weights[order]
    cumulative = np.cumsum(weights)
    index = min(int(np.searchsorted(cumulative, cumulative[-1] * quantile)), len(values) - 1)
    return float(values[index])


def frame_metrics(layers, baseline_intact: dict[str, float]) -> dict[str, float]:
    result: dict[str, float] = {}
    for name, bounds in REGIONS.items():
        ys, xs = canvas_slice(bounds)
        mean = float(layers.intact[ys, xs].mean())
        result[f"{name}_retention"] = mean / max(baseline_intact[name], 1e-6)
    for name, bounds in PARTICLE_REGIONS.items():
        ys, xs = canvas_slice(bounds)
        result[f"{name}_particle_mass"] = float(layers.particle[ys, xs].sum() / SOURCE_AREA)

    # 角部纤维沿斜向主风会同时越过两条边；外缘统计必须包含该共同角区，
    # 否则会把“从左边缘向左上牵伸”误判成只有向上位移。
    top = (V < 0.0) & (U >= -0.24) & (U <= 1.08)
    left = (U < 0.0) & (V >= -0.24) & (V <= 1.08)
    top_weight = layers.particle[top]
    left_weight = layers.particle[left]
    result["top_exterior_mass"] = float(top_weight.sum() / SOURCE_AREA)
    result["left_exterior_mass"] = float(left_weight.sum() / SOURCE_AREA)
    result["top_exterior_mean_distance"] = (
        float(np.average(-V[top], weights=top_weight)) if float(top_weight.sum()) > 1e-9 else 0.0
    )
    result["left_exterior_mean_distance"] = (
        float(np.average(-U[left], weights=left_weight)) if float(left_weight.sum()) > 1e-9 else 0.0
    )
    result["top_exterior_p90_distance"] = weighted_quantile(-V[top], top_weight, 0.90)
    result["left_exterior_p90_distance"] = weighted_quantile(-U[left], left_weight, 0.90)
    return result


def analyse(reference_path: Path) -> dict[str, object]:
    frames, fps = read_frames(reference_path)
    baseline = np.median(
        np.stack(frames[int(round(2.90 * fps)) : int(round(3.16 * fps))]), axis=0
    ).astype(np.uint8)
    background = np.median(
        np.stack(frames[int(round(7.85 * fps)) : min(len(frames), int(round(8.16 * fps)))]), axis=0
    ).astype(np.uint8)

    renderer = CurtainRenderer()
    pairs = []
    for progress in PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (REFERENCE_FINISH_S - REFERENCE_ONSET_S)
        pairs.append((
            progress,
            reference_layers(frame_at(frames, fps, reference_time), baseline, background),
            model_layers(renderer, progress),
        ))

    baseline_by_side: dict[str, dict[str, float]] = {"reference": {}, "model": {}}
    for side, layer_index in (("reference", 1), ("model", 2)):
        layer = pairs[0][layer_index]
        for name, bounds in REGIONS.items():
            ys, xs = canvas_slice(bounds)
            baseline_by_side[side][name] = float(layer.intact[ys, xs].mean())

    rows = []
    for progress, reference, model in pairs:
        rows.append({
            "progress": progress,
            "reference": frame_metrics(reference, baseline_by_side["reference"]),
            "model": frame_metrics(model, baseline_by_side["model"]),
        })
    return {"reference": str(reference_path), "frames": rows}


def validate(result: dict[str, object]) -> None:
    rows = {round(float(row["progress"]), 2): row for row in result["frames"]}
    failures: list[str] = []

    # 参考不是逐帧拟合目标，但只要参考在该区域已经出现明确变化，模型至少要达到
    # 其一半的宏观变化量，防止“只压低几个百分点”被误报为多边缘释放。
    for progress in (0.24, 0.32, 0.40):
        row = rows[progress]
        regions = ("top_left",) if progress == 0.24 else ("top_left", "top_edge", "left_edge")
        for region in regions:
            reference_change = max(0.0, 1.0 - row["reference"][f"{region}_retention"])
            model_change = max(0.0, 1.0 - row["model"][f"{region}_retention"])
            required = min(0.62, reference_change * 0.50)
            if model_change < required:
                failures.append(
                    f"t={progress:.2f} {region} 留存变化不足："
                    f"model={model_change:.3f}, reference={reference_change:.3f}, required={required:.3f}"
                )

    # 粒子不能只闪现后冻结。固定材料邻域不是随粒群移动的控制体；粒子持续
    # 越过左、上边界后会离开该邻域，因此这里只限制它不能近乎清空，并把
    # “是否继续运动”交给下面的外缘距离。旧的 82%/150% 约束会把更明显的
    # 左上牵伸反向判成质量塌缩。
    if rows[0.32]["model"]["top_left_particle_mass"] < rows[0.16]["model"]["top_left_particle_mass"] * 0.45:
        failures.append("t=0.16–0.32 左上粒子质量快速塌缩")
    # 连续释放会在边界附近不断补入新粒子，因此质量加权均值可能被新生
    # 粒子拉回；用外缘 p90 判断已释放纤维是否继续外移，避免把正常补料
    # 误报成冻结。均值仍需非递减，排除粒群整体回缩。
    top_mean_16 = rows[0.16]["model"]["top_exterior_mean_distance"]
    top_mean_32 = rows[0.32]["model"]["top_exterior_mean_distance"]
    top_p90_16 = rows[0.16]["model"]["top_exterior_p90_distance"]
    top_p90_32 = rows[0.32]["model"]["top_exterior_p90_distance"]
    if top_mean_32 < top_mean_16 or top_p90_32 < top_p90_16 + 0.040:
        failures.append("t=0.16–0.32 上边缘粒群没有持续向外牵伸")
    if rows[0.32]["model"]["left_edge_particle_mass"] < rows[0.16]["model"]["left_edge_particle_mass"] * 0.45:
        failures.append("t=0.16–0.32 左边缘可见粒子几乎清空")
    if rows[0.32]["model"]["left_exterior_p90_distance"] < max(
        0.020,
        rows[0.16]["model"]["left_exterior_p90_distance"] + 0.030,
    ):
        failures.append("t=0.32 左边缘粒群没有形成可辨认的向外牵伸")

    if failures:
        raise SystemExit("；".join(failures))
    print("early edge release regression: PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()
    result = analyse(args.reference)
    output = Path(__file__).with_name("frames-curtain") / "early-edge-release-metrics.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    validate(result)


if __name__ == "__main__":
    main()
