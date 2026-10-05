"""上风侧后缘亮带的锐度：颗粒亮度超出背景（σ=5 平滑，只对成片的亮带敏感；σ=2 时被单颗圆点的边缘主导）沿顺风方向的方向导数，取正值的 99% 与 99.9% 分位（顺风走进亮区时亮度陡增处）。
只在可观测区域、控件外扩 0.3 短边的框内统计。用法：trailing_edge.py 场景... -- 标签=变体..."""
import sys, math, numpy as np, cv2
from pathlib import Path
HERE = str(Path(__file__).resolve().parents[1])
sys.path[:0] = [HERE, HERE + '/analysis']
from pathlib import Path
from match_compare import Comparison
from handoff_review import render_all
from recording_sim import chroma420
import json
args = sys.argv[1:]; k = args.index('--'); scenes = args[:k]; cols = [tuple(a.split('=', 1)) for a in args[k + 1:]]
LUM = np.float32([.2126, .7152, .0722])
EB = json.loads((Path(HERE) / 'analysis/match-cache/handoff-review/edge-blur.json').read_text('utf-8'))
for name in scenes:
    c = Comparison(name); s = c.scene; x, y, x1, y1 = s.rect; pad = int(.3 * s.span)
    H, W = s.roi.shape; box = np.zeros((H, W), bool); box[max(0, y - pad):y1 + pad, max(0, x - pad):x1 + pad] = True; box &= s.roi
    a = math.radians(s.meta['direction']); wx, wy = math.cos(a), -math.sin(a)
    bg = s.bg.astype(np.float32) @ LUM
    sig = (EB.get(name) or {}).get('extra', .5) if isinstance(EB.get(name), dict) else .5
    frames = {l: render_all(name, Path(HERE), l, spec=sp) for l, sp in cols}
    def score(f):
        l = np.asarray(f, np.float32) @ LUM
        ex = cv2.GaussianBlur(np.maximum(l - bg, 0), (0, 0), 5.)
        gx = cv2.Sobel(ex, cv2.CV_32F, 1, 0, ksize=3) / 8.; gy = cv2.Sobel(ex, cv2.CV_32F, 0, 1, ksize=3) / 8.
        g = (gx * wx + gy * wy)[box]
        return np.percentile(g, [99, 99.9])
    out = {t: [] for t in ['原片'] + [l for l, _ in cols]}
    for p in [.35, .42, .49, .56, .63]:
        i, ref = c.ref.nearest(p); pp = float(c.ref.progress[i])
        out['原片'].append(score(ref))
        for l, _ in cols: out[l].append(score(chroma420(np.asarray(frames[l][round(pp * 120)]), sig)))
    print(f'{name:22s} ' + '  '.join(f'{t}: 99%={np.mean([v[0] for v in vals]):5.2f} 99.9%={np.mean([v[1] for v in vals]):5.2f}' for t, vals in out.items()), flush=True)
