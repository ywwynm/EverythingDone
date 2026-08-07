#!/usr/bin/env python3
"""在同一空间显露 mask 上比较端侧补图候选模型的桌面 CPU 行为。"""

from __future__ import annotations

import argparse
import json
import threading
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import psutil
from PIL import Image


def measure_peak(callable_):
    process = psutil.Process()
    baseline = process.memory_info().rss
    peak = baseline
    running = True

    def sample():
        nonlocal peak
        while running:
            peak = max(peak, process.memory_info().rss)
            time.sleep(0.005)

    thread = threading.Thread(target=sample, daemon=True)
    thread.start()
    started = time.perf_counter()
    try:
        value = callable_()
    finally:
        elapsed = time.perf_counter() - started
        running = False
        thread.join()
    return value, elapsed, peak - baseline, peak


def session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.inter_op_num_threads = 1
    options.intra_op_num_threads = 4
    return ort.InferenceSession(
        str(path),
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )


def run_migan(
    model: Path,
    image: np.ndarray,
    write_mask: np.ndarray,
    conditioning_mask: np.ndarray,
) -> tuple[np.ndarray, dict]:
    sess, init_seconds, init_delta, init_peak = measure_peak(lambda: session(model))
    chw = np.transpose(image, (2, 0, 1))[None].astype(np.uint8)
    known_mask = np.where(conditioning_mask, 0, 255)[None, None].astype(np.uint8)
    output, seconds, delta, peak = measure_peak(
        lambda: sess.run(None, {"image": chw, "mask": known_mask})[0]
    )
    generated = np.transpose(output[0], (1, 2, 0)).astype(np.uint8)
    composed = image.copy()
    composed[write_mask] = generated[write_mask]
    return composed, {
        "sessionInitSeconds": init_seconds,
        "sessionInitRssDeltaBytes": init_delta,
        "sessionInitPeakRssBytes": init_peak,
        "inferenceSeconds": seconds,
        "inferenceRssDeltaBytes": delta,
        "inferencePeakRssBytes": peak,
    }


def mask_region(hidden: np.ndarray) -> tuple[int, int, int, int]:
    ys, xs = np.nonzero(hidden)
    left, right = int(xs.min()), int(xs.max()) + 1
    top, bottom = int(ys.min()), int(ys.max()) + 1
    padding = max(32, max(right - left, bottom - top) // 2)
    height, width = hidden.shape
    return (
        max(0, left - padding),
        max(0, top - padding),
        min(width, right + padding),
        min(height, bottom + padding),
    )


def aligned(value: int, other: int, long_edge: int) -> int:
    scaled = round(value * long_edge / max(value, other))
    return min(long_edge, max(64, scaled) // 4 * 4)


def run_aot(
    model: Path,
    image: np.ndarray,
    write_mask: np.ndarray,
    conditioning_mask: np.ndarray,
    long_edge: int,
) -> tuple[np.ndarray, dict]:
    left, top, right, bottom = mask_region(conditioning_mask)
    crop = image[top:bottom, left:right]
    crop_write_mask = write_mask[top:bottom, left:right]
    crop_conditioning_mask = conditioning_mask[top:bottom, left:right]
    height, width = crop.shape[:2]
    target_width = aligned(width, height, long_edge)
    target_height = aligned(height, width, long_edge)
    scaled = np.asarray(
        Image.fromarray(crop).resize(
            (target_width, target_height),
            Image.Resampling.BILINEAR,
        )
    )
    scaled_mask = np.asarray(
        Image.fromarray(crop_conditioning_mask).resize(
            (target_width, target_height),
            Image.Resampling.NEAREST,
        )
    ).astype(bool)
    normalized = np.transpose(scaled.astype(np.float32) / 127.5 - 1.0, (2, 0, 1))[None]
    mask_tensor = scaled_mask[None, None].astype(np.float32)
    normalized = normalized * (1.0 - mask_tensor) + mask_tensor

    sess, init_seconds, init_delta, init_peak = measure_peak(lambda: session(model))
    output, seconds, delta, peak = measure_peak(
        lambda: sess.run(None, {"image": normalized, "mask": mask_tensor})[0]
    )
    generated = np.transpose(output[0], (1, 2, 0))
    generated = np.clip((generated + 1.0) * 127.5, 0, 255).round().astype(np.uint8)
    restored = np.asarray(
        Image.fromarray(generated).resize(
            (width, height),
            Image.Resampling.BILINEAR,
        )
    )
    composed = image.copy()
    target = composed[top:bottom, left:right]
    target[crop_write_mask] = restored[crop_write_mask]
    return composed, {
        "workSize": [target_width, target_height],
        "sessionInitSeconds": init_seconds,
        "sessionInitRssDeltaBytes": init_delta,
        "sessionInitPeakRssBytes": init_peak,
        "inferenceSeconds": seconds,
        "inferenceRssDeltaBytes": delta,
        "inferencePeakRssBytes": peak,
    }


def run_big_lama(
    model: Path,
    image: np.ndarray,
    write_mask: np.ndarray,
    conditioning_mask: np.ndarray,
) -> tuple[np.ndarray, dict]:
    """运行固定 512×512 的 Big-LaMa，并用反射填充避免拉伸非方形裁剪。"""
    left, top, right, bottom = mask_region(conditioning_mask)
    crop = image[top:bottom, left:right]
    crop_write_mask = write_mask[top:bottom, left:right]
    crop_conditioning_mask = conditioning_mask[top:bottom, left:right]
    height, width = crop.shape[:2]
    side = max(width, height)
    pad_left = (side - width) // 2
    pad_right = side - width - pad_left
    pad_top = (side - height) // 2
    pad_bottom = side - height - pad_top
    padded = np.pad(
        crop,
        ((pad_top, pad_bottom), (pad_left, pad_right), (0, 0)),
        mode="reflect",
    )
    padded_mask = np.pad(
        crop_conditioning_mask,
        ((pad_top, pad_bottom), (pad_left, pad_right)),
        mode="constant",
        constant_values=False,
    )
    scaled = np.asarray(
        Image.fromarray(padded).resize((512, 512), Image.Resampling.BILINEAR)
    )
    scaled_mask = np.asarray(
        Image.fromarray(padded_mask).resize(
            (512, 512),
            Image.Resampling.NEAREST,
        )
    ).astype(bool)
    image_tensor = np.transpose(
        scaled.astype(np.float32) / 255.0,
        (2, 0, 1),
    )[None]
    mask_tensor = scaled_mask[None, None].astype(np.float32)

    sess, init_seconds, init_delta, init_peak = measure_peak(lambda: session(model))
    output, seconds, delta, peak = measure_peak(
        lambda: sess.run(None, {"image": image_tensor, "mask": mask_tensor})[0]
    )
    generated = np.transpose(output[0], (1, 2, 0))
    generated = np.clip(generated, 0, 255).round().astype(np.uint8)
    restored_square = np.asarray(
        Image.fromarray(generated).resize((side, side), Image.Resampling.BILINEAR)
    )
    restored = restored_square[
        pad_top:pad_top + height,
        pad_left:pad_left + width,
    ]
    composed = image.copy()
    target = composed[top:bottom, left:right]
    target[crop_write_mask] = restored[crop_write_mask]
    return composed, {
        "workSize": [512, 512],
        "sessionInitSeconds": init_seconds,
        "sessionInitRssDeltaBytes": init_delta,
        "sessionInitPeakRssBytes": init_peak,
        "inferenceSeconds": seconds,
        "inferenceRssDeltaBytes": delta,
        "inferencePeakRssBytes": peak,
    }


def boundary_discontinuity(image: np.ndarray, hidden: np.ndarray) -> float:
    values = []
    for dy, dx in ((0, 1), (1, 0)):
        shifted_mask = np.roll(hidden, (dy, dx), axis=(0, 1))
        edge = hidden != shifted_mask
        shifted = np.roll(image, (dy, dx), axis=(0, 1))
        difference = np.abs(image.astype(np.float32) - shifted.astype(np.float32)).mean(axis=2)
        values.extend(difference[edge].tolist())
    return float(np.mean(values)) if values else 0.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--mask", type=Path, required=True)
    parser.add_argument(
        "--conditioning-mask",
        type=Path,
        help="模型推理时抹除的完整遮挡物；省略时与 --mask 相同",
    )
    parser.add_argument("--migan", type=Path, required=True)
    parser.add_argument("--aotgan", type=Path, required=True)
    parser.add_argument("--big-lama", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    image = np.asarray(Image.open(args.image).convert("RGB"))
    write_mask = np.asarray(Image.open(args.mask).convert("L")) >= 128
    conditioning_mask = (
        np.asarray(Image.open(args.conditioning_mask).convert("L")) >= 128
        if args.conditioning_mask is not None
        else write_mask
    )
    if write_mask.shape != image.shape[:2] or conditioning_mask.shape != image.shape[:2]:
        raise ValueError("mask 与图片尺寸不一致")
    if np.any(write_mask & ~conditioning_mask):
        raise ValueError("conditioning mask 必须覆盖实际写入 mask")
    args.output.mkdir(parents=True, exist_ok=True)

    records = {}
    migan, record = run_migan(args.migan, image, write_mask, conditioning_mask)
    Image.fromarray(migan).save(args.output / "migan.png")
    record["boundaryDiscontinuity"] = boundary_discontinuity(migan, write_mask)
    record["outsideMaskChangedPixels"] = int(
        np.any(migan[~write_mask] != image[~write_mask], axis=1).sum()
    )
    records["migan"] = record

    for long_edge in (512, 768, 1024):
        output, record = run_aot(
            args.aotgan,
            image,
            write_mask,
            conditioning_mask,
            long_edge,
        )
        Image.fromarray(output).save(args.output / f"aotgan-{long_edge}.png")
        record["boundaryDiscontinuity"] = boundary_discontinuity(output, write_mask)
        record["outsideMaskChangedPixels"] = int(
            np.any(output[~write_mask] != image[~write_mask], axis=1).sum()
        )
        records[f"aotgan-{long_edge}"] = record

    if args.big_lama is not None:
        output, record = run_big_lama(
            args.big_lama,
            image,
            write_mask,
            conditioning_mask,
        )
        Image.fromarray(output).save(args.output / "big-lama-512.png")
        record["boundaryDiscontinuity"] = boundary_discontinuity(output, write_mask)
        record["outsideMaskChangedPixels"] = int(
            np.any(output[~write_mask] != image[~write_mask], axis=1).sum()
        )
        records["big-lama-512"] = record

    report = {
        "image": str(args.image),
        "mask": str(args.mask),
        "conditioningMask": str(args.conditioning_mask or args.mask),
        "imageSize": [int(image.shape[1]), int(image.shape[0])],
        "writePixelRatio": float(write_mask.mean()),
        "conditioningPixelRatio": float(conditioning_mask.mean()),
        "results": records,
    }
    (args.output / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
