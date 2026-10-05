"""释放之前表面是否先变模糊：按各自的解除时刻图对齐，统计 τ<0（尚未释放）各段内画面与源图的
高频能量比，以及与源图的平均亮度差、饱和度比。原片与模型同口径，只作诊断。

高频能量：亮度减去 σ=1.5 高斯模糊后的均方根，逐 10 像素块计算；只用源图本身高频足够的块。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release

LUM = np.float32([.2126, .7152, .0722])
BINS = [(-.16, -.10), (-.10, -.06), (-.06, -.03), (-.03, -.01), (-.01, .01), (.01, .03)]


def block(a, bh, bw):
    return a[:bh * B, :bw * B].reshape(bh, B, bw, B).mean((1, 3))


def measure(probe, T, frame_at, phases):
    s = probe.scene; x, y, x1, y1 = probe.rect; bh, bw = probe.bh, probe.bw
    src = s.source[y:y1, x:x1].astype(np.float32)
    sl = src @ LUM
    shp = lambda l: np.sqrt(block((l - cv2.GaussianBlur(l, (0, 0), 1.5)) ** 2, bh, bw))
    sat = lambda f: block(f.max(2) - f.min(2), bh, bw)
    s_hf = shp(sl); s_sat = sat(src); s_mean = block(sl, bh, bw)
    ok = probe.valid & np.isfinite(T) & (s_hf > 4.)
    acc = {b: [] for b in BINS}
    for p in phases:
        f = frame_at(p)[y:y1, x:x1].astype(np.float32); l = f @ LUM
        hf = shp(l); st = sat(f); mn = block(l, bh, bw)
        tau = p - T
        for lo, hi in BINS:
            m = ok & (tau >= lo) & (tau < hi)
            if m.sum() == 0: continue
            acc[(lo, hi)].append(np.stack([hf[m] / s_hf[m], st[m] / np.maximum(s_sat[m], 1), mn[m] - s_mean[m]], 1))
    out = {}
    for b, rows in acc.items():
        if not rows: continue
        r = np.concatenate(rows)
        if len(r) < 20: continue
        out[f'{b[0]:+.2f}~{b[1]:+.2f}'] = dict(n=len(r), hf_ratio=float(np.median(r[:, 0])), sat_ratio=float(np.median(r[:, 1])), dlum=float(np.median(r[:, 2])))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'thanos', 'kobe', 'spiderman-original', 'reference-0926-5'])
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--out', default=str(OUT / 'prerelease-blur.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        probe = Probe(name); c = probe.comp
        phases = [float(p) for p in c.ref.progress if .03 <= p <= .9]
        rows = [('原片', probe.huawei(), lambda p: np.asarray(c.ref.nearest(p)[1]))]
        for v in a.variants:
            label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); pth = Path(d)
            fr = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            rows.append((label, model_release(probe, name, fr), lambda p, fr=fr: np.asarray(fr[round(np.clip(p, 0, 1) * 120)])))
        report[name] = {}
        print('==', name)
        for label, T, at in rows:
            r = measure(probe, T, at, phases); report[name][label] = r
            print(f'  {label:>4}: ' + '  '.join(f'τ{k}: 高频{v["hf_ratio"]:.2f} 饱和{v["sat_ratio"]:.2f} 亮差{v["dlum"]:+.1f}' for k, v in r.items()), flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
