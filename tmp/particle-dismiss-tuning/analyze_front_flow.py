# -*- coding: utf-8 -*-
"""前沿在「流动」吗？

前沿是「已消逝区」与「未消逝区」的分界线。把它按横风坐标参数化成一条剖面
f(across, t) = 该横风位置上前沿推进到的沿风坐标，然后：

- **横向漂移**：f(·, t) 与 f(·, t+dt) 的互相关峰值位置。褶皱沿着前沿行进时该值
  非零且方向一致——这就是「流动」。前沿只是平推时该值为 0。
- **形状更新率**：去掉整体推进量后，两个时刻的剖面差的 rms（按卡宽归一）。
  形状冻住时接近 0。

同内容比较，判据用最朴素的那个。
"""
import glob, os
import numpy as np
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
OURS = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-refcontent"
CARD = (45, 95, 525, 567)
CW, CH = CARD[2] - CARD[0], CARD[3] - CARD[1]
DIRECTION = np.array([np.cos(np.radians(118.0)), -np.sin(np.radians(118.0))])
BINS = 64


def profile(a, src):
    """每个横风 bin 里，未消逝像素的最大沿风坐标 = 前沿位置。"""
    x0, y0, x1, y1 = CARD
    intact = (np.abs(a[y0:y1, x0:x1] - src[y0:y1, x0:x1]).max(2) <= 26)
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float64)
    along = ((xx - CW / 2) * DIRECTION[0] + (yy - CH / 2) * DIRECTION[1]) / CW
    across = (-(xx - CW / 2) * DIRECTION[1] + (yy - CH / 2) * DIRECTION[0]) / CW
    lo, hi = across.min(), across.max()
    idx = np.clip(((across - lo) / (hi - lo) * BINS).astype(int), 0, BINS - 1)
    # 前沿位置按「该 bin 里已消耗的比例」取分位数，不能取未消逝区的极值：
    # 零散残留的像素会把极值钉死，量出来所有时刻都一样（实测全为 0）。
    out = np.full(BINS, np.nan)
    for b in range(BINS):
        col = (idx == b)
        tot = int(col.sum())
        if tot < 200:
            continue
        eaten = float((col & ~intact).sum()) / tot
        if eaten <= 0.02 or eaten >= 0.98:
            continue
        out[b] = float(np.quantile(along[col], eaten))
    return out


def flow(frames, src, label):
    print("  %s   横向漂移(卡宽) / 形状更新率 rms(卡宽)" % label)
    prev, prev_n = None, None
    rows = []
    for n, a in frames:
        if n < 0.22 or n > 0.66:
            continue
        f = profile(a, src)
        if prev is not None and n - prev_n > 0.04:
            ok = np.isfinite(f) & np.isfinite(prev)
            if ok.sum() > 24:
                x = f[ok] - np.nanmean(f[ok])
                y = prev[ok] - np.nanmean(prev[ok])
                c = np.correlate(x, y, "full")
                shift = (int(np.argmax(c)) - (len(x) - 1)) / float(BINS)
                resid = float(np.sqrt(np.mean((x - y) ** 2)))
                rows.append((n, shift, resid))
            prev, prev_n = f, n
        elif prev is None:
            prev, prev_n = f, n
    if rows:
        sh = np.array([r[1] for r in rows])
        rs = np.array([r[2] for r in rows])
        print("    每 ~0.05 归一时间：漂移中位=%+.4f  |漂移|中位=%.4f  形状更新 rms=%.4f"
              % (np.median(sh), np.median(np.abs(sh)), np.median(rs)))
        print("    漂移序列:", " ".join("%+.3f" % v for v in sh[:10]))


fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
ts = np.array([int(os.path.basename(f)[2:7]) for f in fs]) / 60.0
T0, T1 = 3.28, 8.38
rf = [((t - T0) / (T1 - T0), np.asarray(Image.open(f).convert("RGB"), np.float32))
      for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
flow(rf, rf[0][1], "参考")

mf = sorted(glob.glob(os.path.join(OURS, "frame-*.png")))
N = len(mf) - 1
mm = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32)) for i, f in enumerate(mf)]
flow(mm, mm[0][1], "我们")
