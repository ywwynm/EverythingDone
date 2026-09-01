# -*- coding: utf-8 -*-
"""把参考、桌面 canonical、真机三路素材归一化到同一个真实秒并拼成对照视频。

参考原片在效果段被慢放 3.42×（由状态栏录屏计时器的秒进位实测），窗口
3.28–8.38 s 对应真实约 1.49 s；这里把它重采样成 1.000 s，与模型、真机
统一，便于逐帧对照。

运行：
    & 'C:\\Users\\ywwynm\\miniconda3\\envs\\everythingdone\\python.exe' \
      tmp\\particle-dismiss-tuning\\make_reference_comparison.py
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "cloth-motion-prototype"
FRAMES = HERE / "frames-curtain"
FPS = 60
DURATION = 1.0

REFERENCE = Path(
    r"E:\WeChatFiles\xwechat_files\wxid_yizrz7pph07f22_8943\temp\RWTemp"
    r"\2026-08\b95204e02d0afaf2bf4fb5148d30500c\93292e5c744d4770c4f7ecb686f3dd60.mp4"
)
# 参考效果窗口（视频秒）。末帧亮像素归零于 8.38 s。
REFERENCE_WINDOW = (3.28, 8.38)
# 参考画面里卡片所在区域，留出粒子外扩的余量。
REFERENCE_CROP = (580, 660, 85, 170)   # w, h, x, y

DEVICE_META = HERE / "device-recordings" / "latest.json"


def run(args: list[str]) -> None:
    subprocess.run(args, check=True)


def normalize_clip(source: Path, window: tuple[float, float], crop, target: Path) -> None:
    """把 source 的 [t0, t1] 段重采样成严格 1.000 s / 60 fps。"""
    t0, t1 = window
    span = t1 - t0
    filters = []
    if crop:
        filters.append("crop=%d:%d:%d:%d" % crop)
    # setpts 把窗口线性映射到 1 秒；再用 fps 滤镜重采样到固定 60 帧
    filters.append(f"setpts=(PTS-STARTPTS)*{DURATION / span:.9f}")
    filters.append(f"fps={FPS}")
    run([
        "ffmpeg", "-v", "error", "-y",
        "-ss", f"{t0:.4f}", "-to", f"{t1:.4f}", "-i", str(source),
        "-an", "-vf", ",".join(filters),
        "-frames:v", str(FPS),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
        str(target),
    ])


def normalize_model(target: Path) -> None:
    frame_dir = FRAMES / "primary-frames"
    if not (frame_dir / "frame-000.png").is_file():
        raise SystemExit("缺少桌面 canonical 帧，先运行 render_curtain_model.py")
    run([
        "ffmpeg", "-v", "error", "-y",
        "-framerate", str(FPS), "-i", str(frame_dir / "frame-%03d.png"),
        "-frames:v", str(FPS),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
        str(target),
    ])


def normalize_refcontent(target: Path) -> None:
    """参考的控件内容 + 我们的动画。

    用途是隔离内容变量：参考是一张照片、我们的对话框是一片白，观感差异里混了
    「内容不同」和「动画不同」两部分。同一份内容跑我们的着色器之后，剩下的差异
    就只剩动画本身。放在参考的紧右侧，方便逐帧对位。
    """
    frame_dir = HERE / "frames-refcontent"
    if not (frame_dir / "frame-000.png").is_file():
        print("! 缺少参考内容场景帧，先运行 render_reference_content_scene.py")
        return
    run([
        "ffmpeg", "-v", "error", "-y",
        "-framerate", str(FPS), "-i", str(frame_dir / "frame-%03d.png"),
        "-frames:v", str(FPS),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
        str(target),
    ])


def normalize_attachment(target: Path) -> None:
    """按真机截图重建的「添加附件」桌面场景。

    手机不在手边时用它判断真机观感；接上手机后它仍然有用——它与真机跑的是同一套
    着色器和同一组常量，两列并排能直接看出「桌面重建」与「真机实拍」之间还剩多少
    差异（录屏压缩、屏幕色彩、真机分辨率）。
    """
    frame_dir = HERE / "frames-attachment"
    if not (frame_dir / "frame-000.png").is_file():
        print("! 缺少「添加附件」场景帧，先运行 render_attachment_scene.py")
        return
    run([
        "ffmpeg", "-v", "error", "-y",
        "-framerate", str(FPS), "-i", str(frame_dir / "frame-%03d.png"),
        "-frames:v", str(FPS),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
        str(target),
    ])


def stack(paths: list[Path], labels: list[str], target: Path) -> None:
    """各列等高并排，并在每列上方烧入标签。"""
    inputs: list[str] = []
    for path in paths:
        inputs += ["-i", str(path)]
    height = 640
    parts = []
    for index, label in enumerate(labels):
        parts.append(
            f"[{index}:v]scale=-2:{height},pad=iw:ih+40:0:40:color=0x0f1211,"
            f"drawtext=text='{label}':fontcolor=0xe9ede9:fontsize=22:x=14:y=9[v{index}]"
        )
    parts.append("".join(f"[v{i}]" for i in range(len(paths))) + f"hstack=inputs={len(paths)}[out]")
    run([
        "ffmpeg", "-v", "error", "-y", *inputs,
        "-filter_complex", ";".join(parts),
        "-map", "[out]", "-frames:v", str(FPS),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
        str(target),
    ])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-reference", action="store_true")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    produced: list[tuple[Path, str]] = []

    reference_out = OUT / "reference-normalized.mp4"
    if not args.skip_reference:
        if REFERENCE.is_file():
            normalize_clip(REFERENCE, REFERENCE_WINDOW, REFERENCE_CROP, reference_out)
            print("参考已归一化 ->", reference_out.name)
        else:
            print("! 找不到参考原片，跳过：", REFERENCE)
    if reference_out.is_file():
        produced.append((reference_out, "reference"))

    refcontent_out = OUT / "refcontent-normalized.mp4"
    normalize_refcontent(refcontent_out)
    if refcontent_out.is_file():
        print("参考内容 + 我们的动画 已归一化 ->", refcontent_out.name)
        produced.append((refcontent_out, "our anim / ref content"))

    model_out = OUT / "model-normalized.mp4"
    normalize_model(model_out)
    print("桌面 canonical 已归一化 ->", model_out.name)
    produced.append((model_out, "desktop canonical"))

    attachment_out = OUT / "attachment-normalized.mp4"
    normalize_attachment(attachment_out)
    if attachment_out.is_file():
        print("「添加附件」场景已归一化 ->", attachment_out.name)
        produced.append((attachment_out, "desktop attachment"))

    device_out = OUT / "device-normalized.mp4"
    if DEVICE_META.is_file():
        meta = json.loads(DEVICE_META.read_text(encoding="utf-8"))
        source = Path(meta["source"])
        if not source.is_absolute():
            source = HERE / source
        normalize_clip(
            source,
            (float(meta["start"]), float(meta["end"])),
            tuple(meta["crop"]) if meta.get("crop") else None,
            device_out,
        )
        print("真机录制已归一化 ->", device_out.name)
    else:
        print("! 还没有真机录制元数据：", DEVICE_META)
    if device_out.is_file():
        produced.append((device_out, "device SM-S9180"))

    if len(produced) >= 2:
        target = OUT / "comparison.mp4"
        stack([p for p, _ in produced], [label for _, label in produced], target)
        print("对照视频 ->", target)
    else:
        print("! 可用素材不足两路，未生成对照视频")


if __name__ == "__main__":
    main()
