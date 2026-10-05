"""原片连续独立帧的原尺寸局部，并排或放大，用于逐颗粒判断运动方向与速度。

可选 --model 同时给出模型在相同进度的局部（完整继承链缓存）。只作目视诊断。
"""
from pathlib import Path
import argparse, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
OUT = HERE / 'analysis/match-cache/handoff-review'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--start', type=float, required=True)
    ap.add_argument('--count', type=int, default=8)
    ap.add_argument('--step', type=int, default=1, help='每隔几个独立帧取一帧')
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--zoom', type=float, default=2.)
    ap.add_argument('--per-row', type=int, default=4)
    ap.add_argument('--model', action='store_true')
    ap.add_argument('--spec', default='', help='模型列使用的 handoff_variants 变体')
    ap.add_argument('--out')
    a = ap.parse_args()
    ref = RefFrames(a.scene)
    i0 = int(np.argmin(abs(ref.progress - a.start)))
    idx = [i0 + k * a.step for k in range(a.count) if i0 + k * a.step < len(ref)]
    frames = None
    if a.model:
        from handoff_review import render_all
        frames = render_all(a.scene, HERE, 'current' if not a.spec else 'consecutive', spec=a.spec)
    x0, y0, x1, y1 = a.box; w = round((x1 - x0) * a.zoom); h = round((y1 - y0) * a.zoom)
    cells = []
    for i in idx:
        p = float(ref.progress[i])
        im = Image.fromarray(np.asarray(ref.frames[i])).crop(a.box).resize((w, h), Image.Resampling.NEAREST)
        cells.append((f'原片 #{i} p={p:.4f}', im))
        if frames is not None:
            k = round(np.clip(p, 0, 1) * 120)
            cells.append((f'模型 p={k / 120:.4f}', Image.fromarray(np.asarray(frames[k])).crop(a.box).resize((w, h), Image.Resampling.NEAREST)))
    per = a.per_row * (2 if frames is not None else 1)
    rows = (len(cells) + per - 1) // per
    canvas = Image.new('RGB', (per * (w + 4), rows * (h + 22)), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for j, (title, im) in enumerate(cells):
        cx = (j % per) * (w + 4); cy = (j // per) * (h + 22)
        d.text((cx + 2, cy + 2), title, font=FONT, fill=(235, 242, 250)); canvas.paste(im, (cx, cy + 20))
    out = Path(a.out) if a.out else OUT / f'{a.scene}-consecutive-{a.start:.3f}.png'
    canvas.save(out); print(out, '独立帧间隔', np.round(np.diff(ref.progress[idx]), 4).tolist())


if __name__ == '__main__':
    main()
