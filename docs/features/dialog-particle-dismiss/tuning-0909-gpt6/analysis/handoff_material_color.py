"""按材料类别、按年龄箱统计颗粒峰的绝对 RGB：原片与各变体（模型帧过录像口径），与该材料的源色中位比较。
2026-09-30 Fable 5.1 审阅时写成，收入 analysis/ 并改为接受变体描述。"""
# 峰 = 3×3 局部极大（|亮度−背景| 的极值），只取「本箱释放的该材料像素」外扩 r px 内的峰。
import sys, json
from pathlib import Path
import numpy as np, cv2
from scipy.ndimage import maximum_filter, minimum_filter, binary_dilation
from PIL import Image, ImageDraw

T = Path(r'E:/projects/EverythingDone/docs/features/dialog-particle-dismiss/tuning-0909-gpt6')
sys.path[:0] = [str(T), str(T / 'analysis')]
from match_compare import Comparison
from recording_sim import chroma420
from handoff_birth_ratio import release_map, sigma_of, OUT, LUM, BINS
from handoff_review import render_all

SP = OUT


def sat(c):
    return (c.max(-1) - c.min(-1)) / np.maximum(c.max(-1), 1)


MATERIAL = {
    'kobe': {'黄球衣': lambda s: (s[..., 0] > 180) & (s[..., 1] > 120) & (s[..., 2] < 90) & (s[..., 0] - s[..., 2] > 100),
             '深色头发': lambda s: (s @ LUM < 45)},
    'reference-0926-6': {'深红盔甲': lambda s: (s[..., 0] > s[..., 1] + 12) & (s[..., 0] > s[..., 2] + 12) & (s @ LUM > 15) & (s @ LUM < 90),
                         '亮材料': lambda s: (s @ LUM > 170)},
    'ironman': {'白底': lambda s: (s @ LUM > 185) & (sat(s) < .2), '中灰': lambda s: (s @ LUM > 90) & (s @ LUM < 160)},
    'reference-0926-5': {'深色外套': lambda s: (s @ LUM < 70), '白色': lambda s: (s @ LUM > 200)},
    'reference-0926-1': {'黑底': lambda s: (s @ LUM < 40), '白衣': lambda s: (s @ LUM > 170)},
    'thanos': {'紫脸': lambda s: (s[..., 2] > s[..., 1] + 25) & (s @ LUM > 60) & (s @ LUM < 170), '亮材料': lambda s: (s @ LUM > 200)},
}


def med(c):
    return ' '.join(f'{v:5.0f}' for v in np.median(c, 0)) if len(c) else '    -     -     -'


def run(name, phases, variants):
    c = Comparison(name); s = c.scene; sig = sigma_of(name)
    prog = np.array([float(c.ref.progress[i]) for i in c.index])
    Tr = release_map(s, lambda j: c.ref.frames[c.index[j]], prog)
    models = {}
    for l, spec in variants:
        fr = render_all(name, T, l, spec=spec); mp = np.linspace(0, 1, 121)
        models[l] = (fr, release_map(s, lambda j, fr=fr: chroma420(np.asarray(fr[j], np.float32), sig), mp), mp)
    bgl = s.bg @ LUM
    print(f'\n===== {name}  σ={sig:.2f}')
    for mname, fn in MATERIAL[name].items():
        mat = fn(s.source) & s.card & s.distinct
        src = s.source[mat]
        print(f'  [{mname}] 源像素 {mat.sum()}  源色中位 RGB {med(src)}  亮度 {np.median(src @ LUM):5.0f}  饱和 {np.median(sat(src)):.2f}  该处背景亮度 {np.median(bgl[mat]):5.0f}')
        acc = {}
        for p in phases:
            i, f = c.ref.nearest(p); pr = float(c.ref.progress[i])
            rows = [('原片', np.asarray(f, np.float32), Tr, pr)]
            for l, (fr, Tm, mp) in models.items():
                j = int(round(pr * 120)); rows.append((l, chroma420(np.asarray(fr[j], np.float32), sig), Tm, mp[j]))
            for lab, frame, Tm, pp in rows:
                lum = frame @ LUM; e = lum - bgl
                for lo, hi, r in BINS:
                    win = mat & np.isfinite(Tm) & (Tm >= pp - hi) & (Tm < pp - lo)
                    if win.sum() < 30: continue
                    region = binary_dilation(win, iterations=r) & s.roi
                    pk = (((e >= maximum_filter(e, size=3)) & (e > 15)) | ((e <= minimum_filter(e, size=3)) & (e < -15))) & region
                    acc.setdefault((lab, (lo, hi)), []).append(frame[pk])
        for lab in ['原片'] + list(models):
            for lo, hi, r in BINS:
                v = acc.get((lab, (lo, hi)))
                if not v: continue
                v = np.concatenate(v)
                if len(v) < 20: continue
                print(f'    {lab:5s} τ{lo:.2f}-{hi:.2f} n={len(v):6d}  峰 RGB 中位 {med(v)}  亮度 {np.median(v @ LUM):5.0f}  饱和 {np.median(sat(v)):.2f}  亮度 q10/q90 {np.percentile(v @ LUM, 10):4.0f}/{np.percentile(v @ LUM, 90):4.0f}')


def zoom(name, p, box, k=3):
    c = Comparison(name); s = c.scene; sig = sigma_of(name)
    i, f = c.ref.nearest(p); pr = float(c.ref.progress[i]); j = int(round(pr * 120))
    tiles = [('原片', np.asarray(f, np.uint8))]
    for l, ident in IDENT.items():
        pth = OUT / l / f'{name}-{ident}.npy'
        if pth.is_file():
            fr = np.load(pth, mmap_mode='r')
            tiles.append((l + '录像口径', np.clip(chroma420(np.asarray(fr[j], np.float32), sig), 0, 255).astype(np.uint8)))
            tiles.append((l + '原样', np.asarray(fr[j], np.uint8)))
    x0, y0, x1, y1 = box; w = (x1 - x0) * k; h = (y1 - y0) * k
    canvas = Image.new('RGB', (len(tiles) * (w + 6), h + 24), (14, 20, 30)); d = ImageDraw.Draw(canvas)
    for n, (lab, t) in enumerate(tiles):
        canvas.paste(Image.fromarray(t[y0:y1, x0:x1]).resize((w, h), Image.NEAREST), (n * (w + 6), 24))
        d.text((n * (w + 6) + 2, 4), f'{lab} p={pr:.3f}', fill=(235, 235, 235))
    out = SP / f'matzoom-{name}-{p:.2f}.jpg'; canvas.save(out, quality=90); print('->', out)


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser(description='按材料类别 × 释放后年龄箱比较颗粒峰的绝对颜色（模型帧过录像口径）。')
    ap.add_argument('--scenes', nargs='+', default=['reference-0926-6', 'ironman', 'reference-0926-5', 'reference-0926-1', 'thanos'])
    ap.add_argument('--variants', nargs='+', required=True, help='标签=变体')
    ap.add_argument('--phases', type=float, nargs='+', default=[.30, .35, .40, .45, .50, .55, .60])
    a = ap.parse_args()
    variants = [tuple(v.split('=', 1)) for v in a.variants]
    for n in a.scenes:
        run(n, a.phases, variants)
