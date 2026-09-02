# -*- coding: utf-8 -*-
"""参考 vs 草稿：(A) 以源点为中心按方向统计释放时刻；(B) 粒子亮度/密度随到完好区的距离。

2026-09-02 建立。用户指出参考的分界线"向下凸"、羽流边缘更亮且亮区沿边缘流动，
这两条各对应一项统计：A 给出快慢随方向的分布（扇区），B 给出离开前沿之后粒子
是变亮还是变暗。
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image

H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, H)
import release_time_map as RT  # noqa: E402

S = str(RT.SP)


def ring(field, sx, sy, r0, r1):
    h, w = field.shape
    yy, xx = np.mgrid[0:h, 0:w]
    dx = xx - sx * w
    dy = yy - sy * h
    r = np.hypot(dx, dy) / w
    ang = np.degrees(np.arctan2(dy, dx))
    out = []
    for a in range(-180, 180, 15):
        m = (r >= r0) & (r < r1) & (ang >= a) & (ang < a + 15) & np.isfinite(field)
        out.append((a, np.median(field[m]) if m.sum() > 30 else float("nan")))
    return out


def main():
    ref = RT.reference_frames()
    times = [n for n, _ in ref]
    x0, y0, x1, y1 = RT.CARD
    ox0, oy0, ox1, oy1 = RT.OURS_CARD
    ref_src = np.asarray(Image.open(ref[0][1]).convert("RGB"), np.float32)
    bg = np.asarray(Image.open(os.path.join(S, "bg.png")).convert("RGB"), np.float32)
    ours = list(RT.ours_frames(times))
    our_src = ours[0]
    rfield = np.load(os.path.join(H, "perframe", "ref-field.npy"))
    ofield = RT.release_map(zip(times, ours), our_src, RT.OURS_CARD)
    for r0, r1 in ((0.25, 0.35), (0.35, 0.45)):
        ra = ring(rfield, 0.49, 0.84, r0, r1)
        oa = ring(ofield, 0.50, 0.84, r0, r1)
        cells = ["%d:%.2f/%.2f" % (a, rv, ov) for (a, rv), (_, ov) in zip(ra, oa) if np.isfinite(rv)]
        print("A 半径 %.2f-%.2f 角度(-90=上): 参考/我们  " % (r0, r1) + "  ".join(cells))
    tol = 26.0
    bins = [(0, 8), (8, 20), (20, 40), (40, 80)]

    def masks(fr, src, bgc):
        intact = np.abs(fr - src).max(2) <= tol
        empty = np.abs(fr - bgc).max(2) <= tol
        return intact, (~intact) & (~empty)

    for n_t in (0.33, 0.45, 0.57):
        i = int(np.argmin(np.abs(np.array(times) - n_t)))
        rf = np.asarray(Image.open(ref[i][1]).convert("RGB"), np.float32)[y0:y1, x0:x1]
        of = ours[i][oy0:oy1, ox0:ox1]
        line = []
        for name, fr, src in (("参考", rf, ref_src[y0:y1, x0:x1]), ("我们", of, our_src[oy0:oy1, ox0:ox1])):
            it, pt = masks(fr, src, bg[y0:y1, x0:x1])
            d = cv2.distanceTransform((~it).astype(np.uint8), cv2.DIST_L2, 5)
            lum = fr.mean(2)
            bgl = bg[y0:y1, x0:x1].mean(2)
            cells = []
            for a, b in bins:
                m = (d >= a) & (d < b) & (~it)
                p = m & pt
                bright = (lum[p] - bgl[p]).mean() if p.sum() > 50 else float("nan")
                cells.append("%d-%d:%3.0f/%.2f" % (a, b, bright, p.sum() / max(m.sum(), 1)))
            line.append(name + " " + " ".join(cells))
        print("B n=%.2f 亮度超背景/密度 | " % times[i] + " | ".join(line))


if __name__ == "__main__":
    main()
