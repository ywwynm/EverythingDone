# -*- coding: utf-8 -*-
"""参考 / 桌面蓝本在同一归一化时刻上的并排对照（等尺度）。

输出：tmp/particle-dismiss-tuning/gap-analysis-20260830/11-折叠压缩后.jpg
"""
import glob, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
ROOT = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning"
MODEL = os.path.join(ROOT, "frames-curtain", "primary-frames")
OUT = os.path.join(ROOT, "gap-analysis-20260830", "11-折叠压缩后.jpg")
STEPS = (0.30, 0.45, 0.60)
T0, T1 = 3.28, 8.38
REF_CROP = (45, 95, 525, 567)          # 参考卡片外扩后的观察窗
MODEL_CROP = (170, 150, 1110, 790)     # 蓝本同比例观察窗


def font(sz):
    try:
        return ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", sz)
    except Exception:
        return ImageFont.load_default()


rfs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
rts = np.array([int(os.path.basename(f)[2:7]) for f in rfs]) / 60.0
mfs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
N = len(mfs) - 1

TILE_W = 440
tiles = []
for n in STEPS:
    r = Image.open(rfs[int(np.argmin(np.abs(rts - (T0 + n * (T1 - T0)))))]).convert("RGB").crop(REF_CROP)
    m = Image.open(mfs[int(round(n * N))]).convert("RGB").crop(MODEL_CROP)
    h = int(round(TILE_W * r.height / r.width))
    tiles.append((n, r.resize((TILE_W, h), Image.LANCZOS),
                  m.resize((TILE_W, int(round(TILE_W * m.height / m.width))), Image.LANCZOS)))

rows = [max(a.height, b.height) + 18 for _, a, b in tiles]
sheet = Image.new("RGB", (TILE_W * 2 + 12, sum(rows)), (8, 8, 10))
d = ImageDraw.Draw(sheet)
y = 0
for (n, r, m), rh in zip(tiles, rows):
    sheet.paste(r, (0, y + 18))
    sheet.paste(m, (TILE_W + 12, y + 18))
    d.text((4, y + 2), "REFERENCE n=%.2f" % n, font=font(15), fill=(120, 230, 220))
    d.text((TILE_W + 16, y + 2), "MODEL n=%.2f" % n, font=font(15), fill=(250, 200, 90))
    y += rh
sheet.save(OUT, quality=92)
print("并排对照 ->", OUT)
