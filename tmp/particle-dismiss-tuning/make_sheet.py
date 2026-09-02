# -*- coding: utf-8 -*-
"""参考 / 草稿 八个时刻的卡片裁剪对照表（上行参考、下行草稿，两组）。"""
import sys, numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, ".")
import release_time_map as RT
ref = RT.reference_frames(); times = [n for n, _ in ref]
x0, y0, x1, y1 = RT.CARD; ox0, oy0, ox1, oy1 = RT.OURS_CARD
wants = [0.10, 0.17, 0.24, 0.31, 0.38, 0.45, 0.52, 0.60]
idx = [int(np.argmin(np.abs(np.array(times) - w))) for w in wants]
ours = list(RT.ours_frames(times))
tiles_r, tiles_o = [], []
for i in idx:
    n, p = ref[i]
    a = Image.open(p).convert("RGB").crop((x0 - 20, y0 - 20, x1 + 20, y1 + 20))
    b = Image.fromarray(ours[i].astype(np.uint8)).crop((ox0 - 20, oy0 - 20, ox1 + 20, oy1 + 20))
    for im, lab in ((a, "ref n=%.2f" % n), (b, "ours n=%.2f" % n)):
        d = ImageDraw.Draw(im); d.rectangle((0, 0, 110, 22), fill=(0, 0, 0)); d.text((4, 4), lab, fill=(255, 255, 255))
    tiles_r.append(a); tiles_o.append(b)
tw, th = tiles_r[0].size
sheet = Image.new("RGB", (tw * 4 + 30, th * 4 + 40), (40, 40, 40))
for i in range(8):
    col, row = i % 4, i // 4
    sheet.paste(tiles_r[i], (col * (tw + 10), row * 2 * (th + 10)))
    sheet.paste(tiles_o[i], (col * (tw + 10), (row * 2 + 1) * (th + 10)))
sheet.save("perframe/sheet-ref-vs-ours.png"); print("sheet", sheet.size)
