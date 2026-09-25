"""看画面用的联系表：指定会话、若干时刻、可选裁剪区域，按全分辨率出图后缩放拼接。

python -X utf8 look.py --scene scenes/samsung-send --times 4.3 4.6 4.9 --out out/look/x.png
                       [--crop x0 y0 x1 y1] [--width 360] [--cols 6] [--params file|scene|<路径>] [--seed N]
                       [--dark]（深色底，看颗粒的明暗）
裁剪坐标是屏幕像素。每格左上角标时刻与阶段。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from export import FONT, load_params
from sim_model import HERE
from sim_session import SessionPlayer
from sim_timeline import Session


def render_sheet(player, times, out: Path, crop=None, width=360, cols=6, title: str | None = None):
    font = ImageFont.truetype(FONT, 18)
    tiles = []
    for t in times:
        image, label = player.render(t)
        if crop:
            x0, y0, x1, y1 = crop
            image = image[y0:y1, x0:x1]
        im = Image.fromarray(image)
        im = im.resize((width, max(1, round(im.height * width / im.width))), Image.LANCZOS)
        draw = ImageDraw.Draw(im)
        text = f'{t:.2f}s {label}'
        box = draw.textbbox((0, 0), text, font=font)
        draw.rectangle([0, 0, box[2] + 8, box[3] + 6], fill=(0, 0, 0))
        draw.text((4, 2), text, fill=(255, 255, 255), font=font)
        tiles.append(np.asarray(im))
    th = max(t.shape[0] for t in tiles)
    tiles = [np.pad(t, ((0, th - t.shape[0]), (0, 0), (0, 0))) for t in tiles]
    rows = []
    for i in range(0, len(tiles), cols):
        row = tiles[i:i + cols]
        while len(row) < cols:
            row.append(np.zeros_like(tiles[0]))
        rows.append(np.concatenate(row, axis=1))
    sheet = np.concatenate(rows, axis=0)
    if title:
        head = Image.new('RGB', (sheet.shape[1], 34), (16, 20, 28))
        ImageDraw.Draw(head).text((8, 5), title, fill=(230, 236, 244), font=ImageFont.truetype(FONT, 20))
        sheet = np.concatenate([np.asarray(head), sheet], axis=0)
    out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(sheet).save(out)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--times', type=float, nargs='+', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--crop', type=int, nargs=4)
    ap.add_argument('--width', type=int, default=360)
    ap.add_argument('--cols', type=int, default=6)
    ap.add_argument('--params', default='file')
    ap.add_argument('--seed', type=int)
    ap.add_argument('--hz', type=float, default=120.)
    ap.add_argument('--title')
    ap.add_argument('--recolor', type=int, help='按这个种子随机换记事颜色（纯色或渐变）')
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    session = Session(HERE / a.scene)
    if a.recolor is not None:
        from sim_recolor import recolor
        session = recolor(session, a.recolor)
        print(session.title)
    player = SessionPlayer(session, load_params(session, a.params), ctx, 1., a.seed, a.hz)
    try:
        print(render_sheet(player, a.times, Path(a.out), a.crop, a.width, a.cols, a.title))
    finally:
        player.close()


if __name__ == '__main__':
    main()
