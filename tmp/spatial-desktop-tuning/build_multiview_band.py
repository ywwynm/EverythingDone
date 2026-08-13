#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多视角补全：**在会被看到的那个视角上补洞**，再映射回第二层。产出 `_mv` 变体。

为什么这条路对（用户 2026-08-12 提出）：现在的补全全部发生在**中心视角**，那时带藏在
主体背后，"补出来的内容要与将来贴在它旁边的真实背景接得上"这件事**没有任何约束**
（D190 实测：那条缝从未被约束过；D192 对照实验：连模型原始输出都带着这条缝）。
换到目标视角补，洞的四周本来就全是真实像素——模型的 Dirichlet 边界天然就是对的那个，
它必须同时接上真实背景和真实前景边缘。

多视角一致性：几个角度各补各的会互相打架，中间角度混合就闪。这里用**顺序补全**——
按角度依次补，每一步先把已经填好的内容映射到当前视角当作已知像素，本步只补**剩余**
的洞。于是后一步永远看得见前一步的结果，按构造一致。

映射不需要"洞处的深度"：第二层像素 p 有自己的 `hidden_z`，正投影到目标视角得到 q，
直接在该视角的补全结果上取 q 处的颜色即可。只在 p 于该视角**确实被显露**（q 落在洞里）
时采样，否则取到的是挡着它的前景。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from band_source import occluder_mask
from build_hidden_layer import inpaint_moebius, inpaint_onnx_tiled


def project(z, meta, tx, ty, margin, out_hw):
    """第二层像素 → 目标视角的画布坐标（与 SPLAT_VS 逐字一致）。"""
    h, w = z.shape
    H, W = out_hw
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    zz = np.maximum(z, 1e-6)
    X = (xx + 0.5 - meta["cx"]) * zz / meta["fx"]
    Y = (yy + 0.5 - meta["cy"]) * zz / meta["fy"]
    u = meta["fx"] * (X - tx) / zz + meta["cx"] + meta["fx"] * tx / meta["pivotZ"]
    v = meta["fy"] * (Y - ty) / zz + meta["cy"] + meta["fy"] * ty / meta["pivotZ"]
    cu = (u / w - margin) / (1 - 2 * margin)
    cv_ = (v / h - margin) / (1 - 2 * margin)
    return cu * W, cv_ * H


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--frames", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/orbit/diag/mv"))
    ap.add_argument("--scene", default="00_original_single")
    ap.add_argument("--src-tag", default="_dz")
    ap.add_argument("--tag", default="_mv")
    ap.add_argument("--degs", nargs="*", type=float,
                    default=[0, 45, 90, 135, 180, 225, 270, 315])
    ap.add_argument("--baseline-cm", type=float, default=4.5)
    ap.add_argument("--margin", type=float, default=0.059)
    ap.add_argument("--lama", type=Path,
                    default=Path("build/spatial-model-poc/artifacts/big_lama_places2_512_fp32.onnx"))
    ap.add_argument("--edge-guard", type=int, default=1,
                    help="洞边缘内缩几像素才允许采样，避开补全模型自己的过渡带")
    # 后端默认给 **Moebius**（ECCV 2026 生成式）而不是 Big-LaMa（WACV 2022 回归式）。
    # 第一版默认 LaMa 纯粹是因为它在 CPU 上快，不是因为它更适合——而多视角这条路恰恰
    # 是最有希望的一条，用弱模型跑它本末倒置。而且这里的情形对生成式更有利：
    # 多视角补全时洞的四周**全是真实像素**（中心视角补全时有一半贴着主体）。
    ap.add_argument("--subject-guard", type=float, default=4.0,
                    help="离主体投影多近就完全不采（权重 0）")
    ap.add_argument("--subject-fade", type=float, default=16.0,
                    help="离主体投影多远才完全采信（权重 1）")
    ap.add_argument("--prior-weight", type=float, default=0.5,
                    help="原档内容的先验权重，低置信处据此退回，避免硬边")
    ap.add_argument("--backend", choices=("moebius", "lama"), default="moebius")
    ap.add_argument("--moebius-steps", type=int, default=20)
    ap.add_argument("--moebius-cfg", type=float, default=2.0)
    args = ap.parse_args()

    g = args.geometry / args.scene
    st, t = args.src_tag, args.tag
    meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
    h, w = meta["height"], meta["width"]
    band = cv2.imread(str(g / f"hidden_mask{st}.png"), 0) > 127
    col = cv2.imread(str(g / f"hidden_color{st}.png")).astype(np.float32)
    hz = np.fromfile(g / f"hidden_z{st}.f32", dtype=np.float32).reshape(h, w)
    img = cv2.imread(str(args.assets / args.scene / "center.jpg")).astype(np.float32)
    z_fg = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
    occ_fg = occluder_mask(args.assets / args.scene, w, h)

    acc = np.zeros((h, w, 3), np.float32)
    wsum = np.zeros((h, w), np.float32)
    filled = np.zeros((h, w), bool)          # 已经由前面的角度补好的第二层像素

    print(f"{args.scene}  带 {int(band.sum())} px，按 {len(args.degs)} 个角度顺序补全")
    for deg in args.degs:
        p = args.frames / f"l1-{int(deg)}.png"
        if not p.is_file():
            print(f"  {deg:>5.0f}deg 缺帧 {p.name}，跳过")
            continue
        # 洞用**品红标记**识别，不用 alpha：`fill=0` 时填充遍会把没填上的像素写成
        # 不透明黑（`FILL_FS` 里 `uRadius<0.5` 那条早退分支），alpha 恒为 1，读不出洞。
        bgr = cv2.imread(str(p), cv2.IMREAD_COLOR)
        if bgr is None:
            print(f"  {deg:>5.0f}deg 读不出帧，跳过")
            continue
        H, W = bgr.shape[:2]
        view = bgr.astype(np.float32)
        hole = np.abs(view - np.array([229.0, 0.0, 255.0])).sum(2) < 90
        view[hole] = 0.0
        if hole.sum() < 64:
            print(f"  {deg:>5.0f}deg 无洞")
            continue

        a = np.deg2rad(deg)
        b = args.baseline_cm / 100.0
        tx, ty = b * np.cos(a), b * np.sin(a)
        qx, qy = project(hz, meta, tx, ty, args.margin, (H, W))

        # 顺序补全：先把**已填好**的第二层内容投到本视角，当作已知像素补进去，
        # 本步只补剩余的洞。这样后一步永远看得见前一步的结果。
        if filled.any():
            iy, ix = np.nonzero(filled & band)
            px = np.round(qx[iy, ix]).astype(int)
            py = np.round(qy[iy, ix]).astype(int)
            ok = (px >= 0) & (px < W) & (py >= 0) & (py < H) & hole[np.clip(py, 0, H-1),
                                                                    np.clip(px, 0, W-1)]
            view[py[ok], px[ok]] = (acc[iy, ix] / np.maximum(wsum[iy, ix], 1e-6)[:, None])[ok]
            hole[py[ok], px[ok]] = False
            hole = cv2.morphologyEx(hole.astype(np.uint8), cv2.MORPH_OPEN,
                                    np.ones((3, 3), np.uint8)) > 0

        n_hole = int(hole.sum())
        if n_hole < 64:
            print(f"  {deg:>5.0f}deg 剩余洞 {n_hole} px，跳过")
            continue
        if args.backend == "moebius":
            out = inpaint_moebius(view, hole, "tiled", args.moebius_steps, args.moebius_cfg)
        else:
            out = inpaint_onnx_tiled(view, hole, args.lama)
        cv2.imwrite(str(args.frames / f"filled-{int(deg)}.png"),
                    np.clip(out, 0, 255).astype(np.uint8))

        # 只在"这一步真的补过"的区域内侧采样，避开模型自己的过渡带。
        # **还必须避开洞的主体侧**：洞的一侧贴着位移后的主体，补全模型在那一侧的
        # 输出混着主体的暗色，采进来就是剪影上一条深色颗粒边（第一版实测可见）。
        # 与 D137「参照取到了被测对象自己」同类，判据同样用深度：把遮挡物按前景深度
        # 投到本视角，退开它 `--subject-guard` 像素再采。
        core = cv2.erode(hole.astype(np.uint8), np.ones((2*args.edge_guard+1,)*2, np.uint8)) > 0
        sx_, sy_ = project(z_fg, meta, tx, ty, args.margin, (H, W))
        spx = np.round(sx_[occ_fg]).astype(int)
        spy = np.round(sy_[occ_fg]).astype(int)
        okp = (spx >= 0) & (spx < W) & (spy >= 0) & (spy < H)
        subj = np.zeros((H, W), np.uint8)
        subj[spy[okp], spx[okp]] = 1
        subj = cv2.morphologyEx(subj, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        # **硬门槛换成随距离渐变的权重**：固定退开 N 像素只能减轻不能消除那条深色颗粒边
        # ——补全模型在贴着主体那一侧的输出是渐变污染的，没有一个干净的截断位置。
        # 改成 w = smoothstep(lo, hi, 到主体投影的距离)，越靠近主体权重越低，
        # 最终与 `_dz` 按权重混合，低置信处自然退回原内容。
        dsub = cv2.distanceTransform((1 - subj).astype(np.uint8), cv2.DIST_L2, 5)
        lo, hi = float(args.subject_guard), float(args.subject_fade)
        wmap = np.clip((dsub - lo) / max(hi - lo, 1e-6), 0.0, 1.0)
        wmap = wmap * wmap * (3.0 - 2.0 * wmap)
        wmap *= core

        iy, ix = np.nonzero(band)
        px = np.round(qx[iy, ix]).astype(int)
        py = np.round(qy[iy, ix]).astype(int)
        inb = (px >= 0) & (px < W) & (py >= 0) & (py < H)
        wv = np.zeros(iy.size, np.float32)
        wv[inb] = wmap[py[inb], px[inb]]
        ok = wv > 1e-3
        if ok.sum() == 0:
            print(f"  {deg:>5.0f}deg 洞 {n_hole} px，但没有带像素落在其中")
            continue
        acc[iy[ok], ix[ok]] += out[py[ok], px[ok]] * wv[ok][:, None]
        wsum[iy[ok], ix[ok]] += wv[ok]
        filled[iy[ok], ix[ok]] |= wv[ok] > 0.5
        print(f"  {deg:>5.0f}deg 洞 {n_hole:>6d} px → 采到 {int(ok.sum()):>6d} 个带像素"
              f"（权重>0.5 的累计覆盖 {100*filled[band].mean():.1f}%）")

    # 与 `_dz` 按权重混合：给原内容一个小的先验权重，采样置信度低的地方自然退回去，
    # 不留硬边（硬切会在"采到"和"没采到"之间造出新的边界）。
    w0 = args.prior_weight
    new = (acc + col * w0) / (wsum + w0)[..., None]
    new = np.where(band[..., None], new, img)
    got = wsum > 0.5
    print(f"  权重>0.5 覆盖带的 {100*got[band].mean():.1f}%，"
          f"平均权重 {float(wsum[band].mean()):.2f}，其余按权重退回 {st} 档")

    cv2.imwrite(str(g / f"hidden_color{t}.png"), np.clip(new, 0, 255).astype(np.uint8))
    cv2.imwrite(str(g / f"mv_cover{t}.png"), (got * 255).astype(np.uint8))
    for name in (f"hidden_mask{st}.png", f"selfocc_code{st}.png", f"hidden_paint{st}.png",
                 f"hidden_paint_aggr{st}.png", f"hidden_paint_cons{st}.png",
                 f"hidden_raw_aggr{st}.png", f"hidden_raw_cons{st}.png", f"hidden_z{st}.f32"):
        q = g / name
        if q.is_file():
            shutil.copyfile(q, g / name.replace(st, t))


if __name__ == "__main__":
    main()
