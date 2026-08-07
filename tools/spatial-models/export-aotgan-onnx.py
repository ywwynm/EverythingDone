#!/usr/bin/env python3
"""将 AOT-GAN Places2 生成器导出为 EverythingDone 使用的 ONNX ABI。"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import onnx
import onnxruntime as ort
import torch


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--opset", type=int, default=18)
    args = parser.parse_args()

    source = args.source.resolve()
    checkpoint = args.checkpoint.resolve()
    output = args.output.resolve()
    metadata = (args.metadata or output.with_suffix(".json")).resolve()
    sys.path.insert(0, str(source / "src"))

    from model.aotgan import InpaintGenerator  # pylint: disable=import-error,import-outside-toplevel

    model_args = SimpleNamespace(rates=[1, 2, 4, 8], block_num=8)
    model = InpaintGenerator(model_args)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    model.load_state_dict(state)
    model.eval()

    generator = torch.Generator(device="cpu").manual_seed(20260731)
    image = torch.rand((1, 3, 512, 512), generator=generator) * 2.0 - 1.0
    mask = torch.zeros((1, 1, 512, 512))
    mask[:, :, 144:368, 176:336] = 1.0
    masked_image = image * (1.0 - mask) + mask

    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    with torch.inference_mode():
        reference = model(masked_image, mask).cpu().numpy()
        torch.onnx.export(
            model,
            (masked_image, mask),
            output,
            export_params=True,
            opset_version=args.opset,
            do_constant_folding=True,
            input_names=["image", "mask"],
            output_names=["output"],
            dynamic_axes={
                "image": {2: "height", 3: "width"},
                "mask": {2: "height", 3: "width"},
                "output": {2: "height", 3: "width"},
            },
            dynamo=False,
        )
    export_seconds = time.perf_counter() - started

    graph = onnx.load(output)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(
        str(output),
        providers=["CPUExecutionProvider"],
        sess_options=ort.SessionOptions(),
    )
    started = time.perf_counter()
    actual = session.run(
        ["output"],
        {
            "image": masked_image.cpu().numpy(),
            "mask": mask.cpu().numpy(),
        },
    )[0]
    inference_seconds = time.perf_counter() - started
    difference = np.abs(reference - actual)
    if not np.isfinite(actual).all():
        raise RuntimeError("ONNX 输出包含非有限值")
    if float(difference.max()) > 1e-3:
        raise RuntimeError(
            f"PyTorch 与 ONNX 输出偏差过大：max={float(difference.max()):.8f}"
        )

    operator_counts: dict[str, int] = {}
    for node in graph.graph.node:
        operator_counts[node.op_type] = operator_counts.get(node.op_type, 0) + 1
    record = {
        "modelId": "aotgan_places2_512",
        "modelVersion": "1.0.0",
        "sourceRepository": "https://github.com/researchmm/AOT-GAN-for-Inpainting",
        "sourceCheckpointSha256": sha256(checkpoint),
        "outputSha256": sha256(output),
        "sizeBytes": output.stat().st_size,
        "license": "Apache-2.0",
        "opset": args.opset,
        "inputContract": "float32-aotgan-rgb-mask",
        "dynamicSpatialAxes": True,
        "operators": dict(sorted(operator_counts.items())),
        "exportSeconds": export_seconds,
        "desktopCpuInferenceSeconds512": inference_seconds,
        "verificationMaxAbsoluteError": float(difference.max()),
        "verificationMeanAbsoluteError": float(difference.mean()),
    }
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(
        json.dumps(record, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(record, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
