#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""MI-GAN（Android 的默认补全模型）接进带真值台。

Android 上线的是 MI-GAN 与 AOT-GAN，**没有 Big-LaMa**；而桌面 `_d45` 用的是
`lama_tiled`。D160 的带真值台上 AOT-GAN 是 9.14 dB / 梯度相关 0.088，惨败给 LaMa 的
22.03 / 0.604，但 **MI-GAN 从未在这个台子上量过**。移植 `_d45` 之前必须先知道它够不够用。

推理契约照抄 Android 的 `SpatialInpaintingEngine.runUint8Pipeline`，逐位对齐：

- `image`  uint8 [1,3,H,W]，**通道顺序 RGB**（Android 从 ARGB_8888 按 16/8/0 位移取）；
- `mask`   uint8 [1,1,H,W]，**0 = 要补的洞、255 = 已知**（与 LaMa 相反，别弄反）；
- 输出   uint8 [1,3,H,W]，RGB。

模型自带 pipeline（v2 导出把预处理/后处理都包了进去），因此**不需要外部缩放到 512**；
但它内部是 512 契约，这里同样按 512/重叠 128 做 1:1 分块，与 `inpaint_onnx_tiled`
同规格，A/B 单变量成立。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

MIGAN_ONNX = Path("E:/projects/EverythingDone/build/spatial-ldi-lite-poc/migan_pipeline_v2.onnx")


def _session(model: Path):
    import os
    import torch
    lib = os.path.join(os.path.dirname(torch.__file__), "lib")
    if os.path.isdir(lib):
        try:
            os.add_dll_directory(lib)
        except (OSError, AttributeError):
            pass
    import onnxruntime as ort
    want = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider")
            if p in ort.get_available_providers()]
    return ort.InferenceSession(str(model), providers=want or ["CPUExecutionProvider"])


def infer_tile(sess, patch: np.ndarray, hole: np.ndarray) -> np.ndarray:
    """patch RGB uint8 [S,S,3]，hole bool [S,S]（True=洞）。"""
    names = [i.name for i in sess.get_inputs()]
    img = patch.astype(np.uint8).transpose(2, 0, 1)[None]              # [1,3,S,S] RGB
    msk = np.where(hole, 0, 255).astype(np.uint8)[None, None]          # 0=洞
    out = sess.run(None, {names[0]: img, names[1]: msk})[0]
    out = np.asarray(out)
    if out.ndim == 4:
        out = out[0]
    if out.shape[0] in (1, 3):
        out = out.transpose(1, 2, 0)
    if out.dtype != np.uint8:
        out = np.clip(out if out.max() > 1.5 else out * 255.0, 0, 255).astype(np.uint8)
    return out.astype(np.float32)


def inpaint_tiled(image: np.ndarray, hole: np.ndarray, model: Path = MIGAN_ONNX,
                  tile: int = 512, overlap: int = 128) -> np.ndarray:
    sess = _session(model)
    h, w = image.shape[:2]
    stride = tile - overlap
    pad_r = (tile - w) if w <= tile else (-(-max(w - tile, 0) // stride) * stride + tile - w)
    pad_b = (tile - h) if h <= tile else (-(-max(h - tile, 0) // stride) * stride + tile - h)
    img_p = np.pad(image, ((0, max(pad_b, 0)), (0, max(pad_r, 0)), (0, 0)), mode="reflect")
    hole_p = np.pad(hole.astype(np.uint8), ((0, max(pad_b, 0)), (0, max(pad_r, 0)))) > 0
    ph, pw = img_p.shape[:2]

    ramp = np.hanning(overlap * 2)[:overlap]
    v = np.ones(tile, np.float32)
    v[:overlap] = ramp
    v[-overlap:] = ramp[::-1]
    win = np.outer(v, v).astype(np.float32)

    acc = np.zeros((ph, pw, 3), np.float32)
    wgt = np.zeros((ph, pw), np.float32)
    used = 0
    for y in range(0, max(ph - tile, 0) + 1, stride):
        for x in range(0, max(pw - tile, 0) + 1, stride):
            m = hole_p[y:y + tile, x:x + tile]
            if not m.any():
                continue
            used += 1
            o = infer_tile(sess, img_p[y:y + tile, x:x + tile], m)
            acc[y:y + tile, x:x + tile] += o * win[..., None]
            wgt[y:y + tile, x:x + tile] += win
    print(f"    MI-GAN 分块补全：{used} 块（{tile}px，重叠 {overlap}）")
    filled = np.where(wgt[..., None] > 1e-6, acc / np.maximum(wgt, 1e-6)[..., None], img_p)
    return filled[:h, :w]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--hole", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--model", type=Path, default=MIGAN_ONNX)
    args = ap.parse_args()
    image = np.asarray(Image.open(args.image).convert("RGB"))
    hole = np.asarray(Image.open(args.hole).convert("L")) > 127
    out = inpaint_tiled(image, hole, args.model)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(args.out)
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
