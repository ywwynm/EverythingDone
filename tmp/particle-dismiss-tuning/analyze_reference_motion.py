"""量化不透明控件粒子化参考视频的断裂锋线与二维输运。

脚本不尝试识别视频来源或产品实现，只把消失前后的静态画面相减，区分完整表面、
高密度断裂锋线和稀疏尾流，并用稠密光流估计尾流的主速度与方向离散度。
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np


def read_frames(path: Path) -> tuple[list[np.ndarray], float]:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise RuntimeError(f"无法打开视频：{path}")
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    frames: list[np.ndarray] = []
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        frames.append(frame)
    capture.release()
    return frames, fps


def frame_at(frames: list[np.ndarray], fps: float, seconds: float) -> np.ndarray:
    index = int(round(seconds * fps))
    return frames[min(max(index, 0), len(frames) - 1)]


def largest_component_box(mask: np.ndarray) -> tuple[int, int, int, int]:
    count, _labels, stats, _centroids = cv2.connectedComponentsWithStats(mask, 8)
    if count <= 1:
        raise RuntimeError("没有找到消失控件区域")
    component = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    x, y, w, h, _area = stats[component]
    return int(x), int(y), int(w), int(h)


def foreground_mask(
    frame: np.ndarray,
    background: np.ndarray,
    analysis_roi: tuple[int, int, int, int],
) -> np.ndarray:
    delta = cv2.absdiff(frame, background)
    # max-channel 差比灰度差更能保留蓝紫等高饱和小粒子。
    mask = (delta.max(axis=2) >= 20).astype(np.uint8)
    x, y, w, h = analysis_roi
    roi_mask = np.zeros_like(mask)
    roi_mask[y : y + h, x : x + w] = 1
    mask *= roi_mask
    # 只清除孤立压缩噪点；不能闭运算，否则会把真实稀疏尾流重新粘成片。
    neighbours = cv2.boxFilter(mask.astype(np.float32), -1, (3, 3), normalize=False)
    mask[(mask > 0) & (neighbours < 2.0)] = 0
    return mask


def angle_degrees(vector: np.ndarray) -> float:
    # Android/图像坐标：0° 向右，-90° 向上，-135° 向左上。
    return math.degrees(math.atan2(float(vector[1]), float(vector[0])))


def percentile_or_zero(values: np.ndarray, percentile: float) -> float:
    return float(np.percentile(values, percentile)) if values.size else 0.0


def analyse(video: Path, output: Path) -> dict[str, object]:
    frames, fps = read_frames(video)
    duration = len(frames) / fps

    # 7.85–8.15 秒已经只剩静态桌面；取中值可压低 H.264 块噪声。
    bg_start = int(round(7.85 * fps))
    bg_end = min(len(frames), int(round(8.15 * fps)) + 1)
    background = np.median(np.stack(frames[bg_start:bg_end]), axis=0).astype(np.uint8)

    height, width = frames[0].shape[:2]
    analysis_roi = (55, 105, min(610, width - 55), min(800, height - 105))

    baseline = frame_at(frames, fps, 3.00)
    baseline_mask = foreground_mask(baseline, background, analysis_roi)
    baseline_dense = (cv2.boxFilter(
        baseline_mask.astype(np.float32), -1, (19, 19), normalize=True
    ) > 0.72).astype(np.uint8)
    baseline_dense = cv2.morphologyEx(
        baseline_dense, cv2.MORPH_CLOSE, np.ones((17, 17), np.uint8)
    )
    source_box = largest_component_box(baseline_dense)
    sx, sy, sw, sh = source_box
    side_flow_roi = np.zeros((height, width), dtype=bool)
    side_flow_roi[
        int(sy + sh * 0.42) : min(height, int(sy + sh + 120)),
        max(0, int(sx - 120)) : min(width, int(sx + sw * 0.60)),
    ] = True
    lift_roi = np.zeros((height, width), dtype=bool)
    lift_roi[
        max(0, int(sy + sh * 0.10)) : min(height, int(sy + sh + 100)),
        max(0, int(sx - 140)) : min(width, int(sx + sw * 0.65)),
    ] = True
    # 消除由圆角和内容纹理导致的面积偏差，所有比例都相对消失前基线计算。
    source_slice = np.s_[sy : sy + sh, sx : sx + sw]
    baseline_dense_area = int(baseline_dense[source_slice].sum())

    samples: list[dict[str, float]] = []
    sample_step = max(1, int(round(fps / 20.0)))
    start_index = int(round(2.90 * fps))
    end_index = min(len(frames) - 1, int(round(8.15 * fps)))
    masks: dict[int, np.ndarray] = {}
    occupancies: dict[int, np.ndarray] = {}

    for index in range(start_index, end_index + 1, sample_step):
        mask = foreground_mask(frames[index], background, analysis_roi)
        occupancy = cv2.boxFilter(mask.astype(np.float32), -1, (19, 19), normalize=True)
        dense = (occupancy > 0.72).astype(np.uint8)
        front = (mask > 0) & (occupancy >= 0.24) & (occupancy <= 0.72)
        tail = (mask > 0) & (occupancy < 0.24)
        dense_area = int(dense[source_slice].sum())
        front_area = int(front.sum())
        tail_area = int(tail.sum())
        foreground_area = int(mask.sum())
        ys, xs = np.nonzero(tail)
        samples.append({
            "time_s": round(index / fps, 4),
            "intact_ratio": dense_area / max(1, baseline_dense_area),
            "front_pixels": float(front_area),
            "tail_pixels": float(tail_area),
            "foreground_pixels": float(foreground_area),
            "tail_centroid_x": float(xs.mean()) if xs.size else 0.0,
            "tail_centroid_y": float(ys.mean()) if ys.size else 0.0,
        })
        masks[index] = mask
        occupancies[index] = occupancy

    baseline_samples = [sample for sample in samples if sample["time_s"] <= 3.20]
    baseline_fg = float(np.median([
        sample["foreground_pixels"] for sample in baseline_samples
    ]))
    baseline_intact = float(np.median([
        sample["intact_ratio"] for sample in baseline_samples
    ]))
    baseline_tail = float(np.median([
        sample["tail_pixels"] for sample in baseline_samples
    ]))
    for sample in samples:
        sample["intact_ratio"] /= max(0.001, baseline_intact)
    onset_candidates = [
        sample for sample in samples
        if sample["time_s"] >= 3.15
        and (
            sample["foreground_pixels"] < baseline_fg * 0.989
            or sample["tail_pixels"] > baseline_tail + max(450.0, baseline_fg * 0.008)
        )
    ]
    onset = onset_candidates[0]["time_s"] if onset_candidates else samples[0]["time_s"]
    surface_finish_candidates = [
        sample for sample in samples
        if sample["time_s"] > onset and sample["intact_ratio"] < 0.01
    ]
    surface_finish = (
        surface_finish_candidates[0]["time_s"]
        if surface_finish_candidates else samples[-1]["time_s"]
    )
    finish_candidates = [
        sample for sample in samples
        if sample["time_s"] > onset and sample["foreground_pixels"] < baseline_fg * 0.012
    ]
    finish = finish_candidates[0]["time_s"] if finish_candidates else samples[-1]["time_s"]

    # 逐帧稠密光流；仅统计已脱离的稀疏粒子和断裂锋线，排除静止完整表面。
    flow_rows: list[dict[str, float]] = []
    side_flow_rows: list[dict[str, float]] = []
    lift_rows: list[dict[str, float]] = []
    flow_start = max(start_index, int(round((onset - 0.05) * fps)))
    flow_end = min(end_index - 1, int(round((finish + 0.05) * fps)))
    prev_gray = cv2.cvtColor(frames[flow_start], cv2.COLOR_BGR2GRAY)
    for index in range(flow_start, flow_end):
        next_gray = cv2.cvtColor(frames[index + 1], cv2.COLOR_BGR2GRAY)
        flow = cv2.calcOpticalFlowFarneback(
            prev_gray, next_gray, None,
            pyr_scale=0.5, levels=4, winsize=17, iterations=3,
            poly_n=7, poly_sigma=1.5, flags=0,
        )
        mask = foreground_mask(frames[index], background, analysis_roi)
        occupancy = cv2.boxFilter(mask.astype(np.float32), -1, (19, 19), normalize=True)
        gradient_x = cv2.Sobel(prev_gray, cv2.CV_32F, 1, 0, ksize=3)
        gradient_y = cv2.Sobel(prev_gray, cv2.CV_32F, 0, 1, ksize=3)
        texture = np.hypot(gradient_x, gradient_y)
        magnitude = np.linalg.norm(flow, axis=2)
        valid = (
            (mask > 0)
            & (occupancy < 0.72)
            & (texture > 18.0)
            & (magnitude > 0.08)
            & (magnitude < 18.0)
        )
        vectors = flow[valid]
        if len(vectors) >= 80:
            median = np.median(vectors, axis=0)
            unit = vectors / np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-5)
            mean_unit = unit.mean(axis=0)
            coherence = float(np.linalg.norm(mean_unit))
            angles = np.degrees(np.arctan2(vectors[:, 1], vectors[:, 0]))
            mean_angle = math.degrees(math.atan2(mean_unit[1], mean_unit[0]))
            wrapped = (angles - mean_angle + 180.0) % 360.0 - 180.0
            flow_rows.append({
                "time_s": index / fps,
                "median_dx_per_frame": float(median[0]),
                "median_dy_per_frame": float(median[1]),
                "median_speed_px_per_s": float(np.median(np.linalg.norm(vectors, axis=1)) * fps),
                "mean_angle_deg": float(mean_angle),
                "angle_spread_p80_deg": percentile_or_zero(np.abs(wrapped), 80.0),
                "direction_coherence": coherence,
                "valid_vectors": float(len(vectors)),
            })
        for target_rows, roi in (
            (side_flow_rows, side_flow_roi),
            (lift_rows, lift_roi),
        ):
            local_vectors = flow[valid & roi]
            if len(local_vectors) < 40:
                continue
            median = np.median(local_vectors, axis=0)
            target_rows.append({
                "time_s": index / fps,
                "median_dx_per_frame": float(median[0]),
                "median_dy_per_frame": float(median[1]),
                "median_speed_px_per_s": float(
                    np.median(np.linalg.norm(local_vectors, axis=1)) * fps
                ),
            })
        prev_gray = next_gray

    def summarise_flow(lo: float, hi: float) -> dict[str, float]:
        rows = [row for row in flow_rows if lo <= row["time_s"] < hi]
        if not rows:
            return {}
        vectors = np.array([
            [row["median_dx_per_frame"], row["median_dy_per_frame"]] for row in rows
        ])
        median_vector = np.median(vectors, axis=0)
        return {
            "from_s": round(lo, 3),
            "to_s": round(hi, 3),
            "median_dx_px_per_s": float(median_vector[0] * fps),
            "median_dy_px_per_s": float(median_vector[1] * fps),
            "median_direction_deg": angle_degrees(median_vector),
            "median_speed_px_per_s": float(np.median([
                row["median_speed_px_per_s"] for row in rows
            ])),
            "median_angle_spread_p80_deg": float(np.median([
                row["angle_spread_p80_deg"] for row in rows
            ])),
            "median_direction_coherence": float(np.median([
                row["direction_coherence"] for row in rows
            ])),
        }

    effect_span = max(0.001, finish - onset)
    stages = {
        "early": summarise_flow(onset, onset + effect_span * 0.34),
        "middle": summarise_flow(onset + effect_span * 0.34, onset + effect_span * 0.70),
        "late": summarise_flow(onset + effect_span * 0.70, finish + 0.05),
    }

    def summarise_local(
        rows: list[dict[str, float]],
        fraction_lo: float,
        fraction_hi: float,
    ) -> dict[str, float]:
        lo = onset + effect_span * fraction_lo
        hi = onset + effect_span * fraction_hi
        selected = [row for row in rows if lo <= row["time_s"] < hi]
        if not selected:
            return {}
        dx = float(np.median([row["median_dx_per_frame"] for row in selected]) * fps)
        dy = float(np.median([row["median_dy_per_frame"] for row in selected]) * fps)
        return {
            "from_normalized": fraction_lo,
            "to_normalized": fraction_hi,
            "median_dx_px_per_s": dx,
            "median_dy_px_per_s": dy,
            "median_direction_deg": math.degrees(math.atan2(dy, dx)),
            "left_to_up_ratio": abs(dx) / max(abs(dy), 1e-5),
            "median_speed_px_per_s": float(np.median([
                row["median_speed_px_per_s"] for row in selected
            ])),
        }

    lower_curtain = {
        "side_flow": summarise_local(side_flow_rows, 0.46, 0.62),
        "lift": summarise_local(lift_rows, 0.62, 0.78),
    }

    # 在三个时刻统计真实粒子密度的分位数，确认效果是否由等密粒子组成。
    density_stages: dict[str, dict[str, float]] = {}
    for label, fraction in (("early", 0.20), ("middle", 0.52), ("late", 0.82)):
        target = onset + effect_span * fraction
        index = min(masks, key=lambda item: abs(item / fps - target))
        mask = masks[index]
        occupancy = occupancies[index]
        active_density = occupancy[mask > 0]
        density_stages[label] = {
            "time_s": index / fps,
            "p20": percentile_or_zero(active_density, 20),
            "median": percentile_or_zero(active_density, 50),
            "p80": percentile_or_zero(active_density, 80),
            "p95": percentile_or_zero(active_density, 95),
            "p95_to_p20": (
                percentile_or_zero(active_density, 95)
                / max(0.001, percentile_or_zero(active_density, 20))
            ),
        }

    metrics: dict[str, object] = {
        "video": str(video),
        "fps": fps,
        "duration_s": duration,
        "source_box_xywh": source_box,
        "effect_onset_s": onset,
        "surface_finish_s": surface_finish,
        "surface_duration_s": surface_finish - onset,
        "effect_finish_s": finish,
        "effect_duration_s": finish - onset,
        "flow_stages": stages,
        "lower_curtain": lower_curtain,
        "density_stages": density_stages,
        "samples": samples,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    metrics = analyse(args.video, args.output)
    summary = {key: value for key, value in metrics.items() if key != "samples"}
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
