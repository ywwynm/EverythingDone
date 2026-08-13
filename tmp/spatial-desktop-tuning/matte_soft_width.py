#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""软 α 的**过渡带宽度**（像素）：环内占比只说"有多少软像素"，说不出"软带有多宽"。

宽度 = 中间值像素数 ÷ 剪影周长。周长取二值核（α>0.5）的 1px 边界像素数。
这个数决定下一段能不能用：工单里过渡带取 k=8–16px @540×720，如果模型自己的 α
只在 1–2px 内完成 0→1，那么带内绝大部分像素仍然只有 0 或 1，软边界等于没铺开。

同时给原生 1024² 上的宽度（换算到成品分辨率），排除降采样的影响。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

SCENES = [
    "00_original_single", "01_original_double", "02_indoor", "03_office",
    "05_near_object", "06_statue", "07_food", "08_person_pet",
]


def width_px(alpha: np.ndarray, lo: float = 0.05, hi: float = 0.95) -> tuple[float, int, int]:
    core = (alpha > 0.5).astype(np.uint8)
    k3 = np.ones((3, 3), np.uint8)
    perim = int(((cv2.dilate(core, k3) - core) > 0).sum())
    mid = int(((alpha > lo) & (alpha < hi)).sum())
    return (mid / perim if perim else 0.0), mid, perim


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--probe-dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/matte-soft-probe"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    args = ap.parse_args()

    # 控制台是 GBK，别往 print 里放 '²' 之类 GBK 编不出的字符（会 UnicodeEncodeError）
    print(f"{'场景':<22} {'软α带宽px':>10} {'matte带宽px':>12} "
          f"{'1024带宽px':>12} {'折算到成品':>10}")
    print("-" * 72)
    stats = {}
    for scene in args.scenes:
        d = args.assets / scene
        soft = cv2.imread(str(d / "matte_soft.png"), cv2.IMREAD_GRAYSCALE)
        if soft is None:
            continue
        h, w = soft.shape[:2]
        w_soft, mid_s, per_s = width_px(soft.astype(np.float32) / 255.0)

        hard = cv2.imread(str(d / "matte.png"), cv2.IMREAD_GRAYSCALE)
        if hard is not None and (hard.shape[1], hard.shape[0]) != (w, h):
            hard = cv2.resize(hard, (w, h), interpolation=cv2.INTER_NEAREST)
        w_hard = width_px(hard.astype(np.float32) / 255.0)[0] if hard is not None else float("nan")

        raw_p = args.probe_dir / f"{scene}_soft_raw1024.png"
        if raw_p.is_file():
            raw = cv2.imread(str(raw_p), cv2.IMREAD_UNCHANGED).astype(np.float32) / 65535.0
            w_raw = width_px(raw)[0]
            # 1024² 的一个像素在成品上有多长：按长边比例折算
            w_raw_asset = w_raw * max(w, h) / 1024.0
        else:
            w_raw = w_raw_asset = float("nan")

        print(f"{scene:<22} {w_soft:>10.2f} {w_hard:>12.2f} {w_raw:>12.2f} {w_raw_asset:>10.2f}")
        stats[scene] = {"softWidthPx": w_soft, "hardWidthPx": w_hard,
                        "rawWidthPx1024": w_raw, "rawWidthPxAsset": w_raw_asset,
                        "midPx": mid_s, "perimeterPx": per_s}

    (args.probe_dir / "soft_matte_width.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
