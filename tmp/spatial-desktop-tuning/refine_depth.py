#!/usr/bin/env python
"""用彩色图做引导锐化深度断崖，让断边贴住真实物体边缘。

动机（D161）：断边轮廓的几何粗糙度实测 2.15，即断边判定在真实边缘附近**游走**。
根因是 MoGe 的深度在剪影处过渡是软的、且与图像边缘不完全对齐——MoGe-3 论文自己也写
明回归范式无法消解物体边界处的像素歧义。彩色图的边缘是硬的，用它做引导即可把深度断崖
拉到正确位置。

用导向滤波（Guided Filter, He et al.），在逆深度上做：
    q = a·I + b,  a = cov(I,p)/(var(I)+eps),  b = mean(p) − a·mean(I)
eps 小则更贴合引导边缘，但也更容易把**纹理**边缘误当成深度边缘；因此对 a 再按
局部深度动态范围加权——平坦区不该被纹理拉出假断崖。

opencv 5 的 pip 轮子不带 ximgproc，这里用盒滤波直接实现，无额外依赖。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def box(a: np.ndarray, r: int) -> np.ndarray:
    return cv2.boxFilter(a, -1, (2 * r + 1, 2 * r + 1), normalize=True, borderType=cv2.BORDER_REFLECT)


def guided(p: np.ndarray, guide: np.ndarray, r: int, eps: float) -> np.ndarray:
    mI, mp = box(guide, r), box(p, r)
    corr_I, corr_Ip = box(guide * guide, r), box(guide * p, r)
    var_I = corr_I - mI * mI
    cov_Ip = corr_Ip - mI * mp
    a = cov_Ip / (var_I + eps)
    b = mp - a * mI
    return box(a, r) * guide + box(b, r)


def write(args, meta, z: np.ndarray, z2: np.ndarray) -> None:
    dst = args.geo / f"{args.scene}{args.suffix}"
    dst.mkdir(parents=True, exist_ok=True)
    z2.tofile(dst / "depth_z.f32")
    meta = dict(meta)
    meta.pop("hiddenLayer", None)
    (dst / "moge-meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    idx = args.geo / "index.json"
    scenes = json.loads(idx.read_text(encoding="utf-8"))["scenes"]
    name = f"{args.scene}{args.suffix}"
    if name not in scenes:
        idx.write_text(json.dumps({"scenes": sorted(set(scenes) | {name})},
                                  ensure_ascii=False, indent=2), encoding="utf-8")
    d = np.abs(z2 - z)
    print(f"{name}: 深度改动 中位 {np.median(d)*1000:.2f}mm  "
          f"p99 {np.percentile(d, 99)*1000:.2f}mm  最大 {d.max()*1000:.1f}mm")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geo", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scene", required=True)
    ap.add_argument("--suffix", default="@gf")
    ap.add_argument("--radius", type=int, default=6)
    ap.add_argument("--eps", type=float, default=1e-4)
    ap.add_argument("--passes", type=int, default=2)
    ap.add_argument("--mode", choices=("guided", "self", "incut"), default="guided",
                    help="guided=彩色引导（实测有害）；self=自导向保边平滑（也更差）；incut=先定断边再只在面内平滑")
    args = ap.parse_args()

    src = args.geo / args.scene
    meta = json.loads((src / "moge-meta.json").read_text(encoding="utf-8"))
    w, h = meta["width"], meta["height"]
    z = np.fromfile(src / "depth_z.f32", dtype=np.float32).reshape(h, w)
    img = np.asarray(Image.open(args.assets / meta["scene"] / "center.jpg").convert("RGB"))
    guide = cv2.cvtColor(img.astype(np.float32) / 255.0, cv2.COLOR_RGB2GRAY)

    # 在**逆深度**上滤波：视差与像素位移成正比，断崖在逆深度里才是等高的台阶
    inv = 1.0 / np.maximum(z, 1e-6)
    lo, hi = float(inv.min()), float(inv.max())
    p = (inv - lo) / max(hi - lo, 1e-9)
    if args.mode == "incut":
        # 断边**内**平滑：先按与查看器同一判据定出断边，再只在面内做 Jacobi 平均，
        # 绝不跨断崖取值。按构造断边几何一格不动，只压面内抖动。
        # （前两种做法都栽在同一处：平滑把陡崖变成斜坡，阈值就在斜坡上时过时不过。）
        max_baseline = 60 * max(w, h) / 720 * meta["baselinePerPixel"]
        jump = 1.5 / max(meta["fx"] * max_baseline, 1e-9)
        cut_r = np.abs(np.diff(inv, axis=1)) > jump            # (h, w-1)
        cut_d = np.abs(np.diff(inv, axis=0)) > jump            # (h-1, w)
        q = inv.copy()
        for _ in range(args.passes * 4):
            acc = q * 0.0
            cnt = np.zeros_like(q)
            for shift, cut, axis in ((1, cut_r, 1), (-1, cut_r, 1), (1, cut_d, 0), (-1, cut_d, 0)):
                nb = np.roll(q, shift, axis=axis)
                ok = np.ones_like(q, dtype=bool)
                if axis == 1:
                    if shift == 1:   ok[:, 1:] = ~cut;  ok[:, 0] = False
                    else:            ok[:, :-1] = ~cut; ok[:, -1] = False
                else:
                    if shift == 1:   ok[1:, :] = ~cut;  ok[0, :] = False
                    else:            ok[:-1, :] = ~cut; ok[-1, :] = False
                acc += np.where(ok, nb, 0.0)
                cnt += ok
            lam = 0.5
            q = np.where(cnt > 0, (1 - lam) * q + lam * acc / np.maximum(cnt, 1), q)
        z2 = (1.0 / np.maximum(q, 1e-9)).astype(np.float32)
        n_cut = int(cut_r.sum() + cut_d.sum())
        print(f"  断边 {n_cut} 条；面内 Jacobi {args.passes*4} 轮")
        write(args, meta, z, z2)
        return

    out = p.copy()
    for _ in range(args.passes):
        if args.mode == "guided":
            out = guided(out, guide, args.radius, args.eps)
        else:
            # 自导向（引导图就是深度自己）＝保边平滑，只压深度抖动，
            # **不会把纹理边缘搬进深度**——彩色引导正是栽在这里（几何粗糙 +47%、散点翻倍）。
            out = guided(out, out.copy(), args.radius, args.eps)
    # 只允许在**原本就有断崖**的地方改动：平坦区若被纹理拉出假边，这一步会挡掉。
    # 判据用原始逆深度的局部动态范围，与断边阈值同源。
    span = cv2.dilate(p, np.ones((7, 7), np.uint8)) - cv2.erode(p, np.ones((7, 7), np.uint8))
    wgt = np.clip(span / max(float(np.percentile(span, 99.0)), 1e-9), 0.0, 1.0)
    out = wgt * out + (1.0 - wgt) * p
    # 导向滤波是线性回归，会在强边处**过冲**到原值域之外。逆深度一旦冲到 0 附近或负数，
    # 深度就炸到 1e9（实测最大改动 1e12 mm）。限回原值域，且只允许在原始局部
    # [min, max] 之内变化——这样它只能"把断崖挪到正确位置"，不能凭空造出新的深度层级。
    lo_l = cv2.erode(p, np.ones((2 * args.radius + 1,) * 2, np.uint8))
    hi_l = cv2.dilate(p, np.ones((2 * args.radius + 1,) * 2, np.uint8))
    out = np.clip(out, lo_l, hi_l)

    inv2 = out * (hi - lo) + lo
    z2 = (1.0 / np.maximum(inv2, 1e-9)).astype(np.float32)
    write(args, meta, z, z2)


if __name__ == "__main__":
    main()
