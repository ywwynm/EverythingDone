"""十二段华为原片按相同进度并排（只看原片），用于归纳共同的释放与运动规律。

每行一个序列，列为进度；裁剪框为控件外扩 0.45 短边，并标出观测起点。只读原片帧库，不参与运行模型。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from export_v2_suite import Truth
from v2_inputs import OBSERVED_SITES

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
OUT = HERE / 'analysis/match-cache/handoff-review'


def meta(name):
    return json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--phases', type=float, nargs='+', default=[.20, .32, .44, .56, .68])
    ap.add_argument('--cell', type=int, default=300)
    ap.add_argument('--margin', type=float, default=.30)
    ap.add_argument('--out', default=str(OUT / 'originals.jpg'))
    ap.add_argument('--model', help='同时给出该配置目录的模型帧（原片、模型交替成列）')
    ap.add_argument('--label', default='current')
    ap.add_argument('--variants', nargs='*', default=[], help='另加的变体列：标签=配置目录::变体')
    a = ap.parse_args()
    rows = []
    for name in a.scenes:
        m = meta(name); x, y, x1, y1 = m['rect']; fw, fh = m['frame']; span = min(x1 - x, y1 - y)
        pad = round(span * a.margin)
        box = (max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad))
        truth = Truth(name); cells = []
        sources = [truth.at]
        if a.model:
            from handoff_review import render_all
            root = Path(a.model); root = root if root.is_absolute() else HERE / root
            frames = render_all(name, root, a.label)
            sources.append(lambda p, f=frames: np.asarray(f[round(np.clip(p, 0, 1) * (len(f) - 1))]))
        for s in a.variants:
            from handoff_review import render_all
            label, rest = s.split('=', 1); d, _, spec = rest.partition('::'); root = Path(d)
            root = root if root.is_absolute() else HERE / root
            frames = render_all(name, root, label, spec=spec)
            sources.append(lambda p, f=frames: np.asarray(f[round(np.clip(p, 0, 1) * (len(f) - 1))]))
        for p, get in [(p, g) for p in a.phases for g in sources]:
            im = Image.fromarray(np.ascontiguousarray(get(p))).crop(box)
            d = ImageDraw.Draw(im)
            for sx, sy, st in OBSERVED_SITES[name]:
                cx = x + sx * (x1 - x) - box[0]; cy = y + sy * (y1 - y) - box[1]
                d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), outline=(255, 60, 60), width=2)
            # 风向箭头：场景方向角（0° 向右，逆时针为正，屏幕 y 向下）。
            ang = np.radians(m['direction']); ox, oy = (x1 - x) / 2 + x - box[0], (y1 - y) / 2 + y - box[1]
            d.line((ox, oy, ox + 50 * np.cos(ang), oy - 50 * np.sin(ang)), fill=(80, 255, 120), width=3)
            im.thumbnail((a.cell, a.cell), Image.Resampling.LANCZOS)
            cells.append(im)
        rows.append((name, m['title'], cells))
    cw = max(c.width for _, _, cs in rows for c in cs); ch = max(c.height for _, _, cs in rows for c in cs)
    ncols = len(rows[0][2])
    canvas = Image.new('RGB', (160 + ncols * (cw + 4), 24 + len(rows) * (ch + 4)), (14, 20, 30))
    d = ImageDraw.Draw(canvas)
    per = ncols // len(a.phases)
    names = ['原片'] + (['模型'] if a.model else []) + [s.split('=', 1)[0] for s in a.variants]
    for j in range(ncols):
        p = a.phases[j // per]; tag = '' if per == 1 else names[j % per] + ' '
        d.text((160 + j * (cw + 4) + 4, 2), f'{tag}{p:.2f}', font=FONT, fill=(235, 242, 250))
    for i, (name, title, cells) in enumerate(rows):
        y0 = 24 + i * (ch + 4)
        d.text((4, y0 + ch // 2 - 18), title, font=FONT, fill=(235, 242, 250))
        d.text((4, y0 + ch // 2 + 2), name, font=FONT, fill=(150, 170, 190))
        for j, c in enumerate(cells):
            canvas.paste(c, (160 + j * (cw + 4), y0))
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    canvas.save(a.out, quality=90)
    print(a.out)


if __name__ == '__main__':
    main()
