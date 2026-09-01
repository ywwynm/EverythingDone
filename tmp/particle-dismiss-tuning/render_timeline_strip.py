# -*- coding: utf-8 -*-
"""逐时刻的四列并排长条，用来一帧一帧比，而不是只看两三个时刻。

直接读四个已归一化的分片（同一时间基准、同为 60 帧 / 1.000 秒），因此不同列的
同一列位就是同一个归一化时刻。每列上方标注它自己的裁剪范围——「谁飞得远」这类
判断会被裁剪尺度直接决定，不标注就会得出错误结论。
"""
import os
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
CLIPS = os.path.join(HERE, "cloth-motion-prototype")
OUT = os.path.join(HERE, "gap-analysis-20260830", "19-逐时刻对照.jpg")
NAMES = (("reference", "reference-normalized.mp4"),
         ("our anim / ref content", "refcontent-normalized.mp4"),
         ("desktop canonical", "model-normalized.mp4"),
         ("desktop attachment", "attachment-normalized.mp4"),
         ("device SM-S9180", "device-normalized.mp4"))
STEPS = [0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80]
TILE = 260


def font(sz):
    try:
        return ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", sz)
    except Exception:
        return ImageFont.load_default()


def read(path):
    cap = cv2.VideoCapture(path)
    out = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        out.append(f)
    cap.release()
    return out


rows = []
for label, fn in NAMES:
    path = os.path.join(CLIPS, fn)
    if not os.path.isfile(path):
        print("跳过缺失的分片:", fn)
        continue
    fr = read(path)
    if fr:
        rows.append((label, fr))

n = min(len(fr) for _, fr in rows)
tiles = []
for label, fr in rows:
    h, w = fr[0].shape[:2]
    th = int(round(TILE * h / w))
    tiles.append((label, [cv2.resize(fr[int(round(s * (n - 1)))], (TILE, th),
                                     interpolation=cv2.INTER_AREA) for s in STEPS]))

heights = [t[1][0].shape[0] for t in tiles]
sheet = Image.new("RGB", (TILE * len(STEPS), sum(heights) + 22 * len(tiles) + 8), (8, 8, 10))
d = ImageDraw.Draw(sheet)
y = 0
for (label, imgs), th in zip(tiles, heights):
    d.text((6, y + 3), label, font=font(14), fill=(120, 230, 220))
    for k, im in enumerate(imgs):
        sheet.paste(Image.fromarray(im[:, :, ::-1]), (k * TILE, y + 22))
        if y == 0:
            d.text((k * TILE + 6, y + 6), "n=%.2f" % STEPS[k], font=font(13), fill=(240, 220, 120))
    y += th + 22
sheet.save(OUT, quality=92)
print("逐时刻对照 ->", OUT)
