# -*- coding: utf-8 -*-
"""按真机截图重建「添加附件」对话框的桌面场景。

手机不在手边时用它判断真机上的观感。构图取自 2026-08-31 的真机录像：对话框近似
正方（设备像素约 1050x1035），白底、四行图标条目，背景是偏暖的深色，左上角有几行
浅色文字。

它复用 render_curtain_model 的 canonical GLSL 与全部常量——只换快照与背景，不换
任何模型参数，因此和真机跑的是同一套着色器。
"""
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import render_curtain_model as R  # noqa: E402

# 真机对话框近似正方；改 SNAP_* 必须在构造 CurtainRenderer 之前。
R.SNAP_W, R.SNAP_H = 620, 610
OUT = HERE / "frames-attachment"
ROWS = (("拍照", "camera"), ("录制视频", "video"), ("录音", "mic"), ("选择媒体文件", "play"))


def make_snapshot(width=None, height=None):
    w, h = width or R.SNAP_W, height or R.SNAP_H
    image = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    d.rounded_rectangle((1, 1, w - 2, h - 2), radius=44, fill=(252, 252, 252, 255))
    d.text((52, 44), "添加附件", font=R.font(40, True), fill=(24, 26, 30, 255))
    for i, (label, glyph) in enumerate(ROWS):
        y = 150 + i * 108
        d.rounded_rectangle((52, y, 96, y + 44), radius=8, fill=(120, 126, 134, 255))
        if glyph == "camera":
            d.ellipse((64, y + 12, 84, y + 32), fill=(252, 252, 252, 255))
        elif glyph == "video":
            d.polygon([(66, y + 14), (66, y + 30), (86, y + 22)], fill=(252, 252, 252, 255))
        elif glyph == "mic":
            d.rounded_rectangle((68, y + 10, 80, y + 30), radius=6, fill=(252, 252, 252, 255))
        else:
            d.ellipse((62, y + 10, 86, y + 34), outline=(252, 252, 252, 255), width=3)
        d.text((132, y + 6), label, font=R.font(32), fill=(52, 56, 62, 255))
    return image


def make_background():
    image = Image.new("RGB", (R.VIEW_W, R.VIEW_H), (28, 25, 20))
    d = ImageDraw.Draw(image)
    for y in range(0, R.VIEW_H, 3):                      # 轻微的暖色渐变
        t = y / float(R.VIEW_H)
        d.rectangle((0, y, R.VIEW_W, y + 3),
                    fill=(int(30 + 10 * t), int(27 + 8 * t), int(21 + 5 * t)))
    d.text((70, 120), "标题", font=R.font(34, True), fill=(126, 122, 112))
    d.text((70, 186), "测试空间照片效果", font=R.font(26), fill=(96, 93, 86))
    d.text((70, 250), "创建于 2026-08-31", font=R.font(24), fill=(84, 81, 75))
    for i in range(2):                                    # 顶部的照片附件缩略图
        x = 70 + i * 300
        d.rounded_rectangle((x, 320, x + 260, 520), radius=16, fill=(58, 54, 48))
        d.polygon([(x + 40, 470), (x + 120, 380), (x + 200, 470)], fill=(96, 92, 84))
    return image


R.make_snapshot = make_snapshot
R.make_background = make_background


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    renderer = R.CurtainRenderer()
    # 真机上对话框从右下往左上消散；沿用同一个角度与种子。
    renderer.configure(R.Scenario("attachment", 242.0, 42))
    frames = []
    for i in range(R.FPS + 1):
        frames.append(renderer.composite(i / R.FPS))
    for i, im in enumerate(frames):
        im.save(OUT / ("frame-%03d.png" % i))
    proc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", "%dx%d" % (R.VIEW_W, R.VIEW_H), "-r", str(R.FPS), "-i", "-",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
         "-movflags", "+faststart", str(OUT / "attachment.mp4")],
        stdin=subprocess.PIPE)
    for im in frames:
        proc.stdin.write(np.asarray(im.convert("RGB")).tobytes())
    proc.stdin.close()
    proc.wait()
    print("动画 ->", OUT / "attachment.mp4")

    picks = [int(R.FPS * r) for r in (0.22, 0.36, 0.50, 0.64)]
    crop = (250, 100, 1030, 800)
    sheet = Image.new("RGB", ((crop[2] - crop[0]) * 2, (crop[3] - crop[1]) * 2))
    for k, i in enumerate(picks):
        sheet.paste(frames[i].crop(crop),
                    ((k % 2) * (crop[2] - crop[0]), (k // 2) * (crop[3] - crop[1])))
    path = HERE / "gap-analysis-20260830" / "18-添加附件场景.jpg"
    sheet.save(path, quality=92)
    print("关键帧 ->", path)


main()
