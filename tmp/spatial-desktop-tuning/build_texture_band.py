#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把**真实纹理**搬进带内，产出 `_tex` 变体。接 D190（色阶已修好，剩纹理）。

D190 之后带的色阶已经贴回背景，但带仍可辨——它**比真实背景平滑**。之前那版结构搬运
（D189）只换低频结构、高频照抄 `_mix`，等于纹理根本没动过。

做法：**沿剪影法向把真实背景的高频镜像搬进带内**。
- 对带内每点 p，取最近的**远侧真实背景**边界点 b（跨过深度断边、更远那侧），
  镜像点 s = 2b − p；s 命中合法来源（逐段深度窗 + 前景排除，D190/`band_source`）时，
  把 s 的高频 `img − blur(img)` 加到 p 上。
- 镜像保证**缝上高频连续**（p→b 时 s→b，两侧同一段纹理），不会在缝上再造一条边。
- 增益按"紧邻真实背景的局部高频能量 ÷ 带内当前局部高频能量"定，但**上下限对称**
  （D186 的教训：单边截断会把中位为 1 的比值整体抬起来），且有 σ 下限防平坦区炸噪点。

低频一个像素都不动——那是 D190 刚修好的东西，这一步只叠高频。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from band_source import build_source, occluder_mask
from build_seam_matched_band import far_side_reference

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def nearest_point(zero_set):
    """每个像素到 `zero_set` 的最近点坐标。`DIST_LABEL_PIXEL` 给的是最近零像素的编号。"""
    src = (~zero_set).astype(np.uint8)
    dist, lab = cv2.distanceTransformWithLabels(src, cv2.DIST_L2, 5,
                                                labelType=cv2.DIST_LABEL_PIXEL)
    ys, xs = np.nonzero(zero_set)
    # 编号从 1 开始，按行优先给零像素编号
    table_y = np.zeros(ys.size + 1, np.int32)
    table_x = np.zeros(ys.size + 1, np.int32)
    order = np.lexsort((xs, ys))
    table_y[1:] = ys[order]
    table_x[1:] = xs[order]
    lab = np.clip(lab, 0, ys.size)
    return dist, table_y[lab], table_x[lab]


def local_hf(img, sel, sigma=6.0):
    hf = img - cv2.GaussianBlur(img, (0, 0), 1.5)
    e = (hf * hf).mean(2)
    s = cv2.GaussianBlur((e * sel).astype(np.float32), (0, 0), sigma)
    n = cv2.GaussianBlur(sel.astype(np.float32), (0, 0), sigma)
    return hf, np.sqrt(np.maximum(np.where(n > 1e-3, s / np.maximum(n, 1e-6), 0.0), 0.0))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--src-tag", default="_seam")
    ap.add_argument("--tag", default="_tex")
    ap.add_argument("--sep-px", type=float, default=1.5)
    ap.add_argument("--gain-cap", type=float, default=2.0)
    ap.add_argument("--sigma-floor", type=float, default=2.0)
    ap.add_argument("--exclude-fg", type=int, default=12)
    args = ap.parse_args()

    print(f"{'场景':<22} {'带px':>7} {'镜像命中%':>9} {'高频比 前→后':>16} {'缝失配 前→后':>14}")
    stats = {}
    for scene in args.scenes:
        g = args.geometry / scene
        st = args.src_tag
        mp = g / f"hidden_mask{st}.png"
        if not mp.is_file():
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        img = cv2.imread(str(args.assets / scene / "center.jpg")).astype(np.float32)
        band = cv2.imread(str(mp), 0) > 127
        col = cv2.imread(str(g / f"hidden_color{st}.png")).astype(np.float32)
        if band.sum() < 256:
            continue
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{st}.f32", dtype=np.float32).reshape(h, w)
        inv, inv_layer = 1.0 / np.maximum(z, 1e-6), 1.0 / np.maximum(hz, 1e-6)
        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        sep = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)

        occ = occluder_mask(args.assets / scene, w, h)
        legal, _ = build_source(band, inv, inv_layer, occ, sep, args.exclude_fg, verbose=False)
        far, _ = far_side_reference(band, inv, sep)
        anchor = far & legal
        if anchor.sum() < 128:
            print(f"{scene:<22} 远侧合法参照不足，跳过")
            continue

        _, by, bx = nearest_point(anchor)
        ys, xs = np.nonzero(band)
        sy = np.clip(2 * by[ys, xs] - ys, 0, h - 1)
        sx = np.clip(2 * bx[ys, xs] - xs, 0, w - 1)
        hit = legal[sy, sx]
        # 镜像点不合法时退回它的锚点本身（锚点一定合法），代价是该点纹理偏弱
        sy = np.where(hit, sy, by[ys, xs])
        sx = np.where(hit, sx, bx[ys, xs])

        hf_img, e_ref = local_hf(img, anchor)
        _, e_band = local_hf(col, band)
        gain = np.clip(e_ref / np.maximum(e_band, args.sigma_floor),
                       1.0 / args.gain_cap, args.gain_cap)

        out = col.copy()
        base_hf = col - cv2.GaussianBlur(col, (0, 0), 1.5)
        add = hf_img[sy, sx] * gain[ys, xs][:, None] - base_hf[ys, xs]
        out[ys, xs] = np.clip(col[ys, xs] + add, 0, 255)
        # 缝上羽化回原值，避免在缝上出现新的高频台阶
        ring = band & (cv2.dilate(anchor.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0)
        wgt = cv2.GaussianBlur(ring.astype(np.float32), (0, 0), 1.5)[..., None]
        out = np.where(band[..., None], out * (1 - wgt) + col * wgt, img)

        def hf_ratio(c):
            _, eb = local_hf(c, band)
            m = band & (e_ref > 1e-3)
            return float(np.median(eb[m] / e_ref[m])) if m.sum() else float("nan")

        def seam_gap(c):
            inner = band & (cv2.dilate(anchor.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0)
            sm = lambda v, s: cv2.GaussianBlur((v * s[..., None]).astype(np.float32), (0, 0), 6.0) / \
                np.maximum(cv2.GaussianBlur(s.astype(np.float32), (0, 0), 6.0), 1e-6)[..., None]
            d = np.abs(sm(c, inner) - sm(img, anchor)).mean(2)
            return float(np.median(d[inner])) if inner.sum() else float("nan")

        r0, r1 = hf_ratio(col), hf_ratio(out)
        s0, s1 = seam_gap(col), seam_gap(out)
        print(f"{scene:<22} {int(band.sum()):>7d} {100*float(hit.mean()):>9.1f} "
              f"{r0:>6.2f} → {r1:<7.2f} {s0:>5.1f} → {s1:<6.1f}")
        stats[scene] = {"bandPx": int(band.sum()), "mirrorHitPct": 100 * float(hit.mean()),
                        "hfRatioBefore": r0, "hfRatioAfter": r1,
                        "seamBefore": s0, "seamAfter": s1}

        t = args.tag
        cv2.imwrite(str(g / f"hidden_color{t}.png"), out.astype(np.uint8))
        for name in (f"hidden_mask{st}.png", f"selfocc_code{st}.png", f"hidden_paint{st}.png",
                     f"hidden_paint_aggr{st}.png", f"hidden_paint_cons{st}.png",
                     f"hidden_raw_aggr{st}.png", f"hidden_raw_cons{st}.png",
                     f"hidden_z{st}.f32", f"struct_conf{st}.png", f"band_src{st}.png"):
            p = g / name
            if p.is_file():
                shutil.copyfile(p, g / name.replace(st, t))

    (args.geometry.parent / "matte-soft-probe" / "texture_band_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
