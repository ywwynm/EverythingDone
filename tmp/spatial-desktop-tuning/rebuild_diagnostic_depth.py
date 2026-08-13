#!/usr/bin/env python
"""重建 `diagnostic-depth/disparity.png`，供 Kotlin 侧的 vNext11 导出测试消费。

现有资产没有这个文件（生成器当前不写），但它可以从已发布的两张位移图精确重建：

    disp_front.png = clip(masked_extend(disparity, erode(matte_core))     / 1.4) * 255
    disp_back.png  = clip(masked_extend(disparity, erode(1 - matte_core)) / 1.4) * 255

各自在**自己的域内**就是未经外推的真实视差，只有跨过剪影才是外推值。因此按 matte
取并集即可还原：主体内取 front，背景取 back。这样不必重跑生成器（一次 SDXL 补全约
一两分钟／场景），也不改动任何已发布资产。

契约与 `SpatialDepthSurfelAssetExportTest` 既有写法一致：guide 分辨率、8 位、
读取端按 `sample / 255` 取值。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

DISP_SCALE = 1.4  # 生成器写盘时的除数（generate_assets.py: data / 1.4）


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=None)
    args = ap.parse_args()

    scenes = args.scenes or [
        p.name for p in sorted(args.assets.iterdir())
        if p.is_dir() and (p / "meta.json").is_file() and "baseline" not in p.name
    ]
    for scene in scenes:
        base = args.assets / scene
        meta = json.loads((base / "meta.json").read_text(encoding="utf-8"))
        gw, gh = meta["guideWidth"], meta["guideHeight"]
        front_path, back_path = base / "disp_front.png", base / "disp_back.png"
        if not back_path.is_file():
            print(f"{scene:22s} 缺 disp_back.png，跳过")
            continue
        back = np.asarray(Image.open(back_path).convert("L")).astype(np.float32) / 255.0
        if front_path.is_file() and meta.get("layered"):
            front = np.asarray(Image.open(front_path).convert("L")).astype(np.float32) / 255.0
            matte = np.asarray(Image.open(base / "matte.png").convert("L")).astype(np.float32) / 255.0
            core = cv2.resize(matte, (gw, gh), interpolation=cv2.INTER_AREA)
            # 硬取并集：两图各自域内都是真实值，1px 边界带任取其一都可接受
            disparity = np.where(core > 0.5, front, back)
        else:
            disparity = back
        out_dir = base / "diagnostic-depth"
        out_dir.mkdir(exist_ok=True)
        Image.fromarray(
            np.rint(np.clip(disparity, 0.0, 1.0) * 255.0).astype(np.uint8), mode="L"
        ).save(out_dir / "disparity.png")
        print(f"{scene:22s} {gw}x{gh}  视差 p5-p95 "
              f"{np.percentile(disparity, 5) * DISP_SCALE:.3f}–"
              f"{np.percentile(disparity, 95) * DISP_SCALE:.3f}")


if __name__ == "__main__":
    main()
