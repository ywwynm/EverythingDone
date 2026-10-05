"""用静态边锚定录像模糊（评估口径用，不进入模型）：动画开始前卡片四条直边的边缘扩展函数拟合误差函数，得到亮度 σ。

卡片是矢量绘制的界面，在设备分辨率上边缘清晰；录像里的边宽来自下采样、编码与平台转码，与颗粒无关，
所以不依赖「模型颗粒的固有高频与华为相同」这个前提（estimate_recording_blur.py 依赖它）。
模型的圆点在 2 倍超采样后按面积平均缩小，自身的边宽另算（--model-sigma，默认用同样方法在一条理想阶跃上测得）。
需要叠加到模型帧上的额外模糊 σ_extra = sqrt(σ_录像² − σ_模型²)。只取对比足够、两侧平坦的剖面。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from scipy.optimize import least_squares
from scipy.special import erf

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import OUT
from handoff_cloud import SCENES

LUM = np.float32([.2126, .7152, .0722])
HALF = 6


def profiles(lum, rect, radius_frac=.2):
    """沿四条边的中段取垂直剖面，方向统一为「外 → 内」。"""
    x0, y0, x1, y1 = rect; w, h = x1 - x0, y1 - y0; out = []
    t = np.arange(-HALF, HALF + 1, dtype=np.float32)
    for k in range(int(w * radius_frac), int(w * (1 - radius_frac)), 2):
        x = x0 + k
        out.append(('top', lum[y0 + t.astype(int), x])); out.append(('bottom', lum[(y1 - 1) - t.astype(int), x]))
    for k in range(int(h * radius_frac), int(h * (1 - radius_frac)), 2):
        y = y0 + k
        out.append(('left', lum[y, x0 + t.astype(int)])); out.append(('right', lum[y, (x1 - 1) - t.astype(int)]))
    return out


def fit(rows):
    """每条剖面各自的亮度两端与边位置，共用一个 σ。"""
    t = np.arange(-HALF, HALF + 1, dtype=np.float64)
    good = []
    for side, p in rows:
        p = np.asarray(p, np.float64)
        a, b = p[:3].mean(), p[-3:].mean()
        if abs(b - a) < 40 or p[:3].std() > 4 or p[-3:].std() > 4: continue
        good.append((p - a) / (b - a))
    if len(good) < 20: return None, len(good)
    good = np.array(good)

    def resid(q):
        sigma = abs(q[0]) + 1e-3; shifts = q[1:]
        model = .5 * (1 + erf((t[None, :] - shifts[:, None]) / (sigma * math.sqrt(2))))
        return (model - good).ravel()
    q0 = np.concatenate([[.7], np.zeros(len(good))])
    r = least_squares(resid, q0)
    return abs(r.x[0]), len(good)


def model_sigma():
    """理想阶跃按 2 倍超采样后面积平均缩小（与渲染器同一路径）后的边宽。"""
    big = np.zeros((64, 64), np.float32); big[:, 33:] = 255.   # 边落在超采样像素之间的任意位置
    rows = []
    for off in range(0, 2):
        b = np.roll(big, off, axis=1)
        small = cv2.resize(b, (32, 32), interpolation=cv2.INTER_AREA)
        for y in range(32): rows.append(('left', small[y, 16 - HALF:16 + HALF + 1]))
    return fit(rows)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--out', default=str(OUT / 'edge-blur.json'))
    a = ap.parse_args()
    sm = model_sigma(); print(f'模型渲染路径自身的边宽 σ≈{sm:.2f}', flush=True)
    report = {'_model_sigma': sm}
    for name in a.scenes:
        c = Comparison(name); s = c.scene
        # 动画开始前（进度 ≤0.02）的几帧；卡片完整、背景静止。
        idx = [i for i, p in enumerate(c.ref.progress) if p <= .02][:4] or [0]
        rows = []
        for i in idx:
            lum = np.asarray(c.ref.frames[i]).astype(np.float32) @ LUM
            rows += profiles(lum, s.rect)
        sigma, n = fit(rows)
        if sigma is None:
            print(f'{name:22s} 可用剖面不足（{n}）', flush=True); report[name] = None; continue
        extra = math.sqrt(max(sigma ** 2 - sm ** 2, 0.))
        report[name] = dict(sigma=round(float(sigma), 3), extra=round(extra, 3), profiles=n)
        print(f'{name:22s} 录像边宽 σ={sigma:.2f}（{n} 条剖面）  需叠加 σ_extra={extra:.2f}', flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
