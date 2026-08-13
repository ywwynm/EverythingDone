#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""桌面调优资产生成器（D124 / Task #10）。

单场景流程：
  原图 → reflect_square(256) → OVIE 零位 + K 个外圈 keyview（半径 r，xyzw 单位四元数）
      → 裁回原图宽高比 → NeuFlow 双向对应（flow: target→zero，逆向采样场）
      → 全局相似（scale/rotation）移除 → 周期 Fourier 系数拟合（harmonics 阶）
      → 资产包：center.jpg / zero.jpg / keys.jpg / conf.png / flow_coeffs.bin / meta.json

网页查看器按 compose_detail 的 direct 语义在 GPU 复刻：
  flow(x,θ,f) = f · Σ coeffs·basis(θ)（guide 像素单位，采样原图前按分辨率比例放大）
  alpha = clamp(conf(θ,f),0,1)^confidencePower
  out = center(x + flow·scale) · alpha + fallback(x) · (1-alpha)
  fallback = zero·(1-f) + keyBlend(θ)·f；f=0 时强制原图直通。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=Path("tmp/spatial-desktop-tuning/corpus"))
    parser.add_argument("--output", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    parser.add_argument("--ovie-repo", type=Path, default=Path("tmp/ovie-research"))
    parser.add_argument("--ovie-model", type=Path, default=Path("tmp/ovie-model-v1.0"))
    parser.add_argument("--neuflow-repo", type=Path, default=Path("tmp/NeuFlow_v2"))
    parser.add_argument(
        "--neuflow-checkpoint", type=Path, default=Path("tmp/NeuFlow_v2/neuflow_mixed.pth")
    )
    parser.add_argument("--radius", type=float, default=0.05)
    parser.add_argument("--key-count", type=int, default=16)
    parser.add_argument("--harmonics", type=int, default=1)
    parser.add_argument("--flow-size", type=int, default=512, help="NeuFlow 推理边长（16 的倍数）")
    parser.add_argument("--long-edge", type=int, default=720, help="center.jpg 长边")
    parser.add_argument("--canvas", type=int, default=256, help="OVIE 画布边长")
    parser.add_argument("--photo-error-scale", type=float, default=0.30)
    parser.add_argument("--guide-upscale", type=int, default=2, help="流场网格相对 OVIE 输出的放大倍数")
    parser.add_argument("--depth-model", type=Path,
                        default=Path("tmp/new-device-spatial/files/da3_small_mono_518.onnx"))
    parser.add_argument("--matting-model", type=Path,
                        default=Path("tmp/birefnet-lite/onnx/model.onnx"))
    parser.add_argument("--matting-backend", type=str, default="birefnet",
                        choices=("birefnet", "modnet"),
                        help="birefnet：1024² ImageNet 归一化、输出 logits 过 sigmoid；"
                             "modnet：长边 512、(x-0.5)/0.5")
    parser.add_argument("--inpaint-model", type=Path,
                        default=Path("build/spatial-model-poc/artifacts/big_lama_places2_512_fp32.onnx"),
                        help="底板被遮内部的补全模型（Big-LaMa 512 契约）；缺失时退回涂抹填充")
    parser.add_argument("--inpaint-backend", type=str, default="sdxl",
                        choices=("sdxl", "lama"),
                        help="sdxl：SDXL-inpainting 1024 全支撑域补全（D132，5090 GPU）；"
                             "lama：Big-LaMa 512 旧后端（A/B 用）")
    parser.add_argument("--sdxl-dir", type=Path,
                        default=Path("tmp/sdxl-inpainting"))
    parser.add_argument("--sdxl-steps", type=int, default=30)
    parser.add_argument("--sdxl-prompt", type=str,
                        default="background scene, nobody, empty",
                        help="通用背景提示词（A/B 三档中最干净的一档）")
    parser.add_argument("--matting-alpha-low", type=float, default=0.35,
                        help="alpha 高于该值的像素才接受主体深度抬升")
    parser.add_argument("--subject-relief", type=float, default=0.20,
                        help="主体内部深度起伏幅度（归一视差单位，全场 p5-p95≈1.0）。"
                             "0 = 退回旧的恒定平台（纸片人）。D133")
    parser.add_argument("--plate-seamless", dest="plate_seamless",
                        action="store_true", default=True,
                        help="底板补全做梯度域膜校正 + 颗粒匹配（D133，默认开）")
    parser.add_argument("--no-plate-seamless", dest="plate_seamless",
                        action="store_false")
    parser.add_argument("--depth-span-px720", type=float, default=22.0,
                        help="深度骨架场的 p5–p95 目标视差（px@720）")
    parser.add_argument("--target-span-px720", type=float, default=20.0,
                        help="成品最终跨度目标（2026-08-08 Apple 量化：iPhone 锁屏"
                             "≈18–25px@720/倾斜、visionOS 展示级 43；不足者按增益补足）")
    parser.add_argument("--span-gain-max", type=float, default=2.5,
                        help="跨度增益上限（防止退化场被无脑放大；应变预算随后仍按方向压制）")
    parser.add_argument("--depth-sign", type=float, default=1.0,
                        help="OVIE 相关性无法判定时使用的深度视差符号（00 场景实测 corr +0.84 → +1）")
    parser.add_argument("--residual-low", type=float, default=0.45, help="OVIE 残差渐入下沿（置信度）")
    parser.add_argument("--residual-high", type=float, default=0.75, help="OVIE 残差完整保留上沿")
    parser.add_argument("--ovie-gate-low", type=float, default=6.0,
                        help="OVIE 信任跨度低于此值（px@720）时完全回退深度场")
    parser.add_argument("--ovie-gate-high", type=float, default=12.0)
    parser.add_argument("--cut-grad-threshold", type=float, default=0.04,
                        help="归一化视差梯度超过该值视为断边（豁免应变预算）")
    parser.add_argument("--strain-budget", type=float, default=0.18,
                        help="旧 reference 诊断场的残差条件化预算；不再决定成品安全场")
    parser.add_argument(
        "--safe-field-mode",
        choices=("depth-plane", "legacy-reference"),
        default="legacy-reference",
        help="legacy-reference：用户要求回退的 v8 基线；depth-plane：已被用户否决的 D135，仅供回归 A/B",
    )
    parser.add_argument(
        "--safe-blur-sigma",
        type=float,
        default=16.0,
        help="成品连续场在 guide 网格上的高斯正则半径；抑制深度断边造成的撕裂和色带",
    )
    parser.add_argument(
        "--safe-field-source",
        choices=("reference", "front"),
        default="reference",
        help="仅 legacy-reference 模式使用：旧安全场来源",
    )
    parser.add_argument(
        "--safe-depth-sigma-ratio",
        type=float,
        default=0.375,
        help="D135 深度场高斯 σ 相对 guide 长边的比例",
    )
    parser.add_argument(
        "--safe-depth-plane-residual",
        type=float,
        default=0.25,
        help="D135 最佳仿射深度面之外保留的平滑深度残差比例",
    )
    parser.add_argument("--safe-total-non-sim-limit", type=float, default=0.03)
    parser.add_argument("--safe-total-scale-limit", type=float, default=0.03)
    parser.add_argument("--safe-rubber-limit", type=float, default=0.008)
    parser.add_argument("--safe-shape-directions", type=int, default=48)
    parser.add_argument("--reveal-div0", type=float, default=0.5, help="查看器显露带散度下沿")
    parser.add_argument("--reveal-div1", type=float, default=1.5, help="查看器显露带散度上沿")
    parser.add_argument("--envelope-directions", type=int, default=16)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--scenes", type=str, default="", help="逗号分隔的场景名过滤")
    parser.add_argument("--keep-global-similarity", action="store_true")
    parser.add_argument("--debug-dump", action="store_true",
                        help="纯插桩：落盘中间信号（不改变任何计算）")
    return parser.parse_args()


def deformation_percentiles(
    field: np.ndarray, mask: np.ndarray
) -> tuple[float, float, float]:
    """返回 source mapping 的 p99 非相似形变、p99 局部缩放与折返占比。"""
    du_dx = np.gradient(field[..., 0], axis=1)
    du_dy = np.gradient(field[..., 0], axis=0)
    dv_dx = np.gradient(field[..., 1], axis=1)
    dv_dy = np.gradient(field[..., 1], axis=0)
    a, b = 1.0 + du_dx, du_dy
    c, d = dv_dx, 1.0 + dv_dy
    determinant = a * d - b * c
    c00 = a * a + c * c
    c01 = a * b + c * d
    c11 = b * b + d * d
    trace = c00 + c11
    discriminant = np.sqrt(np.maximum((c00 - c11) ** 2 + 4.0 * c01 * c01, 0.0))
    sigma_max = np.sqrt(np.maximum(0.5 * (trace + discriminant), 1e-12))
    sigma_min = np.sqrt(np.maximum(0.5 * (trace - discriminant), 1e-12))
    local_scale = np.sqrt(np.maximum(sigma_max * sigma_min, 1e-12))
    non_similarity = np.maximum(
        np.abs(sigma_max / local_scale - 1.0),
        np.abs(sigma_min / local_scale - 1.0),
    )
    return (
        float(np.percentile(non_similarity[mask], 99.0)),
        float(np.percentile(np.abs(local_scale[mask] - 1.0), 99.0)),
        float((determinant[mask] <= 0.0).mean()),
    )


def remove_best_affine(field: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """扣除整幅一致的仿射视角变化，隔离局部橡皮式弯折。"""
    height, width = mask.shape
    yy, xx = np.mgrid[0:height, 0:width]
    design = np.stack(
        [np.ones(mask.sum()), xx[mask], yy[mask]], axis=1
    ).astype(np.float64)
    parameters = np.linalg.lstsq(
        design, field[mask].astype(np.float64), rcond=None
    )[0]
    full_design = np.stack(
        [np.ones(height * width), xx.reshape(-1), yy.reshape(-1)], axis=1
    ).astype(np.float64)
    affine = (full_design @ parameters).reshape(height, width, 2)
    return (field - affine).astype(np.float32)


def safe_field_shape_metrics(
    coeffs: np.ndarray, directions: int, scale: float = 1.0
) -> dict[str, float]:
    height, width = coeffs.shape[1:3]
    mask = np.zeros((height, width), dtype=bool)
    mask[2:-2, 2:-2] = True
    totals: list[tuple[float, float, float]] = []
    rubbers: list[tuple[float, float, float]] = []
    for index in range(directions):
        theta = index * 2.0 * math.pi / directions
        field = coeffs[0].copy()
        order = 1
        coefficient_index = 1
        while coefficient_index + 1 < coeffs.shape[0]:
            field += coeffs[coefficient_index] * math.cos(order * theta)
            field += coeffs[coefficient_index + 1] * math.sin(order * theta)
            coefficient_index += 2
            order += 1
        field *= scale
        totals.append(deformation_percentiles(field, mask))
        rubbers.append(deformation_percentiles(remove_best_affine(field, mask), mask))
    return {
        "totalNonSimilarityP99Max": max(item[0] for item in totals),
        "totalScaleChangeP99Max": max(item[1] for item in totals),
        "rubberNonSimilarityP99Max": max(item[0] for item in rubbers),
        "rubberScaleChangeP99Max": max(item[1] for item in rubbers),
        "foldShareMax": max(item[2] for item in totals),
    }


def build_depth_plane_coefficients(
    disparity: np.ndarray,
    coefficient_count: int,
    px720_scale: float,
    target_span_px720: float,
    depth_sign: float,
    sigma_ratio: float,
    plane_residual: float,
) -> tuple[np.ndarray, float, float]:
    """构造 D135 的一阶圆周安全场。"""
    height, width = disparity.shape
    if coefficient_count < 3:
        raise ValueError("D135 成品场至少需要一阶 Fourier 系数")
    sigma = sigma_ratio * max(width, height)
    smooth_depth = cv2.GaussianBlur(
        disparity,
        (0, 0),
        sigmaX=sigma,
        sigmaY=sigma,
        borderType=cv2.BORDER_REFLECT_101,
    )
    grid_y, grid_x = np.mgrid[0:height, 0:width]
    design = np.stack(
        [
            grid_x.reshape(-1) / max(width - 1, 1),
            grid_y.reshape(-1) / max(height - 1, 1),
            np.ones(width * height, dtype=np.float32),
        ],
        axis=1,
    )
    parameters = np.linalg.lstsq(design, smooth_depth.reshape(-1), rcond=None)[0]
    plane = (design @ parameters).reshape(height, width).astype(np.float32)
    safe_depth = plane + plane_residual * (smooth_depth - plane)
    safe_depth -= float(np.median(safe_depth))
    raw_span = float(
        np.percentile(safe_depth, 95.0) - np.percentile(safe_depth, 5.0)
    )
    depth_scale = (target_span_px720 / px720_scale) / max(raw_span, 1e-6)
    scalar = (depth_sign * safe_depth * depth_scale).astype(np.float32)
    coefficients = np.zeros(
        (coefficient_count, height, width, 2), dtype=np.float32
    )
    coefficients[1, ..., 0] = scalar
    coefficients[2, ..., 1] = scalar
    return coefficients, sigma, raw_span


def calibrate_safe_field(
    coefficients: np.ndarray,
    directions: int,
    total_non_similarity_limit: float,
    total_scale_limit: float,
    rubber_limit: float,
) -> tuple[np.ndarray, float, dict[str, float], dict[str, float]]:
    """用单一全方向倍率校准形变，不引入逐方向幅度呼吸。"""
    before = safe_field_shape_metrics(coefficients, directions)

    def passes(metrics: dict[str, float]) -> bool:
        return (
            metrics["totalNonSimilarityP99Max"] <= total_non_similarity_limit
            and metrics["totalScaleChangeP99Max"] <= total_scale_limit
            and metrics["rubberNonSimilarityP99Max"] <= rubber_limit
            and metrics["rubberScaleChangeP99Max"] <= rubber_limit
            and metrics["foldShareMax"] == 0.0
        )

    scale = 1.0
    if not passes(before):
        low, high = 0.0, 1.0
        for _ in range(14):
            middle = 0.5 * (low + high)
            if passes(safe_field_shape_metrics(coefficients, directions, middle)):
                low = middle
            else:
                high = middle
        scale = low
    calibrated = (coefficients * scale).astype(np.float32)
    after = safe_field_shape_metrics(calibrated, directions)
    return calibrated, scale, before, after


def main() -> None:
    args = parse_args()
    sys.path.insert(0, str(args.ovie_repo.resolve()))

    import torch
    from safetensors.torch import load_file
    from models.models import OVIEModel
    from transfer_source_detail import (
        NeuFlowEstimator,
        crop_guide_to_aspect,
        estimate_correspondence,
        reflect_square,
        resize_long_edge,
    )
    from evaluate_flow_ring import fourier_basis, remove_global_scale_rotation

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    ovie_dtype = torch.bfloat16 if device.type == "cuda" else torch.float32

    config = json.loads((args.ovie_model / "config.json").read_text(encoding="utf-8"))
    model = OVIEModel(**config)
    state = load_file(str(args.ovie_model / "model.safetensors"), device="cpu")
    model.load_state_dict(state, strict=True)
    model.eval().to(device)
    if device.type == "cuda":
        model.to(ovie_dtype)

    estimator = NeuFlowEstimator(args.neuflow_repo, args.neuflow_checkpoint, args.flow_size)

    import onnxruntime as ort

    depth_session = ort.InferenceSession(
        str(args.depth_model), providers=["CPUExecutionProvider"]
    )
    imagenet_mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    imagenet_std = np.array([0.229, 0.224, 0.225], dtype=np.float32)

    def run_depth_disparity(image: np.ndarray, out_size: tuple[int, int]) -> np.ndarray:
        """DA3-small：518² ImageNet 归一化，输出 depth；返回鲁棒归一化视差（p05→0, p95→1）。"""
        resized = cv2.resize(image, (518, 518), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
        tensor = ((resized - imagenet_mean) / imagenet_std).transpose(2, 0, 1)[None]
        depth = depth_session.run(None, {depth_session.get_inputs()[0].name: tensor})[0]
        depth = np.asarray(depth).reshape(518, 518).astype(np.float32)
        disparity = 1.0 / np.maximum(depth, 1e-4)
        disparity = cv2.resize(disparity, out_size, interpolation=cv2.INTER_LINEAR)
        p05, p95 = np.percentile(disparity, (5.0, 95.0))
        normalized = (disparity - p05) / max(p95 - p05, 1e-6)
        normalized = np.clip(normalized, -0.2, 1.4).astype(np.float32)
        # 锐边深度模型适配（D53 同源）：边缘保持双边滤波——保留 >σColor 的真实断层，
        # 抹掉亚阈的高频纹理视差（MONO-LARGE 在细结构场景会产生逐像素抖动流场）。
        return cv2.bilateralFilter(normalized, d=0, sigmaColor=0.08, sigmaSpace=3.0)

    matting_session = ort.InferenceSession(
        str(args.matting_model), providers=["CPUExecutionProvider"]
    )
    inpaint_session = (
        ort.InferenceSession(str(args.inpaint_model), providers=["CPUExecutionProvider"])
        if args.inpaint_model and args.inpaint_model.is_file()
        else None
    )

    def run_inpaint_512(image_rgb: np.ndarray, hole_mask: np.ndarray) -> np.ndarray:
        """Big-LaMa 契约：image [1,3,512,512] 0..1，mask [1,1,512,512] 1=洞，输出 0..255。
        方形反射填充 → 512 推理 → 还原尺寸；只保证 hole 内像素被替换。"""
        height, width = image_rgb.shape[:2]
        side = max(width, height)
        pad_l = (side - width) // 2
        pad_t = (side - height) // 2
        padded = np.pad(
            image_rgb,
            ((pad_t, side - height - pad_t), (pad_l, side - width - pad_l), (0, 0)),
            mode="reflect",
        )
        padded_mask = np.pad(
            hole_mask.astype(np.uint8),
            ((pad_t, side - height - pad_t), (pad_l, side - width - pad_l)),
            mode="constant",
        )
        scaled = cv2.resize(padded, (512, 512), interpolation=cv2.INTER_LINEAR)
        scaled_mask = cv2.resize(padded_mask, (512, 512), interpolation=cv2.INTER_NEAREST)
        out = inpaint_session.run(
            None,
            {
                "image": (scaled.astype(np.float32) / 255.0).transpose(2, 0, 1)[None],
                "mask": scaled_mask[None, None].astype(np.float32),
            },
        )[0][0].transpose(1, 2, 0)
        out = np.clip(out, 0, 255)
        restored = cv2.resize(out, (side, side), interpolation=cv2.INTER_LINEAR)
        return restored[pad_t : pad_t + height, pad_l : pad_l + width]

    sdxl_pipe = None
    if args.inpaint_backend == "sdxl":
        import torch as _torch
        from diffusers import AutoPipelineForInpainting

        sdxl_pipe = AutoPipelineForInpainting.from_pretrained(
            str(args.sdxl_dir), torch_dtype=_torch.float16, variant="fp16"
        ).to("cuda")
        sdxl_pipe.set_progress_bar_config(disable=True)

    def run_inpaint_sdxl(image_rgb: np.ndarray, hole_mask: np.ndarray) -> np.ndarray:
        """SDXL-inpainting（D132）：方形反射填充 → 1024 推理 → 还原尺寸。
        固定种子保证同图重生成确定；提示词为通用背景描述（场景无关）。"""
        import torch as _torch
        from PIL import Image as _Image

        height, width = image_rgb.shape[:2]
        side = max(width, height)
        pad_l = (side - width) // 2
        pad_t = (side - height) // 2
        padded = np.pad(
            image_rgb.astype(np.uint8),
            ((pad_t, side - height - pad_t), (pad_l, side - width - pad_l), (0, 0)),
            mode="reflect",
        )
        padded_mask = np.pad(
            hole_mask.astype(np.uint8),
            ((pad_t, side - height - pad_t), (pad_l, side - width - pad_l)),
            mode="constant",
        )
        scaled = cv2.resize(padded, (1024, 1024), interpolation=cv2.INTER_AREA)
        scaled_mask = cv2.resize(padded_mask, (1024, 1024), interpolation=cv2.INTER_NEAREST)
        generator = _torch.Generator("cuda").manual_seed(20260808)
        result = sdxl_pipe(
            prompt=args.sdxl_prompt,
            image=_Image.fromarray(scaled),
            mask_image=_Image.fromarray((scaled_mask * 255).astype(np.uint8)),
            num_inference_steps=args.sdxl_steps,
            generator=generator,
        ).images[0]
        out = cv2.resize(
            np.asarray(result).astype(np.float32), (side, side),
            interpolation=cv2.INTER_LINEAR,
        )
        return out[pad_t : pad_t + height, pad_l : pad_l + width]

    def run_matting_alpha(image: np.ndarray, out_size: tuple[int, int]) -> np.ndarray:
        """主体 alpha。birefnet：方形 1024²、ImageNet 归一化、logits 过 sigmoid（2026-08-08
        换型：MODNet 在 00 吞手臂酒杯、08 整体失效，BiRefNet_lite 三场景 IoU 全面占优）。
        modnet 旧契约保留作 A/B：等比长边 512、双边对齐 32、(x-0.5)/0.5。"""
        h, w = image.shape[:2]
        if args.matting_backend == "birefnet":
            resized = cv2.resize(image, (1024, 1024), interpolation=cv2.INTER_AREA)
            tensor = ((resized.astype(np.float32) / 255.0 - imagenet_mean)
                      / imagenet_std).transpose(2, 0, 1)[None]
            outputs = matting_session.run(
                None, {matting_session.get_inputs()[0].name: tensor}
            )
            # BiRefNet 多输出 ONNX 的最后一路才是最终融合结果；第 0 路是粗预测，
            # 会漏掉桌边手臂等细部。matte_compare.py 与官方导出契约均取最后一路。
            logits = outputs[-1].reshape(1024, 1024)
            alpha = 1.0 / (1.0 + np.exp(-logits.astype(np.float32)))
        else:
            scale = 512.0 / max(h, w)
            tw = max(32, int(round(w * scale / 32.0)) * 32)
            th = max(32, int(round(h * scale / 32.0)) * 32)
            resized = cv2.resize(image, (tw, th), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
            tensor = ((resized - 0.5) / 0.5).transpose(2, 0, 1)[None]
            alpha = matting_session.run(
                None, {matting_session.get_inputs()[0].name: tensor}
            )[0].reshape(th, tw)
        return np.clip(cv2.resize(alpha, out_size, interpolation=cv2.INTER_LINEAR), 0.0, 1.0)

    scene_filter = {name.strip() for name in args.scenes.split(",") if name.strip()}
    scene_paths = sorted(
        path
        for path in args.corpus.glob("*.png")
        if not scene_filter or path.stem in scene_filter
    )
    if not scene_paths:
        raise SystemExit(f"corpus 为空：{args.corpus}")
    args.output.mkdir(parents=True, exist_ok=True)

    key_angles = (
        np.arange(args.key_count, dtype=np.float32) * (2.0 * math.pi / args.key_count)
    )
    harmonics = min(max(1, args.harmonics), (args.key_count - 1) // 2)

    @torch.inference_mode()
    def run_ovie(canvas: np.ndarray, poses: np.ndarray) -> list[np.ndarray]:
        source = (
            torch.from_numpy(canvas.astype(np.float32) / 255.0)
            .permute(2, 0, 1)
            .to(device, ovie_dtype)
        )
        outputs: list[np.ndarray] = []
        pose_tensor = torch.from_numpy(poses).to(device, ovie_dtype)
        for start in range(0, len(poses), args.batch_size):
            batch = pose_tensor[start : start + args.batch_size]
            images = source.unsqueeze(0).expand(batch.shape[0], -1, -1, -1)
            result = model(x=images, cam_params=batch).float().clamp(0.0, 1.0).cpu()
            outputs.extend(
                (frame.permute(1, 2, 0).numpy() * 255.0).round().astype(np.uint8)
                for frame in result
            )
        return outputs

    index: list[dict[str, object]] = []
    import shutil
    from datetime import datetime

    for scene_path in scene_paths:
        scene = scene_path.stem
        # 原子发布：全部写入 .tmp 目录，末尾整目录换入，杜绝“刷新读到半套资产”
        final_dir = args.output / scene
        scene_dir = args.output / f"{scene}.tmp"
        if scene_dir.exists():
            shutil.rmtree(scene_dir)
        scene_dir.mkdir(parents=True, exist_ok=True)
        generated_at = datetime.now().strftime("%m-%d %H:%M:%S")
        source_image = Image.open(scene_path).convert("RGB")
        aspect = source_image.width / source_image.height
        center_hr = np.asarray(resize_long_edge(source_image, args.long_edge)).copy()
        # 所有派生场必须与查看器实际采样的 JPEG 像素对齐。旧版在无损 PNG 上推理，
        # 最后才另存 JPEG；边界细节会因编码而改变模型输出，造成 alpha/颜色错位。
        Image.fromarray(center_hr).save(scene_dir / "center.jpg", quality=92)
        center_hr = np.asarray(Image.open(scene_dir / "center.jpg").convert("RGB")).copy()
        canvas = np.asarray(reflect_square(source_image, args.canvas))

        poses = np.zeros((args.key_count + 1, 7), dtype=np.float32)
        poses[:, 6] = 1.0  # xyzw 单位四元数
        poses[1:, 0] = args.radius * np.cos(key_angles)
        poses[1:, 1] = args.radius * np.sin(key_angles)

        frames = run_ovie(canvas, poses)
        guides = [crop_guide_to_aspect(frame, aspect) for frame in frames]
        if args.guide_upscale > 1:
            guides = [
                cv2.resize(
                    g,
                    (g.shape[1] * args.guide_upscale, g.shape[0] * args.guide_upscale),
                    interpolation=cv2.INTER_LANCZOS4,
                )
                for g in guides
            ]
        zero_lr = guides[0]
        key_guides = guides[1:]
        guide_height, guide_width = zero_lr.shape[:2]
        px720_scale = args.long_edge / float(max(guide_width, guide_height))

        key_flows: list[np.ndarray] = []
        key_confidences: list[np.ndarray] = []
        for target in key_guides:
            flow, confidence, _ = estimate_correspondence(
                zero_lr, target, estimator, args.photo_error_scale
            )
            key_flows.append(flow)
            key_confidences.append(confidence)
        flow_array = np.stack(key_flows)
        confidence_array = np.stack(key_confidences).astype(np.float32)
        if not args.keep_global_similarity:
            flow_array = remove_global_scale_rotation(flow_array, confidence_array)

        def smoothstep(low: float, high: float, x):
            t = np.clip((x - low) / max(high - low, 1e-6), 0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)

        # ---- 深度骨架（v3）----
        disparity = run_depth_disparity(
            center_hr, (guide_width, guide_height)
        )
        debug_store: dict[int, dict[str, np.ndarray]] = {}
        disparity_raw = disparity.copy()
        # matting 深度先验修正：DA3 会把亮背景前的暗发帘判为远景（NeuFlow 同样跟踪
        # 背景），发帘与脸解耦。MODNet alpha 高的主体像素把视差抬到主体水平；运动仍
        # 只由连续深度场产生，不建刚性层（该用法与 D111 的边界关系需用户裁定）。
        alpha_matte = run_matting_alpha(
            center_hr, (guide_width, guide_height)
        )
        alpha_model_raw = alpha_matte.copy()
        # matte 卫生层（内容无关）：MODNet 在主体核心外常有碎块/蛇形轮廓/环形岛，
        # 每条虚假轮廓都会变成层边界伪影。开运算去斑 → 最大连通域 → 填封闭孔洞 →
        # 闭运算平滑；软 alpha（发丝）只在核心邻域保留。
        strong_core = (alpha_matte > 0.5).astype(np.uint8)
        # 先按模型原始拓扑选主实例。旧版先做 open9 会切断与躯干窄接的手臂/手部，
        # 随后的“只留最大连通域”再把正确部件永久删除。孤立灯杆等误检由连通域面积
        # 过滤处理，不再用会改变主实例拓扑的大核开运算。
        component_count, labels, stats, _ = cv2.connectedComponentsWithStats(strong_core)
        raw_core = strong_core
        if component_count > 1:
            largest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
            largest_mask = labels == largest
            # 强阈值可能把低置信连接处切开。用 α>0.15 的弱连通域只判断“是否属于
            # 同一实例”，最终仍只保留 α>0.5 的强像素，避免把整圈软裙扩进剪影。
            weak_count, weak_labels = cv2.connectedComponents(
                (alpha_matte > 0.15).astype(np.uint8)
            )
            if weak_count > 1 and largest_mask.any():
                weak_ids, weak_sizes = np.unique(
                    weak_labels[largest_mask], return_counts=True
                )
                valid = weak_ids > 0
                main_weak = int(weak_ids[valid][np.argmax(weak_sizes[valid])]) if valid.any() else 0
                raw_core = (
                    (strong_core > 0) & (weak_labels == main_weak)
                ).astype(np.uint8)
            else:
                raw_core = largest_mask.astype(np.uint8)
        # 主实例确定后再做细杆去噪。旧版把 open9 放在主实例筛选之前，手臂/手部
        # 一旦在窄连接处被切开，后续“只留最大连通域”会把整块正确部件永久删除；
        # 完全取消 open9 又会让办公室台灯杆、地球仪环等细高视差脊进入独立前景层。
        # 此处不再重跑连通域筛选：粗部件即使暂时断开也仍被保留，只有不足 9px 的
        # 细杆和碎屑被归还连续背景场，随后 close3 负责修复单像素裂口。
        raw_core = cv2.morphologyEx(
            raw_core, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8)
        )
        flood = raw_core.copy()
        flood_mask = np.zeros((guide_height + 2, guide_width + 2), np.uint8)
        cv2.floodFill(flood, flood_mask, (0, 0), 1)
        holes = ((flood == 0) & (raw_core == 0)).astype(np.uint8)
        # 只填卫生噪点，不改变模型已经正确识别的主体拓扑。旧实现无条件填掉所有
        # 封闭孔洞，会把手臂/躯干之间、手与杯子之间的真实背景并入前景层。
        hole_count, hole_labels, hole_stats, _ = cv2.connectedComponentsWithStats(holes)
        max_noise_hole_area = max(16, int(round(guide_width * guide_height * 0.0003)))
        for hole_label in range(1, hole_count):
            if hole_stats[hole_label, cv2.CC_STAT_AREA] <= max_noise_hole_area:
                raw_core[hole_labels == hole_label] = 1
        raw_core = cv2.morphologyEx(raw_core, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        # 仅抑制单像素锯齿；大 σ 会再次吞掉窄连接和真实凹口。
        raw_core = (
            cv2.GaussianBlur(raw_core.astype(np.float32), (0, 0), 1.0) > 0.5
        ).astype(np.uint8)
        raw_core_clean = raw_core.copy()
        near_core = cv2.dilate(raw_core, np.ones((15, 15), np.uint8)).astype(np.float32)
        alpha_matte = np.maximum(
            alpha_matte * near_core, raw_core.astype(np.float32)
        )
        # 软裙支撑域必须在硬收窄前捕获（收窄会把裙 α 压 0，plate 清空即失去目标）
        alpha_soft_support = (alpha_matte > 0.06).astype(np.uint8)
        # F/B 去污要使用模型原始软 alpha；下方硬收窄后的 alpha 只用于成品剪影。
        alpha_layer_source = alpha_matte.copy()
        # α 硬收窄（2026-08-08 D129 终案回定 0.35/0.65）：臂缘/肩缘的软裙是模型
        # 在背景上的不确定晕（非真发丝），放进前景层即成半透明暗翼（0.25/0.75
        # 实测更宽更糟）。硬收窄 + plate 软裙支撑域清空的组合：边缘收紧、背后
        # 无残余；头发最外圈细丝两层都不保留（轻微修剪换干净，Apple 同款取舍）。
        remap_t = np.clip((alpha_matte - 0.35) / 0.30, 0.0, 1.0)
        alpha_matte = (remap_t * remap_t * (3.0 - 2.0 * remap_t)).astype(np.float32)
        alpha_before_strip = alpha_matte.copy()
        bright_far_strip = np.zeros_like(alpha_matte, dtype=np.float32)
        core = alpha_matte > 0.7
        raised_share = 0.0
        if core.sum() > guide_width * guide_height * 0.01:
            subject_level = float(np.percentile(disparity[core], 60.0))
            # 误圈亮背景剥离（2026-08-08 D129 终案）：general/tiny/portrait 三模型
            # 一致的 matte 在明亮远景侧仍会多圈 15-25px（发缘对窗、肩缘对椅背）。
            # 前景层带着这条亮背景移动、plate 又静态续接同样内容，错位叠加即用户
            # 三次实锤的"玻璃状轮廓条带"。剥离条件三重独立：外缘带（<20 guide px）
            # ∧ 亮色（暗发丝不受伤）∧ 远深度（白衣近主体不受伤）。
            gray_guide = cv2.resize(
                cv2.cvtColor(
                    center_hr, cv2.COLOR_RGB2GRAY
                ),
                (guide_width, guide_height), interpolation=cv2.INTER_AREA,
            ).astype(np.float32) / 255.0
            rgb_guide_for_strip = cv2.resize(
                center_hr,
                (guide_width, guide_height), interpolation=cv2.INTER_AREA,
            ).astype(np.float32)
            core_u8_strip = (alpha_matte > 0.5).astype(np.uint8)
            dist_edge = cv2.distanceTransform(core_u8_strip, cv2.DIST_L2, 3)
            strip = (
                (core_u8_strip > 0)
                & (dist_edge < 20.0)
                & (gray_guide > 0.55)
                & (disparity_raw < subject_level - 0.30)
            )
            if strip.any():
                # “亮且远”只说明深度模型与 matte 冲突，不能证明像素属于背景。
                # 皮椅高光、台灯、地球仪等实体表面同样满足旧条件，旧逻辑会在
                # 主体内部挖出大片透明孔。真实误圈背景还必须满足两项拓扑/外观证据：
                # 1) 候选连通块接触与画框连通的外部背景；
                # 2) 像素颜色接近最近的外部背景，而非主体自身高光。
                background = (core_u8_strip == 0).astype(np.uint8)
                bg_count, bg_labels = cv2.connectedComponents(background, connectivity=8)
                border_labels = np.unique(
                    np.concatenate(
                        (
                            bg_labels[0], bg_labels[-1],
                            bg_labels[:, 0], bg_labels[:, -1],
                        )
                    )
                )
                border_labels = border_labels[border_labels > 0]
                external_background = np.isin(bg_labels, border_labels)
                if bg_count > 1 and external_background.any():
                    from scipy.ndimage import distance_transform_edt

                    _, nearest_external = distance_transform_edt(
                        ~external_background, return_indices=True
                    )
                    nearest_rgb = rgb_guide_for_strip[
                        nearest_external[0], nearest_external[1]
                    ]
                    background_color_delta = np.linalg.norm(
                        rgb_guide_for_strip - nearest_rgb, axis=2
                    )
                    strip &= background_color_delta < 45.0

                    touches_external = cv2.dilate(
                        external_background.astype(np.uint8),
                        np.ones((3, 3), np.uint8),
                    ) > 0
                    candidate_count, candidate_labels = cv2.connectedComponents(
                        strip.astype(np.uint8), connectivity=8
                    )
                    connected_strip = np.zeros_like(strip)
                    for candidate_label in range(1, candidate_count):
                        component = candidate_labels == candidate_label
                        if (component & touches_external).any():
                            connected_strip |= component
                    strip = connected_strip
                else:
                    strip[:] = False
            if strip.any():
                bright_far_strip = strip.astype(np.float32)
                alpha_matte = alpha_matte.copy()
                alpha_matte[strip] = 0.0
                cleaned = cv2.morphologyEx(
                    (alpha_matte > 0.5).astype(np.uint8), cv2.MORPH_OPEN,
                    np.ones((5, 5), np.uint8),
                )
                cleaned = (
                    cv2.GaussianBlur(cleaned.astype(np.float32), (0, 0), 2.0) > 0.5
                ).astype(np.float32)
                alpha_matte = np.minimum(alpha_matte, np.maximum(cleaned, 0.0))
                print(f"  [{scene}] bright-far rim stripped: {int(strip.sum())} px")
            eligible = alpha_matte > args.matting_alpha_low
            # 保序重锚（2026-08-10 D133，替代恒定平台）：旧式 max(d, subject_level)
            # 把主体里所有低于 60 分位的像素压成同一个值——主体 60% 的面积成为一个
            # 严格的平面，鼻尖与耳朵、手臂与躯干之间没有任何前后关系。这就是"纸片
            # 人"：无论深度模型多准，压平都发生在模型之后。
            # 改法：整体锚到 subject_level（保留"暗发帘被判远"的修正意图），但下界
            # 随 raw 深度的稳健归一起伏 ±relief，主体内部恢复保序的体积。DA3 的错误
            # 被压缩进 relief 带内，不再塌到背景层级。内部起伏产生的剪切由既有应变
            # 预算 + safeScale 自动限幅，不需要额外门禁。
            before = disparity.copy()
            relief_span = 0.0
            if args.subject_relief > 1e-4 and int(eligible.sum()) > 64:
                raw_subject = disparity_raw[eligible]
                raw_median = float(np.median(raw_subject))
                low_p, high_p = np.percentile(raw_subject, (10.0, 90.0))
                spread = max(float(high_p - low_p), 1e-4)
                relief = np.clip(
                    (disparity_raw - raw_median) * (args.subject_relief / spread),
                    -args.subject_relief,
                    args.subject_relief,
                )
                # 起伏必须平滑：raw 深度的逐像素抖动会变成主体内部的高频剪切
                relief = cv2.GaussianBlur(relief.astype(np.float32), (0, 0), 2.0)
                floor_field = subject_level + relief
                relief_span = float(
                    np.percentile(floor_field[eligible], 95.0)
                    - np.percentile(floor_field[eligible], 5.0)
                )
            else:
                floor_field = np.full_like(disparity, subject_level)
            disparity = np.where(eligible, np.maximum(disparity, floor_field), disparity)
            raised_share = float((disparity > before + 1e-4).mean())
            interior_span = float(
                np.percentile(disparity[eligible], 95.0)
                - np.percentile(disparity[eligible], 5.0)
            ) if int(eligible.sum()) > 64 else 0.0
            print(
                f"  [{scene}] 主体内部深度跨度 p5-p95 {interior_span:.3f}"
                f"（下界起伏 {relief_span:.3f}；0 = 纸片）"
            )

        # 闭运算（D53 同源）：只抬升主体内的细背景缝（发丝间隙）到主体深度，
        # 大块远景（玻璃/真实背景）不受影响；np.maximum 保证绝不压低主体。
        # 细脊深度并邻（2026-08-08 03 台灯/球环实锤）：背景层内 <9px 的细高视差
        # 脊（灯颈、球环）没有第二层盖它们的断边剪切，连续场 warp 直接显形为弯折
        # /拖影。opening9 把细脊的深度并入邻域——细结构随周围背景整体移动，放弃
        # 独立视差。主体区经 matting 抬升成台地不受影响（06 雕像珠串在 matte 内，
        # 走前景层）。必须在 close 之前做：close 的 max 语义会把细脊加宽过检测窗。
        disparity_opened = cv2.morphologyEx(
            disparity, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8)
        )
        ridge = cv2.dilate(
            ((disparity - disparity_opened) > 0.15).astype(np.uint8),
            np.ones((5, 5), np.uint8),
        ) > 0
        disparity = np.where(ridge, disparity_opened, disparity)
        close_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
        disparity = np.maximum(
            disparity, cv2.morphologyEx(disparity, cv2.MORPH_CLOSE, close_kernel)
        )
        disparity_centered = disparity - float(np.median(disparity))
        span_norm = float(
            np.percentile(disparity_centered, 95.0) - np.percentile(disparity_centered, 5.0)
        )
        depth_scale_px = (args.depth_span_px720 / px720_scale) / max(span_norm, 1e-6)

        mean_conf_all = confidence_array.mean(axis=0)
        trusted0 = mean_conf_all > 0.6
        radial = np.stack(
            [
                flow_array[k, ..., 0] * math.cos(a) + flow_array[k, ..., 1] * math.sin(a)
                for k, a in enumerate(key_angles)
            ]
        ).mean(axis=0)
        if trusted0.sum() > 256:
            r_t = radial[trusted0]
            d_t = disparity_centered[trusted0]
            corr = float(
                np.corrcoef(r_t.reshape(-1), d_t.reshape(-1))[0, 1]
            ) if r_t.std() > 1e-6 and d_t.std() > 1e-6 else 0.0
            ovie_span_guide = float(np.percentile(r_t, 95.0) - np.percentile(r_t, 5.0))
        else:
            corr = 0.0
            ovie_span_guide = 0.0
        ovie_span_px720 = ovie_span_guide * px720_scale
        depth_sign = (
            math.copysign(1.0, corr)
            if abs(corr) >= 0.1 and ovie_span_px720 >= args.ovie_gate_low
            else args.depth_sign
        )
        w_global = float(smoothstep(args.ovie_gate_low, args.ovie_gate_high, np.float64(ovie_span_px720)))
        print(
            f"  [{scene}] ovie_span {ovie_span_px720:.1f} px@720, corr {corr:+.3f}, "
            f"depth_sign {depth_sign:+.0f}, w_global {w_global:.2f}, matte_raised {raised_share*100:.1f}%"
        )

        cut_mask = (
            cv2.dilate(
                (
                    np.hypot(
                        np.gradient(disparity, axis=1), np.gradient(disparity, axis=0)
                    )
                    > args.cut_grad_threshold
                ).astype(np.float32),
                np.ones((3, 3), np.uint8),
            )
            > 0.5
        )
        # 深度门控分层：层 α = matte × smoothstep(主体视差 − 局部背景视差)。
        # 分层只存在于确有深度落差处（头肩 vs 远窗）；同深接触区（躯干 vs 桌面）
        # α→0 退化为连续单场，matte 破碎下边界不再制造层缝。
        # v4 双层（D114 同构）：matte 边界是前/背景层间的合法断边，进应变豁免
        matte_core = (alpha_matte > 0.5).astype(np.float32)
        layered = bool(matte_core.mean() > 0.01)
        # boundaryCliff 仅作诊断记录（2026-08-08：断崖/实心度/复杂度三个几何门禁
        # 均无法把 03 书房与良好的 05/06/07 分开——03 的 matte 含桌沿是真断崖、
        # 卫生层填洞后实心度反高、复杂度与蛋糕持平。03 的伪影按机制修复：细横杆
        # 归还背景（open9）、边界平滑、plate 近边 TELEA 续接，不做场景分类降级。）
        boundary_cliff = 1.0
        if layered:
            core_u8 = (matte_core > 0.5).astype(np.uint8)
            ring_in = (core_u8 - cv2.erode(core_u8, np.ones((7, 7), np.uint8))) > 0
            dil3 = cv2.dilate(core_u8, np.ones((7, 7), np.uint8))
            ring_out = (cv2.dilate(core_u8, np.ones((19, 19), np.uint8)) - dil3) > 0
            if ring_in.sum() > 100 and ring_out.sum() > 100:
                boundary_cliff = float(
                    np.median(disparity_raw[ring_in]) - np.median(disparity_raw[ring_out])
                )
        matte_edge = (
            (
                cv2.dilate(matte_core, np.ones((5, 5), np.uint8))
                - cv2.erode(matte_core, np.ones((5, 5), np.uint8))
            )
            > 0.5
        )
        if layered:
            cut_mask = cut_mask | matte_edge
        # matte 边界带：主体/背景运动的真断边（应变豁免 + 锐利过渡重建的接缝位置）
        matte_core = (alpha_matte > 0.5).astype(np.float32)
        matte_edge = (
            cv2.dilate(matte_core, np.ones((5, 5), np.uint8))
            - cv2.erode(matte_core, np.ones((5, 5), np.uint8))
        ) > 0.5
        cut_mask = cut_mask | matte_edge

        def masked_extend(field: np.ndarray, known: np.ndarray) -> np.ndarray:
            """归一化卷积逐级外推：把 known 区域的场扩散到全图（多尺度）。"""
            out = field * known[..., None]
            weight = known.copy()
            for sigma in (2.0, 4.0, 8.0, 16.0, 32.0, 64.0):
                blurred_f = np.stack(
                    [cv2.GaussianBlur(out[..., ch], (0, 0), sigma) for ch in (0, 1)],
                    axis=-1,
                )
                blurred_w = cv2.GaussianBlur(weight, (0, 0), sigma)
                empty = weight < 1e-3
                fill = blurred_f / np.maximum(blurred_w, 1e-6)[..., None]
                out[empty] = fill[empty]
                weight = np.maximum(weight, np.minimum(blurred_w * 4.0, 1.0))
            return out

        conditioned: list[np.ndarray] = []
        for k, angle in enumerate(key_angles):
            direction = np.array([math.cos(angle), math.sin(angle)], dtype=np.float32)
            flow_depth = (
                depth_sign * disparity_centered[..., None] * depth_scale_px * direction
            ).astype(np.float32)
            # 平移对齐：OVIE 场保留了每 key 的全局取景平移（D35 语义），深度场是零均值；
            # 不对齐会在置信度过渡带形成 O(1) 应变尖峰。按信任区平移中位数把深度场搬到
            # OVIE 的取景坐标里。
            trusted_k = confidence_array[k] > 0.6
            if trusted_k.sum() > 256:
                diff = flow_array[k] - flow_depth
                translation = np.array(
                    [
                        float(np.median(diff[..., 0][trusted_k])),
                        float(np.median(diff[..., 1][trusted_k])),
                    ],
                    dtype=np.float32,
                ) * w_global
            else:
                translation = np.zeros(2, dtype=np.float32)
            flow_depth = flow_depth + translation
            # 深度分桶引导的 regime 权重：w_res 的空间平滑只在相近深度内进行，
            # 运动 regime 的切换边界因此贴合深度边而非置信度等值线——玻璃（与背景
            # 同深度桶）整体取一致 regime，杯沿不再被切成两种运动。
            w_raw = smoothstep(args.residual_low, args.residual_high, confidence_array[k])
            bucket_count = 8
            buckets = np.clip(
                (np.clip(disparity, 0.0, 1.0) * bucket_count).astype(np.int32),
                0,
                bucket_count - 1,
            )
            w_res2d = np.zeros_like(w_raw)
            for b in range(bucket_count):
                mask = (buckets == b).astype(np.float32)
                if mask.sum() < 16:
                    continue
                num = cv2.GaussianBlur(w_raw * mask, (0, 0), 8.0)
                den = np.maximum(cv2.GaussianBlur(mask, (0, 0), 8.0), 1e-6)
                w_res2d += (num / den) * mask
            # 闭运算补细缝：主体内部的低权细缝（发丝间隙）继承周围高权，
            # 让整个主体骑同一 regime；大块低权区（玻璃）不会被翻转。
            w_res2d = np.maximum(
                w_res2d, cv2.morphologyEx(w_res2d, cv2.MORPH_CLOSE, close_kernel)
            )
            w_res = w_res2d[..., None] * w_global
            flow_c = flow_depth + (flow_array[k] - flow_depth) * w_res
            # 预算图：断边本体豁免（inf），近断边环带 0.25，内部区 0.10——
            # 不再留“无预算真空环”。台阶锯齿改由渲染端 2×2 超采样解决，不羽化流场。
            near_cut = (
                cv2.dilate(cut_mask.astype(np.float32), np.ones((11, 11), np.uint8)) > 0.5
            ) & (~cut_mask)
            interior = (
                cv2.erode(
                    (confidence_array[k] > 0.6).astype(np.float32), np.ones((3, 3), np.uint8)
                )
                > 0.5
            )
            budget_map = np.full_like(w_raw, args.strain_budget)
            budget_map[near_cut] = args.strain_budget * 2.5
            budget_map[cut_mask] = 1e6
            budget_map[~interior] = np.maximum(budget_map[~interior], args.strain_budget * 2.5)
            for _ in range(2):
                ux = np.gradient(flow_c[..., 0], axis=1)
                uy = np.gradient(flow_c[..., 0], axis=0)
                vx = np.gradient(flow_c[..., 1], axis=1)
                vy = np.gradient(flow_c[..., 1], axis=0)
                strain = 0.5 * np.sqrt((ux - vy) ** 2 + (uy + vx) ** 2)
                scale = np.minimum(
                    1.0, budget_map / np.maximum(strain, 1e-6)
                ).astype(np.float32)
                scale = cv2.erode(scale, np.ones((5, 5), np.uint8))
                scale = cv2.GaussianBlur(scale, (0, 0), 3.0)[..., None]
                flow_c = flow_depth + (flow_c - flow_depth) * scale
            if args.debug_dump and k in (0, args.key_count // 2):
                debug_store[k] = {
                    "flow_neuflow": flow_array[k].copy(),
                    "flow_depth": flow_depth.copy(),
                    "flow_final": flow_c.copy(),
                    "w_res": w_res2d.copy(),
                    "conf": confidence_array[k].copy(),
                }
            conditioned.append(flow_c)
        flow_array = np.stack(conditioned).astype(np.float32)

        if args.debug_dump:
            debug_dir = scene_dir / "debug"
            debug_dir.mkdir(parents=True, exist_ok=True)
            def save_gray(name: str, data: np.ndarray) -> None:
                Image.fromarray(
                    np.clip(data * 255.0, 0, 255).astype(np.uint8), mode="L"
                ).save(debug_dir / name)
            save_gray("disparity_raw.png", np.clip(disparity_raw, 0, 1.2) / 1.2)
            save_gray("disparity_final.png", np.clip(disparity, 0, 1.2) / 1.2)
            save_gray("alpha_model_raw.png", alpha_model_raw)
            save_gray("raw_core_clean.png", raw_core_clean.astype(np.float32))
            save_gray("alpha_before_strip.png", alpha_before_strip)
            save_gray("bright_far_strip.png", bright_far_strip)
            save_gray("alpha_matte.png", alpha_matte)
            for k, store in debug_store.items():
                Image.fromarray(key_guides[k]).save(debug_dir / f"key{k:02d}_view.png")
                save_gray(f"key{k:02d}_conf.png", store["conf"])
                save_gray(f"key{k:02d}_wres.png", store["w_res"])
                for field_name in ("flow_neuflow", "flow_depth", "flow_final"):
                    np.save(debug_dir / f"key{k:02d}_{field_name}.npy", store[field_name])
            Image.fromarray(zero_lr).save(debug_dir / "zero_view.png")

        # v4：主体/背景场分离——各自从"确定属于该侧"的像素归一化卷积外推到全图。
        # 剪影不连续由双层合成表达，单场内部保持光滑（伪影母体的架构级修复，D114 同构）。
        def masked_extend(field: np.ndarray, known: np.ndarray) -> np.ndarray:
            """归一化卷积外推（修正版）。

            分子/分母永远来自**原始已知集**的预乘场与权重，逐尺度只给尚未填充且
            分母足够的像素赋值——旧实现把已填充输出再喂回模糊、权重却不计账，
            比值逐轮 ~1e6 倍复利爆炸（曾产出 1e7–1e14 的系数垃圾）。
            """
            channels = field.shape[-1]
            known_f = known.astype(np.float32)
            num0 = field * known_f[..., None]
            out = num0.copy()
            filled = known_f > 0.5
            for sigma in (2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0):
                if filled.all():
                    break
                blurred_f = np.stack(
                    [cv2.GaussianBlur(num0[..., ch], (0, 0), sigma) for ch in range(channels)],
                    axis=-1,
                )
                blurred_w = cv2.GaussianBlur(known_f, (0, 0), sigma)
                take = (~filled) & (blurred_w > 1e-4)
                if take.any():
                    normalized = np.zeros_like(blurred_f)
                    np.divide(
                        blurred_f,
                        blurred_w[..., None],
                        out=normalized,
                        where=blurred_w[..., None] > 1e-4,
                    )
                    out[take] = normalized[take]
                    filled = filled | take
            if not filled.all():
                mean_value = num0.reshape(-1, channels).sum(axis=0) / max(known_f.sum(), 1.0)
                out[~filled] = mean_value
            return out.astype(np.float32)

        def masked_extend_scalar(field: np.ndarray, known: np.ndarray) -> np.ndarray:
            return masked_extend(field[..., None], known)[..., 0]

        if layered:
            subject_known = cv2.erode(matte_core, np.ones((3, 3), np.uint8))
            background_known = cv2.erode(1.0 - matte_core, np.ones((3, 3), np.uint8))
            flow_front = np.stack(
                [masked_extend(flow_array[k], subject_known) for k in range(args.key_count)]
            )
            flow_back = np.stack(
                [masked_extend(flow_array[k], background_known) for k in range(args.key_count)]
            )
        else:
            flow_front = flow_array
            flow_back = flow_array

        if layered:
            # 自指深度门控（区域级）：分层只存在于前/背景场实际不同处。
            # |F_front − F_back| 的 K 均值经 σ12 平滑后过 smoothstep(1.5px, 3.5px)；
            # 同深接触区（躯干/桌面）两场一致 → gate→0 → 退化为连续单场，
            # 头肩 vs 远背景 → gate→1 → 完整分层。不依赖会被近处吊灯污染的"背景深度"。
            field_gap = np.mean(
                np.linalg.norm(flow_front - flow_back, axis=-1), axis=0
            )
            field_gap = cv2.GaussianBlur(field_gap, (0, 0), 12.0)
            tt = np.clip((field_gap - 1.5) / 2.0, 0.0, 1.0)
            layer_gate = tt * tt * (3.0 - 2.0 * tt)
            # 门控调制场而非 α（2026-08-08 D129 终局根因）：原 alpha*=gate 把 σ12
            # 平滑的 0-1 场乘进 α，同深接触区的 gate 渐变（经 12px 高斯扩散到手臂/
            # 手部）把 α 压成宽半透明带——用户三次实锤的"玻璃状轮廓条带"即此。
            # α 必须保持不透明；demote 语义改由场收敛表达：gate→0 处前/背景场都
            # 收敛到连续单场，合成结果与单层渲染等价，透明伪影结构上不可能出现。
            gate3 = layer_gate[..., None]
            flow_front = np.stack([
                gate3 * flow_front[k] + (1.0 - gate3) * flow_array[k]
                for k in range(args.key_count)
            ])
            flow_back = np.stack([
                gate3 * flow_back[k] + (1.0 - gate3) * flow_array[k]
                for k in range(args.key_count)
            ])
            layered = bool((layer_gate * matte_core).mean() > 0.01)
            if not layered:
                # 门控整体降级为非分层：还原完整连续场（孤儿前景场教训）。
                flow_front = flow_array
                flow_back = flow_array
            if args.debug_dump:
                gate_dir = scene_dir / "debug"
                gate_dir.mkdir(parents=True, exist_ok=True)
                for name, data in (
                    ("layer_gate.png", layer_gate),
                    ("field_gap.png", np.clip(field_gap / 8.0, 0, 1)),
                    ("alpha_final.png", alpha_matte),
                ):
                    Image.fromarray(
                        np.clip(data * 255.0, 0, 255).astype(np.uint8), mode="L"
                    ).save(gate_dir / name)

        basis = fourier_basis(key_angles, harmonics)  # [K, 1+2H]
        gram = basis.T @ basis + np.eye(basis.shape[1], dtype=np.float32) * 1e-4
        solve = np.linalg.solve(gram, basis.T)  # [(1+2H), K]
        coeffs_front = np.tensordot(solve, flow_front, axes=(1, 0)).astype(np.float32)
        coeffs_back = np.tensordot(solve, flow_back, axes=(1, 0)).astype(np.float32)
        # 未正则的分层前场在深度断边存在位移跳变；前/背景场在查看器侧混合又会
        # 在遮罩边界产生接缝。成品只发布一套经空间正则的单一连续映射。
        coeffs_reference = np.tensordot(solve, flow_array, axes=(1, 0)).astype(np.float32)
        if args.safe_field_mode == "legacy-reference":
            safe_source = (
                coeffs_reference if args.safe_field_source == "reference" else coeffs_front
            )
            if args.safe_blur_sigma > 0.0:
                coeffs_safe = np.stack(
                    [
                        cv2.GaussianBlur(
                            item,
                            (0, 0),
                            sigmaX=args.safe_blur_sigma,
                            sigmaY=args.safe_blur_sigma,
                            borderType=cv2.BORDER_REFLECT_101,
                        )
                        for item in safe_source
                    ]
                ).astype(np.float32)
            else:
                coeffs_safe = safe_source.copy()
            safe_depth_sigma = 0.0
            safe_depth_raw_span = 0.0
        else:
            try:
                coeffs_safe, safe_depth_sigma, safe_depth_raw_span = (
                    build_depth_plane_coefficients(
                        disparity_raw,
                        coeffs_reference.shape[0],
                        px720_scale,
                        args.target_span_px720,
                        depth_sign,
                        args.safe_depth_sigma_ratio,
                        args.safe_depth_plane_residual,
                    )
                )
            except ValueError as error:
                raise SystemExit(f"[{scene}] {error}") from error

        mean_confidence = confidence_array.mean(axis=0)
        directions = (
            np.arange(args.envelope_directions, dtype=np.float32)
            * (2.0 * math.pi / args.envelope_directions)
        )
        direction_basis = fourier_basis(directions, harmonics)  # [D, 1+2H]
        # 跨度重校（D 线 Apple 参考落地）：先量 raw 能力（无 safeScale 的 16 方向
        # 加权 p5–p95 中位），差多少补多少；过头的方向由下方应变预算 safeScale 压回，
        # 因此增益是质量有界的。不缩减（Apple 上参考 43px 远高于我们全部场景）。
        raw_spans: list[float] = []
        for row, theta in zip(direction_basis, directions):
            field = np.tensordot(row, coeffs_reference, axes=(0, 0))
            projection = field[..., 0] * math.cos(theta) + field[..., 1] * math.sin(theta)
            weights = mean_confidence.reshape(-1)
            values = projection.reshape(-1)
            order = np.argsort(values)
            cumulative = np.cumsum(weights[order])
            total = max(float(cumulative[-1]), 1e-8)
            p05 = float(values[order][np.searchsorted(cumulative, 0.05 * total)])
            p95 = float(
                values[order][
                    min(np.searchsorted(cumulative, 0.95 * total), len(values) - 1)
                ]
            )
            raw_spans.append((p95 - p05) * px720_scale)
        raw_span_median = float(sorted(raw_spans)[len(raw_spans) // 2])
        span_gain = min(
            args.span_gain_max,
            max(1.0, args.target_span_px720 / max(raw_span_median, 1e-6)),
        )
        if span_gain > 1.0:
            coeffs_front *= span_gain
            coeffs_back *= span_gain
            coeffs_reference *= span_gain
            if args.safe_field_mode == "legacy-reference":
                coeffs_safe *= span_gain
        print(f"  [{scene}] raw span median {raw_span_median:.1f} px@720, "
              f"span_gain {span_gain:.2f}")

        # D135 depth-plane 仍保留为失败回归样本；只有该模式执行其形状限幅。
        # legacy-reference 必须精确保留 v8 基线能力，不能在“回退”时再次被新门禁
        # 缩小成另一套低空间感场。
        shape_metrics_before = safe_field_shape_metrics(
            coeffs_safe, args.safe_shape_directions
        )
        if args.safe_field_mode == "depth-plane":
            coeffs_safe, safe_shape_scale, shape_metrics_before, shape_metrics_final = (
                calibrate_safe_field(
                    coeffs_safe,
                    args.safe_shape_directions,
                    args.safe_total_non_sim_limit,
                    args.safe_total_scale_limit,
                    args.safe_rubber_limit,
                )
            )
        else:
            safe_shape_scale = 1.0
            shape_metrics_final = shape_metrics_before
        print(
            f"  [{scene}] safe shape scale {safe_shape_scale:.3f}, "
            f"total {shape_metrics_final['totalNonSimilarityP99Max']:.3%}/"
            f"{shape_metrics_final['totalScaleChangeP99Max']:.3%}, "
            f"rubber {shape_metrics_final['rubberNonSimilarityP99Max']:.3%}/"
            f"{shape_metrics_final['rubberScaleChangeP99Max']:.3%}"
        )
        # 默认查看器与边界统计使用实际发布的安全连续场。
        coeffs = coeffs_safe
        # coeffs: [(1+2H), gh, gw, 2]

        trusted_mask = (
            cv2.erode(
                (mean_confidence > 0.6).astype(np.float32), np.ones((9, 9), np.uint8)
            )
            > 0.5
        ) & (~cut_mask)
        # 主体内部必须进应变统计（2026-08-10 D135）：trusted_mask 用 OVIE 置信度
        # >0.6 且排除断边，暗色主体内部大面积落在统计之外。主体是平台时这没问题
        # （内部无运动），但 D133 打开内部起伏后，前景场在主体内部产生了未被任何
        # 门禁看见的剪切——实测渲染结果主体内部应变 p99 = 1.929（>1 即映射折叠，
        # 表现为手指被复制一份），而 meta 记录的 strainP99 只有 0.128、safeScale
        # 仍是 1.00。这里把主体内部（内缩 5px 避开剪影带）并入统计，让既有
        # safeScale 自动限幅；统计对象用真正被渲染的前景场 coeffs_front。
        subject_interior = cv2.erode(
            (matte_core > 0.5).astype(np.uint8), np.ones((5, 5), np.uint8)
        ) > 0
        envelope: list[dict[str, float]] = []
        for row, theta in zip(direction_basis, directions):
            field = np.tensordot(row, coeffs_safe, axes=(0, 0))
            if args.safe_field_mode == "legacy-reference":
                strain_field = np.tensordot(
                    row, coeffs_reference, axes=(0, 0)
                )
            else:
                strain_field = field
            ux = np.gradient(strain_field[..., 0], axis=1)
            uy = np.gradient(strain_field[..., 0], axis=0)
            vx = np.gradient(strain_field[..., 1], axis=1)
            vy = np.gradient(strain_field[..., 1], axis=0)
            strain = 0.5 * np.sqrt((ux - vy) ** 2 + (uy + vx) ** 2)
            strain_vals = strain[trusted_mask] if trusted_mask.any() else strain.reshape(-1)
            if layered and subject_interior.any():
                field_front = np.tensordot(row, coeffs_front, axes=(0, 0))
                f_ux = np.gradient(field_front[..., 0], axis=1)
                f_uy = np.gradient(field_front[..., 0], axis=0)
                f_vx = np.gradient(field_front[..., 1], axis=1)
                f_vy = np.gradient(field_front[..., 1], axis=0)
                strain_front = 0.5 * np.sqrt((f_ux - f_vy) ** 2 + (f_uy + f_vx) ** 2)
                strain_vals = np.concatenate(
                    [strain_vals, strain_front[subject_interior]]
                )
                # 折叠上限（2026-08-10 D135）：p99 会把小面积折叠稀释掉——手只占主体
                # 约 3%，而它正是被复制的那块。折叠是硬失败不是预算项：后向 warp
                # x ↦ x + f·flow(x) 在雅可比行列式变号处会把同一块源像素映射到两个
                # 输出位置（实拍表现为手指复制一份）。要求位移场雅可比的谱范数
                # （用 Frobenius 范数作上界）乘以强度后 < 0.8，从构造上排除折叠。
                jacobian_norm = np.sqrt(f_ux**2 + f_uy**2 + f_vx**2 + f_vy**2)
                fold_peak = float(np.percentile(jacobian_norm[subject_interior], 99.9))
                fold_scale = min(1.0, 0.8 / max(fold_peak, 1e-6))
            else:
                fold_scale = 1.0
            # p99（p99.9 已实测否决：全帧应变尾部由噪声尖峰主导，p99.9 把 00/08
            # 等良好场景压到 4-6px。稀疏细脊的剪切不走统计杠杆，在场源头处理——
            # 见细脊深度并邻）。
            strain_p99 = float(np.percentile(strain_vals, 99.0))
            safe_scale = (
                min(1.0, args.strain_budget / max(strain_p99, 1e-6))
                if args.safe_field_mode == "legacy-reference"
                else 1.0
            )
            safe_scale = min(safe_scale, fold_scale)   # 折叠上限无条件生效（D135）
            projection = field[..., 0] * math.cos(theta) + field[..., 1] * math.sin(theta)
            weights = mean_confidence.reshape(-1)
            values = projection.reshape(-1) * safe_scale
            order = np.argsort(values)
            cumulative = np.cumsum(weights[order])
            total = max(float(cumulative[-1]), 1e-8)
            p05 = float(values[order][np.searchsorted(cumulative, 0.05 * total)])
            p95 = float(
                values[order][
                    min(np.searchsorted(cumulative, 0.95 * total), len(values) - 1)
                ]
            )
            envelope.append(
                {
                    "deg": float(math.degrees(theta)),
                    "p05GuidePx": p05,
                    "p95GuidePx": p95,
                    "spanPx720": (p95 - p05) * px720_scale,
                    "strainP99": strain_p99,
                    "safeScale": safe_scale,
                }
            )

        # 恒定取景内缩（D76 语义：旋转不变，不随方向呼吸）：
        # 取 16 方向下边界带（外圈 3 texel）的最大位移，换算为归一化内缩比例。
        border = np.zeros((guide_height, guide_width), dtype=bool)
        border[:3, :] = border[-3:, :] = True
        border[:, :3] = border[:, -3:] = True
        border_max = 0.0
        for row in direction_basis:
            field = np.tensordot(row, coeffs, axes=(0, 0))
            border_max = max(
                border_max,
                float(np.abs(field[border][..., 0]).max()) / guide_width,
                float(np.abs(field[border][..., 1]).max()) / guide_height,
            )
        border_inset = min(border_max, 0.06)

        pass
        Image.fromarray(zero_lr).save(scene_dir / "zero.jpg", quality=90)
        atlas_rgb = np.concatenate(key_guides, axis=0)
        Image.fromarray(atlas_rgb).save(scene_dir / "keys.jpg", quality=90)
        encoded_key_atlas = np.asarray(
            Image.open(scene_dir / "keys.jpg").convert("RGB")
        )
        encoded_key_guides = [
            encoded_key_atlas[index * guide_height : (index + 1) * guide_height]
            for index in range(args.key_count)
        ]
        atlas_conf = np.concatenate(
            [
                np.rint(np.clip(item, 0.0, 1.0) * 255.0).astype(np.uint8)
                for item in key_confidences
            ],
            axis=0,
        )
        Image.fromarray(atlas_conf, mode="L").save(scene_dir / "conf.png")
        if layered:
            # keyview 的 RGB 只有在该视角已不再被主体占据时才可作为隐藏背景。
            # 保存逐 key 主体遮罩，查看器据此排除生成视图中的人物残影。
            cached_key_mattes = final_dir / "key_mattes.png"
            cached_size = None
            cached_meta = {}
            if cached_key_mattes.is_file():
                with Image.open(cached_key_mattes) as cached_image:
                    cached_size = cached_image.size
                cached_meta_path = final_dir / "meta.json"
                if cached_meta_path.is_file():
                    cached_meta = json.loads(cached_meta_path.read_text(encoding="utf-8"))
            if (
                cached_size == (guide_width, guide_height * args.key_count)
                and cached_meta.get("keyMattingOutput") == "last"
                and cached_meta.get("keyMattingSource") == "encoded-key-atlas"
            ):
                key_matte_atlas = np.asarray(
                    Image.open(cached_key_mattes).convert("L"), dtype=np.uint8
                )
            else:
                key_subject_mattes: list[np.ndarray] = []
                for key_guide in encoded_key_guides:
                    key_alpha = run_matting_alpha(
                        key_guide, (guide_width, guide_height)
                    )
                    key_core = cv2.dilate(
                        (key_alpha > 0.15).astype(np.uint8), np.ones((5, 5), np.uint8)
                    ).astype(np.float32)
                    key_subject = np.maximum(
                        key_alpha,
                        cv2.GaussianBlur(key_core, (0, 0), 1.0),
                    )
                    key_subject_mattes.append(np.clip(key_subject, 0.0, 1.0))
                key_matte_atlas = np.concatenate(
                    [
                        np.rint(item * 255.0).astype(np.uint8)
                        for item in key_subject_mattes
                    ],
                    axis=0,
                )
            Image.fromarray(key_matte_atlas, mode="L").save(
                scene_dir / "key_mattes.png"
            )
        Image.fromarray((trusted_mask * 255).astype(np.uint8), mode="L").save(
            scene_dir / "interior.png"
        )
        # 全分辨率剪影（2026-08-10 D135）：matte 此前只存在 guide 分辨率（384×512），
        # 渲染到 810 宽时放大 2.1×，边缘是肉眼可见的阶梯——这条锯齿本身就读作"人是被
        # 抠出来贴上去的"。BiRefNet 本来就在 1024² 上推理，是管线把它降到 guide 才丢
        # 了边缘。这里在成品尺寸上重跑一次，只用它提供**边界带的细节**；内/外的决策
        # （卫生层、亮远剥离、层门控）仍由 guide 版 alpha 说了算，避免推翻既有修复。
        decision_hr = cv2.resize(
            alpha_matte, (center_hr.shape[1], center_hr.shape[0]),
            interpolation=cv2.INTER_LINEAR,
        )
        alpha_matte_hr = decision_hr
        if layered:
            native_hr = run_matting_alpha(
                np.asarray(source_image.convert("RGB")),
                (center_hr.shape[1], center_hr.shape[0]),
            )
            native_hard = np.clip((native_hr - 0.35) / 0.30, 0.0, 1.0)
            native_hard = (native_hard * native_hard * (3.0 - 2.0 * native_hard)).astype(np.float32)
            alpha_matte_hr = np.where(
                decision_hr >= 0.95, 1.0,
                np.where(decision_hr <= 0.05, 0.0, native_hard),
            ).astype(np.float32)
        occlusion_band_px = 0.0
        # 持久隐藏背景板（Android 持久隐藏背景同源）：主体区域用周边背景色外推填充，
        # 背景层 warp 底板使剪影显露圈无缝；OVIE 结构化内容只在深显露区接管。
        if layered:
            guide_rgb = cv2.resize(
                center_hr, (guide_width, guide_height), interpolation=cv2.INTER_AREA
            ).astype(np.float32)
            bg_known = cv2.erode(1.0 - matte_core, np.ones((3, 3), np.uint8))
            fill_g = masked_extend(guide_rgb, bg_known)
            # 可见窄显露带采用最近真实背景的法向延拓。高斯归一化外推会同时混入
            # 不同方向的亮桌面/暗墙，曾在左下边界生成孤立亮点和灰雾；最近点延拓
            # 保持边界颜色连续，深部再交给 SDXL。
            from scipy.ndimage import distance_transform_edt

            _, nearest_indices = distance_transform_edt(
                bg_known < 0.5, return_indices=True
            )
            nearest_fill = guide_rgb[
                nearest_indices[0], nearest_indices[1]
            ].astype(np.float32)
            # 从 OVIE 环重建隐藏环带：每个 keyview 里主体移开后显露的真实背景，
            # 按背景场对齐回零位并合并；涂抹填充只兜底谁都没拍到的最深处。
            grid_x, grid_y = np.meshgrid(
                np.arange(guide_width, dtype=np.float32),
                np.arange(guide_height, dtype=np.float32),
            )
            accum = np.zeros_like(guide_rgb)
            weight_sum = np.zeros((guide_height, guide_width), dtype=np.float32)
            # OVIE 环带合并仅 lama 后端保留（D132：sdxl 后端下环带的 256² 奶白
            # 混合片段会成为扩散调和锚点，把整个填充带向乳白薄雾；OVIE 真实内容
            # 仍通过渲染时深显露层（uZero/uKeys + deepB 门控）参与，不经 plate）。
            ring_keys = range(args.key_count) if args.inpaint_backend == "lama" else ()
            for k in ring_keys:
                back_k = flow_back[k]
                # 方向无关判据：keyview 自己的 MODNet alpha 对齐回零位，
                # “对应处没有主体”才算真显露；不再做符号敏感的前向位移近似。
                alpha_key = run_matting_alpha(key_guides[k], (guide_width, guide_height))
                key_zero_alpha = cv2.remap(
                    alpha_key,
                    grid_x - back_k[..., 0], grid_y - back_k[..., 1],
                    interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE,
                )
                reveal_k = ((matte_core > 0.5) & (key_zero_alpha < 0.3)).astype(np.float32)
                if reveal_k.sum() < 16:
                    continue
                key_zero = cv2.remap(
                    key_guides[k].astype(np.float32),
                    grid_x - back_k[..., 0], grid_y - back_k[..., 1],
                    interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE,
                )
                accum += key_zero * reveal_k[..., None]
                weight_sum += reveal_k
            ring_valid = weight_sum > 0.5
            fill_g[ring_valid] = (
                accum[ring_valid] / weight_sum[ring_valid][..., None]
            )
            # 底板替换支撑域 = 软 α 裙全域（D129）+ 外扩 5px：三模型的 matte 核心
            # 都保守切在蓬松发区内侧，软裙残余留在 plate 会静止挂在原位透出"玻璃
            # 发纱"。α>0.06 全部像素连同核心一并替换为背景续接。
            core_u8_plate = (
                (alpha_soft_support > 0) | (matte_core > 0.5)
            ).astype(np.uint8)
            # 替换域重新覆盖软裙（2026-08-10 D138，撤销 D135 的内收）。
            #
            # D135 把替换域 erode 回剪影内，理由是"越过剪影向外扩会留下静态缝"。但那条
            # 静态缝的成因是膜校正坏掉（参照环吃进剪影边缘的暗混合像素，把补全整体压暗
            # 18 级，D137 已修），不是外扩本身。内收的代价是：紧贴轮廓外侧那一圈
            # 发/衣×背景 的暗混合像素（实测比真实背景暗 25 级）原样留在 plate 里，
            # 显露带的**外边缘每个角度都要扫过它**——用户在 uAblate=12（整带用 plate）
            # 下描述的"条带外边缘一道清晰的轮廓线"就是它。
            #
            # 因此替换域必须重新盖住这圈污染；膜校正修好后，外扩部分与真实背景是
            # 连续的，不应再产生静态环。静态环风险按小 f 帧复核。
            core5 = cv2.dilate(core_u8_plate, np.ones((3, 3), np.uint8))
            if int(core5.sum()) < 64:
                core5 = core_u8_plate.copy()
            # 最大前后景相对位移决定本轮可能真实显露的宽度。这个范围内必须延续
            # 邻近实拍背景；扩散模型只允许处理更深、当前运动包络不会直接采到的区域。
            relative_p99: list[float] = []
            subject_pixels = matte_core > 0.5
            for row in direction_basis:
                relative = np.tensordot(
                    row, coeffs_front - coeffs_back, axes=(0, 0)
                )
                if subject_pixels.any():
                    relative_p99.append(
                        float(np.percentile(np.linalg.norm(relative[subject_pixels], axis=1), 99.0))
                    )
            occlusion_band_px = float(
                np.clip(math.ceil((max(relative_p99) if relative_p99 else 2.0) + 2.0), 4, 18)
            )
            local_fill = nearest_fill
            # 支撑域补全（2026-08-08 D132）：SDXL-inpainting 接管整个支撑域
            # （OVIE 真实环带保留其上）。A/B 实锤：LaMa 在 00/03 生成"类主体"
            # 暗色团——用户四次指出的玻璃条带的内容源头；SDXL 无缝续接背景
            # （桌椅/墙面结构连贯、无主体残余），边界过渡原生干净，镜像克隆带
            # 与 TELEA 全部退役。--inpaint-backend lama 保留作 A/B。
            # 补全结果只在支撑域内部接管；外侧 2px 是已知背景调和带，保证生成块
            # 与原图在其边界处连续。后续查看器仍会用独立支撑纹理限制实际采样。
            inpaint_distance = cv2.distanceTransform(core5, cv2.DIST_L2, 3)
            inpaint_blend = smoothstep(0.5, 2.5, inpaint_distance)
            plate_fill_hr = None      # 全分辨率补全结果（D133）
            plate_region_hr = None

            def seamless_harmonize(
                generated: np.ndarray, region: np.ndarray, base: np.ndarray | None = None
            ) -> np.ndarray:
                """梯度域膜校正 + 颗粒匹配（2026-08-10 D133）。

                旧做法是边界 2px 的 alpha 交叉淡入：它只把亮度阶跃往里挪 2px，并没有
                消除它。实测（00 场景）补全区比真实背景暗 5.7 级、纹理梯度低 27%、
                接缝梯度 23.7 高于两侧任何一边——位移把这条边露出来就是"玻璃条带"。

                ① 颗粒匹配：按外缘真实背景的高频标准差补齐生成内容的高频增益，
                   消除"发虚/蒙玻璃"；
                ② 膜校正：以外缘环上的 (原图 − 生成) 为 Dirichlet 边界解一张平滑偏置
                   场并加回，使接缝处逐像素等于原图、梯度不连续归零，同时完整保留
                   生成内容的结构。
                """
                reference_base = guide_rgb if base is None else base
                region_u8 = region.astype(np.uint8)
                # 参照环必须避开剪影软边（2026-08-10 D137）。紧贴剪影外侧 0–2px 的原图
                # 像素是 发/衣×背景 的混合，实测比真实背景暗 25 级（y=190 扫描线：轮廓外
                # 第一像素 60.9，第二像素起 87.9）。旧实现把参照环贴着剪影取
                # （dilate(region,9) - region，即 0–4px），于是把这段污染当成"真实背景"，
                # 反过来把补全内容整体压暗——实测 sdxl_out 94.8 → harmonized 74.5，而真实
                # 背景是 85.8。**膜校正本身就是玻璃条带的制造者**，补图模型是清白的。
                # 外环退到剪影外 3–8px 的干净背景，内环取域内 0–4px。
                outer_ring = (
                    cv2.dilate(region_u8, np.ones((17, 17), np.uint8))
                    - cv2.dilate(region_u8, np.ones((7, 7), np.uint8))
                ) > 0
                inner_ring = (region_u8 - cv2.erode(region_u8, np.ones((9, 9), np.uint8))) > 0
                if int(outer_ring.sum()) < 64 or int(inner_ring.sum()) < 64:
                    return generated
                low = cv2.GaussianBlur(generated, (0, 0), 2.0)
                high = generated - low
                base_high = reference_base - cv2.GaussianBlur(reference_base, (0, 0), 2.0)
                interior = cv2.erode(region_u8, np.ones((9, 9), np.uint8)) > 0
                grain_gain = 1.0
                if int(interior.sum()) > 64:
                    # 颗粒参照同样取干净外环：贴边取会把剪影边缘的高对比混合像素当成
                    # 背景纹理，把增益顶到 2.5 的钳位上（实测旧值 2.11）。
                    reference = float(np.std(base_high[outer_ring]))
                    current = float(np.std(high[interior]))
                    grain_gain = float(np.clip(reference / max(current, 1e-3), 1.0, 2.5))
                matched = low + high * grain_gain
                # 跨缝 Dirichlet（2026-08-10 D137）：旧式取 `reference_base - matched`
                # 于 ring 上，而 ring 整个在替换域**外侧**（实测 90.3% 落在剪影外的真实
                # 背景上）。run_inpaint_sdxl 返回整幅 VAE 解码结果，域外本就近似等于
                # 输入，所以那个差值量到的是 VAE/重采样误差，不是"域内生成内容 vs 域外
                # 真实背景"的跨缝落差；膜校正因此把一个近似零的偏置扩散进域内，对内部
                # 色阶毫无作用。实测后果：plate 沿轮廓比紧邻真实背景中位暗 13.5 级、
                # 72% 周长暗 5 级以上——位移把它露出来就是九次打回的"玻璃条带"。
                #
                # 正确形式是把落差量在缝的两侧：域外紧邻真实背景的局部均值 减去
                # 域内紧邻生成内容的局部均值，取在**内环**上作边界条件再向内扩散。
                def local_mean(field: np.ndarray, mask: np.ndarray, sigma: float):
                    weight = mask.astype(np.float32)
                    num = np.stack(
                        [
                            cv2.GaussianBlur(field[..., ch] * weight, (0, 0), sigma)
                            for ch in range(field.shape[-1])
                        ],
                        axis=-1,
                    )
                    den = cv2.GaussianBlur(weight, (0, 0), sigma)
                    return num / np.maximum(den, 1e-6)[..., None], den

                # 两侧用同一核做局部均值，才是可比的量；σ 取缝宽量级，太小会把生成
                # 内容自身的纹理当成落差，太大会跨过转角把不同背景混在一起。
                mean_out, w_out = local_mean(reference_base, outer_ring, 6.0)
                mean_in, w_in = local_mean(matched, inner_ring, 6.0)
                seam_valid = inner_ring & (w_out > 1e-3) & (w_in > 1e-3)
                if int(seam_valid.sum()) < 64:
                    return matched
                offset = masked_extend(mean_out - mean_in, seam_valid.astype(np.float32))
                step = float(np.median(np.linalg.norm((mean_out - mean_in)[seam_valid], axis=-1)))
                print(
                    f"  [{scene}] plate 膜校正：颗粒增益 {grain_gain:.2f}，"
                    f"跨缝色阶落差 median {step:.1f} 级（校正前），"
                    f"扩散后域内偏置 |median| {float(np.median(np.abs(offset[region_u8 > 0]))):.1f}"
                )
                return matched + offset
            if args.inpaint_backend == "sdxl" and core5.sum() > 64:
                # 掩膜 = 全支撑域（不抠环带）。输入中性化（主体区换涂抹填充）：
                # 直接传原图时模型会把洞理解成"把这个人画完"，生成新人物
                # （2026-08-08 实发）；抠掉环带则其奶白片段把填充带向薄雾
                # （同日实发）。全掩膜 + 中性化输入 = 与独立 A/B 等价的干净背景。
                support3 = (core5 > 0)[..., None].astype(np.float32)
                sdxl_input = np.clip(
                    guide_rgb * (1.0 - support3) + fill_g * support3, 0, 255
                )
                if args.plate_seamless:
                    # 全分辨率生成（2026-08-10 D133）：旧路径在 guide 分辨率
                    # （384×512）上补全再线性上采样到成品尺寸，SDXL 的 1024² 输出
                    # 被降采样再升采样两次，高频被抹掉——实测补全区纹理梯度只有真实
                    # 背景的 72%，这就是颗粒匹配也救不回来的那部分"发虚"。改为直接在
                    # center_hr 尺寸上补全与调和，guide 版由 hr 版降采样得到。
                    target_size = (center_hr.shape[1], center_hr.shape[0])
                    region_hr = cv2.resize(
                        core5, target_size, interpolation=cv2.INTER_NEAREST
                    ) > 0
                    neutral_hr = cv2.resize(fill_g, target_size, interpolation=cv2.INTER_LINEAR)
                    base_hr = center_hr.astype(np.float32)
                    region3_hr = region_hr[..., None].astype(np.float32)
                    sdxl_input_hr = np.clip(
                        base_hr * (1.0 - region3_hr) + neutral_hr * region3_hr, 0, 255
                    )
                    sdxl_out_hr = run_inpaint_sdxl(sdxl_input_hr, region_hr).astype(np.float32)
                    harmonized_hr = seamless_harmonize(sdxl_out_hr, region_hr, base_hr)
                    if os.environ.get("PLATE_DEBUG_DUMP"):
                        dump = Path(os.environ["PLATE_DEBUG_DUMP"])
                        dump.mkdir(parents=True, exist_ok=True)
                        np.save(dump / f"{scene}-sdxl_out_hr.npy", sdxl_out_hr)
                        np.save(dump / f"{scene}-harmonized_hr.npy", harmonized_hr)
                        np.save(dump / f"{scene}-region_hr.npy", region_hr)
                    plate_fill_hr = np.where(region3_hr > 0.5, harmonized_hr, base_hr)
                    plate_region_hr = region_hr
                    harmonized = cv2.resize(
                        harmonized_hr, (guide_width, guide_height),
                        interpolation=cv2.INTER_AREA,
                    )
                else:
                    sdxl_out = run_inpaint_sdxl(sdxl_input, core5 > 0).astype(np.float32)
                    blend3 = inpaint_blend[..., None]
                    harmonized = guide_rgb * (1.0 - blend3) + sdxl_out * blend3
                fill_g[core5 > 0] = harmonized[core5 > 0]
            elif inpaint_session is not None and ((core5 > 0) & (~ring_valid)).sum() > 64:
                fill_holes = (core5 > 0) & (~ring_valid)
                try:
                    lama_input = np.clip(
                        guide_rgb * (1.0 - matte_core[..., None])
                        + fill_g * matte_core[..., None],
                        0,
                        255,
                    ).astype(np.uint8)
                    lama_out = run_inpaint_512(lama_input, fill_holes).astype(np.float32)
                    if args.plate_seamless:
                        harmonized = seamless_harmonize(lama_out, fill_holes)
                    else:
                        blend3 = inpaint_blend[..., None]
                        harmonized = guide_rgb * (1.0 - blend3) + lama_out * blend3
                    fill_g[fill_holes] = harmonized[fill_holes]
                except Exception as error:  # 补全失败不阻断生成
                    print(f"  [{scene}] inpaint fallback (smear): {error}")
            # 最近邻涂抹带（旧法）：把显露带整条替换为最近真实背景像素的径向涂抹，
            # 目的是保证与实拍背景连续——代价是显露出来的是放射状拉丝。膜校正已经
            # 在边界上给出逐像素连续性，且带内是有真实纹理统计的生成内容，涂抹严格
            # 更差，故 --plate-seamless 时停用（D133）。
            if not args.plate_seamless:
                local_distance = cv2.distanceTransform(core_u8_plate, cv2.DIST_L2, 3)
                local_weight = (
                    1.0 - smoothstep(
                        occlusion_band_px,
                        occlusion_band_px + 4.0,
                        local_distance,
                    )
                ) * core_u8_plate.astype(np.float32)
                fill_g = (
                    fill_g * (1.0 - local_weight[..., None])
                    + local_fill * local_weight[..., None]
                )
            fill_hr = cv2.resize(
                fill_g, (center_hr.shape[1], center_hr.shape[0]),
                interpolation=cv2.INTER_LINEAR,
            )
            if plate_fill_hr is not None:
                # 补全区直接取全分辨率结果，不经 guide 往返（D133）
                fill_hr[plate_region_hr] = plate_fill_hr[plate_region_hr]
            matte_hr = alpha_matte_hr  # D135：全分辨率剪影，替代 guide 上采样
            _unused_matte_hr = cv2.resize(
                alpha_matte, (center_hr.shape[1], center_hr.shape[0]),
                interpolation=cv2.INTER_LINEAR,
            )
            alpha_layer_hr = cv2.resize(
                alpha_layer_source, (center_hr.shape[1], center_hr.shape[0]),
                interpolation=cv2.INTER_LINEAR,
            )
            # plate.png 保存完整补全色；layer_masks.R 决定哪些源采样点能使用它。
            # 已知背景在查看器里始终回到 center，生成块不会再作为整张背景层泄漏。
            sample_core = cv2.dilate(
                (matte_core > 0.5).astype(np.uint8), np.ones((3, 3), np.uint8)
            )
            plate_support = cv2.GaussianBlur(sample_core.astype(np.float32), (0, 0), 0.6)
            plate_support = smoothstep(0.05, 0.95, plate_support).astype(np.float32)
            # 底板合成（2026-08-10 D133，恢复 v4 语义）：替换域之外必须逐像素等于原图。
            # 此前 plate_hr = fill_hr 把整张背景都换成 guide 分辨率（384×512）的上采样
            # 版——实测 84% 画面被改动、亮度整体偏 16 级。显露带露出来的因此是一张糊
            # 图，无论补全多好都是"发虚"。软边界避免最近邻上采样的阶梯锯齿。
            region_soft = cv2.resize(
                core5.astype(np.float32),
                (center_hr.shape[1], center_hr.shape[0]),
                interpolation=cv2.INTER_LINEAR,
            )
            region_soft = np.clip(cv2.GaussianBlur(region_soft, (0, 0), 1.0), 0.0, 1.0)
            plate_alpha = smoothstep(0.35, 0.65, region_soft)[..., None]
            plate_hr = (
                center_hr.astype(np.float32) * (1.0 - plate_alpha) + fill_hr * plate_alpha
            )
            Image.fromarray(np.clip(plate_hr, 0, 255).astype(np.uint8)).save(
                scene_dir / "plate.png"
            )
            # 前景板（近似 F/B 分解，Android P1 同源）：源图发缘像素是 发×背景 的
            # 预污染混合，直接采样会在剪影外圈形成亮缝。用底板作 B 解出前景真色：
            # F = (C - (1-α)B) / α；α 很小的像素无意义，回退到扩展填充色。
            alpha_c = np.clip(alpha_layer_hr, 0.0, 1.0)[..., None]
            decontaminated = np.clip(
                (center_hr.astype(np.float32) - (1.0 - alpha_c) * fill_hr)
                / np.maximum(alpha_c, 0.25),
                0.0,
                255.0,
            )
            # 安全约束：去污只作用于剪影 0.5 等值线 ±3px 的窄带（亮缝所在处）；
            # 主体内部（含玻璃盖脸等 matte 不可靠区）一律保留原图，防止负值截断变黑。
            core_hr = (matte_hr > 0.5).astype(np.float32)
            edge_band = cv2.dilate(core_hr, np.ones((7, 7), np.uint8)) - cv2.erode(
                core_hr, np.ones((7, 7), np.uint8)
            )
            edge_band = cv2.GaussianBlur(edge_band, (0, 0), 1.5)[..., None]
            # alpha 很低处没有可逆的前景颜色，旧实现却仍把去污结果混入外圈，截断后
            # 形成黑边/彩点。只在模型确认属于过渡前景的窄带应用 F/B 反解。
            decontam_valid = smoothstep(0.12, 0.35, alpha_layer_hr)[..., None]
            decontam_weight = np.clip(edge_band * decontam_valid, 0.0, 1.0)
            fg_hr = (
                center_hr.astype(np.float32) * (1.0 - decontam_weight)
                + decontaminated * decontam_weight
            )
            Image.fromarray(np.clip(fg_hr, 0, 255).astype(np.uint8)).save(
                scene_dir / "fg.png"
            )
            # z-test 只允许在“前后景相对位移可能跨越的剪影内边界带”生效。
            # 旧实现对整张主体应用深度比较，深度外推误差会挖掉躯干和手部内部。
            distance_inside = cv2.distanceTransform(
                subject_pixels.astype(np.uint8), cv2.DIST_L2, 3
            )
            occlusion_band = (
                1.0 - smoothstep(occlusion_band_px - 1.0, occlusion_band_px + 1.0, distance_inside)
            ) * subject_pixels.astype(np.float32)
            layer_masks = np.zeros((guide_height, guide_width, 4), dtype=np.uint8)
            layer_masks[..., 0] = np.rint(np.clip(plate_support, 0.0, 1.0) * 255.0).astype(np.uint8)
            layer_masks[..., 1] = np.rint(np.clip(occlusion_band, 0.0, 1.0) * 255.0).astype(np.uint8)
            layer_masks[..., 3] = 255
            Image.fromarray(layer_masks, mode="RGBA").save(scene_dir / "layer_masks.png")
        # 发布前完整性门禁：系数必须有限且量级合理（fp16 纹理与物理位移双重上界），
        # 坏数据宁可拒绝发布也不得进入查看器（派生完整性校验同款纪律）。
        for label, data in (
            ("safe", coeffs_safe),
            ("reference", coeffs_reference),
            ("front", coeffs_front),
            ("back", coeffs_back),
        ):
            if not np.isfinite(data).all():
                raise SystemExit(f"[{scene}] coeffs_{label} 含非有限值，拒绝发布")
            peak = float(np.abs(data).max())
            if peak > 500.0:
                raise SystemExit(
                    f"[{scene}] coeffs_{label} 峰值 {peak:.1f} px 超出合理位移上界，拒绝发布"
                )
        (scene_dir / "flow_coeffs_front.bin").write_bytes(
            coeffs_front.astype("<f4").tobytes()
        )
        (scene_dir / "flow_coeffs_back.bin").write_bytes(
            coeffs_back.astype("<f4").tobytes()
        )
        (scene_dir / "flow_coeffs_safe.bin").write_bytes(
            coeffs_safe.astype("<f4").tobytes()
        )
        (scene_dir / "flow_coeffs_reference.bin").write_bytes(
            coeffs_reference.astype("<f4").tobytes()
        )
        # 这张图是**二值区域标签**，不是覆盖率：上面两次硬化（第 638 行的
        # smoothstep 收窄、第 1389 行的 0.95/0.05 硬切）是 D129 为"遮挡物身份"定的，
        # 别以为它是 bug 就去掉——`segment_occluders.py` 靠它与 SAM 3 取并集。
        # 软边界要的连续 α 是**另一张图** `matte_soft.png`，由 `build_soft_matte.py`
        # 从同一权重、同一推理契约单独生成，一次硬化都不做（D183）。
        Image.fromarray(
            np.rint(np.clip(alpha_matte_hr, 0.0, 1.0) * 255.0).astype(np.uint8), mode="L"
        ).save(scene_dir / "matte.png")
        # z 排序纹理（D37 正向双层 z-test 同源）：主体/背景两侧各自外推的视差，
        # 合成时"更近者赢"——桌面比躯干近的地方由背景层正确遮挡前景层。
        if layered:
            disp_front_map = masked_extend_scalar(
                disparity.copy(), cv2.erode(matte_core, np.ones((3, 3), np.uint8))
            )
            disp_back_map = masked_extend_scalar(
                disparity.copy(),
                cv2.erode(1.0 - matte_core, np.ones((3, 3), np.uint8)),
            )
            for name, data in (("disp_front.png", disp_front_map), ("disp_back.png", disp_back_map)):
                Image.fromarray(
                    np.rint(np.clip(data / 1.4, 0.0, 1.0) * 255.0).astype(np.uint8), mode="L"
                ).save(scene_dir / name)

        meta = {
            "version": 9 if args.safe_field_mode == "depth-plane" else 8,
            "safeCoeffFile": "flow_coeffs_safe.bin",
            "safeFieldMode": args.safe_field_mode,
            "safeFieldSource": (
                "low-frequency-depth-plane"
                if args.safe_field_mode == "depth-plane"
                else f"regularized-{args.safe_field_source}"
            ),
            "safeBlurSigmaGuidePx": (
                safe_depth_sigma
                if args.safe_field_mode == "depth-plane"
                else args.safe_blur_sigma
            ),
            "safeDepthSigmaRatio": args.safe_depth_sigma_ratio,
            "safeDepthPlaneResidual": args.safe_depth_plane_residual,
            "safeDepthRawSpan": safe_depth_raw_span,
            "safeShapeScale": safe_shape_scale,
            "safeShapeMetricsBeforeScale": shape_metrics_before,
            "safeShapeMetrics": shape_metrics_final,
            "safeTotalNonSimilarityLimit": args.safe_total_non_sim_limit,
            "safeTotalScaleLimit": args.safe_total_scale_limit,
            "safeRubberLimit": args.safe_rubber_limit,
            "generatedAt": generated_at,
            "mattingBackend": args.matting_backend,
            "keyMattingOutput": "last",
            "keyMattingSource": "encoded-key-atlas",
            "inpaintBackend": args.inpaint_backend,
            "spanGain": round(span_gain, 3),
            "rawSpanMedianPx720": round(raw_span_median, 1),
            "boundaryCliff": round(boundary_cliff, 3),
            "layered": layered,
            "zTestDefault": False,
            "occlusionBandPx": round(occlusion_band_px, 1),
            "scene": scene,
            "revealDiv0": args.reveal_div0,
            "revealDiv1": args.reveal_div1,
            "strainBudget": args.strain_budget,
            "depthSpanPx720": args.depth_span_px720,
            "ovieSpanPx720": ovie_span_px720,
            "ovieCorr": corr,
            "depthSign": depth_sign,
            "wGlobal": w_global,
            "cutGradThreshold": args.cut_grad_threshold,
            "borderInset": border_inset,
            "radius": args.radius,
            "keyCount": args.key_count,
            "harmonics": harmonics,
            "keyAnglesDeg": [float(math.degrees(a)) for a in key_angles],
            "guideWidth": guide_width,
            "guideHeight": guide_height,
            "centerWidth": int(center_hr.shape[1]),
            "centerHeight": int(center_hr.shape[0]),
            "sourceWidth": source_image.width,
            "sourceHeight": source_image.height,
            "coeffCount": int(coeffs.shape[0]),
            "px720Scale": px720_scale,
            "photoErrorScale": args.photo_error_scale,
            "guideUpscale": args.guide_upscale,
            "residualLow": args.residual_low,
            "residualHigh": args.residual_high,
            "globalSimilarityRemoved": not args.keep_global_similarity,
            "envelope": envelope,
        }
        (scene_dir / "meta.json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        # 原子换入
        if final_dir.exists():
            old_dir = args.output / f"{scene}.old"
            if old_dir.exists():
                shutil.rmtree(old_dir)
            final_dir.rename(old_dir)
            scene_dir.rename(final_dir)
            shutil.rmtree(old_dir)
        else:
            scene_dir.rename(final_dir)
        spans = [item["spanPx720"] for item in envelope]
        print(
            f"{scene}: guide {guide_width}x{guide_height}, "
            f"span px@720 min {min(spans):.1f} / median {sorted(spans)[len(spans)//2]:.1f} / max {max(spans):.1f}"
        )
        index.append({"scene": scene, "spanPx720Median": sorted(spans)[len(spans) // 2]})

    (args.output / "index.json").write_text(
        json.dumps(
            {"scenes": [item["scene"] for item in index], "details": index},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"assets written to {args.output}")


if __name__ == "__main__":
    main()
