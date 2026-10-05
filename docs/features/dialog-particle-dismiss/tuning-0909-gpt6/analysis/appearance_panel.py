"""颗粒外观定标面板（只作诊断）：按区域（卡内／卡外）统计四个与颗粒直接相关的量，原片与各变体同口径。

- 峰密度：每千像素的亮峰数（3×3 局部极大、对背景的亮度超出 > 25 或低于背景 25）；
- 颗粒像素占比：observe() 判为颗粒的像素占区域的比例；
- 每峰像素：颗粒像素 / 峰数，反映单颗足迹的大小（或连成串的程度）；
- 峰的亮度超出分位（10/50/90%）：浅色背景上改用「低于背景」的深度。

模型帧先经录像口径：亮度 σ 取静态边锚定的 edge-blur.json（σ_extra），再做 4:2:0 色度；--no-sim 关闭。
报告每项「模型 / 原片」的比值，按段、按区域给出，不做十段平均。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from scipy.ndimage import maximum_filter, minimum_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_sim import chroma420

LUM = np.float32([.2126, .7152, .0722])
EDGE = OUT / 'edge-blur.json'


def extra_sigma(name):
    try:
        d = json.loads(EDGE.read_text('utf-8'))[name]
        return float(d['extra']) if d else .5
    except (OSError, KeyError, TypeError, ValueError):
        return .5


def stats(scene, frame, region, light):
    f = np.asarray(frame, np.float32)
    lum = f @ LUM; bg = scene.bg.astype(np.float32) @ LUM
    par = scene.observe(f)['particle'] & region
    if light:
        depth = bg - lum
        pk = (lum <= minimum_filter(lum, size=3)) & (depth > 25) & region
        val = depth[pk]
    else:
        ex = lum - bg
        pk = (lum >= maximum_filter(lum, size=3)) & (ex > 25) & region
        val = ex[pk]
    area = max(region.sum(), 1)
    return dict(peaks=pk.sum() / area * 1000, cover=par.sum() / area, vals=val)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'reference-0926-5'])
    ap.add_argument('--variants', nargs='+', required=True, help='标签=变体')
    ap.add_argument('--phases', type=float, nargs='+', default=[.40, .46, .52, .58, .64])
    ap.add_argument('--no-sim', action='store_true')
    ap.add_argument('--out', default=str(OUT / 'appearance-panel.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene; x, y, x1, y1 = s.rect
        H, W = s.roi.shape
        inside = np.zeros((H, W), bool); inside[y:y1, x:x1] = True
        regions = {'卡内': inside & s.roi, '卡外': ~inside & s.roi}
        light = float((s.bg.astype(np.float32) @ LUM)[s.roi].mean()) > 128
        sig = 0. if a.no_sim else extra_sigma(name)
        cols = [('原片', None)] + [tuple(v.split('=', 1)) for v in a.variants]
        frames = {l: render_all(name, HERE, l, spec=sp) for l, sp in cols if sp is not None}
        acc = {l: {r: dict(peaks=[], cover=[], vals=[]) for r in regions} for l, _ in cols}
        for p in a.phases:
            i, ref = c.ref.nearest(p); pp = float(c.ref.progress[i])
            for l, _ in cols:
                if l == '原片': f = np.asarray(ref)
                else:
                    f = np.asarray(frames[l][round(pp * 120)])
                    if not a.no_sim: f = chroma420(f, sig)
                for r, m in regions.items():
                    st = stats(s, f, m, light)
                    for k in st: acc[l][r][k].append(st[k])
        report[name] = {}
        print(f'=== {name}（{"浅底：峰取低于背景的深度" if light else "深底：峰取高于背景的亮度"}；模型 σ_extra={sig:.2f}{"" if a.no_sim else " + 4:2:0"}）')
        for r in regions:
            ref = acc['原片'][r]; rp = np.mean(ref['peaks']); rc = np.mean(ref['cover']); rv = np.concatenate(ref['vals'])
            rq = np.quantile(rv, [.1, .5, .9]) if len(rv) else np.zeros(3)
            print(f'  {r}  原片：峰密度 {rp:.2f}/千像素  占比 {rc:.4f}  每峰像素 {rc*1000/max(rp,1e-6):.2f}  峰值分位 {rq.round(0)}')
            for l, _ in cols[1:]:
                d = acc[l][r]; mp = np.mean(d['peaks']); mc = np.mean(d['cover']); mv = np.concatenate(d['vals'])
                mq = np.quantile(mv, [.1, .5, .9]) if len(mv) else np.zeros(3)
                row = dict(peaks=mp / max(rp, 1e-9), cover=mc / max(rc, 1e-9), per_peak=(mc / max(mp, 1e-9)) / max(rc / max(rp, 1e-9), 1e-9),
                           q=(mq - rq).tolist())
                report[name].setdefault(r, {})[l] = row
                print(f'    {l:8s} 峰数比 {row["peaks"]:.2f}  占比比 {row["cover"]:.2f}  每峰像素比 {row["per_peak"]:.2f}  峰值分位差 {np.round(mq - rq, 0)}')
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
