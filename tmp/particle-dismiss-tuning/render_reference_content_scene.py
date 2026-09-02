# -*- coding: utf-8 -*-
"""把参考的控件内容与背景原样搬过来，跑我们自己的动画。

这一列的用途是**隔离内容变量**：参考是一张照片、我们的对话框是一片白，两者的
观感差异里混了「内容不同」和「动画不同」两部分。用同一份内容跑我们的着色器，
剩下的差异就只剩动画本身。

快照取参考的首帧（t=3.28，动画尚未开始）在卡片区域的裁剪；背景取末帧（粒子散尽
后的画面）。卡片在参考画面里的位置是 (45, 95)，而 CurtainRenderer 会把快照居中，
因此背景要反向平移同样的量，卡片才落回它原本所在的位置。
"""
import glob
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import render_curtain_model as R  # noqa: E402

SP = Path(r"C:\Users\ywwynm\AppData\Local\Temp\claude"
          r"\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad")
CARD = (45, 95, 525, 567)          # 参考画面里卡片的位置
OUT = HERE / "frames-refcontent"

R.VIEW_W, R.VIEW_H = 580, 660
# 角度按实测对齐参考：拟合两边的释放时刻场，参考的扫描方向是 58.5°，
# 我们在 242.0 下是 50.9°。方向不一致会把其余所有比较都污染掉。
# release_time_map.py 会读这个变量，两处不得各写各的。
SCENARIO_ANGLE = 235.0   # 参考粒子光流 -125°（图像坐标），2026-09-02 实测
# 种子：分界线上走得慢的那一段落在哪里由种子决定（用户 2026-09-02 选 C）。参考只有
# 一个样本，其最后消失的那片在左偏中 (-0.25,-0.26)；扫了 10 个种子，77 的落点
# (-0.40,-0.40) 与之最接近，对照时用它，免得把"随机落在另一侧"当成模型差异。
SCENARIO_SEED = 77
R.SNAP_W, R.SNAP_H = CARD[2] - CARD[0], CARD[3] - CARD[1]


def _first_frame():
    fs = sorted(glob.glob(str(SP / "anim2" / "a_*.png")))
    if not fs:
        raise SystemExit("找不到参考帧序列")
    return Image.open(fs[0]).convert("RGB")


def make_snapshot(width=None, height=None):
    return _first_frame().crop(CARD).convert("RGBA")


def make_background():
    bg = Image.open(SP / "bg.png").convert("RGB")
    # CurtainRenderer 把快照居中；把背景反向平移，卡片才落回 (45, 95)。
    dx = int(round((R.VIEW_W - R.SNAP_W) / 2.0)) - CARD[0]
    dy = int(round((R.VIEW_H - R.SNAP_H) / 2.0)) - CARD[1]
    shifted = Image.new("RGB", (R.VIEW_W, R.VIEW_H), (0, 0, 0))
    shifted.paste(bg, (dx, dy))
    return shifted


R.make_snapshot = make_snapshot
R.make_background = make_background


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    renderer = R.CurtainRenderer()
    # 与其它列同一个方向与种子，保证比较的是内容而不是随机性。
    renderer.configure(R.Scenario("refcontent", SCENARIO_ANGLE, SCENARIO_SEED))
    frames = [renderer.composite(i / R.FPS) for i in range(R.FPS + 1)]
    for i, im in enumerate(frames):
        im.save(OUT / ("frame-%03d.png" % i))
    proc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", "%dx%d" % (R.VIEW_W, R.VIEW_H), "-r", str(R.FPS), "-i", "-",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
         "-movflags", "+faststart", str(OUT / "refcontent.mp4")],
        stdin=subprocess.PIPE)
    for im in frames:
        proc.stdin.write(np.asarray(im.convert("RGB")).tobytes())
    proc.stdin.close()
    proc.wait()
    print("动画 ->", OUT / "refcontent.mp4")


main()
