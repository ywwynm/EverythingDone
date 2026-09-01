"""从参考动画反推归一化留存区域的释放时钟。

输出的低分辨率控制场只描述“哪块材料何时开始粒子化”，不包含参考素材颜色。
所有像素先映射到控件自身 0..1 材料坐标；缺少纹理对比、无法直接观测的像素由邻域
插值。开场的阈值子集只保留与外边缘连通的分量，避免拟合出内部孤立圆环。
"""

from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

from analyze_reference_morphology import (
    CANVAS_BOUNDS,
    CANVAS_SIZE,
    PROGRESSES,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    density_field,
    reference_layers,
)
from analyze_reference_motion import frame_at, read_frames


PROFILE_COLUMNS = 49
PROFILE_ROWS = 32
DENSITY_COLUMNS = 24
DENSITY_ROWS = 24
DENSITY_FRAMES = 61
HANDOFF_MIDPOINT = 0.055
EARLY_EDGE_CONNECT_UNTIL = 0.26
OUT = Path(__file__).resolve().parent / "reference-profile"


def source_slice() -> tuple[slice, slice]:
    lo_x, lo_y, hi_x, hi_y = CANVAS_BOUNDS
    x0 = round((0.0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE)
    x1 = round((1.0 - lo_x) / (hi_x - lo_x) * CANVAS_SIZE)
    y0 = round((0.0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE)
    y1 = round((1.0 - lo_y) / (hi_y - lo_y) * CANVAS_SIZE)
    return slice(y0, y1), slice(x0, x1)


def edge_connected(mask: np.ndarray) -> np.ndarray:
    count, labels = cv2.connectedComponents(mask.astype(np.uint8), connectivity=8)
    if count <= 1:
        return np.zeros_like(mask, dtype=bool)
    border_labels = np.unique(np.concatenate((
        labels[0], labels[-1], labels[:, 0], labels[:, -1],
    )))
    border_labels = border_labels[border_labels != 0]
    return np.isin(labels, border_labels)


def estimate_reference_profiles(
    reference_path: Path,
) -> tuple[np.ndarray, np.ndarray, dict[str, object]]:
    frames, fps = read_frames(reference_path)
    baseline = frame_at(frames, fps, 3.00)
    background_start = int(round(7.85 * fps))
    background_end = min(len(frames), int(round(8.15 * fps)) + 1)
    background = np.median(
        np.stack(frames[background_start:background_end]), axis=0
    ).astype(np.uint8)

    crop = source_slice()
    intact_frames: list[np.ndarray] = []
    raw_density_frames: list[np.ndarray] = []
    for progress in PROGRESSES:
        seconds = REFERENCE_ONSET_S + progress * (
            REFERENCE_FINISH_S - REFERENCE_ONSET_S
        )
        layers = reference_layers(frame_at(frames, fps, seconds), baseline, background)
        intact_frames.append(layers.intact[crop].astype(np.float32))
        density = density_field(layers.particle)
        raw_density_frames.append(density)

    # 所有帧共用同一个亮度尺度。逐帧单独归一化会把 t=0.1 的少量粒子误放大，
    # 也会抹掉中后段质量变化，无法约束“每一帧”的真实密度关系。
    global_active = np.concatenate([
        density[density > 0.006] for density in raw_density_frames
        if np.any(density > 0.006)
    ])
    global_scale = float(np.percentile(global_active, 98)) if global_active.size else 1.0
    density_frames = [
        cv2.resize(
            np.sqrt(np.clip(density / max(global_scale, 1e-5), 0.0, 1.0)),
            (DENSITY_COLUMNS, DENSITY_ROWS),
            interpolation=cv2.INTER_AREA,
        )
        for density in raw_density_frames
    ]

    intact = np.stack(intact_frames)
    baseline_intact = np.maximum.reduce(intact[:3])
    confidence = np.clip((baseline_intact - 0.10) / 0.55, 0.0, 1.0)
    normalized = np.clip(
        intact / np.maximum(baseline_intact[None, :, :], 0.12),
        0.0,
        1.0,
    )
    changed = np.maximum.accumulate(1.0 - normalized, axis=0)

    # 参考纹理中暗部不可直接观测；先估计高置信像素的 50% 交接时刻。
    crossed = changed >= 0.50
    first_index = np.argmax(crossed, axis=0)
    never = ~np.any(crossed, axis=0)
    observed_time = np.asarray(PROGRESSES, dtype=np.float32)[first_index]
    observed_time[never] = 1.0
    activation = np.clip(observed_time - HANDOFF_MIDPOINT, 0.008, 0.94)

    missing = (confidence < 0.20).astype(np.uint8)
    activation = cv2.inpaint(
        activation.astype(np.float32), missing, 7.0, cv2.INPAINT_NS
    )
    activation = cv2.GaussianBlur(activation, (0, 0), 5.0)

    # t<=0.26 的释放必须从真实外边缘进入，不能留下内部孤立粒子环。
    repaired = np.full_like(activation, 1.0)
    previous = np.zeros_like(activation, dtype=bool)
    for progress in PROGRESSES:
        raw = activation <= progress
        if progress <= EARLY_EDGE_CONNECT_UNTIL:
            raw = edge_connected(raw)
        current = previous | raw
        repaired[(repaired >= 1.0) & current] = progress
        previous = current
    activation = cv2.GaussianBlur(repaired, (0, 0), 4.0)

    low = cv2.resize(
        activation,
        (PROFILE_COLUMNS, PROFILE_ROWS),
        interpolation=cv2.INTER_AREA,
    )
    low = cv2.GaussianBlur(low, (0, 0), 0.55)
    early_weight = 1.0 - np.clip((low - 0.10) / 0.20, 0.0, 1.0)
    early_weight = early_weight * early_weight * (3.0 - 2.0 * early_weight)
    low -= early_weight * 0.020
    low = np.clip(low, 0.008, 0.94).astype(np.float32)

    up = cv2.resize(low, activation.shape[::-1], interpolation=cv2.INTER_CUBIC)
    weighted_ious: list[float] = []
    frame_rows: list[dict[str, float]] = []
    weight = confidence >= 0.20
    for index, progress in enumerate(PROGRESSES):
        observed = changed[index] >= 0.50
        predicted = up <= max(0.0, progress - HANDOFF_MIDPOINT)
        intersection = np.count_nonzero(observed & predicted & weight)
        union = np.count_nonzero((observed | predicted) & weight)
        iou = intersection / max(union, 1)
        if 0.08 <= progress <= 0.72:
            weighted_ious.append(iou)
        frame_rows.append({
            "t": float(progress),
            "observedReleased": float(np.mean(observed[weight])),
            "predictedReleased": float(np.mean(predicted[weight])),
            "iou": float(iou),
        })

    metadata: dict[str, object] = {
        "columns": PROFILE_COLUMNS,
        "rows": PROFILE_ROWS,
        "densityColumns": DENSITY_COLUMNS,
        "densityRows": DENSITY_ROWS,
        "densityFrames": DENSITY_FRAMES,
        "handoffMidpoint": HANDOFF_MIDPOINT,
        "medianIou008To072": float(np.median(weighted_ious)),
        "frames": frame_rows,
    }
    temporal_indices = np.rint(
        np.linspace(0, len(PROGRESSES) - 1, DENSITY_FRAMES)
    ).astype(np.int32)
    density_profile = np.stack([density_frames[index] for index in temporal_indices])
    density_profile = np.clip(density_profile, 0.0, 1.0).astype(np.float32)
    return low, density_profile, metadata


def save_diagnostic(
    profile: np.ndarray,
    density_profile: np.ndarray,
    metadata: dict[str, object],
) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    width, height = 360, 360
    times = (0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70)
    up = cv2.resize(profile, (width, height), interpolation=cv2.INTER_CUBIC)
    sheet = Image.new("RGB", (width * len(times), height + 34), (15, 17, 22))
    draw = ImageDraw.Draw(sheet)
    for column, progress in enumerate(times):
        released = np.clip((progress - up) / 0.115, 0.0, 1.0)
        released = released * released * (3.0 - 2.0 * released)
        image = np.zeros((height, width, 3), dtype=np.uint8)
        image[:, :, 0] = np.rint(released * 244).astype(np.uint8)
        image[:, :, 1] = np.rint((1.0 - released) * 214).astype(np.uint8)
        image[:, :, 2] = 72
        sheet.paste(Image.fromarray(image), (column * width, 34))
        draw.text((column * width + 8, 7), f"t={progress:.2f}", fill=(235, 237, 242))
    sheet.save(OUT / "release-profile-timeline.png")
    density_sheet = Image.new(
        "RGB", (width * len(times), height + 34), (15, 17, 22)
    )
    density_draw = ImageDraw.Draw(density_sheet)
    for column, progress in enumerate(times):
        frame_index = round(progress * (DENSITY_FRAMES - 1))
        density = cv2.resize(
            density_profile[frame_index],
            (width, height),
            interpolation=cv2.INTER_CUBIC,
        )
        heat = cv2.applyColorMap(
            np.rint(np.clip(density, 0.0, 1.0) * 255.0).astype(np.uint8),
            cv2.COLORMAP_TURBO,
        )
        density_sheet.paste(
            Image.fromarray(cv2.cvtColor(heat, cv2.COLOR_BGR2RGB)),
            (column * width, 34),
        )
        density_draw.text(
            (column * width + 8, 7),
            f"t={progress:.2f}",
            fill=(235, 237, 242),
        )
    density_sheet.save(OUT / "density-profile-timeline.png")

    density_bytes = np.rint(density_profile * 255.0).astype(np.uint8).tobytes()
    (OUT / "release-profile.json").write_text(
        json.dumps(
            {
                **metadata,
                "values": [[round(float(value), 6) for value in row] for row in profile],
                "densityBase64": base64.b64encode(density_bytes).decode("ascii"),
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()
    profile, density_profile, metadata = estimate_reference_profiles(args.reference)
    save_diagnostic(profile, density_profile, metadata)
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
