"""颗粒外观的同口径统计：峰值亮度分布、颗粒尺度（自相关半高半径）、颗粒峰密度与低频亮度差。

只在背景干净的深色区域统计（背景亮度 < dark），颗粒像素取 match_observe 的 particle；
原片与各变体用同一流程，结果只用于排序外观参数，最终以放大目视为准。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT

LUM = np.float32([.2126, .7152, .0722])


def correlation_radius(lum, mask):
    """颗粒区的高通亮度自相关，返回相关值降到 0.5 的半径（像素，按方位平均）。"""
    hp = lum - cv2.GaussianBlur(lum, (0, 0), 4.)
    hp = np.where(mask, hp, 0.).astype(np.float32)
    if mask.sum() < 400: return np.nan
    F = np.fft.rfft2(hp); ac = np.fft.irfft2(F * np.conj(F), s=hp.shape)
    ac = np.fft.fftshift(ac); cy, cx = np.array(ac.shape) // 2; ac /= ac[cy, cx]
    ys, xs = np.mgrid[-6:7, -6:7]; r = np.hypot(ys, xs)
    prof = [ac[cy + ys[(r >= k - .5) & (r < k + .5)], cx + xs[(r >= k - .5) & (r < k + .5)]].mean() for k in range(0, 7)]
    for k in range(1, 7):
        if prof[k] < .5:
            return float(k - 1 + (prof[k - 1] - .5) / max(prof[k - 1] - prof[k], 1e-6))
    return 6.


def stats(scene, frame, dark, region):
    o = scene.observe(frame)
    bgl = scene.bg @ LUM
    par = o['particle'] & scene.roi & (bgl < dark) & region
    lum = frame.astype(np.float32) @ LUM
    pk = (lum >= maximum_filter(lum, size=3)) & par & (lum - bgl > 25)
    # 颗粒较密处（周围 15×15 内颗粒占比 ≥ 0.3）才用于尺度估计，避免孤立亮点主导自相关。
    dens = cv2.blur(par.astype(np.float32), (15, 15))
    return dict(peaks=lum[pk], npk=int(pk.sum()), npar=int(par.sum()), rad=correlation_radius(lum, par & (dens > .3)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6'])
    ap.add_argument('--phases', type=float, nargs='+', default=[.25, .3, .35, .4, .45, .5, .55, .6, .65])
    ap.add_argument('--dark', type=float, default=70.)
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--out', default=str(OUT / 'speckle-fit.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        region = np.ones(s.roi.shape, bool)
        rows = [('原片', lambda p: np.asarray(c.ref.nearest(p)[1]))]
        for v in a.variants:
            label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); pth = Path(d)
            fr = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            rows.append((label, lambda p, fr=fr: np.asarray(fr[round(np.clip(p, 0, 1) * 120)])))
        ref_lp = {p: cv2.GaussianBlur(np.asarray(c.ref.nearest(p)[1]).astype(np.float32) @ LUM, (0, 0), 6.) for p in a.phases}
        report[name] = {}
        print('==', name)
        for label, at in rows:
            peaks, npk, npar, rads, lps = [], 0, 0, [], []
            for p in a.phases:
                f = at(p); st = stats(s, f, a.dark, region)
                peaks.append(st['peaks']); npk += st['npk']; npar += st['npar']; rads.append(st['rad'])
                lps.append(float(np.abs(cv2.GaussianBlur(f.astype(np.float32) @ LUM, (0, 0), 6.) - ref_lp[p])[s.roi].mean()))
            pk = np.concatenate(peaks) if peaks else np.zeros(0)
            q = np.quantile(pk, [.25, .5, .75, .9]).round(1).tolist() if len(pk) else []
            r = dict(peak_q=q, white=float((pk >= 200).mean()) if len(pk) else 0., npk=npk, npar=npar,
                     peak_per_par=npk / max(npar, 1), rad=float(np.nanmedian(rads)), lp6=float(np.mean(lps)))
            report[name][label] = r
            print(f'  {label:>6}: 峰亮分位{q} 近白{r["white"]:.2f} 峰数{npk} 颗粒像素{npar} 峰/像素{r["peak_per_par"]:.3f} '
                  f'相关半径{r["rad"]:.2f} lp6={r["lp6"]:.2f}', flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8'); print(a.out)


if __name__ == '__main__':
    main()
