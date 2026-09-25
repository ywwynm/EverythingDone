"""轨迹诊断图：一次飞行里抽一部分颗粒，把它们在 61 份采样里的位置连成线画在底图上。

颜色区分三类：被吸入天线的（橙）、途中消散的（青）、流向边框的（紫）；线的透明度随颗粒是否存活变化，
只画它真正可见的那一段。用来看路径的形状（是不是直线汇聚、有没有尖角、螺线多宽），不代表画面。

python -X utf8 paths.py --scene scenes/meizu-send --out out/look/paths.png [--count 1500] [--params file]
"""
from __future__ import annotations

import argparse
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

from export import load_params
from sim_model import HERE, SAMPLES, build_materials, field_targets
from sim_renderer import TapRenderer
from sim_timeline import Session


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--count', type=int, default=1500)
    ap.add_argument('--params', default='file')
    ap.add_argument('--width', type=int, default=900)
    ap.add_argument('--at', type=float, nargs='*', help='另画这些时刻（0–1）存活颗粒的位置点')
    a = ap.parse_args()
    session = Session(HERE / a.scene)
    scene = session.flights[0][1]
    params = load_params(session, a.params)
    ctx = moderngl.create_standalone_context(require=430)
    seed = int(scene.meta['seed'])
    build = build_materials(scene, params, seed)
    renderer = TapRenderer(scene, build, ctx, .25)
    n = renderer.n
    history = np.frombuffer(renderer.history.read(), dtype='float32').reshape(SAMPLES + 1, n, 8)
    base = build.base
    _, frame_bound, absorbed = field_targets(base, build.sink, build.card)
    rng = np.random.default_rng(1)
    pick = rng.choice(n, size=min(a.count, n), replace=False)
    w, h = scene.screen
    s = a.width / w
    bg = Image.fromarray(scene.background).convert('RGB').resize((a.width, round(h * s)))
    bg = Image.blend(bg, Image.new('RGB', bg.size, (0, 0, 0)), .55)
    over = Image.new('RGBA', bg.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(over)
    ox, oy = build.offset
    scale = build.scale
    for i in pick:
        born, life = base[i, 2], base[i, 6]
        color = (160, 120, 255) if frame_bound[i] else (255, 160, 60) if absorbed[i] else (60, 220, 230)
        prev = None
        for k in range(SAMPLES + 1):
            t = k / SAMPLES
            alive = born < t < born + life
            x, y = history[k, i, 0], history[k, i, 1]
            q = ((x + ox) * scale * s, (y + oy) * scale * s)
            if alive and prev is not None:
                age = (t - born) / max(life, 1e-3)
                alpha = int(200 * (1 - age) + 40)
                draw.line([prev, q], fill=color + (alpha,), width=1)
            prev = q if alive else None
    for t in a.at or []:
        k = int(round(t * SAMPLES))
        alive = (base[:, 2] < t) & (t < base[:, 2] + base[:, 6])
        for i in np.flatnonzero(alive)[::7]:
            x, y = history[k, i, 0], history[k, i, 1]
            q = ((x + ox) * scale * s, (y + oy) * scale * s)
            draw.point(q, fill=(255, 255, 255, 255))
    sx, sy = scene.sink
    draw.ellipse([sx * s - 5, sy * s - 5, sx * s + 5, sy * s + 5], outline=(255, 255, 255, 255), width=2)
    out = Image.alpha_composite(bg.convert('RGBA'), over).convert('RGB')
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    out.save(a.out)
    print(a.out, f'n={n} 吸入 {absorbed[~frame_bound].mean():.2f} 边框 {frame_bound.mean():.2f}')
    renderer.close()


if __name__ == '__main__':
    main()
