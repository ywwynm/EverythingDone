"""录像口径的模拟（只用于评估，不进入模型）：参考视频都经过录屏与转码，亮度在 720 宽上保留、色度只有一半分辨率，
并带有约 0.4～0.7 像素的模糊。孤立的小颗粒在这条链路上会失去饱和度、峰值变暗，比较模型帧时需要同样处理，
否则会把录像造成的差别误当成效果本身的差别。

- 亮度模糊 σ：按段取 estimate_recording_blur.py 的估计（recording-blur.json），缺省 0.5；
- 色度：BT.709 全范围近似，Cb/Cr 按 2×2 平均后线性放大回原尺寸（4:2:0）。
"""
from pathlib import Path
import json
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
BLUR = HERE / 'analysis/match-cache/handoff-review/recording-blur.json'


def scene_sigma(name, default=.5):
    try:
        return float(json.loads(BLUR.read_text('utf-8'))[name]['sigma'])
    except (OSError, KeyError, ValueError):
        return default


def chroma420(frame, sigma=0., k=2):
    f = np.clip(np.asarray(frame, np.float32), 0, 255)
    if sigma > 0:
        f = cv2.GaussianBlur(f, (0, 0), sigma)
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    y = .2126 * r + .7152 * g + .0722 * b
    cb = (b - y) / 1.8556; cr = (r - y) / 1.5748
    h, w = y.shape

    def sub(c):
        return cv2.resize(cv2.resize(c, (w // k, h // k), interpolation=cv2.INTER_AREA), (w, h), interpolation=cv2.INTER_LINEAR)
    cb, cr = sub(cb), sub(cr)
    r = y + 1.5748 * cr; b = y + 1.8556 * cb; g = (y - .2126 * r - .0722 * b) / .7152
    return np.clip(np.stack([r, g, b], -1), 0, 255)


def recorded(name, frame):
    """按该段的录像模糊估计加上 4:2:0 色度，返回 float32 RGB。"""
    return chroma420(frame, scene_sigma(name))
