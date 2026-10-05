# 出生比：颗粒在刚释放、尚未离开原处时，峰的（亮度−背景）与其附近源色（亮度−背景）最大值之比。
# 比值≈1：不透明、无提亮；<1：半透明或尺寸小于像素；>1：逐颗提亮或重叠累加。按年龄 τ 分箱，窗口随 τ 放大。
# 原片与 M（录像口径：σ_extra 模糊 + 4:2:0）各用自己的解除时刻图，同一套判据。
import sys, json
from pathlib import Path
import numpy as np, cv2
from scipy.ndimage import maximum_filter, minimum_filter, binary_dilation

T = Path(r'E:/projects/EverythingDone/docs/features/dialog-particle-dismiss/tuning-0909-gpt6')
sys.path[:0] = [str(T), str(T / 'analysis')]
from match_compare import Comparison
from recording_sim import chroma420

OUT = T / 'analysis/match-cache/handoff-review'
EDGE = json.loads((OUT / 'edge-blur.json').read_text('utf-8'))
LUM = np.float32([.2126, .7152, .0722])
IDENT = {'M': '6672f7a37b1ac7ae', 'P14': '588fd37566ab8847'}
BINS = [(0., .02, 3), (.02, .06, 6), (.06, .12, 12), (.12, .25, 24)]   # (τ 下限, 上限, 源色搜索半径 px)


def sigma_of(name):
    d = EDGE.get(name)
    return float(d['extra']) if d else .5


def release_map(s, get, prog):
    surf = np.array([s.observe(np.asarray(get(i), np.float32))['surface'] for i in range(len(prog))])
    valid = s.card & s.distinct
    Tm = np.full(surf.shape[1:], np.nan, np.float32); gone = ~surf; k = 4
    for j in range(len(prog) - k + 1):
        w = gone[j:j + k].all(0) & valid & np.isnan(Tm)
        Tm[w] = prog[j]
    return Tm


def ratios(s, frame, Tm, p, bgl, srcl, thr=15):
    """返回 {箱: (亮峰比数组, 暗峰比数组)}。"""
    lum = frame @ LUM; e = lum - bgl
    out = {}
    for lo, hi, r in BINS:
        win = np.isfinite(Tm) & (Tm >= p - hi) & (Tm < p - lo)
        if win.sum() < 50: out[(lo, hi)] = (np.array([]), np.array([])); continue
        region = binary_dilation(win, iterations=r) & s.roi
        k = 2 * r + 1
        smax = maximum_filter(np.where(win, srcl - bgl, -1e9), size=k)   # 只在本箱释放的源像素里找
        smin = minimum_filter(np.where(win, srcl - bgl, 1e9), size=k)
        br = (e >= maximum_filter(e, size=3)) & (e > thr) & region & (smax > thr)
        dk = (e <= minimum_filter(e, size=3)) & (e < -thr) & region & (smin < -thr)
        out[(lo, hi)] = (e[br] / smax[br], e[dk] / smin[dk])
    return out


def q(v):
    return ' '.join(f'{x:5.2f}' for x in (np.percentile(v, [10, 50, 90]) if len(v) else [np.nan] * 3)) + f' (n={len(v)})'


def run(name, phases):
    c = Comparison(name); s = c.scene; sig = sigma_of(name)
    x, y, x1, y1 = s.rect
    print(f'\n===== {name}  卡片 {x1-x}×{y1-y}  短边 {s.span}  σ_extra={sig:.2f}')
    prog = np.array([float(c.ref.progress[i]) for i in c.index])
    Tr = release_map(s, lambda j: c.ref.frames[c.index[j]], prog)
    models = {}
    for l, ident in IDENT.items():
        pth = OUT / l / f'{name}-{ident}.npy'
        if pth.is_file():
            fr = np.load(pth, mmap_mode='r')
            mp = np.linspace(0, 1, 121)
            models[l] = (fr, release_map(s, lambda j: chroma420(np.asarray(fr[j], np.float32), sig), mp), mp)
    bgl = s.bg @ LUM; srcl = s.source @ LUM
    acc = {}
    for p in phases:
        i, f = c.ref.nearest(p); pr = float(c.ref.progress[i])
        rows = [('原片', np.asarray(f, np.float32), Tr, pr)]
        for l, (fr, Tm, mp) in models.items():
            j = int(round(pr * 120)); rows.append((l, chroma420(np.asarray(fr[j], np.float32), sig), Tm, mp[j]))
        for lab, frame, Tm, pp in rows:
            for b, (a, d) in ratios(s, frame, Tm, pp, bgl, srcl).items():
                acc.setdefault((lab, b), [[], []])
                acc[(lab, b)][0].append(a); acc[(lab, b)][1].append(d)
    for lab in ['原片'] + list(models):
        for lo, hi, r in BINS:
            a, d = acc.get((lab, (lo, hi)), ([], []))
            a = np.concatenate(a) if len(a) else np.array([]); d = np.concatenate(d) if len(d) else np.array([])
            print(f'  {lab:5s} τ{lo:.2f}-{hi:.2f}  亮峰比 {q(a)}   暗峰比 {q(d)}')


if __name__ == '__main__':
    scenes = sys.argv[1:] or ['ironman', 'reference-0926-5', 'reference-0926-6', 'kobe', 'thanos', 'reference-0926-1', 'reference-0926-4', 'spiderman-original']
    for n in scenes:
        try:
            run(n, [.25, .30, .35, .40, .45, .50, .55, .60])
        except Exception as ex:
            import traceback; traceback.print_exc()
