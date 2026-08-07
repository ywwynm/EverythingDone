#!/usr/bin/env python3
"""校验所有发布模型的 ONNX 算子都包含在裁剪 Runtime 配置中。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import onnx


def read_config(path: Path) -> dict[tuple[str, int], set[str]]:
    result: dict[tuple[str, int], set[str]] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        domain, opset, operators = line.split(";", 2)
        result[(domain, int(opset))] = {
            operator.strip()
            for operator in operators.split(",")
            if operator.strip()
        }
    return result


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


def verify_model(
    path: Path,
    config: dict[tuple[str, int], set[str]],
) -> dict:
    model = onnx.load(path, load_external_data=False)
    onnx.checker.check_model(model)
    imports = {
        (item.domain or "ai.onnx"): int(item.version)
        for item in model.opset_import
    }
    operators = graph_operators(model.graph)
    missing = []
    for domain, operator in sorted(operators):
        opset = imports.get(domain)
        if opset is None or operator not in config.get((domain, opset), set()):
            missing.append(
                {
                    "domain": domain,
                    "opset": opset,
                    "operator": operator,
                }
            )
    if missing:
        raise RuntimeError(
            f"{path} 含有 Runtime 配置未覆盖的算子："
            f"{json.dumps(missing, ensure_ascii=False)}"
        )
    return {
        "path": str(path),
        "opsets": imports,
        "operatorCount": len(operators),
        "operators": [
            {"domain": domain, "operator": operator}
            for domain, operator in sorted(operators)
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--model", type=Path, action="append", required=True)
    args = parser.parse_args()

    config = read_config(args.config)
    report = [
        verify_model(model.resolve(), config)
        for model in args.model
    ]
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
