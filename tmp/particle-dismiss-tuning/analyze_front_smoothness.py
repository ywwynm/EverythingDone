# -*- coding: utf-8 -*-
"""前沿曲线有多光滑？

用户说我们的边界「一会上一会下、凹凸不平」，而参考是一条优美的曲线。把它量出来：
把前沿按横风坐标参数化成 f(across)，然后取

- **弯折能量**：二阶差分的 rms（按卡宽归一）。越小越光滑。
- **单调段数**：f 的一阶差分变号的次数。参考若是一条大尺度起伏的曲线，变号次数少；
  「一会上一会下」则变号次数多。
- **孤岛数**：未消逝区的连通块个数。真正的「奇怪暗区」体现在这一项。

同内容比较（frames-refcontent），判据用最朴素的那个。
"""
import glob, os
import numpy as np
import cv2
from PIL import Image

SP = r"C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad"
OURS = r"E:\projects\EverythingDone\tmp\particle-dismiss-tuning\frames-refcontent"
CARD = (45, 95, 525, 567)
CW, CH = CARD[2] - CARD[0], CARD[3] - CARD[1]
DIRECTION = np.array([np.cos(np.radians(118.0)), -np.sin(np.radians(118.0))])
BINS = 48
STEPS = (0.25, 0.35, 0.45, 0.55)


def front(a, src):
    x0, y0, x1, y1 = CARD
    intact = (np.abs(a[y0:y1, x0:x1] - src[y0:y1, x0:x1]).max(2) <= 26)
    # 只留最大连通块之外的那些小块，用来数孤岛
    n, lab, st, _ = cv2.connectedComponentsWithStats(
        cv2.morphologyEx(intact.astype(np.uint8), cv2.MORPH_OPEN,
                         np.ones((5, 5), np.uint8)), 8)
    areas = st[1:, 4] if n > 1 else np.array([0])
    islands = int((areas > (CW * 0.03) ** 2).sum()) - (1 if len(areas) else 0)
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float64)
    along = ((xx - CW / 2) * DIRECTION[0] + (yy - CH / 2) * DIRECTION[1]) / CW
    across = (-(xx - CW / 2) * DIRECTION[1] + (yy - CH / 2) * DIRECTION[0]) / CW
    lo, hi = across.min(), across.max()
    idx = np.clip(((across - lo) / (hi - lo) * BINS).astype(int), 0, BINS - 1)
    f = np.full(BINS, np.nan)
    for b in range(BINS):
        col = (idx == b)
        tot = int(col.sum())
        if tot < 200:
            continue
        eaten = float((col & ~intact).sum()) / tot
        if eaten <= 0.02 or eaten >= 0.98:
            continue
        f[b] = float(np.quantile(along[col], eaten))
    return f, max(islands, 0)


def run(frames, src, label):
    print("  %s   弯折能量(‰卡宽) / 变号次数 / 孤岛数" % label)
    for target in STEPS:
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - target))
        n, a = frames[i]
        f, islands = front(a, src)
        ok = np.isfinite(f)
        if ok.sum() < 12:
            continue
        # 在有效区间内线性内插补洞，避免缺口被当成折角
        xs = np.arange(BINS, dtype=float)
        g = np.interp(xs, xs[ok], f[ok])
        lo, hi = int(xs[ok][0]), int(xs[ok][-1])
        g = g[lo:hi + 1]
        d2 = np.diff(g, 2)
        d1 = np.diff(g)
        signs = np.sign(d1[np.abs(d1) > 1e-4])
        flips = int((np.diff(signs) != 0).sum()) if len(signs) > 1 else 0
        print("    n=%.2f   %5.2f   %2d   %d"
              % (n, float(np.sqrt((d2 ** 2).mean())) * 1000.0, flips, islands))


fs = sorted(glob.glob(os.path.join(SP, "anim2", "a_*.png")))
ts = [int(os.path.basename(f)[2:7]) / 60.0 for f in fs]
T0, T1 = 3.28, 8.38
rf = [((t - T0) / (T1 - T0), np.asarray(Image.open(f).convert("RGB"), np.float32))
      for f, t in zip(fs, ts) if T0 - 0.02 <= t <= T1]
run(rf, rf[0][1], "参考")

mf = sorted(glob.glob(os.path.join(OURS, "frame-*.png")))
N = len(mf) - 1
mm = [(i / N, np.asarray(Image.open(f).convert("RGB"), np.float32)) for i, f in enumerate(mf)]
run(mm, mm[0][1], "我们")
