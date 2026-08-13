#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""重算带宽与"到真实背景的距离"。前两版都算错了，错法不同，这里一次说清。

**错法一（D185 的 27/102/133）**：按行数 `np.where(mask[y])[0].size`，那是"这一行上
band 像素的**总个数**"，一行里几段互不相连的带被加在了一起。带宽被系统性放大。

**错法二（band_structure_feasibility 的 3.6px）**：距离量到的"真实背景"集合是
`(~band) & 深度落在背景层级内`，而实测这个集合里有 28.5%（00 场景）的像素落在主体
matte 内——量到的"最近的真实背景"经常是主体自己。距离被系统性缩小。

**这一版**：
- 带宽用**局部宽度**：带内每点的距离变换 ×2（到最近非带像素的距离的两倍），
  与方向无关，不受带走向影响，也不会把互不相连的段加起来。
- 到真实背景的距离，来源集合要同时满足：不在带内、**不在遮挡物内**
  （`occluders.png` ∪ `matte_soft>0.5`）、逆深度落在该带背景层级内。
- 两个量都同时给**源分辨率**和**渲染分辨率**（长边 1080，缩放 ×1.5）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def q(v, p):
    return float(np.percentile(v, p)) if v.size else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tag", default="_mix")
    ap.add_argument("--box", default="", help="只看某个框：x0,y0,x1,y1（源图坐标）")
    args = ap.parse_args()

    box = [int(v) for v in args.box.split(",")] if args.box else None
    print(f"{'场景':<20} | {'局部带宽(源px) 中位':>17} {'p90':>6} {'max':>5} "
          f"| {'渲染px 中位':>11} {'p90':>6} | {'到真背景 中位':>12} {'p90':>6} {'max':>5}")
    out = {}
    for scene in args.scenes:
        g = args.geometry / scene
        mp = g / f"hidden_mask{args.tag}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        band = cv2.imread(str(mp), 0) > 127
        if band.sum() < 256:
            continue
        scale = 1080.0 / max(w, h)

        # 局部宽度 = 距离变换 ×2。方向无关，不把互不相连的段加起来。
        width = 2.0 * cv2.distanceTransform(band.astype(np.uint8), cv2.DIST_L2, 5)

        # 合法来源：带外 + 非遮挡物 + 背景层深度
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{args.tag}.f32", dtype=np.float32).reshape(h, w)
        inv, inv_layer = 1.0 / np.maximum(z, 1e-6), 1.0 / np.maximum(hz, 1e-6)
        lo, hi = np.percentile(inv_layer[band], 2), np.percentile(inv_layer[band], 98)
        occ = np.zeros((h, w), bool)
        for nm in ("occluders.png", "matte_soft.png"):
            p = args.assets / scene / nm
            if p.is_file():
                m = cv2.imread(str(p), 0)
                if (m.shape[1], m.shape[0]) != (w, h):
                    m = cv2.resize(m, (w, h), interpolation=cv2.INTER_NEAREST)
                occ |= m > 127
        src = (~band) & (~occ) & (inv >= lo) & (inv <= hi)
        dist = (cv2.distanceTransform((~src).astype(np.uint8), cv2.DIST_L2, 5)
                if src.sum() > 256 else np.full((h, w), np.nan, np.float32))

        sel = band.copy()
        if box:
            m = np.zeros((h, w), bool)
            m[box[1]:box[3], box[0]:box[2]] = True
            sel &= m
        if sel.sum() < 64:
            continue
        wv, dv = width[sel], dist[sel]
        print(f"{scene:<20} | {q(wv,50):>17.1f} {q(wv,90):>6.1f} {wv.max():>5.0f} "
              f"| {q(wv,50)*scale:>11.1f} {q(wv,90)*scale:>6.1f} "
              f"| {q(dv,50):>12.1f} {q(dv,90):>6.1f} {np.nanmax(dv):>5.0f}")
        out[scene] = {"px": int(sel.sum()),
                      "widthMedian": q(wv, 50), "widthP90": q(wv, 90), "widthMax": float(wv.max()),
                      "widthMedianRender": q(wv, 50) * scale,
                      "distMedian": q(dv, 50), "distP90": q(dv, 90),
                      "srcPx": int(src.sum())}
    tail = "_box" if box else ""
    (args.geometry.parent / "matte-soft-probe" / f"band_width_redo{tail}.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
