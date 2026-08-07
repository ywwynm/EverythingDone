#!/usr/bin/env python3
"""从「按目标优化级别优化后的模型」生成裁剪 Runtime 的算子并集配置。

ONNX Runtime 文档要求 reduced build 的算子配置覆盖运行时图优化会新造的算子：
EXTENDED 及以上级别的优化器会在会话初始化时把 Conv+ReLU 等模式融合为
com.microsoft.FusedConv 等 contrib 节点。只统计原始静态图（r2 之前
verify-spatial-model-operators.py 的做法）会漏掉这些算子——r2 正因此在真机上
出现 AOT-GAN 自检失败：
ORT_NOT_IMPLEMENTED, Failed to find kernel for com.microsoft.FusedConv(1)。

本脚本对每个模型分别在 BASIC 与 EXTENDED 两档转储优化后模型，把静态图与两档
优化产物的算子并集写入配置。App 在仅含静态算子的 Runtime（r2）上必须使用
BASIC 级会话；用本配置重建的 Runtime（r3+）经真机自检通过后，才允许恢复
EXTENDED/ALL 级会话。

注意：
- 应使用与 Android Runtime 相同（或尽量接近）的 onnxruntime 版本运行；
  实际使用的版本会写入配置头部注释，重建 r3 前须人工核对。
- 布局转换（NHWC/FP16 等）按 D42 由非 minimal 构建自动保留，不依赖本配置。

用法：
  python generate-ort-required-operators.py \
      --model <path.onnx> [--model ...] --output ort-required-operators.config
"""

from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

import onnx
import onnxruntime as ort


def graph_operators(graph: onnx.GraphProto) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    for node in graph.node:
        result.add((node.domain or "ai.onnx", node.op_type))
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                result.update(graph_operators(attribute.g))
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for nested in attribute.graphs:
                    result.update(graph_operators(nested))
    return result


def optimized_operators(
    model_path: Path,
    level: "ort.GraphOptimizationLevel",
    dump_path: Path,
) -> set[tuple[str, str]]:
    options = ort.SessionOptions()
    options.graph_optimization_level = level
    options.optimized_model_filepath = str(dump_path)
    ort.InferenceSession(
        str(model_path),
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )
    optimized = onnx.load(dump_path, load_external_data=False)
    return graph_operators(optimized.graph)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    # (domain, opset) -> operators
    merged: dict[tuple[str, int], set[str]] = {}
    per_model_lines: list[str] = []

    with tempfile.TemporaryDirectory() as raw_temp:
        temp = Path(raw_temp)
        for model_path in args.model:
            model_path = model_path.resolve()
            model = onnx.load(model_path, load_external_data=False)
            onnx.checker.check_model(model)
            imports = {
                (item.domain or "ai.onnx"): int(item.version)
                for item in model.opset_import
            }
            static_ops = graph_operators(model.graph)
            optimizer_ops: set[tuple[str, str]] = set()
            for suffix, level in (
                ("basic", ort.GraphOptimizationLevel.ORT_ENABLE_BASIC),
                ("extended", ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED),
            ):
                optimizer_ops |= optimized_operators(
                    model_path,
                    level,
                    temp / f"{model_path.stem}-{suffix}.onnx",
                )
            introduced = sorted(
                operator for operator in optimizer_ops if operator not in static_ops
            )
            per_model_lines.append(
                f"# {model_path.name}: opset {imports.get('ai.onnx')}, "
                f"优化器新增 {introduced if introduced else '无'}"
            )
            for domain, op_type in static_ops | optimizer_ops:
                # ai.onnx 按模型声明的 opset 归组；contrib 域（com.microsoft 等）
                # 的 kernel 均注册为 since-version 1。
                opset = imports.get(domain, 1) if domain == "ai.onnx" else 1
                merged.setdefault((domain, opset), set()).add(op_type)

    lines = [
        "# 由 generate-ort-required-operators.py 生成：传入各模型在"
        " BASIC+EXTENDED 优化后的算子并集（逐模型清单见下方注释行）。",
        f"# onnxruntime {ort.__version__} / onnx {onnx.__version__}；"
        "重建 Runtime 前核对该版本与 Android Runtime 一致或相近。",
        "# 更新任一模型后必须重新运行本脚本，再重建 Runtime；"
        "同时运行 verify-spatial-model-operators.py 做静态回归。",
    ]
    lines.extend(per_model_lines)
    for (domain, opset), operators in sorted(merged.items()):
        lines.append(f"{domain};{opset};{','.join(sorted(operators))}")
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
