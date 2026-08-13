#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把真机取帧拼成可目检的图。两种版式：

- `sheet`：每场景一行五帧（中心 + 左/右/上/下满偏移），用于快速扫一遍；
- `zoom`：对指定场景/方向按 9 宫格裁剪放大，用于逐块查条带与空洞（D186 的教训：
  只放大两三处会漏，要铺开）。

缺数据一律画斜纹并写明缺什么，**不得用别的帧兜底**。
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw


import sys
sys.path.insert(0, str(Path(__file__).parent))
from android_frame_audit import content_box


def crop_frame(scene_dir: Path, scene: str, tag: str):
    bounds = content_box(scene_dir / f"{scene}-center.png")
    p = scene_dir / f"{scene}-{tag}.png"
    if not p.is_file():
        return None, (bounds["width"], bounds["height"])
    im = Image.open(p).convert("RGB")
    return im.crop((bounds["x1"], bounds["y1"], bounds["x2"], bounds["y2"])), None


def missing_tile(size, label):
    im = Image.new("RGB", size, (32, 32, 32))
    d = ImageDraw.Draw(im)
    for x in range(-size[1], size[0], 24):
        d.line([(x, 0), (x + size[1], size[1])], fill=(90, 40, 40), width=3)
    d.text((10, 10), f"缺：{label}", fill=(255, 200, 200))
    return im


def sheet(root: Path, out: Path, scale: float = 0.34):
    scenes = sorted(b.name.replace("-bounds.json", "") for b in root.glob("*-bounds.json"))
    tags = ["center", "d000", "d180", "d090", "d270"]
    titles = ["中心", "右(0deg)", "左(180deg)", "上(90deg)", "下(270deg)"]
    tiles = {}
    tw = th = 0
    for s in scenes:
        for t in tags:
            im, fallback = crop_frame(root, s, t)
            if im is None:
                im = missing_tile((int(fallback[0]), int(fallback[1])), f"{s}-{t}")
            im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
            tiles[(s, t)] = im
            tw, th = max(tw, im.width), max(th, im.height)
    pad, head, left = 6, 26, 150
    W = left + len(tags) * (tw + pad) + pad
    H = head + len(scenes) * (th + pad) + pad
    canvas = Image.new("RGB", (W, H), (18, 18, 18))
    d = ImageDraw.Draw(canvas)
    for i, t in enumerate(titles):
        d.text((left + i * (tw + pad) + 4, 6), t, fill=(220, 220, 220))
    for j, s in enumerate(scenes):
        y = head + j * (th + pad)
        d.text((6, y + th // 2), s, fill=(220, 220, 220))
        for i, t in enumerate(tags):
            canvas.paste(tiles[(s, t)], (left + i * (tw + pad), y))
    canvas.save(out)
    print(f"{out}  {W}x{H}  {len(scenes)} 场景 x {len(tags)} 帧")


def zoom(root: Path, scene: str, tag: str, out: Path, grid: int = 3, box: int = 300):
    im, _ = crop_frame(root, scene, tag)
    if im is None:
        raise SystemExit(f"没有这一帧：{scene}-{tag}")
    W, H = im.size
    pad = 6
    canvas = Image.new("RGB", (grid * (box + pad) + pad, grid * (box + pad) + pad + 22),
                       (18, 18, 18))
    d = ImageDraw.Draw(canvas)
    d.text((6, 4), f"{scene} {tag}  取景 {W}x{H}  每块 {box}px 原尺寸", fill=(220, 220, 220))
    for j in range(grid):
        for i in range(grid):
            cx = int((i + 0.5) * W / grid)
            cy = int((j + 0.5) * H / grid)
            x1 = max(0, min(W - box, cx - box // 2))
            y1 = max(0, min(H - box, cy - box // 2))
            canvas.paste(im.crop((x1, y1, x1 + box, y1 + box)),
                         (pad + i * (box + pad), 22 + pad + j * (box + pad)))
    canvas.save(out)
    print(f"{out}  {canvas.size[0]}x{canvas.size[1]}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["sheet", "zoom"])
    ap.add_argument("root", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--scene")
    ap.add_argument("--tag", default="d000")
    ap.add_argument("--scale", type=float, default=0.34)
    ap.add_argument("--box", type=int, default=300)
    a = ap.parse_args()
    if a.mode == "sheet":
        sheet(a.root, a.out, a.scale)
    else:
        zoom(a.root, a.scene, a.tag, a.out, box=a.box)
