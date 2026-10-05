"""颗粒之间空隙的颜色：释放后空隙是纯背景，还是残留一层朝源色的淡色（残留表面或细雾）。

浅色背景取 5×5 窗口内亮度最高的像素、深色背景取最低的像素作为「空隙」，按 10 像素块平均；
按各自的解除时刻对齐，给出空隙朝源色的比例 k = (空隙−背景)·(源−背景)/|源−背景|²。
原片与模型同口径，只作诊断。
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
BINS = [(-.02, .02), (.02, .06), (.06, .12), (.12, .2), (.2, .3), (.3, .45)]


def gaps(frame, light):
    f = frame.astype(np.float32); l = f @ LUM
    k = np.ones((5, 5), np.uint8)
    ext = cv2.dilate(l, k) if light else cv2.erode(l, k)
    # 取与极值亮度相同位置的颜色：用极值附近 (±2) 的像素平均近似
    m = (np.abs(l - ext) <= 2.).astype(np.float32)
    num = cv2.boxFilter(f * m[..., None], -1, (5, 5), normalize=False)
    den = cv2.boxFilter(m, -1, (5, 5), normalize=False)[..., None]
    return num / np.maximum(den, 1e-3)


def block(a, bh, bw):
    return a[:bh * B, :bw * B].reshape(bh, B, bw, B, -1).mean((1, 3))


SMOOTH = None   # 设为数值时只用源图块内亮度标准差低于该值的平滑块


def measure(probe, T, frame_at, phases, light):
    s = probe.scene; x, y, x1, y1 = probe.rect; bh, bw = probe.bh, probe.bw
    S = block(s.source[y:y1, x:x1].astype(np.float32), bh, bw); G = block(s.bg[y:y1, x:x1].astype(np.float32), bh, bw)
    d = S - G; dn = (d * d).sum(-1)
    ok = probe.valid & np.isfinite(T) & (dn > 40 ** 2)
    if SMOOTH is not None:
        sl = s.source[y:y1, x:x1].astype(np.float32) @ LUM
        sd = sl[:bh * B, :bw * B].reshape(bh, B, bw, B).std((1, 3))
        ok &= sd < SMOOTH
    acc = {b: [] for b in BINS}
    chan = {b: [] for b in BINS}
    for p in phases:
        Gp = block(gaps(frame_at(p), light)[y:y1, x:x1], bh, bw)
        k = ((Gp - G) * d).sum(-1) / np.maximum(dn, 1)
        # 逐通道比例：只用该通道源—背景差足够大的块
        kc = np.where(np.abs(d) > 30, (Gp - G) / np.where(np.abs(d) > 30, d, 1.), np.nan)
        tau = p - T
        for lo, hi in BINS:
            m = ok & (tau >= lo) & (tau < hi)
            if m.sum(): acc[(lo, hi)].append(k[m]); chan[(lo, hi)].append(kc[m])
    out = {}
    for (lo, hi), v in acc.items():
        if not v: continue
        c = np.concatenate(chan[(lo, hi)], 0)
        out[f'{lo:+.2f}~{hi:+.2f}'] = (float(np.median(np.concatenate(v))), int(sum(len(a) for a in v)),
                                         [float(np.nanmedian(c[:, j])) if np.isfinite(c[:, j]).sum() > 20 else float('nan') for j in range(3)])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['reference-0926-5', 'spiderman-original', 'ironman', 'reference-0926-6'])
    ap.add_argument('--variants', nargs='*', default=[])
    ap.add_argument('--smooth', type=float, default=None, help='只用源图块内亮度标准差低于该值的平滑块')
    a = ap.parse_args()
    global SMOOTH
    SMOOTH = a.smooth
    report = {}
    for name in a.scenes:
        probe = Probe(name); c = probe.comp
        light = float(np.median(probe.scene.bg[probe.scene.card] @ LUM)) > 128
        phases = [float(p) for p in c.ref.progress if .03 <= p <= .9]
        rows = [('原片', probe.huawei(), lambda p: np.asarray(c.ref.nearest(p)[1]))]
        for v in a.variants:
            label, rest = v.split('=', 1); dd, _, spec = rest.partition('::'); pth = Path(dd)
            fr = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            rows.append((label, model_release(probe, name, fr), lambda p, fr=fr: np.asarray(fr[round(np.clip(p, 0, 1) * 120)])))
        print('==', name, '浅色背景' if light else '深色背景')
        report[name] = {}
        for label, T, at in rows:
            r = measure(probe, T, at, phases, light); report[name][label] = r
            print(f'  {label:>4}: ' + '  '.join(f'τ{k}: {v:+.2f}' for k, (v, n, _) in r.items()), flush=True)
            print(f'  {"":>4}  逐通道 R/G/B: ' + '  '.join(f'τ{k}: ' + '/'.join(f'{x:+.2f}' for x in cc) for k, (_, _, cc) in r.items()), flush=True)
    (OUT / 'gap-tint.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
