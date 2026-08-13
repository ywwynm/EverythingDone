#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""带内补全内容"糊"到什么程度：与紧邻的真实背景比高频能量。

"糊"不能靠看。判据：高频能量 = 图像减去 σ=1.5 高斯之后的残差 RMS。
分子取遮挡带内（补全出来的），分母取带外 6–20px 的真实背景（同一块内容、同一
曝光），比值 1.0 = 与真背景一样锐，越小越糊。带外参照必须避开带本身，否则量的是
自己（D137 参照环踩过的坑）。

同时按 `selfocc_code` 把带分成"激进档填的"与"保守档填的"，两档各自成绩分开看——
它们是两个不同的模型（Moebius / Big-LaMa），混在一起没法归因。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def hf_rms(gray: np.ndarray, sel: np.ndarray) -> float:
    hi = gray - cv2.GaussianBlur(gray, (0, 0), 1.5)
    return float(np.sqrt((hi[sel] ** 2).mean())) if sel.sum() >= 64 else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=["00_original_single", "05_near_object"])
    ap.add_argument("--tag", default="_mix")
    args = ap.parse_args()

    print(f"{'场景':<22} {'带内HF':>7} {'带外真实HF':>10} {'比值':>6} "
          f"{'激进档比值':>10} {'保守档比值':>10}")
    out = {}
    for scene in args.scenes:
        g = args.geometry / scene
        hc = cv2.imread(str(g / f"hidden_color{args.tag}.png"))
        mask = cv2.imread(str(g / f"hidden_mask{args.tag}.png"), 0)
        cen = cv2.imread(str(args.assets / scene / "center.jpg"))
        if hc is None or mask is None or cen is None:
            print(f"{scene:<22} 缺文件，跳过")
            continue
        band_raw = mask > 127
        # 带**内缩 3px** 再量：带的边界本身是一条接缝（带外被钳回原图），不缩进去量到的
        # 是接缝的阶跃而不是补全内容的纹理——第一版就这么读出了"带内比真背景还锐 1.27×"。
        band = cv2.erode(band_raw.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0
        dist = cv2.distanceTransform((~band_raw).astype(np.uint8), cv2.DIST_L2, 5)
        ref = (~band_raw) & (dist >= 6) & (dist < 20)

        g_band = cv2.cvtColor(hc.astype(np.float32), cv2.COLOR_BGR2GRAY)
        g_ref = cv2.cvtColor(cen.astype(np.float32), cv2.COLOR_BGR2GRAY)
        a, b = hf_rms(g_band, band), hf_rms(g_ref, ref)

        code = cv2.imread(str(g / f"selfocc_code{args.tag}.png"), 0)
        if code is None:
            code = cv2.imread(str(g / "selfocc_code.png"), 0)
        aggr = cons = float("nan")
        if code is not None:
            # 85 = 同一物体 → 保守档；170/255 = 不同物体/无实例 → 激进档
            cons = hf_rms(g_band, band & (code >= 43) & (code < 128)) / max(b, 1e-6)
            aggr = hf_rms(g_band, band & (code >= 128)) / max(b, 1e-6)
        print(f"{scene:<22} {a:>7.2f} {b:>10.2f} {a/max(b,1e-6):>6.2f} "
              f"{aggr:>10.2f} {cons:>10.2f}")
        out[scene] = {"bandHF": a, "refHF": b, "ratio": a / max(b, 1e-6),
                      "aggrRatio": aggr, "consRatio": cons,
                      "bandPx": int(band.sum())}
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
