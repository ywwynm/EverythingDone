# -*- coding: utf-8 -*-
"""生成当前物理布面版本的桌面/参考/真机归一化验证材料。"""

from __future__ import annotations

import json
import statistics
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
OUT = HERE / "final-lower-envelope-validation"
REFERENCE = Path(
    r"E:\WeChatFiles\xwechat_files\wxid_yizrz7pph07f22_8943\temp\RWTemp\2026-08"
    r"\b95204e02d0afaf2bf4fb5148d30500c\93292e5c744d4770c4f7ecb686f3dd60.mp4"
)
DESKTOP = HERE / "cloth-motion-prototype" / "assets" / "android-canonical-left-up.mp4"
DEVICE_RAW = (
    HERE
    / "device-validation"
    / "lower-envelope-20260830-01"
    / "device-dismiss.mp4"
)
DEVICE_SERIAL = "R5CW20BLNKL"
REFERENCE_START = 3.35
REFERENCE_DURATION = 4.70
LOGICAL_DURATION = 1.0
KEY_PROGRESS = (0.10, 0.25, 0.40, 0.47, 0.54, 0.70, 0.90, 1.00)
EARLY_EDGE_PROGRESS = (0.10, 0.16, 0.216, 0.24, 0.32)
LOWER_ENVELOPE_PROGRESS = (0.32, 0.40, 0.47, 0.54, 0.65, 0.70)
REFERENCE_DIALOG = (130, 265, 460, 467)
DESKTOP_DIALOG = (80, 541, 560, 550)
DEVICE_DIALOG = (195, 1055, 1050, 1047)


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\arial.ttf")):
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def video_timestamps(path: Path) -> list[float]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "frame=best_effort_timestamp_time",
            "-of",
            "json",
            str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    data = json.loads(result.stdout)
    return [float(frame["best_effort_timestamp_time"]) for frame in data["frames"]]


def find_active_start(timestamps: list[float]) -> float:
    """找录屏中首个持续高刷新率区间，排除触摸前静态 VFR 帧。"""
    for index in range(1, len(timestamps) - 12):
        previous_gap = timestamps[index] - timestamps[index - 1]
        following = [
            timestamps[offset + 1] - timestamps[offset]
            for offset in range(index, index + 12)
        ]
        if previous_gap >= 0.05 and max(following) <= 0.025:
            return timestamps[index]
    raise RuntimeError("未在真机录屏中找到连续动画帧区间")


def extract_frame(video: Path, timestamp: float, output: Path) -> Image.Image:
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-ss",
        f"{timestamp:.6f}",
        "-i",
        str(video),
        "-frames:v",
        "1",
        str(output),
    ]
    run(command)
    # 1.000 秒是动画的逻辑终点；有些 MP4 的最后一帧时间戳略小于
    # duration，精确 seek 会返回空结果。此时退回半帧读取实际末帧。
    if not output.is_file():
        command[6] = f"{max(0.0, timestamp - 1.0 / 30.0):.6f}"
        run(command)
    with Image.open(output) as image:
        return image.convert("RGB")


def fit_panel(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    result = Image.new("RGB", size, (4, 6, 9))
    fitted = image.copy()
    fitted.thumbnail(size, Image.Resampling.LANCZOS)
    result.paste(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return result


def make_contact_sheet(active_start: float) -> None:
    panel_size = (320, 704)
    label_height = 42
    columns = (
        ("参考", REFERENCE, lambda progress: REFERENCE_START + progress * REFERENCE_DURATION),
        ("桌面 canonical", DESKTOP, lambda progress: progress * LOGICAL_DURATION),
        (
            f"真机 {DEVICE_SERIAL}",
            DEVICE_RAW,
            lambda progress: active_start + progress * LOGICAL_DURATION,
        ),
    )
    sheet = Image.new(
        "RGB",
        (panel_size[0] * len(columns), (panel_size[1] + label_height) * len(KEY_PROGRESS)),
        (10, 12, 17),
    )
    draw = ImageDraw.Draw(sheet)
    title_font = font(20)
    progress_font = font(18)
    temporary_files: list[Path] = []
    try:
        for row, progress in enumerate(KEY_PROGRESS):
            for column, (title, video, timestamp_for) in enumerate(columns):
                temporary = OUT / f".frame-{row}-{column}.png"
                temporary_files.append(temporary)
                frame = fit_panel(
                    extract_frame(video, timestamp_for(progress), temporary), panel_size
                )
                x = column * panel_size[0]
                y = row * (panel_size[1] + label_height)
                sheet.paste(frame, (x, y + label_height))
                draw.text((x + 8, y + 8), title, font=title_font, fill=(232, 235, 242))
                if column == 0:
                    draw.text(
                        (x + panel_size[0] - 88, y + 10),
                        f"t={progress:.2f}",
                        font=progress_font,
                        fill=(136, 178, 247),
                    )
    finally:
        for temporary in temporary_files:
            temporary.unlink(missing_ok=True)
    sheet.save(OUT / "reference-desktop-device-contact.png")


def crop_early_edge(
    image: Image.Image,
    dialog: tuple[int, int, int, int],
) -> Image.Image:
    """裁出左上角、上边缘和左边缘共同可见的归一化区域。"""
    x, y, width, height = dialog
    return image.crop((
        round(x - width * 0.18),
        round(y - height * 0.20),
        round(x + width * 0.78),
        round(y + height * 0.60),
    ))


def make_early_edge_contact(active_start: float) -> None:
    """单独放大开场多边缘，防止全屏缩略图把细粒变化压没。"""
    panel_size = (360, 300)
    label_height = 42
    columns = (
        (
            "参考",
            REFERENCE,
            REFERENCE_DIALOG,
            lambda progress: REFERENCE_START + progress * REFERENCE_DURATION,
        ),
        (
            "桌面 canonical",
            DESKTOP,
            DESKTOP_DIALOG,
            lambda progress: progress * LOGICAL_DURATION,
        ),
        (
            f"真机 {DEVICE_SERIAL}",
            DEVICE_RAW,
            DEVICE_DIALOG,
            lambda progress: active_start + progress * LOGICAL_DURATION,
        ),
    )
    sheet = Image.new(
        "RGB",
        (panel_size[0] * len(columns), (panel_size[1] + label_height) * len(EARLY_EDGE_PROGRESS)),
        (10, 12, 17),
    )
    draw = ImageDraw.Draw(sheet)
    title_font = font(20)
    progress_font = font(18)
    temporary_files: list[Path] = []
    try:
        for row, progress in enumerate(EARLY_EDGE_PROGRESS):
            for column, (title, video, dialog, timestamp_for) in enumerate(columns):
                temporary = OUT / f".early-frame-{row}-{column}.png"
                temporary_files.append(temporary)
                full_frame = extract_frame(video, timestamp_for(progress), temporary)
                frame = fit_panel(crop_early_edge(full_frame, dialog), panel_size)
                x = column * panel_size[0]
                y = row * (panel_size[1] + label_height)
                sheet.paste(frame, (x, y + label_height))
                draw.text((x + 8, y + 8), title, font=title_font, fill=(232, 235, 242))
                if column == 0:
                    draw.text(
                        (x + panel_size[0] - 96, y + 10),
                        f"t={progress:.3f}",
                        font=progress_font,
                        fill=(136, 178, 247),
                    )
    finally:
        for temporary in temporary_files:
            temporary.unlink(missing_ok=True)
    sheet.save(OUT / "early-edge-reference-desktop-device-contact.png")


def crop_lower_envelope(
    image: Image.Image,
    dialog: tuple[int, int, int, int],
) -> Image.Image:
    """裁出包含主体下边缘、右下折叠和已脱离粒子群的连续区域。"""
    x, y, width, height = dialog
    return image.crop((
        max(0, round(x - width * 0.18)),
        max(0, round(y + height * 0.12)),
        min(image.width, round(x + width * 1.25)),
        min(image.height, round(y + height * 1.38)),
    ))


def make_lower_envelope_contact(active_start: float) -> None:
    """放大对比容易出现拼接凸包的下边缘，避免全屏缩放掩盖曲率跳变。"""
    panel_size = (480, 420)
    label_height = 42
    columns = (
        (
            "参考",
            REFERENCE,
            REFERENCE_DIALOG,
            lambda progress: REFERENCE_START + progress * REFERENCE_DURATION,
        ),
        (
            "桌面 canonical",
            DESKTOP,
            DESKTOP_DIALOG,
            lambda progress: progress * LOGICAL_DURATION,
        ),
        (
            f"真机 {DEVICE_SERIAL}",
            DEVICE_RAW,
            DEVICE_DIALOG,
            lambda progress: active_start + progress * LOGICAL_DURATION,
        ),
    )
    sheet = Image.new(
        "RGB",
        (panel_size[0] * len(columns), (panel_size[1] + label_height) * len(LOWER_ENVELOPE_PROGRESS)),
        (10, 12, 17),
    )
    draw = ImageDraw.Draw(sheet)
    title_font = font(20)
    progress_font = font(18)
    temporary_files: list[Path] = []
    try:
        for row, progress in enumerate(LOWER_ENVELOPE_PROGRESS):
            for column, (title, video, dialog, timestamp_for) in enumerate(columns):
                temporary = OUT / f".lower-envelope-frame-{row}-{column}.png"
                temporary_files.append(temporary)
                full_frame = extract_frame(video, timestamp_for(progress), temporary)
                frame = fit_panel(crop_lower_envelope(full_frame, dialog), panel_size)
                x = column * panel_size[0]
                y = row * (panel_size[1] + label_height)
                sheet.paste(frame, (x, y + label_height))
                draw.text((x + 8, y + 8), title, font=title_font, fill=(232, 235, 242))
                if column == 0:
                    draw.text(
                        (x + panel_size[0] - 96, y + 10),
                        f"t={progress:.2f}",
                        font=progress_font,
                        fill=(136, 178, 247),
                    )
    finally:
        for temporary in temporary_files:
            temporary.unlink(missing_ok=True)
    sheet.save(OUT / "lower-envelope-reference-desktop-device-contact.png")


def make_videos(active_start: float) -> None:
    normalized_device = OUT / "device-left-up-normalized-1s.mp4"
    run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{active_start:.6f}",
            "-i",
            str(DEVICE_RAW),
            "-t",
            f"{LOGICAL_DURATION:.6f}",
            "-vf",
            "fps=60,setpts=PTS-STARTPTS",
            "-c:v",
            "libx264",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(normalized_device),
        ]
    )

    font_path = "C\\:/Windows/Fonts/msyh.ttc"
    reference_end = REFERENCE_START + REFERENCE_DURATION
    device_end = active_start + LOGICAL_DURATION
    filter_complex = (
        f"[0:v]trim=start={REFERENCE_START}:end={reference_end},setpts=PTS-STARTPTS,"
        "fps=60,scale=480:-2:flags=lanczos,pad=480:1056:(ow-iw)/2:(oh-ih)/2:black,"
        f"drawtext=fontfile='{font_path}':text='参考':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[r];"
        f"[1:v]trim=start=0:end={LOGICAL_DURATION},"
        f"setpts={REFERENCE_DURATION}*(PTS-STARTPTS),fps=60,scale=480:1056:flags=lanczos,"
        f"drawtext=fontfile='{font_path}':text='桌面 canonical':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[d];"
        f"[2:v]trim=start={active_start}:end={device_end},"
        f"setpts={REFERENCE_DURATION}*(PTS-STARTPTS),fps=60,scale=480:-2:flags=lanczos,"
        "pad=480:1056:(ow-iw)/2:(oh-ih)/2:black,"
        f"drawtext=fontfile='{font_path}':text='真机 {DEVICE_SERIAL}':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[p];"
        "[r][d][p]hstack=inputs=3,format=yuv420p[out]"
    )
    run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(REFERENCE),
            "-i",
            str(DESKTOP),
            "-i",
            str(DEVICE_RAW),
            "-filter_complex",
            filter_complex,
            "-map",
            "[out]",
            "-t",
            f"{REFERENCE_DURATION:.6f}",
            "-c:v",
            "libx264",
            "-crf",
            "19",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(OUT / "reference-desktop-device-slow.mp4"),
        ]
    )

    # 正常验收版本把参考原片的 4.70 秒窗口压缩到统一的真实 1.000 秒；
    # 桌面和真机保持原速。慢速版本只用于逐帧检查，不再作为网页主入口。
    normalized_filter = (
        f"[0:v]trim=start={REFERENCE_START}:end={reference_end},"
        f"setpts=(PTS-STARTPTS)/{REFERENCE_DURATION},fps=60,"
        "scale=480:-2:flags=lanczos,pad=480:1056:(ow-iw)/2:(oh-ih)/2:black,"
        f"drawtext=fontfile='{font_path}':text='参考（归一化 1 s）':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[r];"
        f"[1:v]trim=start=0:end={LOGICAL_DURATION},setpts=PTS-STARTPTS,fps=60,"
        "scale=480:1056:flags=lanczos,"
        f"drawtext=fontfile='{font_path}':text='桌面 canonical':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[d];"
        f"[2:v]trim=start={active_start}:end={device_end},setpts=PTS-STARTPTS,fps=60,"
        "scale=480:-2:flags=lanczos,pad=480:1056:(ow-iw)/2:(oh-ih)/2:black,"
        f"drawtext=fontfile='{font_path}':text='真机 {DEVICE_SERIAL}':x=12:y=12:fontsize=28:"
        "fontcolor=white:box=1:boxcolor=black@0.62[p];"
        "[r][d][p]hstack=inputs=3,format=yuv420p[out]"
    )
    run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(REFERENCE),
            "-i",
            str(DESKTOP),
            "-i",
            str(DEVICE_RAW),
            "-filter_complex",
            normalized_filter,
            "-map",
            "[out]",
            "-t",
            f"{LOGICAL_DURATION:.6f}",
            "-c:v",
            "libx264",
            "-crf",
            "19",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(OUT / "reference-desktop-device-normalized-1s.mp4"),
        ]
    )


def main() -> None:
    for required in (REFERENCE, DESKTOP, DEVICE_RAW):
        if not required.is_file():
            raise FileNotFoundError(required)
    OUT.mkdir(parents=True, exist_ok=True)

    timestamps = video_timestamps(DEVICE_RAW)
    active_start = find_active_start(timestamps)
    active_timestamps = [
        value for value in timestamps if active_start <= value <= active_start + LOGICAL_DURATION
    ]
    intervals_ms = [
        (right - left) * 1000.0
        for left, right in zip(active_timestamps, active_timestamps[1:])
    ]
    ordered = sorted(intervals_ms)
    p95_index = min(len(ordered) - 1, round((len(ordered) - 1) * 0.95))
    median_interval = statistics.median(intervals_ms)
    metrics = {
        "deviceSerial": DEVICE_SERIAL,
        "deviceCapture": str(DEVICE_RAW),
        "activeStartSeconds": active_start,
        "logicalDurationSeconds": LOGICAL_DURATION,
        "activeFrameCount": len(active_timestamps),
        "medianFrameIntervalMs": median_interval,
        "p95FrameIntervalMs": ordered[p95_index],
        "maxFrameIntervalMs": max(intervals_ms),
        "medianRefreshHz": 1000.0 / median_interval,
        "referenceWindowSeconds": [REFERENCE_START, REFERENCE_START + REFERENCE_DURATION],
        "desktopVideo": str(DESKTOP),
    }
    (OUT / "validation-metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    make_videos(active_start)
    make_contact_sheet(active_start)
    make_early_edge_contact(active_start)
    make_lower_envelope_contact(active_start)
    print(json.dumps(metrics, ensure_ascii=False))


if __name__ == "__main__":
    main()
