# -*- coding: utf-8 -*-
"""逐帧汇总指标：表面/粒子比例与 IoU、亮度偏差、MAE、羽流面积。参考 vs 桌面草稿。"""
import os
import sys

import cv2
import numpy as np
from PIL import Image

H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, H)
import make_draft_comparison as M  # noqa: E402
import release_time_map as RT  # noqa: E402

S = str(RT.SP)
TOL = 26.0


def masks(fr, src, bgc):
    intact = np.abs(fr - src).max(2) <= TOL
    empty = np.abs(fr - bgc).max(2) <= TOL
    return intact, (~intact) & (~empty)


def main():
    ref = RT.reference_frames()
    times = [n for n, _ in ref]
    x0, y0, x1, y1 = RT.CARD
    ox0, oy0, ox1, oy1 = RT.OURS_CARD
    ref_src = np.asarray(Image.open(ref[0][1]).convert("RGB"), np.float32)
    bg = np.asarray(Image.open(os.path.join(S, "bg.png")).convert("RGB"), np.float32)
    bgc = bg[y0:y1, x0:x1]
    ours = list(RT.ours_frames(times))
    our_src = ours[0]
    rows = []
    dense = []
    for (n, p), oc in zip(ref, ours):
        rf = np.asarray(Image.open(p).convert("RGB"), np.float32)
        rc = rf[y0:y1, x0:x1]
        occ = oc[oy0:oy1, ox0:ox1]
        ri, rp = masks(rc, ref_src[y0:y1, x0:x1], bgc)
        oi, op = masks(occ, our_src[oy0:oy1, ox0:ox1], bgc)
        both = rp & op
        bias = (occ.mean(2)[both] - rc.mean(2)[both]).mean() if both.sum() > 200 else float("nan")
        rows.append((n, ri.mean(), oi.mean(), (ri & oi).sum() / max((ri | oi).sum(), 1),
                     rp.mean(), op.mean(), (rp & op).sum() / max((rp | op).sum(), 1),
                     bias, np.abs(occ - rc).mean()))
        ra = sum(cv2.contourArea(c.reshape(-1, 1, 2)) for c in M.plume_contours(rf, ref_src))
        oa = sum(cv2.contourArea(c.reshape(-1, 1, 2)) for c in M.plume_contours(oc, our_src))
        dense.append((n, ra, oa))
    print("(a) n 表面参考/我们 IoU | 粒子参考/我们 IoU | 亮度偏差 | MAE")
    for r in rows[25::25]:
        print("   n=%.3f  %.3f/%.3f %.3f | %.3f/%.3f %.3f | %+5.1f | %.1f" % r)
    pk = max(rows, key=lambda r: r[5])
    print("   粒子峰值 %.3f @ %.3f（参考 0.270 @ 0.573）  MAE均值 %.1f  粒子IoU均值 %.3f  表面IoU均值 %.3f" % (
        pk[5], pk[0], np.mean([r[8] for r in rows]),
        np.mean([r[6] for r in rows if 0.2 <= r[0] <= 0.8]),
        np.mean([r[3] for r in rows if 0.1 <= r[0] <= 0.7])))
    print("(d) 羽流面积（千像素）参考/我们：" + "  ".join(
        "%.2f:%.0f/%.0f" % (n, ra / 1000, oa / 1000) for n, ra, oa in dense[::38]))


if __name__ == "__main__":
    main()
