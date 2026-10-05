"""颗粒轨迹的长曝光图（只作诊断，用于目视比较运动轨迹与颗粒群形状的演化）。

每个时间窗内的全部原片独立帧：颗粒掩码（`Scene.observe` 的 particle，可观测区域内）上取与背景的差值作为强度，
按帧在窗内的先后着色（早 = 青，晚 = 品红），逐像素取强度最大的那一帧的颜色——同一颗粒在窗内走过的路径就成了一条由青到品红的
渐变短线，线的方向即运动方向，长度即速度，弯曲即卷动。模型用同一批帧时刻（默认按原片码率编码），原片与各变体并排。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded

FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)


def exposure(s, frames, box):
    x0, y0, x1, y1 = box
    best = np.zeros((y1 - y0, x1 - x0), np.float32); col = np.zeros((y1 - y0, x1 - x0, 3), np.float32)
    n = len(frames)
    for k, f in enumerate(frames):
        f = np.asarray(f)
        m = s.observe(f)['particle'] & s.roi
        e = np.clip(s.excess(f, s.bgmax, s.bgmin), 0, 160) * m
        e = e[y0:y1, x0:x1]
        t = k / max(n - 1, 1)
        c = np.array([80 + 175 * t, 230 - 190 * t, 255 - 35 * t], np.float32)  # 青 → 品红
        upd = e > best
        best[upd] = e[upd]; col[upd] = c
    img = col * np.clip(best / 90., 0, 1)[..., None]
    return np.clip(img, 0, 255).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--variants', nargs='*', default=[], help='标签=[配置目录::]变体')
    ap.add_argument('--windows', type=float, nargs='+', default=[.10, .18, .26, .34, .42, .50, .58, .66])
    ap.add_argument('--width', type=float, default=.08, help='每个时间窗的长度（进度）')
    ap.add_argument('--pad', type=float, default=.35, help='卡片外扩（短边倍数）')
    ap.add_argument('--scale', type=float, default=.5)
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--tag', default='')
    a = ap.parse_args()
    c = Comparison(a.scene); s = c.scene; x, y, x1, y1 = s.meta['rect']
    pad = round(s.span * a.pad); H, W = s.roi.shape
    box = (max(0, x - pad), max(0, y - pad), min(W, x1 + pad), min(H, y1 + pad))
    prog = np.asarray(c.ref.progress, float)
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        if not a.raw:
            cf = coded(a.scene, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(a.scene, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    w = round((box[2] - box[0]) * a.scale); h = round((box[3] - box[1]) * a.scale)
    sheet = Image.new('RGB', (len(a.windows) * (w + 4), len(seqs) * (h + 22)), (10, 12, 18)); d = ImageDraw.Draw(sheet)
    for j, w0 in enumerate(a.windows):
        idx = [i for i in c.index if w0 <= prog[i] < w0 + a.width]
        for r, (lab, get) in enumerate(seqs):
            img = exposure(s, [get(i) for i in idx], box)
            im = Image.fromarray(img).resize((w, h), Image.Resampling.LANCZOS)
            g = ImageDraw.Draw(im); g.rectangle([(x - box[0]) * a.scale, (y - box[1]) * a.scale, (x1 - box[0]) * a.scale, (y1 - box[1]) * a.scale], outline=(90, 90, 60))
            sheet.paste(im, (j * (w + 4), r * (h + 22) + 22))
            d.text((j * (w + 4) + 3, r * (h + 22) + 3), f'{lab} {w0:.2f}～{w0 + a.width:.2f}（{len(idx)} 帧）', font=FONT, fill='white')
    out = OUT / f'streaks{a.tag}-{a.scene}.jpg'; sheet.save(out, quality=92); print(out)


if __name__ == '__main__':
    main()
