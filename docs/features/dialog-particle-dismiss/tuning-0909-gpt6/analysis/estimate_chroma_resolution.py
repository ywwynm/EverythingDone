"""按段估计参考视频的色度分辨率（评估口径用，不进入模型）。

做法：在同一批帧上比较色度与亮度的功率谱之比 R(f) = (P_Cb + P_Cr)(f) / P_Y(f)。自然图像与模型帧的 R 在高频处大致平坦；
经过 4:2:0 或更粗的色度下采样后，R 在色度奈奎斯特频率以上迅速下降。给模型帧加不同倍数 k 的色度下采样（recording_sim.chroma420），
取 R 曲线与原片最接近的 k。比值只依赖色度相对亮度的损失，受颗粒尺度影响比单看亮度高频小。

输出每段各 k 的对数比误差与最佳 k；只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from handoff_cloud import SCENES
from recording_sim import chroma420

BANDS = np.linspace(.05, .5, 10)


def ycc(f):
    f = np.asarray(f, np.float32)
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    y = .2126 * r + .7152 * g + .0722 * b
    return y, (b - y) / 1.8556, (r - y) / 1.5748


def spectrum(img, box):
    x0, y0, x1, y1 = box
    a = img[y0:y1, x0:x1]; a = a - a.mean()
    h, w = a.shape
    win = np.outer(np.hanning(h), np.hanning(w)).astype(np.float32)
    p = np.abs(np.fft.fftshift(np.fft.fft2(a * win))) ** 2
    fy = np.fft.fftshift(np.fft.fftfreq(h))[:, None]; fx = np.fft.fftshift(np.fft.fftfreq(w))[None, :]
    rad = np.sqrt(fx ** 2 + fy ** 2)
    return np.array([p[(rad >= lo) & (rad < hi)].mean() for lo, hi in zip(BANDS[:-1], BANDS[1:])])


def ratio(frames, box):
    py = pc = 0.
    for f in frames:
        y, cb, cr = ycc(f)
        py = py + spectrum(y, box); pc = pc + spectrum(cb, box) + spectrum(cr, box)
    return pc / np.maximum(py, 1e-9)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--spec', required=True, help='模型变体（handoff_variants）')
    ap.add_argument('--ks', type=int, nargs='+', default=[1, 2, 3, 4])
    ap.add_argument('--sigma', type=float, default=0.)
    ap.add_argument('--out', default=str(OUT / 'chroma-resolution.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene; x, y, x1, y1 = s.rect
        # 卡片外扩 15% 的框：包含颗粒云与一部分背景，排除状态栏等界面元素的大部分。
        pad = round(.15 * s.span); H, W = s.roi.shape
        box = (max(0, x - pad), max(0, y - pad), min(W, x1 + pad), min(H, y1 + pad))
        idx = [i for i in c.index if .3 <= float(c.ref.progress[i]) <= .7][::3]
        ref = ratio([np.asarray(c.ref.frames[i]) for i in idx], box)
        frames = render_all(name, HERE, 'chroma-estimate', spec=a.spec)
        model = [np.asarray(frames[round(float(c.ref.progress[i]) * 120)]) for i in idx]
        rows = {}
        for k in a.ks:
            sim = [chroma420(f, a.sigma, k) if k > 1 else chroma420(f, a.sigma, 1) for f in model]
            r = ratio(sim, box)
            hi = slice(3, None)   # 0.2 周/像素以上
            rows[k] = dict(error=float(np.abs(np.log(r[hi] / ref[hi])).mean()), curve=r.tolist())
        best = min(rows, key=lambda k: rows[k]['error'])
        report[name] = dict(best=best, reference=ref.tolist(), rows=rows)
        print(f'{name:22s} 最佳 k={best}  ' + '  '.join(f'k{k} {v["error"]:.3f}' for k, v in rows.items())
              + '  原片 R[0.2+]=' + ' '.join(f'{v:.3f}' for v in ref[3:]), flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
