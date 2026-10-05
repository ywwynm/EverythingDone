"""按原片解除时刻自动选一块刚被前沿扫过的区域，给出源图与连续独立帧的原尺寸放大，判断释放后颗粒是原地残留还是离开。

区域取解除时刻接近 --release 的 10 像素块附近；可选 --model 同时给出候选模型同一区域。只作目视诊断。
"""
from pathlib import Path
import argparse, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from match_refs import RefFrames

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--release', type=float, default=.3)
    ap.add_argument('--size', type=int, default=60)
    ap.add_argument('--offsets', type=float, nargs='+', default=[-.03, 0., .02, .04, .07, .10, .14])
    ap.add_argument('--zoom', type=int, default=5)
    ap.add_argument('--spec', default=None, help='handoff_variants 变体；给出时第二行为模型')
    ap.add_argument('--pick', type=int, default=0, help='候选块的序号（按接近程度排序）')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    probe = Probe(a.scene); T = probe.huawei(); x0, y0, x1, y1 = probe.rect
    ok = probe.valid & np.isfinite(T) & (np.abs(T - a.release) < .02)
    ys, xs = np.nonzero(ok)
    if not len(ys): raise SystemExit('没有解除时刻接近目标的块')
    # 取周围也在同一时刻附近的块，避免落在前沿拐角。
    score = [abs(np.nanmean(T[max(0, y - 2):y + 3, max(0, x - 2):x + 3]) - a.release) for y, x in zip(ys, xs)]
    order = np.argsort(score); by, bx = ys[order[a.pick]], xs[order[a.pick]]
    cx = x0 + bx * B + B // 2; cy = y0 + by * B + B // 2; h = a.size // 2
    box = (cx - h, cy - h, cx + h, cy + h)
    ref = RefFrames(a.scene)
    src = np.array(Image.open(HERE / 'assets' / a.scene / 'source.png').convert('RGB'))
    rows = [[('源图', Image.fromarray(src).crop(box))]]
    for o in a.offsets:
        i, f = ref.nearest(a.release + o)
        rows[0].append((f'原片 τ={float(ref.progress[i]) - a.release:+.3f}', Image.fromarray(np.asarray(f)).crop(box)))
    if a.spec is not None:
        from handoff_review import render_all
        frames = render_all(a.scene, HERE, 'front-zoom', spec=a.spec)
        rows.append([('模型源图', Image.fromarray(src).crop(box))])
        for o in a.offsets:
            k = round(np.clip(a.release + o, 0, 1) * 120)
            rows[1].append((f'模型 τ={k / 120 - a.release:+.3f}', Image.fromarray(np.asarray(frames[k])).crop(box)))
    w = a.size * a.zoom
    canvas = Image.new('RGB', (len(rows[0]) * (w + 4), len(rows) * (w + 20)), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for r, row in enumerate(rows):
        for j, (t, im) in enumerate(row):
            canvas.paste(im.resize((w, w), Image.Resampling.NEAREST), (j * (w + 4), r * (w + 20) + 18)); d.text((j * (w + 4) + 3, r * (w + 20)), t, font=FONT, fill='white')
    canvas.save(a.out); print(a.out, '区域', box, '块解除时刻', float(T[by, bx]))


if __name__ == '__main__':
    main()
