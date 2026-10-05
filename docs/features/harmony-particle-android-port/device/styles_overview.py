"""把每段参考样式各录的一轮示例对话框消散排成一张总表：一行一段样式，按消散开始后的时刻取帧。

用法：python styles_overview.py --runs c2 --out <png>   （读 tmp/harmony-particle-android-port/c2-<样式名>/round-0.mp4）
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402

TIMES = (0.08, 0.18, 0.3, 0.42, 0.55, 0.7, 0.85)
CROP = (0, 330, 1440, 1500)         # 设备像素：预览区（含颗粒飞出去的那一圈）
DIALOG = (120, 640, 1320, 1120)     # 设备像素：示例对话框按钮行以上（按「稍后」的涟漪不算消散开始）
WIDTH = 1440 // 3


def font(size):
    for name in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def row(video, label):
    items = r.frames(video, video.parent / 'overview-frames', scale_width=WIDTH)
    s = WIDTH / 1440
    x1, y1, x2, y2 = (int(v * s) for v in DIALOG)
    base = np.asarray(Image.open(items[0][1]).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
    start = None
    for t, path in items:
        a = np.asarray(Image.open(path).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
        if np.abs(a - base).mean() > 1.0:
            start = t
            break
    if start is None:
        return None
    cells = []
    for dt in TIMES:
        target = start + dt
        t, path = min(items, key=lambda it: abs(it[0] - target))
        crop = tuple(int(v * s) for v in CROP)
        cells.append((dt, Image.open(path).convert('RGB').crop(crop)))
    return cells


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--runs', default='')
    p.add_argument('--videos', default='', help='直接给「行名=运行目录名」，逗号分隔，例如 原样=c3-ironman,变体=c4-v1')
    p.add_argument('--out', required=True)
    p.add_argument('--names', default='ironman,ironman-up-reference,kobe,thanos,spiderman-original,'
                   'reference-0926-1,reference-0926-2,reference-0926-3,reference-0926-4,reference-0926-5,'
                   'reference-0926-6,reference-0926-7,random')
    a = p.parse_args()
    rows = []
    pairs = [v.split('=', 1) for v in a.videos.split(',') if v] if a.videos else         [(name, '%s-%s' % (a.runs, name)) for name in a.names.split(',')]
    for name, run in pairs:
        video = d.OUT / run / 'round-0.mp4'
        if not video.is_file():
            continue
        cells = row(video, name)
        if cells:
            rows.append((name, cells))
    if not rows:
        raise SystemExit('没有可用的录像')
    cw, ch = rows[0][1][0][1].size
    label_w = 220
    head = 34
    sheet = Image.new('RGB', (label_w + cw * len(TIMES), head + ch * len(rows)), (250, 250, 250))
    draw = ImageDraw.Draw(sheet)
    f = font(20)
    for k, dt in enumerate(TIMES):
        draw.text((label_w + k * cw + 8, 6), '+%.2f s' % dt, fill=(40, 40, 40), font=f)
    for j, (name, cells) in enumerate(rows):
        y = head + j * ch
        draw.text((8, y + ch // 2 - 12), name, fill=(20, 20, 20), font=f)
        for k, (_, img) in enumerate(cells):
            sheet.paste(img, (label_w + k * cw, y))
    sheet.save(a.out)
    print(a.out, sheet.size, [n for n, _ in rows])


if __name__ == '__main__':
    main()
