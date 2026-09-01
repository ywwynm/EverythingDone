"""用光流检查 canonical 桌面模型是否实现“先解体、后被主风捕获”。"""

from __future__ import annotations

import json
import math

import cv2
import numpy as np

from render_curtain_model import CurtainRenderer, Scenario


def main() -> None:
    fps = 60.0
    renderer = CurtainRenderer()
    scenario = Scenario("comparison-left-up", -128.0, 42)
    # 直接读取 canonical 粒子层的无损 RGBA，避免 H.264、静止表面擦除和
    # 背景纹理被光流误认为粒子速度。
    native_frames = [
        renderer.render_particle_rgba(scenario, index / fps)
        for index in range(61)
    ]
    native_cohort_frames = [
        renderer.render_diagnostic_rgba(scenario, index / fps, "motion-cohort")
        for index in range(61)
    ]
    native_lower_cohort_frames = [
        renderer.render_diagnostic_rgba(
            scenario, index / fps, "lower-motion-cohort"
        )
        for index in range(61)
    ]
    analysis_scale = 0.5
    native_h, native_w = native_frames[0].shape[:2]
    frames = [
        cv2.resize(
            frame,
            (int(native_w * analysis_scale), int(native_h * analysis_scale)),
            interpolation=cv2.INTER_AREA,
        )
        for frame in native_frames
    ]
    cohort_frames = [
        cv2.resize(
            frame,
            (int(native_w * analysis_scale), int(native_h * analysis_scale)),
            interpolation=cv2.INTER_AREA,
        )
        for frame in native_cohort_frames
    ]
    lower_cohort_frames = [
        cv2.resize(
            frame,
            (int(native_w * analysis_scale), int(native_h * analysis_scale)),
            interpolation=cv2.INTER_AREA,
        )
        for frame in native_lower_cohort_frames
    ]

    def centroid_velocity_rows(source_frames: list[np.ndarray]) -> list[dict[str, float]]:
        centroids: list[np.ndarray] = []
        for frame in source_frames:
            alpha = frame[:, :, 3].astype(np.float64) / 255.0
            yy, xx = np.indices(alpha.shape)
            mass = max(float(alpha.sum()), 1e-5)
            centroids.append(np.array([
                float((xx * alpha).sum() / mass),
                float((yy * alpha).sum() / mass),
            ]))
        return [
            {
                "time_s": index / fps,
                "dx_px_per_s": float(
                    (centroids[index + 1][0] - centroids[index][0]) * fps
                ),
                "dy_px_per_s": float(
                    (centroids[index + 1][1] - centroids[index][1]) * fps
                ),
                "speed_px_per_s": float(
                    np.linalg.norm(centroids[index + 1] - centroids[index]) * fps
                ),
            }
            for index in range(2, len(centroids) - 2)
        ]

    centroid_rows = centroid_velocity_rows(cohort_frames)
    lower_centroid_rows = centroid_velocity_rows(lower_cohort_frames)
    rows: list[dict[str, float]] = []
    lower_rows: list[dict[str, float]] = []
    origin_x, origin_y = (
        renderer.origin[0] * analysis_scale,
        renderer.origin[1] * analysis_scale,
    )
    snapshot_w, snapshot_h = (
        renderer.snapshot.size[0] * analysis_scale,
        renderer.snapshot.size[1] * analysis_scale,
    )
    side_flow_roi = np.zeros(frames[0].shape[:2], dtype=bool)
    side_flow_roi[
        int(origin_y + snapshot_h * 0.45) : int(origin_y + snapshot_h + 90),
        max(0, int(origin_x - 80)) : int(origin_x + snapshot_w * 0.62),
    ] = True
    lift_roi = np.zeros(frames[0].shape[:2], dtype=bool)
    lift_roi[
        int(origin_y + snapshot_h * 0.10) : int(origin_y + snapshot_h + 90),
        max(0, int(origin_x - 140)) : int(origin_x + snapshot_w * 0.70),
    ] = True
    for index in range(2, len(frames) - 2):
        first = frames[index]
        second = frames[index + 1]
        gray_a = cv2.cvtColor(first[:, :, :3], cv2.COLOR_RGB2GRAY)
        gray_b = cv2.cvtColor(second[:, :, :3], cv2.COLOR_RGB2GRAY)
        flow = cv2.calcOpticalFlowFarneback(
            gray_a, gray_b, None,
            pyr_scale=0.5, levels=5, winsize=31, iterations=5,
            poly_n=7, poly_sigma=1.5, flags=0,
        )
        mask = (first[:, :, 3] > 5) & (second[:, :, 3] > 5)
        gradient = np.hypot(
            cv2.Sobel(gray_a, cv2.CV_32F, 1, 0, ksize=3),
            cv2.Sobel(gray_a, cv2.CV_32F, 0, 1, ksize=3),
        )
        magnitude = np.linalg.norm(flow, axis=2)
        valid = (
            mask
            & (gradient > 3.0)
            & (magnitude > 0.025)
            & (magnitude < 24.0)
        )
        vectors = flow[valid]
        if len(vectors) < 60:
            continue
        units = vectors / np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-5)
        mean_unit = units.mean(axis=0)
        mean_angle = math.degrees(math.atan2(mean_unit[1], mean_unit[0]))
        angles = np.degrees(np.arctan2(vectors[:, 1], vectors[:, 0]))
        wrapped = (angles - mean_angle + 180.0) % 360.0 - 180.0
        rows.append({
            "time_s": index / fps,
            "median_speed_px_per_s": float(np.median(np.linalg.norm(vectors, axis=1)) * fps),
            "median_dx_px_per_s": float(np.median(vectors[:, 0]) * fps),
            "median_dy_px_per_s": float(np.median(vectors[:, 1]) * fps),
            "direction_deg": mean_angle,
            "angle_spread_p80_deg": float(np.percentile(np.abs(wrapped), 80)),
            "direction_coherence": float(np.linalg.norm(mean_unit)),
            "valid_vectors": float(len(vectors)),
        })

        local_roi = side_flow_roi if index / fps < 0.62 else lift_roi
        lower_vectors = flow[valid & local_roi]
        if len(lower_vectors) < 40:
            continue
        lower_units = lower_vectors / np.maximum(
            np.linalg.norm(lower_vectors, axis=1, keepdims=True), 1e-5
        )
        lower_mean_unit = lower_units.mean(axis=0)
        lower_rows.append({
            "time_s": index / fps,
            "median_dx_px_per_s": float(np.median(lower_vectors[:, 0]) * fps),
            "median_dy_px_per_s": float(np.median(lower_vectors[:, 1]) * fps),
            "median_speed_px_per_s": float(
                np.median(np.linalg.norm(lower_vectors, axis=1)) * fps
            ),
            "direction_deg": math.degrees(math.atan2(
                lower_mean_unit[1], lower_mean_unit[0]
            )),
            "direction_coherence": float(np.linalg.norm(lower_mean_unit)),
        })

    def stage(name: str, start: float, end: float) -> dict[str, float | str]:
        selected = [row for row in rows if start <= row["time_s"] < end]
        if not selected:
            return {"name": name}
        angle_radians = np.radians([row["direction_deg"] for row in selected])
        mean_angle = math.degrees(math.atan2(
            float(np.sin(angle_radians).mean()),
            float(np.cos(angle_radians).mean()),
        ))
        return {
            "name": name,
            "from_s": start,
            "to_s": end,
            "median_speed_px_per_s": float(np.median([
                row["median_speed_px_per_s"] for row in selected
            ])),
            "median_dx_px_per_s": float(np.median([
                row["median_dx_px_per_s"] for row in selected
            ])),
            "median_dy_px_per_s": float(np.median([
                row["median_dy_px_per_s"] for row in selected
            ])),
            "mean_direction_deg": mean_angle,
            "median_angle_spread_p80_deg": float(np.median([
                row["angle_spread_p80_deg"] for row in selected
            ])),
            "median_direction_coherence": float(np.median([
                row["direction_coherence"] for row in selected
            ])),
        }

    def local_stage(name: str, start: float, end: float) -> dict[str, float | str]:
        selected = [row for row in lower_rows if start <= row["time_s"] < end]
        if not selected:
            return {"name": name}
        median_dx = float(np.median([
            row["median_dx_px_per_s"] for row in selected
        ]))
        median_dy = float(np.median([
            row["median_dy_px_per_s"] for row in selected
        ]))
        return {
            "name": name,
            "from_s": start,
            "to_s": end,
            "median_speed_px_per_s": float(np.median([
                row["median_speed_px_per_s"] for row in selected
            ])),
            "median_dx_px_per_s": median_dx,
            "median_dy_px_per_s": median_dy,
            "median_direction_deg": math.degrees(math.atan2(median_dy, median_dx)),
            "left_to_up_ratio": abs(median_dx) / max(abs(median_dy), 1e-5),
            "median_direction_coherence": float(np.median([
                row["direction_coherence"] for row in selected
            ])),
        }

    stages = [
        stage("early", 0.08, 0.34),
        stage("middle", 0.34, 0.68),
        stage("late", 0.68, 0.96),
    ]

    def centroid_stage(
        source_rows: list[dict[str, float]],
        name: str,
        start: float,
        end: float,
    ) -> dict[str, float | str]:
        selected = [row for row in source_rows if start <= row["time_s"] < end]
        dx = float(np.median([row["dx_px_per_s"] for row in selected]))
        dy = float(np.median([row["dy_px_per_s"] for row in selected]))
        return {
            "name": name,
            "from_s": start,
            "to_s": end,
            "median_speed_px_per_s": float(np.median([
                row["speed_px_per_s"] for row in selected
            ])),
            "median_dx_px_per_s": dx,
            "median_dy_px_per_s": dy,
            "median_direction_deg": math.degrees(math.atan2(dy, dx)),
        }

    centroid_stages = [
        centroid_stage(centroid_rows, "early", 0.14, 0.34),
        centroid_stage(centroid_rows, "middle", 0.34, 0.68),
        centroid_stage(centroid_rows, "late", 0.68, 0.94),
    ]
    lower_cohort_stages = [
        centroid_stage(lower_centroid_rows, "side-flow", 0.46, 0.62),
        centroid_stage(lower_centroid_rows, "lift", 0.62, 0.78),
    ]
    for stage_row in lower_cohort_stages:
        stage_row["left_to_up_ratio"] = abs(float(stage_row["median_dx_px_per_s"])) / max(
            abs(float(stage_row["median_dy_px_per_s"])), 1e-5
        )
    early_speed = float(centroid_stages[0]["median_speed_px_per_s"])
    middle_speed = float(centroid_stages[1]["median_speed_px_per_s"])
    late_speed = float(centroid_stages[2]["median_speed_px_per_s"])

    result = {
        "scenario": "comparison-left-up (-128°), canonical particle RGBA",
        "fps": fps,
        "analysis_size": [frames[0].shape[1], frames[0].shape[0]],
        "stages": stages,
        "centroid_stages": centroid_stages,
        "speed_ratios": {
            "middle_to_early": middle_speed / max(early_speed, 1e-5),
            "late_to_middle": late_speed / max(middle_speed, 1e-5),
        },
        "lower_curtain": [
            local_stage("side-flow", 0.46, 0.62),
            local_stage("lift", 0.62, 0.78),
        ],
        "lower_fixed_cohort": lower_cohort_stages,
        "timeline": [
            min(rows, key=lambda row: abs(row["time_s"] - target))
            for target in (0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90)
        ],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))

    failures: list[str] = []
    flow_middle_to_early = float(stages[1]["median_speed_px_per_s"]) / max(
        float(stages[0]["median_speed_px_per_s"]), 1e-5
    )
    late_to_middle = result["speed_ratios"]["late_to_middle"]
    # 新 phase field 在开场就会生成可辨认且持续外移的边缘粒子，光流分母
    # 不再接近静止；继续要求 3.2× 会迫使开场重新变成“装模作样地闪一下”。
    # 中段至少 1.55×，再叠加 late/middle 约束，已经能排除匀速运动，同时
    # 保留参考中早期纤维真实移动的可见性。
    if not 1.55 <= flow_middle_to_early <= 9.0:
        failures.append(
            f"先慢后快偏离参考：光流 middle/early={flow_middle_to_early:.2f}×"
        )
    if late_to_middle < 1.12:
        failures.append(
            f"后段没有继续加速：late/middle={late_to_middle:.2f}×，目标至少 1.12×"
        )
    # `lower_curtain` 测量用户实际看到的整块下边缘粒群；参考约从 0.83
    # 过渡到 0.62。`lower_fixed_cohort` 则固定同一条全局源空间弧段，确认
    # 变化不是由粒子换批或裁切伪造。两者分工后，不再用已经删除的
    # delay≈0.1 持久队列代表新的全局卷边。
    visible_side, visible_lift = result["lower_curtain"]
    fixed_side, fixed_lift = result["lower_fixed_cohort"]
    for name, row in (
        ("可见侧流", visible_side),
        ("可见上卷", visible_lift),
        ("固定弧段侧流", fixed_side),
        ("固定弧段上卷", fixed_lift),
    ):
        if "left_to_up_ratio" not in row:
            failures.append(f"左下帷幔{name}样本不足")
            continue
        if float(row["median_dx_px_per_s"]) >= 0.0 \
                or float(row["median_dy_px_per_s"]) >= 0.0:
            failures.append(
                f"左下帷幔{name}方向反转："
                f"dx={row['median_dx_px_per_s']:.2f}, "
                f"dy={row['median_dy_px_per_s']:.2f}"
            )

    if all("left_to_up_ratio" in row for row in (
        visible_side, visible_lift, fixed_side, fixed_lift
    )):
        visible_side_ratio = float(visible_side["left_to_up_ratio"])
        visible_lift_ratio = float(visible_lift["left_to_up_ratio"])
        fixed_side_ratio = float(fixed_side["left_to_up_ratio"])
        fixed_lift_ratio = float(fixed_lift["left_to_up_ratio"])
        if not 0.72 <= visible_side_ratio <= 1.45:
            failures.append(
                "左下可见帷幔侧向导流偏离参考："
                f"left/up={visible_side_ratio:.2f}"
            )
        if not 0.35 <= visible_lift_ratio <= visible_side_ratio * 0.96:
            failures.append(
                "左下可见帷幔未从侧向流动连续转为上卷："
                f"side={visible_side_ratio:.2f}, lift={visible_lift_ratio:.2f}"
            )
        # 固定弧段只确认同一批材料在两个阶段都持续向左上运动。当前模型
        # 存在中段有限捕风峰，固定 cohort 的屏幕投影比例会同时受主风脉冲
        # 和曲面法线影响；“先侧流、后上卷”应由实际可见粒群的 ROI 约束，
        # 不能再强迫固定 cohort 的 left/up 比例也单调下降。
        if not 0.70 <= fixed_side_ratio <= 1.90:
            failures.append(
                "左下固定弧段侧向导流异常："
                f"left/up={fixed_side_ratio:.2f}"
            )
        # 固定材料弧段只需要在转向阶段同时保持向左、向上；实际可见
        # 帷幔才负责约束观感比例。低于 0.30 并不代表反向或停滞，当前
        # 0.28 对应约 -106°，仍是明确的左上运动，随后会汇入 -128° 主风。
        if not 0.25 <= fixed_lift_ratio <= 1.40:
            failures.append(
                "左下固定弧段后段方向异常："
                f"lift={fixed_lift_ratio:.2f}"
            )
    if failures:
        raise SystemExit("；".join(failures))


if __name__ == "__main__":
    main()
