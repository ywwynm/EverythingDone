"""亮材料的去向：把各时刻的高亮像素按时间着色叠在一张图上（早＝蓝，晚＝红），原片与模型并排。

只统计比源图同位置亮出一截、且亮度超过阈值的像素，排除尚未释放的亮源本身。用于看亮源材料被拉成什么形状、
朝哪里走；不是颗粒身份追踪。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames
from handoff_review import render_all, OUT

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)


def paths(frames_at, phases, src, box, lum_min, excess):
    x0, y0, x1, y1 = box
    s = src[y0:y1, x0:x1].astype(np.float32) @ np.float32([.2126, .7152, .0722])
    out = np.zeros((y1 - y0, x1 - x0, 3), np.float32); weight = np.zeros((y1 - y0, x1 - x0), np.float32)
    for k, p in enumerate(phases):
        f = frames_at(p)[y0:y1, x0:x1].astype(np.float32)
        l = f @ np.float32([.2126, .7152, .0722])
        m = (l > lum_min) & (l > s + excess)
        u = k / max(len(phases) - 1, 1)
        color = np.array(cv2.applyColorMap(np.uint8([[u * 255]]), cv2.COLORMAP_JET)[0, 0, ::-1], np.float32)
        out[m] = color; weight[m] += 1
    return np.uint8(out), weight


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman-up-reference')
    ap.add_argument('--start', type=float, default=.04)
    ap.add_argument('--end', type=float, default=.45)
    ap.add_argument('--box', type=int, nargs=4, default=[40, 120, 680, 800])
    ap.add_argument('--lum', type=float, default=185.)
    ap.add_argument('--excess', type=float, default=35.)
    ap.add_argument('--spec', default=None)
    ap.add_argument('--label', default='cand')
    a = ap.parse_args()
    ref = RefFrames(a.scene)
    src = np.array(Image.open(HERE / 'assets' / a.scene / 'source.png').convert('RGB'))
    phases = [float(p) for p in ref.progress if a.start <= p <= a.end]
    panels = [('原片', paths(lambda p: np.asarray(ref.nearest(p)[1]), phases, src, a.box, a.lum, a.excess)[0])]
    if a.spec is not None:
        frames = render_all(a.scene, HERE, a.label, spec=a.spec)
        panels.append((a.label, paths(lambda p: np.asarray(frames[round(p * 120)]), phases, src, a.box, a.lum, a.excess)[0]))
    w = a.box[2] - a.box[0]; h = a.box[3] - a.box[1]
    canvas = Image.new('RGB', (len(panels) * (w + 6), h + 26), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for j, (t, im) in enumerate(panels):
        canvas.paste(Image.fromarray(im), (j * (w + 6), 24)); d.text((j * (w + 6) + 4, 2), f'{t} · 亮材料 {a.start:.2f}→{a.end:.2f}（蓝→红）', font=FONT, fill='white')
    out = OUT / f'bright-paths-{a.scene}-{a.label}.png'; canvas.save(out); print(out, len(phases), '个时刻')


if __name__ == '__main__':
    main()
