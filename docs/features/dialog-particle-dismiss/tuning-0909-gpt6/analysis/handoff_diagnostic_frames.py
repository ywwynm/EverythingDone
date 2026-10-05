"""诊断着色帧：运动颗粒统一染色（diagnostic=1），看清颗粒实际分布与密度，不受源色和明暗影响。"""
from pathlib import Path
import argparse, sys
import numpy as np, moderngl
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from handoff_variants import build
from export_v2_suite import Truth

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
OUT = HERE / 'analysis/match-cache/handoff-review'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--specs', nargs='+', default=[''])
    ap.add_argument('--phases', type=float, nargs='+', default=[.40, .52, .64, .76])
    ap.add_argument('--box', type=int, nargs=4)
    ap.add_argument('--scale', type=float, default=.4)
    ap.add_argument('--out')
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    truth = Truth(a.scene)
    rows = []
    for spec in a.specs:
        cls, kw = build(HERE, spec)
        r = cls(a.scene, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[a.scene]], **kw)
        rows.append((spec or '当前', [r.render(p, diagnostic=1) for p in a.phases]))
        r.close()
    ctx.release()
    box = tuple(a.box) if a.box else None
    def prep(f):
        im = Image.fromarray(np.ascontiguousarray(f))
        if box: im = im.crop(box)
        return im.resize((round(im.width * a.scale), round(im.height * a.scale)), Image.Resampling.LANCZOS)
    cells = [[('原片', prep(truth.at(p))) for p in a.phases]] + [[(label, prep(f)) for f in fs] for label, fs in rows]
    w, h = cells[0][0][1].size
    canvas = Image.new('RGB', (len(a.phases) * (w + 4), len(cells) * (h + 22)), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for i, row in enumerate(cells):
        for j, (label, im) in enumerate(row):
            x0 = j * (w + 4); y0 = i * (h + 22)
            d.text((x0 + 2, y0 + 1), f'{label} · {a.phases[j]:.2f}', font=FONT, fill=(235, 242, 250)); canvas.paste(im, (x0, y0 + 20))
    out = Path(a.out) if a.out else OUT / f'{a.scene}-diagnostic.jpg'
    canvas.save(out, quality=92); print(out)


if __name__ == '__main__':
    main()
