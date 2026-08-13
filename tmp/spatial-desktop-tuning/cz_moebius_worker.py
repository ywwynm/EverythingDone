#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Moebius 批量补全工作进程：**模型只加载一次**，把 npz 里的所有 512² 作业跑完。

`build_hidden_layer.inpaint_moebius` 是"一次调用 = 一次进程 = 一次加载模型"，用来跑
crop-zoom 的扫描（档位 × 膨胀 × CFG × 5 种子 × 九场景）会把绝大部分时间花在反复加载
0.226B 权重上。这里把作业攒成一个 npz 一次跑完。

种子纪律：pipeline 的 `__call__` 里是 `seed = 0 if retry == 0 else retry` 后
`torch.manual_seed(seed)`，随后对**整个 batch** 一起 `randn_like`。因此
**batch 必须固定为 1**，否则"同一种子"在窗数不同的两个档位之间根本不成立。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch
from PIL import Image


def build_pipe(config: Path, weight: Path, vae_dir: Path, device: str):
    from removal.v1_2.pipeline import RemovalSDXLPipeline_BatchMode as Pipeline
    from removal.v1_2 import build_removal_model, load_cfg, load_removal_model
    from diffusers import AutoencoderKL, DDIMScheduler

    model_cfg = load_cfg(str(config))
    model = build_removal_model(model_cfg, 20).to(device)
    load_removal_model(model, str(weight), device)
    vae = AutoencoderKL.from_pretrained(str(vae_dir)).to(device)
    scheduler = DDIMScheduler(beta_start=0.00085, beta_end=0.012,
                              beta_schedule="scaled_linear",
                              num_train_timesteps=1000, clip_sample=False)
    return Pipeline(removal_model=model, vae=vae, scheduler=scheduler,
                    device=device, dtype=torch.float)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--cfg", type=float, default=2.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--noise-offset", type=float, default=0.0357)
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--config", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/config/model_cfg/moebius.yaml"))
    ap.add_argument("--weight", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/weight/Moebius/ft_places2/"
                                 "diffusion_pytorch_model.bin"))
    ap.add_argument("--vae", type=Path,
                    default=Path("E:/projects/EverythingDone/tmp/Moebius/weight/vae"))
    args = ap.parse_args()

    d = np.load(args.inp)
    patches, masks = d["patches"], d["masks"]
    n = len(patches)
    # 逐作业的 CFG / 种子 / 步数（可选）：整个扫描（档位 × 膨胀 × CFG × 5 种子 × 九场景）
    # 因此只加载一次权重。缺省则全体沿用命令行上那一组。
    cfgs = d["cfgs"] if "cfgs" in d else np.full(n, args.cfg, np.float32)
    seeds = d["seeds"] if "seeds" in d else np.full(n, args.seed, np.int64)
    steps = d["steps"] if "steps" in d else np.full(n, args.steps, np.int64)
    # `groups` 把作业分成"同一次运行"的段（同 cfg / 同种子 / 同档位，作业顺序也相同）。
    # 段内可以放心批处理：pipeline 每次 __call__ 只 manual_seed 一次、再对整个 batch
    # 一起 randn_like，所以逐窗噪声取决于 (batch 组成, 位置)。同一段在不同配置下作业
    # 列表逐个相同、顺序相同、batch 大小相同 ⇒ 每个窗拿到的噪声跨配置一致，单变量成立。
    # 缺省整批当一段（等价于旧的 batch=1 行为，因为那时逐作业 cfg 可能不同）。
    groups = d["groups"] if "groups" in d else np.arange(n)
    pipe = build_pipe(args.config, args.weight, args.vae, args.device)

    outs: list = [None] * n
    bounds = [0] + [i for i in range(1, n) if groups[i] != groups[i - 1]] + [n]
    done = 0
    for g0, g1 in zip(bounds[:-1], bounds[1:]):
        for b0 in range(g0, g1, args.batch):
            b1 = min(b0 + args.batch, g1)
            # paste/compensate 一律关：合成由主管线统一做（洞外必须逐像素等于原图，D133），
            # 管线自带的高斯羽化粘贴会引入第二套混合规则，破坏 A/B 的单变量。
            res = pipe([Image.fromarray(patches[i]) for i in range(b0, b1)],
                       [Image.fromarray((masks[i] * 255).astype(np.uint8))
                        for i in range(b0, b1)],
                       image_size=512, num_steps=int(steps[b0]),
                       guidance_scale=float(cfgs[b0]),
                       noise_offset=args.noise_offset, retry=int(seeds[b0]),
                       paste=False, compensate=False, mute=True)
            for i, o in zip(range(b0, b1), res):
                outs[i] = np.asarray(o).astype(np.uint8)
            done += b1 - b0
            if done % 50 < args.batch or done == n:
                print(f"  Moebius {done}/{n}", flush=True)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    np.savez(args.out, outputs=np.stack(outs))


if __name__ == "__main__":
    main()
