#!/usr/bin/env python
"""在遮挡带掩膜上跑 Moebius（ECCV 2026，0.226B）补全，作为 build_hidden_layer 的一个后端。

以子进程方式调用，避免把 Moebius 仓库的顶层 `utils.py` / `utils_infer.py` 混进
主管线的导入空间。调用方需把 PYTHONPATH 指到 Moebius 仓库根。

## 为什么只能按 512 分块

读代码确认（`model_lib/nets/layers/λ/vanillaλ.py`）：
- 自注意力 `MQSλ` 传了 `r=15` ⇒ `local_contexts=True`，走 `pos_conv` 卷积，**形状无关**；
- 交叉注意力 `MQCλ` 没有 `r` ⇒ 走全局分支，位置编码是
  `nn.Parameter(torch.randn(n*n, m, dim_k, dim_u))`，**n 就是 sample_size，被硬锁**。

因此模型只接受它构建时的那个方形分辨率（`image_size=512` ⇒ latent 64×64）。
整幅 540×720 送进去会在第一层 λ 就崩（√6912 不是整数）。
结论：**只能 512×512 逐块 1:1 推理**——正好与 LaMa 的 `inpaint_onnx_tiled` 同规格
（tile 512 / overlap 128 / Hanning 窗 / 跳过无掩膜块），A/B 单变量成立。

模式：
  tiled   —— 上述 1:1 分块，默认。遮挡带 67.5% 宽度 ≤8 px，任何下采样都直接吃掉它。
  square  —— 整幅方形反射填充后缩到 512 推理再放大。作为"分辨率到底重不重要"的对照。

掩膜约定与 LaMa 一致：白（255）= 要补的洞。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image


def build_pipe(config: Path, weight: Path, vae_dir: Path, device: str):
    from removal.v1_2.pipeline import RemovalSDXLPipeline_BatchMode as Pipeline
    from removal.v1_2 import build_removal_model, load_cfg, load_removal_model
    from diffusers import AutoencoderKL, DDIMScheduler

    model_cfg = load_cfg(str(config))
    removal_model = build_removal_model(model_cfg, 20).to(device)
    print(load_removal_model(removal_model, str(weight), device))
    # 不导入 Moebius 的 utils_train：它会拉起 library.train_util → orjson 等一整条
    # 训练期依赖。build_vae 实际只有一行，直接内联。
    # 配置里的 VAE 路径是相对 Moebius 仓库根的 `./weight/vae`，而本脚本从主仓库根调用，
    # 因此改成显式绝对路径。
    vae = AutoencoderKL.from_pretrained(str(vae_dir)).to(device)
    scheduler = DDIMScheduler(
        beta_start=0.00085, beta_end=0.012, beta_schedule="scaled_linear",
        num_train_timesteps=1000, clip_sample=False)
    return Pipeline(removal_model=removal_model, vae=vae, scheduler=scheduler,
                    device=device, dtype=torch.float)


def run_batch(pipe, patches, masks, args) -> list[np.ndarray]:
    out = pipe(
        [Image.fromarray(p) for p in patches],
        [Image.fromarray(m) for m in masks],
        image_size=512,
        num_steps=args.steps,
        guidance_scale=args.cfg,
        noise_offset=args.noise_offset,
        retry=args.retry,
        # paste/compensate 一律关掉：合成由主管线统一做（洞外必须逐像素等于原图，D133 纪律），
        # 管线自带的高斯羽化粘贴会在带边引入第二套混合规则，破坏 A/B 的单变量。
        paste=False, compensate=False,
        mute=True,
    )
    return [np.asarray(o).astype(np.float32) for o in out]


def drift_fix(out: np.ndarray, orig: np.ndarray, hole: np.ndarray, sigma: float) -> np.ndarray:
    """扣掉潜空间扩散的 VAE 往返漂移。

    实测 Moebius 在**未遮挡区**的还原只有 26.7 dB／平均 7.4 级误差，而 Big-LaMa 是
    138 dB／0.00 级——因为前者把整幅图编码进潜空间再解码，后者是回归模型、洞外原样
    透传。这意味着补出来的内容是与"模型自己那版画面"自洽，而不是与它必须接上的原始
    像素自洽，接缝就是这么产生的。

    做法：在未遮挡区量出逐像素误差，用大尺度高斯把它平滑成**低频漂移场**（洞内由
    周围加权外推），再加回输出。只改低频，不动模型补出来的结构。
    """
    err = (orig - out).astype(np.float32)
    w = (~hole).astype(np.float32)
    err = err * w[..., None]
    k = (0, 0)
    num = cv2.GaussianBlur(err, k, sigma)
    den = cv2.GaussianBlur(w, k, sigma)
    field = num / np.maximum(den, 1e-6)[..., None]
    return out + field


def infer_tiled(pipe, image: np.ndarray, hole: np.ndarray, args,
                tile: int = 512, overlap: int = 128) -> np.ndarray:
    h, w = image.shape[:2]
    stride = tile - overlap
    pad_r = max(0, -(-max(w - tile, 0) // stride) * stride + tile - w) if w > tile else tile - w
    pad_b = max(0, -(-max(h - tile, 0) // stride) * stride + tile - h) if h > tile else tile - h
    padded = np.pad(image.astype(np.float32), ((0, pad_b), (0, pad_r), (0, 0)), mode="reflect")
    padded_mask = np.pad(hole.astype(np.uint8), ((0, pad_b), (0, pad_r)))
    ph, pw = padded.shape[:2]

    ramp = np.hanning(overlap * 2)[:overlap]
    win1d = np.ones(tile, dtype=np.float32)
    win1d[:overlap] = ramp
    win1d[-overlap:] = ramp[::-1]
    window = np.outer(win1d, win1d).astype(np.float32)

    coords, patches, masks = [], [], []
    for y in range(0, max(ph - tile, 0) + 1, stride):
        for x in range(0, max(pw - tile, 0) + 1, stride):
            m = padded_mask[y:y + tile, x:x + tile]
            if m.sum() == 0:
                continue
            coords.append((y, x))
            patches.append(padded[y:y + tile, x:x + tile].astype(np.uint8))
            masks.append((m * 255).astype(np.uint8))

    accum = np.zeros((ph, pw, 3), np.float32)
    weight = np.zeros((ph, pw), np.float32)
    for i in range(0, len(coords), args.batch):
        outs = run_batch(pipe, patches[i:i + args.batch], masks[i:i + args.batch], args)
        for (y, x), out in zip(coords[i:i + args.batch], outs):
            accum[y:y + tile, x:x + tile] += np.clip(out, 0, 255) * window[..., None]
            weight[y:y + tile, x:x + tile] += window
        print(f"  已补 {min(i + args.batch, len(coords))}/{len(coords)} 块")
    print(f"  分块补全：{len(coords)} 块（{tile}px，重叠 {overlap}）")
    filled = np.where(weight[..., None] > 1e-6, accum / np.maximum(weight, 1e-6)[..., None], padded)
    return filled[:h, :w]


def infer_square(pipe, image: np.ndarray, hole: np.ndarray, args) -> np.ndarray:
    h, w = image.shape[:2]
    side = max(w, h)
    pad_l, pad_t = (side - w) // 2, (side - h) // 2
    padded = np.pad(image, ((pad_t, side - h - pad_t), (pad_l, side - w - pad_l), (0, 0)), mode="reflect")
    padded_mask = np.pad(hole.astype(np.uint8), ((pad_t, side - h - pad_t), (pad_l, side - w - pad_l)))
    scaled = cv2.resize(padded, (512, 512), interpolation=cv2.INTER_AREA)
    scaled_mask = cv2.resize(padded_mask, (512, 512), interpolation=cv2.INTER_NEAREST)
    out = run_batch(pipe, [scaled], [(scaled_mask * 255).astype(np.uint8)], args)[0]
    restored = cv2.resize(out, (side, side), interpolation=cv2.INTER_LINEAR)
    return restored[pad_t:pad_t + h, pad_l:pad_l + w]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--mask", type=Path, required=True, help="白=洞")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--config", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/config/model_cfg/moebius.yaml"))
    ap.add_argument("--weight", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/weight/Moebius/ft_places2/"
                                 "diffusion_pytorch_model.bin"))
    ap.add_argument("--vae", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/weight/vae"))
    ap.add_argument("--mode", choices=("tiled", "square"), default="tiled")
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--cfg", type=float, default=2.0)
    ap.add_argument("--noise-offset", type=float, default=0.0357)
    ap.add_argument("--retry", type=int, default=0, help="管线内部的随机种子档位")
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--drift-sigma", type=float, default=24.0,
                    help="VAE 往返漂移校正的高斯尺度（0=关）")
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()

    image = np.asarray(Image.open(args.image).convert("RGB"))
    mask = (np.asarray(Image.open(args.mask).convert("L")) > 127).astype(np.uint8)
    h, w = image.shape[:2]
    assert mask.shape[:2] == (h, w), f"掩膜尺寸 {mask.shape[:2]} 与图像 {(h, w)} 不一致"

    pipe = build_pipe(args.config, args.weight, args.vae, args.device)
    if args.mode == "tiled":
        arr = infer_tiled(pipe, image, mask, args)
    else:
        arr = infer_square(pipe, image, mask, args)
    if args.drift_sigma > 0:
        before = float(np.abs(arr[~mask.astype(bool)] - image[~mask.astype(bool)]).mean())
        arr = drift_fix(arr, image.astype(np.float32), mask.astype(bool), args.drift_sigma)
        after = float(np.abs(arr[~mask.astype(bool)] - image[~mask.astype(bool)]).mean())
        print(f"  漂移校正 σ={args.drift_sigma}：未遮挡区误差 {before:.2f} -> {after:.2f} 级")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(args.out)
    print(f"补全完成 {args.mode} {arr.shape[1]}x{arr.shape[0]} -> {args.out}")


if __name__ == "__main__":
    main()
