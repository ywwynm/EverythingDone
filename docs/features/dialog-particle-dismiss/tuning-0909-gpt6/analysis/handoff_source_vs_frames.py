"""同一场景区域：源图（未释放前的控件）与原片若干进度并排放大，判断颗粒是否比源色更亮。"""
from pathlib import Path
import argparse, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--phases', type=float, nargs='+', required=True)
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--zoom', type=float, default=3.)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    ref = RefFrames(a.scene)
    src = np.array(Image.open(HERE / 'assets' / a.scene / 'source.png').convert('RGB'))
    x0, y0, x1, y1 = a.box; w = round((x1 - x0) * a.zoom); h = round((y1 - y0) * a.zoom)
    cells = [('源图', Image.fromarray(src).crop(a.box))]
    for p in a.phases:
        i, f = ref.nearest(p)
        cells.append((f'原片 {float(ref.progress[i]):.3f}', Image.fromarray(np.asarray(f)).crop(a.box)))
    canvas = Image.new('RGB', (len(cells) * (w + 4), h + 22), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for j, (t, im) in enumerate(cells):
        canvas.paste(im.resize((w, h), Image.Resampling.NEAREST), (j * (w + 4), 22)); d.text((j * (w + 4) + 3, 2), t, font=FONT, fill='white')
    canvas.save(a.out); print(a.out)


if __name__ == '__main__':
    main()
