#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""带内补全的两个**成对**读数：与局部真实背景的一致性，以及高频能量比。

用途仅限"排除退化"，**不参与主判定**（`--use-matte` 这个决策上抄袭度已被证明方向相反，
D175）。两个数必须成对读：

- `bgDist`：每个带内像素找**背景侧**最近的真实像素（带外、且逆深度落在该处背景层级
  附近），量色差中位数。**退化路径：把背景整片抹平糊进来，这个数会变好**——所以必须
  同时看 HF。
- `hfRatio`：带内补全的高频能量 ÷ **原图同一区域**的高频能量（内容归一化，
  避免拿不同场景的绝对值互比）。糊掉 → 显著 <1；乱编纹理 → 明显 >1。

另给按"用户圈出的定点"限定的同样两个数（`--spots`），因为整带的均值会被大面积平坦
区域稀释，而争议只发生在几处剪影上。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def hf_energy(img: np.ndarray, sel: np.ndarray) -> float:
    grey = cv2.cvtColor(img.astype(np.float32), cv2.COLOR_BGR2GRAY)
    lap = cv2.Laplacian(grey, cv2.CV_32F, ksize=3)
    return float(np.sqrt((lap[sel] ** 2).mean())) if sel.any() else float("nan")


def _nearest_from(image: np.ndarray, src_ok: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    dist, lbl = cv2.distanceTransformWithLabels(
        (~src_ok).astype(np.uint8), cv2.DIST_L2, 5,
        labelType=cv2.DIST_LABEL_PIXEL)
    ys, xs = np.nonzero(src_ok)
    order = lbl[src_ok]                      # 每个源点自己的标签
    lut = np.zeros((int(lbl.max()) + 1, 2), np.int32)
    lut[order, 0] = ys
    lut[order, 1] = xs
    return image[lut[lbl, 0], lut[lbl, 1]], dist


def nearest_background(image: np.ndarray, inv: np.ndarray, mask: np.ndarray,
                       inv_layer: np.ndarray, tol: float, bins: int = 24
                       ) -> tuple[np.ndarray, np.ndarray]:
    """带内每点 → **与该点自己的背景层级同深度**的最近真实像素的颜色。

    第一版写成了 `(~mask) & (|inv − inv_layer| ≤ tol)`，那是个空条件：
    `propagate_background_depth` 只改带内，带外 inv_layer ≡ inv，于是源集合等于"整个带外"，
    最近邻自然取到**遮挡物本身**——参照变成了前景，指标反过来奖励"抄前景"
    （05 的 roseBR 上实测 ref 均值 BGR(109,112,153) 就是白盘子，不是玫瑰）。
    这正是 memory/metrics-can-reward-the-defect 那条教训的又一次实例。

    改法：把带内的背景层级分箱，逐箱取"带外且 inv 落在该箱 ± tol"的真实像素做源，
    各自做一次距离变换，再按箱把结果拼回去。参照因此永远与被填处**同深度**。
    """
    ref = np.zeros_like(image)
    dist = np.full(inv.shape, np.inf, np.float32)
    lv = inv_layer[mask]
    if lv.size == 0:
        return ref, dist
    lo, hi = float(np.percentile(lv, 0.5)), float(np.percentile(lv, 99.5))
    edges = np.linspace(lo, hi, bins + 1)
    for i in range(bins):
        c = 0.5 * (edges[i] + edges[i + 1])
        want = mask & (inv_layer >= edges[i]) & (inv_layer < edges[i + 1] if i < bins - 1
                                                 else inv_layer <= edges[i + 1] + 1e9)
        if not want.any():
            continue
        src = (~mask) & (np.abs(inv - c) <= tol)
        if src.sum() < 32:                       # 该层级在带外没有真实像素，放宽一档
            src = (~mask) & (np.abs(inv - c) <= 3 * tol)
        if src.sum() < 32:
            continue
        r, d = _nearest_from(image, src)
        ref[want] = r[want]
        dist[want] = d[want]
    return ref, dist


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scene", required=True)
    ap.add_argument("--tags", nargs="*", default=["_mtON", "_mtOFF", "_mtONnm", "_mtOFFnm"])
    ap.add_argument("--mask-tag", default="_mtON", help="所有变体共用同一条带（掩膜相同）")
    ap.add_argument("--spots", default="", help="名字:x,y;… 源图坐标，半径 --spot-r")
    ap.add_argument("--spot-r", type=int, default=55)
    args = ap.parse_args()

    geo = args.geometry / args.scene
    meta = json.loads((geo / "moge-meta.json").read_text(encoding="utf-8"))
    w, h = meta["width"], meta["height"]
    z = np.fromfile(geo / "depth_z.f32", dtype=np.float32).reshape(h, w)
    inv = 1.0 / np.maximum(z, 1e-6)
    image = cv2.imread(str(args.assets / meta["scene"] / "center.jpg"))
    mask = cv2.imread(str(geo / f"hidden_mask{args.mask_tag}.png"), 0) > 127

    hz = np.fromfile(geo / f"hidden_z{args.mask_tag}.f32", dtype=np.float32).reshape(h, w)
    inv_layer = 1.0 / np.maximum(hz, 1e-6)
    baseline = meta["hiddenLayer"]["maxBaseline"]
    tol = 3.0 / max(meta["fx"] * baseline, 1e-9)     # 与断边同源的判据

    regions: list[tuple[str, np.ndarray]] = [("whole", mask)]
    for item in filter(None, args.spots.split(";")):
        name, xy = item.split(":")
        x, y = (int(float(v)) for v in xy.split(","))
        box = np.zeros_like(mask)
        box[max(0, y - args.spot_r):y + args.spot_r,
            max(0, x - args.spot_r):x + args.spot_r] = True
        regions.append((name, mask & box))

    ref, refdist = nearest_background(image, inv, mask, inv_layer, tol)
    vis = image.copy()
    vis[mask] = ref[mask]
    cv2.imwrite(str(geo / "band_ref_bg.jpg"), vis, [cv2.IMWRITE_JPEG_QUALITY, 95])
    print(f"# {args.scene}  带 {100*mask.mean():.2f}%   tol(inv) {tol:.4f}"
          f"   参照图 -> {geo / 'band_ref_bg.jpg'}")
    for rname, sel in regions:
        if sel.sum() < 64:
            print(f"  [{rname}] 带内像素过少（{int(sel.sum())}），跳过")
            continue
        hf_orig = hf_energy(image, sel)
        ok = sel & np.isfinite(refdist)
        print(f"  [{rname}] 带内 {int(sel.sum())} px（有参照 {int(ok.sum())}）  原图 HF {hf_orig:6.2f}"
              f"  参照距离中位 {np.median(refdist[ok]) if ok.any() else float('nan'):.1f}px")
        for tag in args.tags:
            p = geo / f"hidden_color{tag}.png"
            if not p.is_file():
                continue
            color = cv2.imread(str(p))
            d = np.linalg.norm(color[ok].astype(np.float32) - ref[ok].astype(np.float32), axis=1)
            hf = hf_energy(color, sel)
            print(f"    {tag:9s} bgDist 中位 {np.median(d):6.2f}  p90 {np.percentile(d,90):6.2f}"
                  f"   HF {hf:6.2f}  HF比 {hf/max(hf_orig,1e-6):5.2f}")


if __name__ == "__main__":
    main()
