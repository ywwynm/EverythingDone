#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""带内容的**局部**色阶 + 对比度匹配，离线重合成一个 `_lvl` 变体。

为什么是局部而不是全局（2026-08-11 改判）：
先按 D185 的想法量了一版全局中位差，得到"带内比真背景暗 8.2 级"。但那个参照环没有
按深度筛背景侧，环里混进了主体自身的暗像素。换成 D159 的深度筛选参照重量一遍，
00 场景激进区只剩 −2.0 级，05 是 +18.2、08 是 +91.8——**方向都不一致，全局偏置这个
描述不成立**，照它做出来的校正只会把一部分场景推得更远。

改成看得见的那个量。把 328° 曝光出来的带放大 5× 看：带内容不是整体偏暗，是**局部
被抹平**——木椅竖边糊成一道竖向的晕、盘沿的高光整段消失换成一片平灰。对应的量是
**局部对比度塌陷**，它随位置变化，全局仿射修不了。

做法：把带内容的局部均值/局部标准差，对齐到**紧邻真实背景**的同名量。
- 参照只取真实像素，且按深度筛到背景侧（D159 的配方），且退开带 3px（避开接缝）；
- 局部统计用带权盒式滤波，权重就是"该像素是不是可用参照"，所以带内像素不会给自己当参照
  （D137 的教训：参照取到了被测对象自己）；
- 增益下限 1.0（只补对比度缺口，不主动压）、上限可调，σ 有下限防止平坦区炸噪点；
- 带边界 3px 羽化回原值，不制造新接缝。

不重跑任何模型：直接读已落盘的 `hidden_raw_aggr_poisson*.png` / `hidden_raw_cons*.png`
按原配方重合成（已验证与 `hidden_color_mix.png` 中位逐像素一致），只多一步匹配。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]


def local_stats(img: np.ndarray, weight: np.ndarray, radius: int):
    """以 weight 为权重的局部均值与标准差（逐通道）。weight=0 的像素不参与。"""
    k = 2 * radius + 1
    w = cv2.blur(weight, (k, k))
    ok = w > 1e-3
    mean = np.zeros_like(img)
    var = np.zeros_like(img)
    for c in range(img.shape[2]):
        ch = img[..., c] * weight
        m = np.where(ok, cv2.blur(ch, (k, k)) / np.maximum(w, 1e-6), 0.0)
        m2 = np.where(ok, cv2.blur(ch * img[..., c], (k, k)) / np.maximum(w, 1e-6), 0.0)
        mean[..., c] = m
        var[..., c] = np.maximum(m2 - m * m, 0.0)
    return mean, np.sqrt(var), ok


def match_local(color, image, band, ref_ok, radius, gain_cap, gain_floor, sigma_floor, feather):
    """band 内做局部仿射匹配，返回新的 color。"""
    src = color.astype(np.float32)
    mu_c, sd_c, ok_c = local_stats(src, band.astype(np.float32), radius)
    mu_r, sd_r, ok_r = local_stats(image.astype(np.float32), ref_ok.astype(np.float32), radius)
    # 增益必须**双向**可调。第一版下限写成 1.0（"只补缺口、不主动压"），结果在本来
    # 就匹配的场景上把比值从 1.01 顶到 1.19——单边截断会把一个中位为 1 的比值分布
    # 整体抬起来，等于凭空加对比度。00/08 两个场景就是这么被推过头的。
    gain = np.clip(sd_r / np.maximum(sd_c, sigma_floor), gain_floor, gain_cap)
    out = (src - mu_c) * gain + mu_r
    # 支撑不足处不动（宁可不改，也不要用一个没根据的目标去搬）
    out = np.where((ok_c & ok_r)[..., None], out, src)
    # 带边界羽化：带外必须逐像素等于原图（D133），带内边缘平滑过渡回未匹配值
    w = cv2.blur(band.astype(np.float32), (2 * feather + 1,) * 2)[..., None]
    blended = src * (1.0 - w) + out * w
    return np.where(band[..., None], np.clip(blended, 0, 255), src), gain


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--src-tag", default="_mix", help="读哪一档的原始输出")
    ap.add_argument("--tag", default="_lvl", help="写出的变体后缀")
    ap.add_argument("--radius", type=int, default=24, help="局部统计半径（带最宽 133px）")
    ap.add_argument("--gain-cap", type=float, default=2.0)
    ap.add_argument("--gain-floor", type=float, default=0.5,
                    help="下限必须 <1：单边截断会把中位为 1 的比值分布整体抬起来")
    ap.add_argument("--sigma-floor", type=float, default=3.0, help="平坦区不炸噪点")
    ap.add_argument("--feather", type=int, default=3)
    ap.add_argument("--band-feather", type=int, default=3, help="两档合成的羽化，与管线一致")
    args = ap.parse_args()

    print(f"{'场景':<22} {'带px':>7} {'增益中位':>8} {'增益p95':>8} "
          f"{'局部σ比 前→后':>16}")
    stats = {}
    for scene in args.scenes:
        g = args.geometry / scene
        meta_p = g / "moge-meta.json"
        if not meta_p.is_file():
            continue
        meta = json.loads(meta_p.read_text(encoding="utf-8"))
        h, w = meta["height"], meta["width"]
        st = args.src_tag
        img = cv2.imread(str(args.assets / scene / "center.jpg"))
        mk = cv2.imread(str(g / f"hidden_mask{st}.png"), 0)
        ra = cv2.imread(str(g / f"hidden_raw_aggr_poisson{st}.png"))
        rc = cv2.imread(str(g / f"hidden_raw_cons{st}.png"))
        code = cv2.imread(str(g / f"selfocc_code{st}.png"), 0)
        if any(v is None for v in (img, mk, ra, rc, code)):
            print(f"{scene:<22} 缺 {st} 档的原始输出，跳过")
            continue
        band = mk > 127
        keep = (code >= 43) & (code < 128)
        imgf = img.astype(np.float32)
        col_a = np.where(band[..., None], ra.astype(np.float32), imgf)
        col_c = np.where(band[..., None], rc.astype(np.float32), imgf)
        wgt = cv2.blur(keep.astype(np.float32), (2 * args.band_feather + 1,) * 2)[..., None]
        color = col_c * wgt + col_a * (1.0 - wgt)

        # 参照：真实像素、退开带 3px、且逆深度落在带自身背景层级内（只取背景侧，D159）
        z = np.fromfile(g / "depth_z.f32", dtype=np.float32).reshape(h, w)
        hz = np.fromfile(g / f"hidden_z{st}.f32", dtype=np.float32).reshape(h, w)
        inv, inv_layer = 1.0 / np.maximum(z, 1e-6), 1.0 / np.maximum(hz, 1e-6)
        lo, hi = np.percentile(inv_layer[band], 2), np.percentile(inv_layer[band], 98)
        dist = cv2.distanceTransform((~band).astype(np.uint8), cv2.DIST_L2, 5)
        ref_ok = (~band) & (dist > 3) & (inv >= lo) & (inv <= hi)
        if ref_ok.sum() < 512:
            print(f"{scene:<22} 背景侧参照仅 {int(ref_ok.sum())} px，跳过")
            continue

        matched, gain = match_local(color, imgf, band, ref_ok, args.radius,
                                    args.gain_cap, args.gain_floor,
                                    args.sigma_floor, args.feather)

        # 效果口径：带内局部 σ ÷ 参照局部 σ，改前改后各一个（1.0 = 与真背景同对比度）
        def sigma_ratio(c):
            _, sd_c, _ = local_stats(c.astype(np.float32), band.astype(np.float32), args.radius)
            _, sd_r, _ = local_stats(imgf, ref_ok.astype(np.float32), args.radius)
            sel = band & (sd_r.mean(2) > 1e-3)
            return float(np.median(sd_c.mean(2)[sel] / sd_r.mean(2)[sel]))

        before, after = sigma_ratio(color), sigma_ratio(matched)
        gm = float(np.median(gain.mean(2)[band]))
        gp = float(np.percentile(gain.mean(2)[band], 95))
        print(f"{scene:<22} {int(band.sum()):>7d} {gm:>8.2f} {gp:>8.2f} "
              f"{before:>7.2f} → {after:<7.2f}")
        stats[scene] = {"bandPx": int(band.sum()), "gainMedian": gm, "gainP95": gp,
                        "sigmaRatioBefore": before, "sigmaRatioAfter": after}

        t = args.tag
        cv2.imwrite(str(g / f"hidden_color{t}.png"), np.clip(matched, 0, 255).astype(np.uint8))
        # 变体要能在查看器里直接选，几何与判定沿用源档，逐字节复制
        for name in (f"hidden_mask{st}.png", f"selfocc_code{st}.png",
                     f"hidden_paint{st}.png", f"hidden_paint_aggr{st}.png",
                     f"hidden_paint_cons{st}.png", f"hidden_raw_aggr{st}.png",
                     f"hidden_raw_cons{st}.png", f"hidden_z{st}.f32"):
            p = g / name
            if p.is_file():
                shutil.copyfile(p, g / name.replace(st, t))

    (args.geometry.parent / "matte-soft-probe" / "level_match_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
