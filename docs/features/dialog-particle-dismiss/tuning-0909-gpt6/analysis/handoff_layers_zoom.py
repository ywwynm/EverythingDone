"""同一区域的源图、重建背景、原片若干帧与模型帧并排放大，判断画面中的纹理属于颗粒、表面还是背景。

只作目视诊断。
"""
from pathlib import Path
import argparse, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_observe import Scene
from match_refs import RefFrames
from handoff_review import render_all, OUT

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--phases', type=float, nargs='+', default=[.3, .35, .4])
    ap.add_argument('--zoom', type=float, default=4.)
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--out')
    a = ap.parse_args()
    s = Scene(a.scene); ref = RefFrames(a.scene)
    x0, y0, x1, y1 = a.box; w = round((x1 - x0) * a.zoom); h = round((y1 - y0) * a.zoom)
    crop = lambda arr: Image.fromarray(np.clip(np.asarray(arr), 0, 255).astype(np.uint8)).crop(a.box).resize((w, h), Image.Resampling.NEAREST)
    rows = [[('源图', crop(s.source)), ('重建背景', crop(s.bg))]]
    frames = {}
    for v in a.variants:
        label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        frames[label] = render_all(a.scene, p if p.is_absolute() else HERE / p, label, spec=spec)
    for ph in a.phases:
        i, f = ref.nearest(ph)
        row = [(f'原片 {float(ref.progress[i]):.3f}', crop(f))]
        row += [(f'{l} {ph:.3f}', crop(fr[round(ph * 120)])) for l, fr in frames.items()]
        rows.append(row)
    cols = max(len(r) for r in rows)
    canvas = Image.new('RGB', (cols * (w + 4), len(rows) * (h + 22)), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for r, row in enumerate(rows):
        for c, (title, im) in enumerate(row):
            d.text((c * (w + 4) + 2, r * (h + 22) + 2), title, font=FONT, fill=(235, 242, 250)); canvas.paste(im, (c * (w + 4), r * (h + 22) + 20))
    out = Path(a.out) if a.out else OUT / f'layers-{a.scene}.png'
    canvas.save(out); print(out)


if __name__ == '__main__':
    main()
