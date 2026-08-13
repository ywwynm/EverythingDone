# -*- coding: utf-8 -*-
"""扩散补图 A/B（D130 主线 #9）：SD2-inpainting vs Big-LaMa 在 plate 洞上的对照。

洞 = 当前资产的 matte 支撑域（核心 ∪ 软裙，dilate5），与管线 plate 替换域一致。
通用提示词试三档（用户照片场景任意，不能依赖手写场景描述）：
  A 空提示；B "background scene, nobody"；C 加负面提示 person/face/hands。
输出 qa/diffusion-ab/<scene>.jpg：原图 | LaMa plate（现行） | SD2 三档。
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
import torch

ROOT = Path(r"E:\projects\EverythingDone\tmp\spatial-desktop-tuning")
MODEL_DIR = r"E:\projects\EverythingDone\tmp\sdxl-inpainting"
WORK = 1024  # SDXL 原生分辨率


def build_hole(scene_dir: Path) -> tuple[np.ndarray, np.ndarray]:
    center = cv2.cvtColor(cv2.imread(str(scene_dir / "center.jpg")), cv2.COLOR_BGR2RGB)
    matte = cv2.imread(str(scene_dir / "matte.png"), cv2.IMREAD_GRAYSCALE)
    matte = cv2.resize(matte, (center.shape[1], center.shape[0]),
                       interpolation=cv2.INTER_LINEAR)
    support = ((matte > 15).astype(np.uint8))  # 软裙 + 核心
    support = cv2.dilate(support, np.ones((7, 7), np.uint8))
    return center, support


def pad_square(img: np.ndarray) -> tuple[np.ndarray, tuple[int, int, int]]:
    h, w = img.shape[:2]
    side = max(h, w)
    pt, pl = (side - h) // 2, (side - w) // 2
    if img.ndim == 3:
        out = np.pad(img, ((pt, side - h - pt), (pl, side - w - pl), (0, 0)), mode="reflect")
    else:
        out = np.pad(img, ((pt, side - h - pt), (pl, side - w - pl)), mode="constant")
    return out, (pt, pl, side)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", type=str, default="00_original_single")
    parser.add_argument("--steps", type=int, default=30)
    args = parser.parse_args()

    from diffusers import AutoPipelineForInpainting

    pipe = AutoPipelineForInpainting.from_pretrained(
        MODEL_DIR, torch_dtype=torch.float16, variant="fp16"
    ).to("cuda")
    pipe.set_progress_bar_config(disable=True)

    out_dir = ROOT / "qa" / "diffusion-ab"
    out_dir.mkdir(parents=True, exist_ok=True)

    variants = [
        ("empty", "", None),
        ("bg", "background scene, nobody, empty", None),
        ("bg-neg", "background scene, nobody, empty",
         "person, human, face, hands, body, animal"),
    ]

    for scene in args.scenes.split(","):
        scene = scene.strip()
        scene_dir = ROOT / "assets" / scene
        center, hole = build_hole(scene_dir)
        h, w = center.shape[:2]
        if hole.sum() < 500:
            print(f"{scene}: empty matte, skip")
            continue
        img_sq, (pt, pl, side) = pad_square(center)
        hole_sq, _ = pad_square(hole)
        img_work = cv2.resize(img_sq, (WORK, WORK), interpolation=cv2.INTER_AREA)
        hole_work = cv2.resize(hole_sq, (WORK, WORK), interpolation=cv2.INTER_NEAREST)

        from PIL import Image
        pil_img = Image.fromarray(img_work)
        pil_mask = Image.fromarray((hole_work * 255).astype(np.uint8))

        panels = [center]
        plate_raw = cv2.imread(str(scene_dir / "plate.jpg"))
        plate = (cv2.cvtColor(plate_raw, cv2.COLOR_BGR2RGB)
                 if plate_raw is not None else center)
        panels.append(cv2.resize(plate, (w, h)))
        generator = torch.Generator("cuda").manual_seed(20260808)
        for name, prompt, negative in variants:
            result = pipe(
                prompt=prompt, negative_prompt=negative,
                image=pil_img, mask_image=pil_mask,
                num_inference_steps=args.steps, generator=generator,
            ).images[0]
            filled_sq = cv2.resize(np.asarray(result), (side, side),
                                   interpolation=cv2.INTER_LINEAR)
            filled = filled_sq[pt : pt + h, pl : pl + w]
            composite = np.where(hole[..., None] > 0, filled, center)
            panels.append(composite)

        strip = np.concatenate(
            [cv2.resize(p, (400, int(400 * h / w))) for p in panels], axis=1
        )
        labels = ["source", "lama-now"] + [v[0] for v in variants]
        strip = cv2.cvtColor(strip, cv2.COLOR_RGB2BGR)
        for i, label in enumerate(labels):
            cv2.putText(strip, label, (400 * i + 8, 24),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
        cv2.imwrite(str(out_dir / f"{scene}.jpg"), strip)
        print(f"{scene}: done")
    print("->", out_dir)


if __name__ == "__main__":
    main()
