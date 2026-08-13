#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""工单步骤 1：结构延续 + 真实像素搬运，产出 `_str` 变体。

病因（D186 收敛）：显露带里的内容在统计上与周围一致，但**结构是错的**——真值是木椅
竖边、盘沿高光的延续，回归/扩散模型给的是一道抹平的晕。所以这一步不去调色阶、不去
调对比度（两条都已被 D185/D186 否决），而是**把结构按几何延续进带内**。

方法：**相干传输**（coherence transport，Bornemann & März 的形式）。
- 洋葱皮式由外向内填：每轮只填与"已知"相邻的一圈；
- 每个待填像素的取色方向不是各向同性平均，而是沿**等照度线方向**（结构张量主方向的
  垂向）加权——权重 ∝ |cos(邻居方向, 传输方向)|^μ / 距离。直线结构因此被延续进带内，
  而不是被抹平；
- 结构张量只在**已知像素**上算（法向未知的地方不参与），避免用自己填出来的东西当依据。

取色来源严格按 `band_source.build_source` 的**逐段**集合（工单步骤 0）：段自己的背景
深度窗口 + 必须比该段遮挡物更远 + `--exclude-fg` 膨胀区排除。全局窗口会把别的段的
前景放进来（05 的蛋糕下层就是这么进来的）。

纹理：相干传输给的是**结构正确但平滑**的底。高频直接沿用 `_mix` 的
（`mix − blur(mix)`），于是"结构换成对的、纹理保留现有的"，不引入新的噪声来源
——D186 的教训是别去放大填充内容里的低幅噪声。

选择性应用：逐像素按填充时的**相干度**加权混回 `_mix`。相干度低（平坦墙面、无结构可
延续）的地方保持 `_mix` 原样，这正是 D186「不要动本来干净的区域」。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from band_source import build_source, occluder_mask

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def structure_field(gray, known, sigma=3.0):
    """在**已知像素**上算结构张量，返回 (传输方向 tx,ty, 相干度)。

    梯度只在 3×3 邻域全已知处可信，其余用带权高斯把可信处的张量摊开——
    直接在含空洞的图上求梯度会把洞的边界当成结构（D137 参照环同类坑）。
    """
    valid = cv2.erode(known.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, 3) * valid
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, 3) * valid
    g = lambda a: cv2.GaussianBlur(a, (0, 0), sigma)
    wsum = np.maximum(g(valid), 1e-6)
    jxx, jyy, jxy = g(gx * gx) / wsum, g(gy * gy) / wsum, g(gx * gy) / wsum
    # 2×2 对称矩阵闭式特征分解
    tr, det = jxx + jyy, jxx * jyy - jxy * jxy
    disc = np.sqrt(np.maximum(tr * tr / 4.0 - det, 0.0))
    l1, l2 = tr / 2.0 + disc, tr / 2.0 - disc
    # 主特征向量 = 梯度方向；传输方向取其垂向（等照度线）
    vx, vy = jxy, l1 - jxx
    nrm = np.sqrt(vx * vx + vy * vy)
    flat = nrm < 1e-6
    vx = np.where(flat, 1.0, vx / np.maximum(nrm, 1e-6))
    vy = np.where(flat, 0.0, vy / np.maximum(nrm, 1e-6))
    coh = (l1 - l2) / np.maximum(l1 + l2, 1e-6)
    return (-vy).astype(np.float32), vx.astype(np.float32), coh.astype(np.float32)


def coherence_transport(img, hole, src, radius=5, mu=8.0, max_iter=400, refresh=4):
    """把 hole 内的像素由外向内填满，取色只来自 src ∪ 已填。

    返回 (填好的图, 已填掩膜, 逐像素相干度, 逐像素填充步数)。

    **方向性必须随相干度自适应**（2026-08-12 修）：第一版用固定 μ=8，在低相干区
    （人脸、头发这类没有主方向的地方）方向场本身是噪声，强方向性把它拉成一条条
    水平条纹——00 场景人物脸颊上肉眼可见，正是用户点名要消灭的那类条带。
    改成 μ_p = μ·相干度，平坦处退化为各向同性平均，不产生拖影。
    """
    h, w = hole.shape
    out = img.copy().astype(np.float32)
    known = src.copy()
    todo = hole & ~src
    conf = np.zeros((h, w), np.float32)
    step = np.zeros((h, w), np.float32)
    offs = [(dy, dx) for dy in range(-radius, radius + 1)
            for dx in range(-radius, radius + 1)
            if (dy or dx) and dy * dy + dx * dx <= radius * radius]
    ulen = np.array([np.hypot(dy, dx) for dy, dx in offs], np.float32)
    k3 = np.ones((3, 3), np.uint8)
    tx = ty = coh_f = None
    for it in range(max_iter):
        if not todo.any():
            break
        frontier = (cv2.dilate(known.astype(np.uint8), k3) > 0) & todo
        if not frontier.any():
            break                       # 该段剩余像素与来源不连通，交回 _mix
        if it % refresh == 0:
            gray = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
            tx, ty, coh_f = structure_field(gray, known)
        ys, xs = np.nonzero(frontier)
        acc = np.zeros((ys.size, 3), np.float32)
        wsum = np.zeros(ys.size, np.float32)
        tpx, tpy = tx[ys, xs], ty[ys, xs]
        mup = np.clip(mu * coh_f[ys, xs], 0.0, mu).astype(np.float32)
        for (dy, dx), dlen in zip(offs, ulen):
            qy = np.clip(ys + dy, 0, h - 1)
            qx = np.clip(xs + dx, 0, w - 1)
            ok = known[qy, qx]
            if not ok.any():
                continue
            align = np.abs((dx / dlen) * tpx + (dy / dlen) * tpy)
            wq = np.where(ok, np.power(np.maximum(align, 1e-6), mup) / dlen, 0.0).astype(np.float32)
            acc += out[qy, qx] * wq[:, None]
            wsum += wq
        # 方向上一个已知邻居都没有时退回各向同性（否则细颈处会卡死）
        weak = wsum < 1e-6
        if weak.any():
            wy, wx = ys[weak], xs[weak]
            a2 = np.zeros((wy.size, 3), np.float32)
            s2 = np.zeros(wy.size, np.float32)
            for (dy, dx), dlen in zip(offs, ulen):
                qy = np.clip(wy + dy, 0, h - 1)
                qx = np.clip(wx + dx, 0, w - 1)
                ok = known[qy, qx].astype(np.float32) / dlen
                a2 += out[qy, qx] * ok[:, None]
                s2 += ok
            acc[weak], wsum[weak] = a2, np.maximum(s2, 1e-6)
        val = acc / np.maximum(wsum, 1e-6)[:, None]
        fill = wsum > 1e-6
        ys, xs, val = ys[fill], xs[fill], val[fill]
        if ys.size == 0:
            break
        out[ys, xs] = val
        conf[ys, xs] = coh_f[ys, xs]
        step[ys, xs] = it + 1
        known[ys, xs] = True
        todo[ys, xs] = False
    return out, hole & ~todo, conf, step


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--src-tag", default="_mix")
    ap.add_argument("--tag", default="_str")
    ap.add_argument("--radius", type=int, default=5, help="传输邻域半径 px")
    ap.add_argument("--mu", type=float, default=8.0, help="方向性指数，越大越沿结构")
    ap.add_argument("--exclude-fg", type=int, default=12)
    ap.add_argument("--coh-lo", type=float, default=0.25, help="相干度低于此值完全用 _mix")
    ap.add_argument("--coh-hi", type=float, default=0.55, help="高于此值完全用传输结果")
    ap.add_argument("--sep-px", type=float, default=1.5, help="来源须比遮挡物远多少（D154 同常量）")
    ap.add_argument("--near", type=float, default=8.0, help="传输 N 步内完全采信")
    ap.add_argument("--far", type=float, default=20.0, help="超过 N 步完全交回 _mix")
    args = ap.parse_args()

    stats = {}
    for scene in args.scenes:
        g = args.geometry / scene
        st = args.src_tag
        mp = g / f"hidden_mask{st}.png"
        if not mp.is_file():
            print(f"{scene}: 缺 {st} 档，跳过")
            continue
        meta = json.loads((g / "moge-meta.json").read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        img = cv2.imread(str(args.assets / scene / "center.jpg")).astype(np.float32)
        band = cv2.imread(str(mp), 0) > 127
        mix = cv2.imread(str(g / f"hidden_color{st}.png")).astype(np.float32)
        if band.sum() < 256:
            print(f"{scene}: 带太小，跳过")
            continue
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{st}.f32", dtype=np.float32).reshape(h, w)
        inv, inv_layer = 1.0 / np.maximum(z, 1e-6), 1.0 / np.maximum(hz, 1e-6)
        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        sep = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)

        print(f"{scene}：带 {int(band.sum())} px")
        occ = occluder_mask(args.assets / scene, w, h)
        _, segs = build_source(band, inv, inv_layer, occ, sep, args.exclude_fg)

        # 逐段传输。基底用原图（带外真实像素），带内先置为 _mix，只为结构张量提供连续性；
        # 真正写进去的值全部来自 src ∪ 已填。
        work = np.where(band[..., None], mix, img).astype(np.float32)
        filled_all = np.zeros((h, w), bool)
        conf_all = np.zeros((h, w), np.float32)
        step_all = np.zeros((h, w), np.float32)
        for s in segs:
            if s["srcPx"] < 256:
                continue
            sub, fl, cf, sp = coherence_transport(work, s["mask"], s["src"],
                                                  args.radius, args.mu)
            work = np.where(fl[..., None], sub, work)
            filled_all |= fl
            conf_all = np.maximum(conf_all, cf)
            step_all = np.where(fl, sp, step_all)

        # 纹理沿用 _mix 的高频；结构换成传输出来的低频
        hf = mix - cv2.GaussianBlur(mix, (0, 0), 1.5)
        structured = cv2.GaussianBlur(work, (0, 0), 1.5) + hf

        # 相干度加权混回 _mix：低相干（平坦墙面）保持原样，不动干净区域
        t = np.clip((conf_all - args.coh_lo) / max(args.coh_hi - args.coh_lo, 1e-6), 0, 1)
        # **传输距离衰减**：离真实来源越远，延续出来的结构越不可信，越容易变成拖影。
        # 超过 `--far` 步一律交回 _mix。这条与"到合法来源的距离 p90 40–65px"是一对：
        # 远端本来就填不好，宁可保持现状也不要拖出条纹。
        far = np.clip((args.far - step_all) / max(args.far - args.near, 1e-6), 0, 1)
        t = t * np.where(filled_all, far, 0.0)
        t = cv2.GaussianBlur(t.astype(np.float32), (0, 0), 2.0)[..., None]
        out = np.where(band[..., None], mix * (1 - t) + structured * t, img)
        out = np.clip(out, 0, 255)

        cov = float(filled_all.sum()) / max(int(band.sum()), 1)
        applied = float((t[..., 0] > 0.5)[band].mean())
        print(f"    传输覆盖 {100*cov:.1f}% 带像素；相干度 >0.5 实际接管 {100*applied:.1f}%")
        stats[scene] = {"bandPx": int(band.sum()), "transportCoverage": cov,
                        "appliedFrac": applied, "segments": len(segs)}

        t2 = args.tag
        cv2.imwrite(str(g / f"hidden_color{t2}.png"), out.astype(np.uint8))
        # 新中间产物一律落盘未裁剪版，并接进查看器诊断视图
        cv2.imwrite(str(g / f"struct_conf{t2}.png"),
                    (np.clip(conf_all, 0, 1) * 255).astype(np.uint8))
        cv2.imwrite(str(g / f"struct_raw{t2}.png"),
                    np.clip(structured, 0, 255).astype(np.uint8))
        srcvis = np.zeros((h, w), np.uint8)
        for s in segs:
            srcvis[s["src"]] = 255
        cv2.imwrite(str(g / f"band_src{t2}.png"), srcvis)
        for name in (f"hidden_mask{st}.png", f"selfocc_code{st}.png", f"hidden_paint{st}.png",
                     f"hidden_paint_aggr{st}.png", f"hidden_paint_cons{st}.png",
                     f"hidden_raw_aggr{st}.png", f"hidden_raw_cons{st}.png", f"hidden_z{st}.f32"):
            p = g / name
            if p.is_file():
                shutil.copyfile(p, g / name.replace(st, t2))

    (args.geometry.parent / "matte-soft-probe" / "structure_band_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
