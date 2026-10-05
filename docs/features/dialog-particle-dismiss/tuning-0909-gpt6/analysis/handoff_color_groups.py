"""按源色分群的颗粒群轨迹（只作诊断）：卡片源图聚成若干颜色类，逐帧把清晰可见的颗粒像素按颜色归类，
报每一类的像素量、质心与分布椭圆，原片与模型对照。

用户 2026-10-01：多个起点扩散出的颗粒群并不始终相连，每个颗粒群的轨迹与形状都要对上。颜色是原片里唯一能跨帧认出
「同一批材料」的线索（科比的黄球衣、蓝背景，灭霸的紫色衣料等），因此按颜色而不是按连通区域分群。
- 可见颗粒像素：`Scene.observe` 的 particle（偏离背景且偏离原位控件，可观测区域内），且与背景 Lab 色差 ≥ --contrast；
- 颜色类：`assets/<段>/foreground.png` 卡片范围内像素在 Lab 空间做 k 均值（--k 类，只用彩度 ≥ --chroma 的像素另聚一次，
  深色、浅色无彩像素不参与，因为颗粒提亮、录像曝光会让它们的颜色互相混淆）；
- 归类：颗粒像素的色相角与彩度到类中心的距离（Lab 的 a、b 平面，按彩度归一化的角度差 ≤ --hue 度且彩度 ≥ --chroma），
  亮度不参与归类（模型的提亮与录像曝光主要改变亮度）；
- 质心与椭圆以卡片中心为原点、短边为单位；模型帧缺省按原片码率编码。
输出：轨迹图（每一类一种颜色，实线原片、虚线模型，每隔若干帧画 1σ 椭圆）与逐类的时间曲线 JSON。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from PIL import Image

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all
from recording_codec import coded


def lab(f):
    return cv2.cvtColor(np.clip(np.asarray(f)[..., :3], 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)


def palette(scene, k, chroma):
    fg = np.array(Image.open(HERE / 'assets' / scene / 'foreground.png').convert('RGBA'))
    px = fg[..., :3][fg[..., 3] > 200].reshape(-1, 3)
    L = cv2.cvtColor(px[None].astype(np.uint8), cv2.COLOR_RGB2LAB)[0].astype(np.float32)
    ab = L[:, 1:] - 128.
    c = np.hypot(ab[:, 0], ab[:, 1]); keep = c >= chroma
    ang = np.arctan2(ab[keep, 1], ab[keep, 0])
    # 在单位圆上聚类色相（避免 ±180° 断开），类中心再取回角度与平均彩度、平均源色。
    X = np.stack([np.cos(ang), np.sin(ang)], 1).astype(np.float32)
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 100, 1e-4)
    _, lbl, cen = cv2.kmeans(X, k, None, crit, 8, cv2.KMEANS_PP_CENTERS)
    lbl = lbl.ravel(); out = []
    for j in range(k):
        sel = lbl == j
        if sel.sum() < 200: continue
        out.append(dict(hue=float(math.degrees(math.atan2(cen[j, 1], cen[j, 0]))), chroma=float(np.median(c[keep][sel])),
                        share=float(sel.sum() / len(px)), rgb=[int(v) for v in np.median(px[keep][sel], 0)]))
    return sorted(out, key=lambda d: -d['share'])


def classify(frame_lab, mask, pal, hue_tol, chroma):
    ab = frame_lab[..., 1:] - 128.
    c = np.hypot(ab[..., 0], ab[..., 1]); ang = np.degrees(np.arctan2(ab[..., 1], ab[..., 0]))
    best = np.full(mask.shape, -1, np.int32); bestd = np.full(mask.shape, 1e9, np.float32)
    for j, p in enumerate(pal):
        d = np.abs((ang - p['hue'] + 180) % 360 - 180)
        ok = mask & (c >= chroma) & (d <= hue_tol) & (d < bestd)
        best[ok] = j; bestd[ok] = d[ok]
    return best


def stats(cls, j, ctr, span):
    ys, xs = np.nonzero(cls == j)
    if len(xs) < 30: return None
    X = (xs - ctr[0]) / span; Y = (ys - ctr[1]) / span
    C = np.cov(np.stack([X, Y]))
    return dict(n=int(len(xs)), cx=float(X.mean()), cy=float(Y.mean()), cov=C.tolist())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scene')
    ap.add_argument('--variant', required=True, help='标签=[配置目录::]变体（@文件）')
    ap.add_argument('--k', type=int, default=4)
    ap.add_argument('--chroma', type=float, default=18.)
    ap.add_argument('--hue', type=float, default=28.)
    ap.add_argument('--contrast', type=float, default=22.)
    ap.add_argument('--p0', type=float, default=.08)
    ap.add_argument('--p1', type=float, default=.85)
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    label, spec = a.variant.split('=', 1); root = HERE
    if '::' in spec:
        d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
    if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
    c = Comparison(a.scene); s = c.scene; prog = np.asarray(c.ref.progress, float)
    if a.raw:
        fr = render_all(a.scene, root, label, spec=spec); get_m = lambda i: np.asarray(fr[round(float(prog[i]) * 120)])
    else:
        cf = coded(a.scene, label, spec, root=root, verbose=False)[0]; get_m = lambda i: np.asarray(cf[i])
    pal = palette(a.scene, a.k, a.chroma)
    bgl = lab(s.bg)
    x, y, x1, y1 = s.meta['rect']; ctr = ((x + x1) / 2, (y + y1) / 2); span = s.span
    idx = [i for i in c.index if a.p0 <= prog[i] <= a.p1]
    series = {'原片': [], label: []}
    for i in idx:
        for tag, f in (('原片', np.asarray(c.ref.frames[i])), (label, get_m(i))):
            fl = lab(f)
            vis = s.observe(f)['particle'] & s.roi & (np.linalg.norm(fl - bgl, axis=2) >= a.contrast)
            cls = classify(fl, vis, pal, a.hue, a.chroma)
            series[tag].append(dict(p=float(prog[i]), groups=[stats(cls, j, ctr, span) for j in range(len(pal))]))
    report = dict(palette=pal, series=series)
    Path(a.out).with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False), 'utf-8')
    # 轨迹图：卡片外框 + 每一类质心路径（实线原片、虚线模型）与每隔若干帧的 1σ 椭圆。
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    from matplotlib.patches import Ellipse
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']; plt.rcParams['axes.unicode_minus'] = False
    n = len(pal)
    fig, axes = plt.subplots(2, n, figsize=(4.2 * n, 8.4), squeeze=False)
    hw, hh = (x1 - x) / 2 / span, (y1 - y) / 2 / span
    for j, p in enumerate(pal):
        ax = axes[0, j]; col = np.array(p['rgb']) / 255.
        ax.add_patch(plt.Rectangle((-hw, -hh), 2 * hw, 2 * hh, fill=False, ec='gray', lw=1))
        for tag, ls in (('原片', '-'), (label, '--')):
            pts = [(r['p'], r['groups'][j]) for r in series[tag] if r['groups'][j]]
            if not pts: continue
            xs = [g['cx'] for _, g in pts]; ys = [g['cy'] for _, g in pts]
            ax.plot(xs, ys, ls, color='k' if tag == '原片' else 'm', lw=1.6, label=tag)
            ax.scatter(xs, ys, c=[q for q, _ in pts], cmap='turbo', vmin=a.p0, vmax=a.p1, s=14, zorder=3)
            for q, g in pts[::max(1, len(pts) // 5)]:
                w_, v_ = np.linalg.eigh(np.array(g['cov']))
                ang = math.degrees(math.atan2(v_[1, 1], v_[0, 1]))
                ax.add_patch(Ellipse((g['cx'], g['cy']), 2 * math.sqrt(max(w_[1], 1e-6)), 2 * math.sqrt(max(w_[0], 1e-6)), angle=ang,
                                     fill=False, ec='k' if tag == '原片' else 'm', ls=ls, lw=.8, alpha=.7))
        ax.set_xlim(-hw - .7, hw + .7); ax.set_ylim(hh + .7, -hh - .7); ax.set_aspect('equal')
        ax.set_title(f'色相 {p["hue"]:.0f}° 占 {p["share"]:.0%}', color=col if col.mean() < .85 else 'k')
        ax.legend(fontsize=8)
        ax2 = axes[1, j]
        for tag, ls in (('原片', '-'), (label, '--')):
            ps = [r['p'] for r in series[tag]]; ns = [r['groups'][j]['n'] if r['groups'][j] else 0 for r in series[tag]]
            ax2.plot(ps, ns, ls, color='k' if tag == '原片' else 'm', label=f'{tag} 像素量')
        ax2.set_xlabel('进度'); ax2.legend(fontsize=8)
    fig.suptitle(f'{a.scene}：按源色分群的可见颗粒轨迹（卡片中心为原点，短边为单位；点色 = 进度）')
    fig.tight_layout(); fig.savefig(a.out, dpi=70); plt.close(fig)
    print(a.out, [f'{p["hue"]:.0f}°/{p["share"]:.2f}' for p in pal])


if __name__ == '__main__':
    main()
