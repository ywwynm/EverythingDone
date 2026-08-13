#!/usr/bin/env python
"""在带真值的测试掩膜上跑各后端，产出供 band_gt_eval 打分的候选图。

复用 build_hidden_layer 里的补全函数，保证与产线走同一条代码路径——否则比出来的
是两套实现的差别，不是模型的差别（AOT-GAN 那次 −58.8 就是契约没对齐，D151 附注）。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from build_hidden_layer import inpaint_moebius, inpaint_onnx, inpaint_onnx_tiled  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--mask", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--only", nargs="*", default=None)
    args = ap.parse_args()

    import cv2
    image = np.asarray(Image.open(args.image).convert("RGB"))
    mask = np.asarray(Image.open(args.mask).convert("L")) > 127
    args.out.mkdir(parents=True, exist_ok=True)
    lama = Path("build/spatial-model-poc/artifacts/big_lama_places2_512_fp32.onnx")
    aotgan = Path("build/spatial-model-poc/artifacts/aotgan_places2_512.onnx")

    def dil(m, r):
        return cv2.dilate(m.astype(np.uint8), np.ones((2 * r + 1, 2 * r + 1), np.uint8)) > 0

    jobs = {
        "telea":        lambda m: cv2.inpaint(image, m.astype(np.uint8), 5, cv2.INPAINT_TELEA).astype(np.float32),
        "lama_tiled":   lambda m: inpaint_onnx_tiled(image, m, lama),
        "lama_tiled_d8": lambda m: inpaint_onnx_tiled(image, dil(m, 8), lama),
        "aotgan":       lambda m: inpaint_onnx(image, m, aotgan),
        "moebius_c2":   lambda m: inpaint_moebius(image, m, "tiled", 20, 2.0, 0),
        "moebius_c8":   lambda m: inpaint_moebius(image, m, "tiled", 20, 8.0, 0),
        "moebius_c12":  lambda m: inpaint_moebius(image, m, "tiled", 20, 12.0, 0),
        "moebius_c16":  lambda m: inpaint_moebius(image, m, "tiled", 20, 16.0, 0),
        "moebius_c12_d4":  lambda m: inpaint_moebius(image, dil(m, 4), "tiled", 20, 12.0, 0),
        "moebius_c12_d8":  lambda m: inpaint_moebius(image, dil(m, 8), "tiled", 20, 12.0, 0),
        "moebius_c12_d16": lambda m: inpaint_moebius(image, dil(m, 16), "tiled", 20, 12.0, 0),
        # 漂移校正后的同一批：把 VAE 往返造成的低频偏移扣掉，才是模型补全能力的净成绩
        "moebius_c2_df":  lambda m: inpaint_moebius(image, m, "tiled", 20, 2.0, 24),
        "moebius_c8_df":  lambda m: inpaint_moebius(image, m, "tiled", 20, 8.0, 24),
        "moebius_c2_df8": lambda m: inpaint_moebius(image, m, "tiled", 20, 2.0, 8),
    }
    # 细节移植：LaMa 结构对但偏糊（HF 0.78），Moebius 纹理能量对但结构错（HF 1.05）。
    # 取 LaMa 的低频 + Moebius 的高频。对照组 lama_sharp 用非锐化掩蔽做同等强度的
    # 提频——若两者打平，说明增益只来自"变锐"，与 Moebius 补出来的内容无关。
    def hybrid(sigma, gain):
        def f(m):
            a = np.asarray(Image.open(args.out / "lama_tiled.png").convert("RGB")).astype(np.float32)
            b = np.asarray(Image.open(args.out / "moebius_c2.png").convert("RGB")).astype(np.float32)
            hi = b - cv2.GaussianBlur(b, (0, 0), sigma)
            return a + gain * hi
        return f

    def sharpen(sigma, gain):
        def f(m):
            a = np.asarray(Image.open(args.out / "lama_tiled.png").convert("RGB")).astype(np.float32)
            return a + gain * (a - cv2.GaussianBlur(a, (0, 0), sigma))
        return f

    jobs["hyb_s2_g10"] = hybrid(2.0, 1.0)
    jobs["hyb_s2_g05"] = hybrid(2.0, 0.5)
    jobs["hyb_s4_g10"] = hybrid(4.0, 1.0)
    jobs["lama_sharp_g10"] = sharpen(2.0, 1.0)
    jobs["lama_sharp_g05"] = sharpen(2.0, 0.5)

    for name, fn in jobs.items():
        if args.only and name not in args.only:
            continue
        out = fn(mask)
        # 与产线同纪律：洞外必须逐像素等于原图，只有洞内算模型的成绩
        out = np.where(mask[..., None], out, image.astype(np.float32))
        Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(args.out / f"{name}.png")
        print(f"  {name} 完成")


if __name__ == "__main__":
    main()
