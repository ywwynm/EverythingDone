"""验证 FableSol 双机导出矩阵的封装、时间戳、HDR 身份与解码完整性。"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


EXPECTED_CODEC = {
    "hevc": "hevc",
    "av1": "av1",
    "avc": "h264",
}

EXPECTED_TRANSFER = {
    "hdr-vivid": "smpte2084",
    "hdr10-plus": "smpte2084",
    "hdr10": "smpte2084",
    "dolby-vision-84": "arib-std-b67",
    "hlg": "arib-std-b67",
    "sdr-native": "bt709",
    "sdr-tone-mapped": "bt709",
}

EXPECTED_COMPLETION_LABEL = {
    "hdr-vivid": "HDR Vivid",
    "hdr10-plus": "HDR10+",
    "hdr10": "HDR10",
    "dolby-vision-84": "杜比视界 8.4",
    "hlg": "HLG",
    "sdr-native": "SDR（原生渲染）",
    "sdr-tone-mapped": "SDR（保留高光",
}

DYNAMIC_SIDE_DATA = {
    "hdr-vivid": "HDR Dynamic Metadata CUVA 005.1 2021 (Vivid)",
    "hdr10-plus": "HDR Dynamic Metadata SMPTE2094-40 (HDR10+)",
}


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


def parse_rate(value: str | None) -> float:
    if not value or "/" not in value:
        return 0.0
    numerator, denominator = value.split("/", 1)
    denominator_value = float(denominator)
    return float(numerator) / denominator_value if denominator_value else 0.0


def completion_text(path: Path) -> str:
    if not path.exists():
        return ""
    root = ET.parse(path).getroot()
    values: list[str] = []
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        if text:
            values.append(text)
    return "\n".join(values)


def full_decode(ffmpeg: str, video: Path) -> tuple[bool, str]:
    completed = subprocess.run(
        [
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-xerror",
            "-i",
            str(video),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0",
            "-f",
            "null",
            "NUL",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.returncode == 0, completed.stderr.strip()


def frame_timing(ffprobe: str, video: Path, fps: float) -> dict[str, Any]:
    frames = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=best_effort_timestamp_time,pkt_duration_time,key_frame,pict_type",
            "-of",
            "json",
            str(video),
        ]
    ).get("frames", [])
    pts = [
        float(frame["best_effort_timestamp_time"])
        for frame in frames
        if "best_effort_timestamp_time" in frame
    ]
    deltas = [current - previous for previous, current in zip(pts, pts[1:])]
    expected_delta = 1.0 / fps if fps > 0.0 else 0.0
    keyframe_pts = [
        float(frame["best_effort_timestamp_time"])
        for frame in frames
        if frame.get("key_frame") == 1 and "best_effort_timestamp_time" in frame
    ]
    keyframe_intervals = [
        current - previous
        for previous, current in zip(keyframe_pts, keyframe_pts[1:])
    ]
    return {
        "decoded_frame_count": len(frames),
        "first_pts": pts[0] if pts else None,
        "last_pts": pts[-1] if pts else None,
        "non_monotonic_pts_count": sum(delta <= 0.0 for delta in deltas),
        "frame_delta_min": min(deltas) if deltas else None,
        "frame_delta_max": max(deltas) if deltas else None,
        "frame_delta_max_error": (
            max(abs(delta - expected_delta) for delta in deltas)
            if deltas and expected_delta
            else None
        ),
        "keyframe_count": len(keyframe_pts),
        "keyframe_interval_max": max(keyframe_intervals) if keyframe_intervals else None,
    }


def dynamic_metadata(
    ffprobe: str,
    video: Path,
    color_mode: str,
) -> dict[str, Any]:
    expected_type = DYNAMIC_SIDE_DATA.get(color_mode)
    if expected_type is None:
        return {
            "dynamic_metadata_frame_count": 0,
            "dynamic_metadata_unique_payloads": 0,
        }
    frames = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=side_data_list",
            "-of",
            "json",
            str(video),
        ]
    ).get("frames", [])
    hashes: set[str] = set()
    count = 0
    for frame in frames:
        matching = [
            side_data
            for side_data in frame.get("side_data_list", [])
            if side_data.get("side_data_type") == expected_type
        ]
        if matching:
            count += 1
        for side_data in matching:
            canonical = json.dumps(
                side_data,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            hashes.add(hashlib.sha256(canonical.encode("utf-8")).hexdigest())
    return {
        "dynamic_metadata_frame_count": count,
        "dynamic_metadata_unique_payloads": len(hashes),
    }


def cuvv_summary(video: Path) -> dict[str, Any]:
    data = video.read_bytes()
    indexes: list[int] = []
    offset = 0
    while True:
        index = data.find(b"cuvv", offset)
        if index < 0:
            break
        indexes.append(index)
        offset = index + 4
    sizes = [
        int.from_bytes(data[index - 4 : index], "big")
        for index in indexes
        if index >= 4
    ]
    return {"cuvv_count": len(indexes), "cuvv_sizes": sizes}


def dolby_vision_summary(
    ffprobe: str,
    video: Path,
    color_mode: str,
) -> dict[str, Any]:
    if color_mode != "dolby-vision-84":
        return {
            "dvvC_count": 0,
            "dvcC_count": 0,
            "configuration": None,
            "rpu_frame_count": 0,
            "metadata_frame_count": 0,
        }

    stream_probe = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_streams",
            "-of",
            "json",
            str(video),
        ]
    )
    streams = stream_probe.get("streams", [])
    side_data = streams[0].get("side_data_list", []) if streams else []
    configuration = next(
        (
            item
            for item in side_data
            if item.get("side_data_type") == "DOVI configuration record"
        ),
        None,
    )
    frames = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=side_data_list",
            "-of",
            "json",
            str(video),
        ]
    ).get("frames", [])
    rpu_count = 0
    metadata_count = 0
    for frame in frames:
        types = {
            item.get("side_data_type")
            for item in frame.get("side_data_list", [])
        }
        if "Dolby Vision RPU Data" in types:
            rpu_count += 1
        if "Dolby Vision Metadata" in types:
            metadata_count += 1

    data = video.read_bytes()
    return {
        "dvvC_count": data.count(b"dvvC"),
        "dvcC_count": data.count(b"dvcC"),
        "configuration": configuration,
        "rpu_frame_count": rpu_count,
        "metadata_frame_count": metadata_count,
    }


def nclx_summary(video: Path) -> dict[str, Any]:
    data = video.read_bytes()
    boxes: list[dict[str, Any]] = []
    offset = 0
    while True:
        index = data.find(b"colr", offset)
        if index < 0:
            break
        if index + 15 <= len(data) and data[index + 4 : index + 8] == b"nclx":
            boxes.append(
                {
                    "offset": index,
                    "primaries": int.from_bytes(data[index + 8 : index + 10], "big"),
                    "transfer": int.from_bytes(data[index + 10 : index + 12], "big"),
                    "matrix": int.from_bytes(data[index + 12 : index + 14], "big"),
                    "full_range": bool(data[index + 14] & 0x80),
                }
            )
        offset = index + 4
    return {"nclx_count": len(boxes), "nclx_boxes": boxes}


def read_analysis(analysis_roots: list[Path], stem: str) -> dict[str, Any] | None:
    for root in analysis_roots:
        path = root / f"{stem}.summary.json"
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    return None


def validate_one(
    ffmpeg: str,
    ffprobe: str,
    video: Path,
    analysis_roots: list[Path],
) -> dict[str, Any]:
    requested_path = video.with_suffix(".requested.json")
    completion_path = video.with_suffix(".completion.xml")
    requested = json.loads(requested_path.read_text(encoding="utf-8-sig"))
    probe = run_json(
        [
            ffprobe,
            "-v",
            "error",
            "-show_entries",
            (
                "stream=index,codec_type,codec_name,profile,width,height,pix_fmt,"
                "r_frame_rate,avg_frame_rate,color_range,color_space,color_transfer,"
                "color_primaries,bit_rate,nb_frames,has_b_frames,duration:"
                "format=duration,size,bit_rate"
            ),
            "-of",
            "json",
            str(video),
        ]
    )
    streams = probe.get("streams", [])
    video_stream = next(
        stream for stream in streams if stream.get("codec_type") == "video"
    )
    audio_stream = next(
        (stream for stream in streams if stream.get("codec_type") == "audio"),
        None,
    )
    fps = parse_rate(
        video_stream.get("avg_frame_rate") or video_stream.get("r_frame_rate")
    )
    timing = frame_timing(ffprobe, video, fps)
    dynamic = dynamic_metadata(ffprobe, video, requested["colorMode"])
    cuvv = cuvv_summary(video)
    dolby_vision = dolby_vision_summary(
        ffprobe,
        video,
        requested["colorMode"],
    )
    nclx = nclx_summary(video)
    decode_ok, decode_error = full_decode(ffmpeg, video)
    completion = completion_text(completion_path)
    analysis = read_analysis(analysis_roots, video.stem)

    errors: list[str] = []
    warnings: list[str] = []
    expected_codec = EXPECTED_CODEC[requested["codec"]]
    if video_stream.get("codec_name") != expected_codec:
        errors.append(
            f"编码器不一致：请求 {expected_codec}，实际 {video_stream.get('codec_name')}"
        )
    if abs(fps - float(requested["frameRate"])) > 0.01:
        errors.append(f"帧率不一致：请求 {requested['frameRate']}，实际 {fps}")
    expected_transfer = EXPECTED_TRANSFER[requested["colorMode"]]
    if video_stream.get("color_transfer") != expected_transfer:
        errors.append(
            f"传递函数不一致：请求 {expected_transfer}，实际 "
            f"{video_stream.get('color_transfer')}"
        )
    is_hdr = requested["colorMode"].startswith("hdr") or requested["colorMode"] in {
        "hlg",
        "dolby-vision-84",
    }
    ten_bit = is_hdr or requested["sdrBitDepth"] == "ten-bit"
    expected_pix_fmt = "yuv420p10le" if ten_bit else "yuv420p"
    if video_stream.get("pix_fmt") != expected_pix_fmt:
        errors.append(
            f"像素格式不一致：请求 {expected_pix_fmt}，实际 {video_stream.get('pix_fmt')}"
        )
    if is_hdr:
        if video_stream.get("color_primaries") != "bt2020":
            errors.append("HDR 色域不是 BT.2020")
        if video_stream.get("color_space") != "bt2020nc":
            errors.append("HDR 矩阵不是 BT.2020 non-constant")
    else:
        for field in ("color_primaries", "color_space", "color_transfer"):
            if video_stream.get(field) != "bt709":
                errors.append(f"SDR {field} 不是 BT.709")
    # Surface 输入时 RGB→YUV 由编码器完成，编码器回报的 full/limited 都是实际码值语义；
    # 应用自有 P010 则固定 limited。两者只要明确声明即可，后续另核对码流与容器一致性。
    if video_stream.get("color_range") not in {"tv", "pc"}:
        errors.append("视频没有明确的 limited/full 色彩范围")
    if nclx["nclx_count"] != 1:
        errors.append(f"MP4 nclx 数量异常：{nclx['nclx_count']}")
    else:
        expected_cicp = {
            "color_primaries": {"bt709": 1, "bt2020": 9},
            "color_transfer": {"bt709": 1, "smpte2084": 16, "arib-std-b67": 18},
            "color_space": {"bt709": 1, "bt2020nc": 9},
        }
        box = nclx["nclx_boxes"][0]
        comparisons = (
            ("primaries", "color_primaries"),
            ("transfer", "color_transfer"),
            ("matrix", "color_space"),
        )
        for box_field, stream_field in comparisons:
            expected_value = expected_cicp[stream_field].get(
                video_stream.get(stream_field)
            )
            if expected_value is None or box[box_field] != expected_value:
                errors.append(
                    f"MP4 nclx {box_field} 与码流不一致："
                    f"{box[box_field]}/{video_stream.get(stream_field)}"
                )
        stream_full_range = video_stream.get("color_range") == "pc"
        if box["full_range"] != stream_full_range:
            errors.append("MP4 nclx full_range 与码流色彩范围不一致")
    if not decode_ok:
        errors.append(f"完整解码失败：{decode_error}")
    if audio_stream is None:
        errors.append("缺少音频轨")
    if timing["non_monotonic_pts_count"]:
        errors.append(f"PTS 非单调：{timing['non_monotonic_pts_count']} 处")
    stream_frame_count = int(video_stream.get("nb_frames") or 0)
    if stream_frame_count and stream_frame_count != timing["decoded_frame_count"]:
        errors.append(
            f"帧数不一致：流声明 {stream_frame_count}，解码 {timing['decoded_frame_count']}"
        )
    expected_dynamic_type = DYNAMIC_SIDE_DATA.get(requested["colorMode"])
    if expected_dynamic_type and dynamic["dynamic_metadata_frame_count"] != timing[
        "decoded_frame_count"
    ]:
        errors.append(
            "动态元数据未覆盖全部帧："
            f"{dynamic['dynamic_metadata_frame_count']}/"
            f"{timing['decoded_frame_count']}"
        )
    if requested["colorMode"] == "hdr-vivid":
        if cuvv["cuvv_count"] != 1 or cuvv["cuvv_sizes"] != [30]:
            errors.append(
                f"HDR Vivid cuvv 不合规：count={cuvv['cuvv_count']} "
                f"sizes={cuvv['cuvv_sizes']}"
            )
    elif cuvv["cuvv_count"]:
        errors.append("非 HDR Vivid 产物意外包含 cuvv")
    if requested["colorMode"] == "dolby-vision-84":
        configuration = dolby_vision["configuration"] or {}
        if dolby_vision["dvvC_count"] != 1 or dolby_vision["dvcC_count"] != 0:
            errors.append(
                "杜比视界配置盒异常："
                f"dvvC={dolby_vision['dvvC_count']}、"
                f"dvcC={dolby_vision['dvcC_count']}"
            )
        expected_configuration = {
            "dv_profile": 8,
            "rpu_present_flag": 1,
            "el_present_flag": 0,
            "bl_present_flag": 1,
            "dv_bl_signal_compatibility_id": 4,
        }
        for field, expected_value in expected_configuration.items():
            if configuration.get(field) != expected_value:
                errors.append(
                    f"杜比视界配置 {field} 异常："
                    f"{configuration.get(field)}/{expected_value}"
                )
        decoded_frames = timing["decoded_frame_count"]
        if dolby_vision["rpu_frame_count"] != decoded_frames:
            errors.append(
                "杜比视界 RPU 未覆盖全部帧："
                f"{dolby_vision['rpu_frame_count']}/{decoded_frames}"
            )
        if dolby_vision["metadata_frame_count"] != decoded_frames:
            errors.append(
                "杜比视界解析元数据未覆盖全部帧："
                f"{dolby_vision['metadata_frame_count']}/{decoded_frames}"
            )
    expected_completion_label = EXPECTED_COMPLETION_LABEL[requested["colorMode"]]
    if expected_completion_label not in completion:
        errors.append(f"完成态缺少格式标签：{expected_completion_label}")
    codec_label = {"hevc": "HEVC", "av1": "AV1", "avc": "H.264"}[
        requested["codec"]
    ]
    if codec_label not in completion:
        errors.append(f"完成态缺少编码器标签：{codec_label}")
    if f"{requested['frameRate']} fps" not in completion:
        errors.append("完成态帧率与请求不一致")
    if requested["colorMode"] in {"hlg", "dolby-vision-84"}:
        if requested["hlgRange"] == "nominal" and "HLG 名义范围" not in completion:
            errors.append("显式 HLG 名义范围没有如实落到完成态")
        if requested["hlgRange"] == "auto-enhanced" and not any(
            label in completion for label in ("HLG 扩展信号范围", "HLG 名义范围")
        ):
            errors.append("HLG 自动增强没有报告实际信号范围")

    temporal = analysis.get("temporal", {}) if analysis else {}
    if temporal:
        frame_count = max(int(temporal.get("frame_count", 0)), 1)
        alternating_count = int(
            temporal.get("core_luma_alternating_step_over_0_5pct_count", 0)
        )
        flicker_reasons: list[str] = []
        if float(temporal.get("core_luma_frame_step_p99", 0.0)) >= 0.01:
            flicker_reasons.append("主体亮度帧间步进 P99 ≥ 1%")
        if float(temporal.get("core_luma_three_frame_impulse_p99", 0.0)) >= 0.005:
            flicker_reasons.append("三帧脉冲 P99 ≥ 0.5%")
        if alternating_count > max(2, round(frame_count * 0.01)):
            flicker_reasons.append("超过 0.5% 的连续反向步进过多")
        if (
            float(temporal.get("core_luma_high_frequency_power_ratio_8hz", 0.0))
            >= 0.25
            and float(temporal.get("core_luma_half_second_residual_rms", 0.0))
            >= 0.005
        ):
            flicker_reasons.append("8 Hz 以上亮度残差能量偏高")
        if flicker_reasons:
            warnings.append("；".join(flicker_reasons))
    else:
        warnings.append("尚无逐帧画面分析")

    digest = hashlib.sha256(video.read_bytes()).hexdigest()
    result = {
        "id": video.stem,
        "video": str(video),
        "sha256": digest,
        "requested": requested,
        "stream": video_stream,
        "audio_stream": audio_stream,
        "format": probe.get("format", {}),
        "timing": timing,
        "dynamic_metadata": dynamic,
        "cuvv": cuvv,
        "dolby_vision": dolby_vision,
        "nclx": nclx,
        "completion_text": completion,
        "full_decode_ok": decode_ok,
        "full_decode_error": decode_error,
        "analysis": analysis,
        "errors": errors,
        "warnings": warnings,
        "status": "fail" if errors else ("review" if warnings else "pass"),
    }
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", action="append", type=Path, required=True)
    parser.add_argument("--analysis-root", action="append", type=Path, default=[])
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--ffmpeg", default=r"C:\ffmpeg\bin\ffmpeg.exe")
    parser.add_argument("--ffprobe", default=r"C:\ffmpeg\bin\ffprobe.exe")
    args = parser.parse_args()

    roots = [root.resolve() for root in args.root]
    analysis_roots = [root.resolve() for root in args.analysis_root]
    videos = sorted(
        {
            video.resolve()
            for root in roots
            for video in root.glob("*.mp4")
            if video.with_suffix(".requested.json").exists()
        }
    )
    if not videos:
        raise RuntimeError("没有找到带 requested.json 的矩阵 MP4")

    results = [
        validate_one(args.ffmpeg, args.ffprobe, video, analysis_roots)
        for video in videos
    ]
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "matrix-validation.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    csv_fields = [
        "id",
        "status",
        "color_mode",
        "codec",
        "fps",
        "frame_count",
        "duration",
        "bit_rate",
        "color_range",
        "bits_per_pixel_per_frame",
        "frame_step_p99",
        "frame_step_max",
        "three_frame_impulse_p99",
        "alternating_step_count",
        "high_frequency_power_ratio_8hz",
        "dynamic_metadata_frames",
        "dynamic_metadata_unique_payloads",
        "cuvv_count",
        "dolby_vision_rpu_frames",
        "dvvC_count",
        "errors",
        "warnings",
        "sha256",
    ]
    with (output_dir / "matrix-validation.csv").open(
        "w", newline="", encoding="utf-8-sig"
    ) as handle:
        writer = csv.DictWriter(handle, fieldnames=csv_fields)
        writer.writeheader()
        for result in results:
            temporal = (
                result["analysis"].get("temporal", {})
                if result.get("analysis")
                else {}
            )
            writer.writerow(
                {
                    "id": result["id"],
                    "status": result["status"],
                    "color_mode": result["requested"]["colorMode"],
                    "codec": result["stream"].get("codec_name"),
                    "fps": parse_rate(result["stream"].get("avg_frame_rate")),
                    "frame_count": result["timing"]["decoded_frame_count"],
                    "duration": result["format"].get("duration"),
                    "bit_rate": result["stream"].get("bit_rate"),
                    "color_range": result["stream"].get("color_range"),
                    "bits_per_pixel_per_frame": temporal.get(
                        "bits_per_pixel_per_frame"
                    ),
                    "frame_step_p99": temporal.get("core_luma_frame_step_p99"),
                    "frame_step_max": temporal.get("core_luma_frame_step_max"),
                    "three_frame_impulse_p99": temporal.get(
                        "core_luma_three_frame_impulse_p99"
                    ),
                    "alternating_step_count": temporal.get(
                        "core_luma_alternating_step_over_0_5pct_count"
                    ),
                    "high_frequency_power_ratio_8hz": temporal.get(
                        "core_luma_high_frequency_power_ratio_8hz"
                    ),
                    "dynamic_metadata_frames": result["dynamic_metadata"][
                        "dynamic_metadata_frame_count"
                    ],
                    "dynamic_metadata_unique_payloads": result[
                        "dynamic_metadata"
                    ]["dynamic_metadata_unique_payloads"],
                    "cuvv_count": result["cuvv"]["cuvv_count"],
                    "dolby_vision_rpu_frames": result["dolby_vision"][
                        "rpu_frame_count"
                    ],
                    "dvvC_count": result["dolby_vision"]["dvvC_count"],
                    "errors": "；".join(result["errors"]),
                    "warnings": "；".join(result["warnings"]),
                    "sha256": result["sha256"],
                }
            )

    status_counts = {
        status: sum(result["status"] == status for result in results)
        for status in ("pass", "review", "fail")
    }
    print(json.dumps(status_counts, ensure_ascii=False))
    print(output_dir / "matrix-validation.json")


if __name__ == "__main__":
    main()
