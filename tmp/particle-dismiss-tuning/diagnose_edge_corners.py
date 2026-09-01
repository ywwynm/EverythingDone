# -*- coding: utf-8 -*-
"""释放场的等值线诊断：尖角是场本身的构造带来的，还是抖动带来的。

左：释放场 turbo 着色 + 等值线（等值线就是每一时刻未粒子化区域的边界）
右：模型实帧在左上角的放大，与参考同尺度并排
"""
import glob, os, sys, math
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning")
SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
MODEL = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-curtain\primary-frames"
OUT = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\gap-analysis-20260830"


def font(sz):
    try:
        return ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", sz)
    except Exception:
        return ImageFont.load_default()


def contour_sheet(field, label, path):
    v = np.nan_to_num(field, nan=1.0)
    u = np.clip(v, 0, 1)
    col = cv2.applyColorMap((u * 255).astype(np.uint8), cv2.COLORMAP_TURBO)
    for lvl in np.arange(0.06, 0.72, 0.06):
        m = (u <= lvl).astype(np.uint8)
        cs, _ = cv2.findContours(m, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
        cv2.drawContours(col, cs, -1, (255, 255, 255), 1)
    img = Image.fromarray(col[:, :, ::-1])
    z = max(1, 900 // max(img.size))
    img = img.resize((img.width * z, img.height * z), Image.NEAREST)
    d = ImageDraw.Draw(img)
    d.text((6, 4), label, font=font(18), fill=(255, 255, 255))
    img.save(path)
    print("等值线 ->", path)


# ---- 模型释放场
import render_curtain_model as R

renderer = R.CurtainRenderer()
renderer.configure(R.Scenario("left-up", 242.0, 42))
pure, release, raw = renderer.release_probe()
contour_sheet(release, "MODEL release field (contours = intact boundary over time)",
              os.path.join(OUT, "12-模型释放场等值线.png"))

# ---- 参考释放场
ref = np.load(os.path.join(SP, "release2.npy"))
contour_sheet(ref / max(np.nanmax(ref), 1e-6),
              "REFERENCE release field", os.path.join(OUT, "13-参考释放场等值线.png"))

# ---- 左上角放大并排
mfs = sorted(glob.glob(os.path.join(MODEL, "frame-*.png")))
N = len(mfs) - 1
rfs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
rts = np.array([int(os.path.basename(f)[2:7]) for f in rfs]) / 60.0
T0, T1 = 3.28, 8.38
tiles = []
for n in (0.20, 0.30, 0.40):
    m = Image.open(mfs[int(round(n * N))]).convert("RGB").crop((280, 240, 280 + 300, 240 + 175))
    r = Image.open(rfs[int(np.argmin(np.abs(rts - (T0 + n * (T1 - T0)))))]).convert("RGB").crop(
        (45, 95, 45 + 200, 95 + 117))
    tiles.append((n, r.resize((600, 351), Image.NEAREST), m.resize((600, 350), Image.NEAREST)))
sheet = Image.new("RGB", (1212, sum(t[1].height for t in tiles) + 22 * len(tiles)), (8, 8, 10))
d = ImageDraw.Draw(sheet)
y = 0
for n, r, m in tiles:
    d.text((4, y + 2), "REFERENCE top-left n=%.2f" % n, font=font(15), fill=(120, 230, 220))
    d.text((616, y + 2), "MODEL top-left n=%.2f" % n, font=font(15), fill=(250, 200, 90))
    sheet.paste(r, (0, y + 22))
    sheet.paste(m, (612, y + 22))
    y += r.height + 22
p = os.path.join(OUT, "14-左上角边界放大.png")
sheet.save(p)
print("左上角放大 ->", p)
