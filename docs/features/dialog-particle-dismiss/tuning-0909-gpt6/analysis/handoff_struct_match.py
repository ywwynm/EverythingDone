"""释放后画面内容还能被认出多久（只作诊断）。

按 16 像素块，在 ±6 像素位移内取帧与源图高通亮度的最大归一化互相关（NCC）；只用源图块本身有结构（高通标准差 ≥4）的块。
按释放后时间 τ 分箱（原片与模型各用自己的解除时刻图），报每箱 NCC 中位数：值越高，说明释放后原处越能认出源图内容。
模型帧先经录像口径（静态边的 σ + 4:2:0）。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release
from recording_sim import chroma420

LUM = np.float32([.2126, .7152, .0722])
BS, R = 16, 6
TAUS = [(-.02, .01), (.01, .04), (.04, .08), (.08, .14), (.14, .22), (.22, .35)]
EDGE = OUT / 'edge-blur.json'


def hp(l):
    return l - cv2.GaussianBlur(l, (0, 0), 3.)


def sigma(name):
    try:
        d = json.loads(EDGE.read_text('utf-8'))[name]
        return float(d['extra']) if d else .5
    except (OSError, KeyError, TypeError, ValueError):
        return .5


def run(probe, T, at, phases):
    s = probe.scene; x, y, _, _ = probe.rect
    src = hp(s.source.astype(np.float32) @ LUM)
    res = {t: [] for t in TAUS}
    for p in phases:
        fl = hp(np.asarray(at(p), np.float32) @ LUM)
        for by in range(0, probe.bh * B - BS, BS):
            for bx in range(0, probe.bw * B - BS, BS):
                i, j = (by + BS // 2) // B, (bx + BS // 2) // B
                if not probe.valid[i, j] or not np.isfinite(T[i, j]): continue
                tau = p - T[i, j]
                t = next((t for t in TAUS if t[0] <= tau < t[1]), None)
                if t is None: continue
                gy, gx = y + by, x + bx
                a = src[gy:gy + BS, gx:gx + BS]
                if a.std() < 4: continue
                win = fl[gy - R:gy + BS + R, gx - R:gx + BS + R]
                if win.shape != (BS + 2 * R, BS + 2 * R): continue
                res[t].append(float(cv2.matchTemplate(win, a, cv2.TM_CCOEFF_NORMED).max()))
    return {t: (len(v), float(np.median(v))) for t, v in res.items() if len(v) > 30}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'reference-0926-6', 'reference-0926-5', 'kobe', 'thanos', 'reference-0926-1'])
    ap.add_argument('--variants', nargs='*', default=[], help='标签=变体（空变体为交接默认）')
    a = ap.parse_args()
    for name in a.scenes:
        probe = Probe(name); c = probe.comp; sig = sigma(name)
        phases = [float(p) for p in c.ref.progress if .1 <= p <= .85][::2]
        rows = {'原片': run(probe, probe.huawei(), lambda p: np.asarray(c.ref.nearest(p)[1]), phases)}
        for v in a.variants:
            label, spec = v.split('=', 1)
            fr = render_all(name, HERE, label, spec=spec); T = model_release(probe, name, fr)
            rows[label] = run(probe, T, lambda p, fr=fr: chroma420(np.asarray(fr[round(np.clip(p, 0, 1) * 120)]), sig), phases)
        print(f'== {name}（每格：块数 / 最大互相关中位数）', flush=True)
        for t in TAUS:
            line = f'  τ {t[0]:+.2f}~{t[1]:+.2f}'
            for k, v in rows.items():
                line += f' | {k}: {v[t][0]:5d} {v[t][1]:.2f}' if t in v else f' | {k}:     -    -'
            print(line, flush=True)


if __name__ == '__main__':
    main()
