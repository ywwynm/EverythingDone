# -*- coding: utf-8 -*-
"""参考 | 桌面草稿 逐帧并排视频：两列三行。

用户 2026-09-02 的三条要求：至少两列；下面加一行把羽流的边界画出来；再把两个动画
叠在一起看像素差异。

布局（2 列 × 3 行）：
  第一行：参考 | 桌面草稿（原画面）
  第二行：同一帧叠上羽流边界（红线）——明亮、密集的粒子云的外轮廓，两列同一判据
  第三行：叠加图 | 差异图
          叠加图：参考画在红色通道、草稿画在青色通道——两边一致的地方是灰/白，
                 只在参考里有的是红，只在草稿里有的是青，一眼看出差在哪。
          差异图：逐像素绝对差，越亮差越大。

我们的卡片画在 (50, 94)、参考在 (45, 95)，并排前把我们的画面平移对齐。

输出：
  frames-refcontent/compare-2col.mp4        原速（60fps）
  frames-refcontent/compare-2col-slow.mp4   3 倍慢放
"""
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import release_time_map as RT  # noqa: E402
import render_curtain_model as R  # noqa: E402

OUT = HERE / "frames-refcontent"
GAP = 12
BG_ARR = np.asarray(Image.open(RT.SP / "bg.png").convert("RGB"), np.float32)

# 羽流边界判据
BRIGHT_OVER_BG = 55.0      # 粒子像素：亮度高于背景多少才算"亮粒子"
DENSITY_RADIUS = 15        # 密度平滑半径（px）
DENSITY_THRESHOLD = 0.10   # 密度超过多少算"密集云"
MIN_AREA = 1500            # 忽略太小的团


def plume_contours(frame, source):
    """返回密集亮云的外轮廓列表（像素坐标）。"""
    lum = frame.mean(2)
    changed = np.abs(frame - source).max(2) > 26
    bright = changed & (lum > BG_ARR.mean(2) + BRIGHT_OVER_BG)
    bright[:80, :] = False
    k = 2 * DENSITY_RADIUS + 1
    dens = cv2.blur(bright.astype(np.float32), (k, k))
    mask = (dens > DENSITY_THRESHOLD).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    cs, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    return [c.reshape(-1, 2) for c in cs if cv2.contourArea(c) > MIN_AREA]


def draw_contours(image, contours, colour):
    d = ImageDraw.Draw(image)
    for c in contours:
        pts = [tuple(p) for p in c] + [tuple(c[0])]
        d.line(pts, fill=colour, width=3)
    return image


def overlay(a, b):
    """参考→红通道，草稿→青通道。"""
    la = a.mean(2)
    lb = b.mean(2)
    out = np.stack([la, lb, lb], 2)
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def diff_map(a, b):
    d = np.abs(a - b).max(2)
    heat = np.zeros(a.shape, np.float32)
    v = np.clip(d / 120.0, 0, 1)
    heat[..., 0] = v * 255
    heat[..., 1] = v * v * 160
    heat[..., 2] = np.clip(v * 3.0, 0, 1) * 60
    return Image.fromarray(heat.astype(np.uint8))


def label(sheet, x, y, h, text, font):
    d = ImageDraw.Draw(sheet)
    d.rectangle((x, y + h - 34, x + 300, y + h), fill=(0, 0, 0))
    d.text((x + 8, y + h - 30), text, font=font, fill=(240, 240, 240))


def main():
    ref = RT.reference_frames()
    times = [n for n, _ in ref]
    ours = RT.ours_frames(times)
    font = R.font(22, True)
    dx = RT.CARD[0] - RT.OURS_CARD[0]
    dy = RT.CARD[1] - RT.OURS_CARD[1]
    ref_src = np.asarray(Image.open(ref[0][1]).convert("RGB"), np.float32)
    our_src = None
    proc = None
    w = h = None
    for (n, path), mine in zip(ref, ours):
        a = np.asarray(Image.open(path).convert("RGB"), np.float32)
        shifted = Image.new("RGB", (mine.shape[1], mine.shape[0]), (0, 0, 0))
        shifted.paste(Image.fromarray(mine.astype(np.uint8)), (dx, dy))
        b = np.asarray(shifted, np.float32)
        if our_src is None:
            our_src = b.copy()
        if w is None:
            h, w = a.shape[:2]
            frame_w, frame_h = w * 2 + GAP, h * 3 + 2 * GAP
            proc = subprocess.Popen(
                ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                 "-s", "%dx%d" % (frame_w, frame_h), "-r", "60", "-i", "-",
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
                 "-movflags", "+faststart", str(OUT / "compare-2col.mp4")],
                stdin=subprocess.PIPE)
        ia = Image.fromarray(a.astype(np.uint8))
        ib = Image.fromarray(b.astype(np.uint8))
        ca = draw_contours(ia.copy(), plume_contours(a, ref_src), (255, 70, 70))
        cb = draw_contours(ib.copy(), plume_contours(b, our_src), (255, 70, 70))
        sheet = Image.new("RGB", (frame_w, frame_h), (18, 18, 20))
        y1, y2 = h + GAP, 2 * (h + GAP)
        sheet.paste(ia, (0, 0))
        sheet.paste(ib, (w + GAP, 0))
        sheet.paste(ca, (0, y1))
        sheet.paste(cb, (w + GAP, y1))
        sheet.paste(overlay(a, b), (0, y2))
        sheet.paste(diff_map(a, b), (w + GAP, y2))
        label(sheet, 0, 0, h, "参考  n=%.3f" % n, font)
        label(sheet, w + GAP, 0, h, "桌面草稿  n=%.3f" % n, font)
        label(sheet, 0, y1, h, "参考 · 羽流边界", font)
        label(sheet, w + GAP, y1, h, "草稿 · 羽流边界", font)
        label(sheet, 0, y2, h, "叠加：红=只有参考  青=只有草稿", font)
        label(sheet, w + GAP, y2, h, "逐像素差异（越亮差越大）", font)
        proc.stdin.write(np.asarray(sheet).tobytes())
    proc.stdin.close()
    proc.wait()
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(OUT / "compare-2col.mp4"),
         "-vf", "setpts=PTS*3.0,fps=60", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "24",
         "-movflags", "+faststart", str(OUT / "compare-2col-slow.mp4")], check=True)
    print("对比视频（三行）->", OUT / "compare-2col.mp4")
    print("慢放 ->", OUT / "compare-2col-slow.mp4")


if __name__ == "__main__":
    main()
