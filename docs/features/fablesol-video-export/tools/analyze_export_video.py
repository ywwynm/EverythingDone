#!/usr/bin/env python3
"""以固定取样区域分析 FableSol 导出视频的时间稳定性、颜色与 HDR 元数据。"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import subprocess
from pathlib import Path
from typing import Any

import numpy as np


BT2020_LUMA = np.asarray([0.2627, 0.6780, 0.0593], dtype=np.float32)


def run_json(command: list[str]) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    return json.loads(completed.stdout)


def parse_rate(value: str) -> float:
    numerator, denominator = value.split("/", 1)
    denominator_value = float(denominator)
    return float(numerator) / denominator_value if denominator_value else 0.0


def percentile(values: np.ndarray, value: float) -> float:
    return float(np.percentile(values, value))


def safe_ratio(numerator: float, denominator: float) -> float:
    return numerator / denominator if abs(denominator) > 1e-12 else 0.0


def roi(
    image: np.ndarray,
    x_start: float,
    x_end: float,
    y_start: float,
    y_end: float,
) -> np.ndarray:
    _, height, width = image.shape
    x0 = max(0, min(width - 1, round(width * x_start)))
    x1 = max(x0 + 1, min(width, round(width * x_end)))
    y0 = max(0, min(height - 1, round(height * y_start)))
    y1 = max(y0 + 1, min(height, round(height * y_end)))
    return image[:, y0:y1, x0:x1]


def frame_metrics(rgb: np.ndarray, frame_index: int, fps: float) -> dict[str, float]:
    # 卡片下部始终被水体覆盖；避开画框、计时器与大部分星芒，适合检测主体整体明暗呼吸。
    core = roi(rgb, 0.08, 0.92, 0.78, 0.92)
    # 水面区域包含波峰、银丝和星芒，用于衡量高光活动强度。
    surface = roi(rgb, 0.08, 0.92, 0.52, 0.78)

    core_luma = np.tensordot(BT2020_LUMA, core, axes=(0, 0)).reshape(-1)
    surface_luma = np.tensordot(BT2020_LUMA, surface, axes=(0, 0)).reshape(-1)
    lower = np.percentile(core_luma, 5.0)
    upper = np.percentile(core_luma, 95.0)
    trimmed = core_luma[(core_luma >= lower) & (core_luma <= upper)]

    core_rgb = np.median(core.reshape(3, -1), axis=1)
    rgb_sum = float(np.sum(core_rgb))
    chromaticity = core_rgb / rgb_sum if rgb_sum > 1e-12 else np.zeros(3)
    max_channel = np.max(core, axis=0)
    min_channel = np.min(core, axis=0)
    saturation = (max_channel - min_channel) / np.maximum(max_channel, 1e-8)

    return {
        "frame": float(frame_index),
        "time_seconds": safe_ratio(frame_index, fps),
        "core_luma_median": float(np.median(core_luma)),
        "core_luma_trimmed_mean": float(np.mean(trimmed)),
        "core_luma_p10": percentile(core_luma, 10.0),
        "core_luma_p90": percentile(core_luma, 90.0),
        "core_saturation_median": float(np.median(saturation)),
        "core_chroma_r": float(chromaticity[0]),
        "core_chroma_g": float(chromaticity[1]),
        "core_chroma_b": float(chromaticity[2]),
        "surface_luma_p95": percentile(surface_luma, 95.0),
        "surface_luma_p99": percentile(surface_luma, 99.0),
        "surface_luma_max": float(np.max(surface_luma)),
        # zscale 的线性输出以 npl=100 为 1.0；PQ 下这些阈值对应绝对尼特。
        "surface_fraction_over_203_nits": float(np.mean(surface_luma > 2.03)),
        "surface_fraction_over_400_nits": float(np.mean(surface_luma > 4.0)),
        "surface_fraction_over_1000_nits": float(np.mean(surface_luma > 10.0)),
    }


def temporal_summary(rows: list[dict[str, float]]) -> dict[str, Any]:
    core = np.asarray([row["core_luma_median"] for row in rows], dtype=np.float64)
    core_trimmed = np.asarray(
        [row["core_luma_trimmed_mean"] for row in rows], dtype=np.float64
    )
    highlights = np.asarray(
        [row["surface_luma_p99"] for row in rows], dtype=np.float64
    )
    chroma = np.asarray(
        [
            [row["core_chroma_r"], row["core_chroma_g"], row["core_chroma_b"]]
            for row in rows
        ],
        dtype=np.float64,
    )

    steps = np.abs(np.diff(core)) / np.maximum(np.abs(core[:-1]), 1e-9)
    low_cut = np.percentile(highlights, 25.0)
    high_cut = np.percentile(highlights, 75.0)
    low_mask = highlights <= low_cut
    high_mask = highlights >= high_cut
    low_core = float(np.mean(core[low_mask]))
    high_core = float(np.mean(core[high_mask]))
    correlation = (
        float(np.corrcoef(core, highlights)[0, 1])
        if np.std(core) > 1e-12 and np.std(highlights) > 1e-12
        else 0.0
    )

    return {
        "frame_count": len(rows),
        "core_luma_median_mean": float(np.mean(core)),
        "core_luma_median_min": float(np.min(core)),
        "core_luma_median_max": float(np.max(core)),
        "core_luma_median_max_to_min": safe_ratio(float(np.max(core)), float(np.min(core))),
        "core_luma_median_cv": safe_ratio(float(np.std(core)), float(np.mean(core))),
        "core_luma_trimmed_mean_cv": safe_ratio(
            float(np.std(core_trimmed)), float(np.mean(core_trimmed))
        ),
        "core_luma_frame_step_p95": float(np.percentile(steps, 95.0)),
        "core_luma_frame_step_max": float(np.max(steps)),
        "surface_luma_p99_max": float(np.max(highlights)),
        "surface_luma_p99_p95": float(np.percentile(highlights, 95.0)),
        "core_highlight_correlation": correlation,
        "core_luma_low_highlight_mean": low_core,
        "core_luma_high_highlight_mean": high_core,
        "core_luma_high_vs_low_highlight_delta": safe_ratio(
            high_core - low_core, low_core
        ),
        "core_chromaticity_mean": np.mean(chroma, axis=0).tolist(),
        "core_chromaticity_std": np.std(chroma, axis=0).tolist(),
        "core_chromaticity_high_minus_low": (
            np.mean(chroma[high_mask], axis=0) - np.mean(chroma[low_mask], axis=0)
        ).tolist(),
    }


def metadata_summary(
    ffprobe: str,
    video: Path,
    transfer: str,
) -> dict[str, Any]:
    if transfer != "smpte2084":
        return {"hdr10plus_frame_count": 0, "hdr10plus_unique_payloads": 0}

    frames = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=pts_time,side_data_list",
            "-of",
            "json",
            str(video),
        ]
    ).get("frames", [])
    payload_hashes: set[str] = set()
    payload_count = 0
    first_payload: dict[str, Any] | None = None
    for frame in frames:
        for side_data in frame.get("side_data_list", []):
            if side_data.get("side_data_type") != "HDR Dynamic Metadata SMPTE2094-40 (HDR10+)":
                continue
            payload_count += 1
            if first_payload is None:
                first_payload = side_data
            canonical = json.dumps(
                side_data, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
            payload_hashes.add(hashlib.sha256(canonical.encode("utf-8")).hexdigest())
    return {
        "hdr10plus_frame_count": payload_count,
        "hdr10plus_unique_payloads": len(payload_hashes),
        "hdr10plus_first_payload": first_payload,
    }


def create_contact_sheet(
    ffmpeg: str,
    video: Path,
    transfer: str,
    output: Path,
) -> None:
    if transfer in {"smpte2084", "arib-std-b67"}:
        colour = (
            "zscale=transfer=linear:npl=100,"
            "format=gbrpf32le,"
            "tonemap=mobius:param=0.3:desat=0,"
            "zscale=primaries=bt709:transfer=iec61966-2-1:"
            "matrix=bt709:range=full,"
            "format=rgb24"
        )
    else:
        colour = (
            "zscale=primaries=bt709:transfer=iec61966-2-1:"
            "matrix=bt709:range=full,"
            "format=rgb24"
        )
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(video),
            "-map",
            "0:v:0",
            "-vf",
            f"fps=1,{colour},scale=576:-2,tile=4x2:padding=4:margin=4",
            "-frames:v",
            "1",
            "-y",
            str(output),
        ],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--label")
    parser.add_argument("--ffmpeg", default=r"C:\ffmpeg\bin\ffmpeg.exe")
    parser.add_argument("--ffprobe", default=r"C:\ffmpeg\bin\ffprobe.exe")
    parser.add_argument("--sample-width", type=int, default=288)
    args = parser.parse_args()

    video = args.video.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    label = args.label or video.stem

    probe = run_json(
        [
            args.ffprobe,
            "-v",
            "error",
            "-show_entries",
            (
                "stream=index,codec_type,codec_name,profile,width,height,pix_fmt,"
                "r_frame_rate,avg_frame_rate,color_range,color_space,color_transfer,"
                "color_primaries,bit_rate:format=duration,size,bit_rate"
            ),
            "-of",
            "json",
            str(video),
        ]
    )
    video_stream = next(
        stream for stream in probe["streams"] if stream.get("codec_type") == "video"
    )
    width = int(video_stream["width"])
    height = int(video_stream["height"])
    sample_width = args.sample_width
    sample_height = round(height * sample_width / width)
    fps = parse_rate(
        video_stream.get("avg_frame_rate") or video_stream.get("r_frame_rate") or "0/1"
    )
    transfer = video_stream.get("color_transfer", "")

    command = [
        args.ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(video),
        "-map",
        "0:v:0",
        "-vf",
        (
            "zscale=primaries=bt2020:transfer=linear:matrix=gbr:range=full:npl=100:"
            f"w={sample_width}:h={sample_height}:filter=bilinear,"
            "format=gbrpf32le"
        ),
        "-f",
        "rawvideo",
        "-",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None
    frame_size = sample_width * sample_height * 3 * 4
    rows: list[dict[str, float]] = []
    frame_index = 0
    while True:
        data = process.stdout.read(frame_size)
        if not data:
            break
        if len(data) != frame_size:
            process.kill()
            raise RuntimeError(f"第 {frame_index} 帧数据不完整：{len(data)}/{frame_size}")
        planes = np.frombuffer(data, dtype="<f4").reshape(3, sample_height, sample_width)
        # gbrpf32le 的平面顺序为 G、B、R，统一整理成 R、G、B。
        rgb = np.stack((planes[2], planes[0], planes[1]), axis=0)
        rgb = np.nan_to_num(rgb, nan=0.0, posinf=0.0, neginf=0.0)
        rgb = np.maximum(rgb, 0.0)
        rows.append(frame_metrics(rgb, frame_index, fps))
        frame_index += 1
    stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"FFmpeg 解码失败：{stderr}")
    if not rows:
        raise RuntimeError("视频没有可分析帧")

    temporal = temporal_summary(rows)
    video_bitrate = float(video_stream.get("bit_rate") or 0.0)
    temporal["bits_per_pixel_per_frame"] = safe_ratio(
        video_bitrate, width * height * fps
    )
    temporal["linear_scale"] = (
        "PQ 下 1.0 = 100 尼特；其它传递函数仅用于同条件相对比较"
    )
    metadata = metadata_summary(args.ffprobe, video, transfer)
    summary = {
        "label": label,
        "video": str(video),
        "stream": video_stream,
        "format": probe.get("format", {}),
        "analysis_resolution": [sample_width, sample_height],
        "regions": {
            "water_core": [0.08, 0.92, 0.78, 0.92],
            "water_surface": [0.08, 0.92, 0.52, 0.78],
        },
        "temporal": temporal,
        "metadata": metadata,
    }

    csv_path = output_dir / f"{label}.frames.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary_path = output_dir / f"{label}.summary.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    create_contact_sheet(
        args.ffmpeg,
        video,
        transfer,
        output_dir / f"{label}.contact.png",
    )
    print(summary_path)
    print(json.dumps(temporal, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
