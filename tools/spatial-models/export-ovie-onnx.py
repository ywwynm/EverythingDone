#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 OVIE v1.0 checkpoint 导出为静态 shape 的 ONNX，并做 PyTorch/ORT 数值一致性核对。

用法（仓库根目录）：
  python tools/spatial-models/export-ovie-onnx.py \
      --repo tmp/ovie-research --model-dir tmp/ovie-model-v1.0 \
      --output tmp/ovie-onnx/ovie-v1.0-256-fp32.onnx

契约（与 tmp/ovie-research/run_spatial_probe.py 一致）：
  - image: float32 [1,3,256,256]，RGB，[0,1]（无 ImageNet 标准化）
  - cam:   float32 [1,7] = 平移 (x,y,z) + 四元数 (x,y,z,w)；零视角 = [0,0,0, 0,0,0,1]
    （与 run_spatial_probe.py 的 identity_quaternion 一致：quaternion[:, 3] = w = 1）
  - 输出:  float32 [1,3,256,256]，sigmoid 后 RGB [0,1]
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("tmp/ovie-research"))
    parser.add_argument("--model-dir", type=Path, default=Path("tmp/ovie-model-v1.0"))
    parser.add_argument(
        "--output", type=Path, default=Path("tmp/ovie-onnx/ovie-v1.0-256-fp32.onnx")
    )
    parser.add_argument("--opset", type=int, default=18)
    parser.add_argument(
        "--parity-runs", type=int, default=3, help="随机输入一致性核对次数"
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repo = args.repo.resolve()
    sys.path.insert(0, str(repo))

    import torch  # noqa: E402
    from safetensors.torch import load_file  # noqa: E402
    from models.models import Attention, OVIEModel  # noqa: E402

    config = json.loads((args.model_dir / "config.json").read_text(encoding="utf-8"))
    model = OVIEModel(**config)
    state = load_file(str(args.model_dir / "model.safetensors"), device="cpu")
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing or unexpected:
        raise SystemExit(f"state_dict mismatch: missing={missing} unexpected={unexpected}")
    model.eval()

    # 关闭 fused SDPA，走 matmul+softmax 手写路径：导出图可预测，便于后续量化/QNN。
    fused = 0
    for module in model.modules():
        if isinstance(module, Attention):
            module.fused_attn = False
            fused += 1
    print(f"attention modules set to unfused: {fused}")

    size = int(config["image_size"])
    cam_dim = int(config["in_cam_params"])

    class Wrapper(torch.nn.Module):
        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, image: torch.Tensor, cam: torch.Tensor) -> torch.Tensor:
            return self.inner(x=image, cam_params=cam)

    wrapper = Wrapper(model).eval()

    example_image = torch.rand(1, 3, size, size, dtype=torch.float32)
    example_cam = torch.zeros(1, cam_dim, dtype=torch.float32)
    example_cam[0, 6] = 1.0  # 单位四元数 w（xyzw 顺序的最后一位）

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(
            wrapper,
            (example_image, example_cam),
            str(args.output),
            input_names=["image", "cam"],
            output_names=["rgb"],
            opset_version=args.opset,
            do_constant_folding=True,
            dynamic_axes=None,  # 全静态 shape：端侧/QNN 契约
        )
    import onnx  # noqa: E402
    from onnx import TensorProto, helper, numpy_helper  # noqa: E402

    # torch 2.12 dynamo 导出器默认把权重写到 <name>.onnx.data；合并为自包含单文件，
    # 便于量化、设备推送与 catalog 单文件哈希。
    onnx_model = onnx.load(str(args.output))  # load 时按同目录解析外部数据
    data_sidecar = args.output.parent / (args.output.name + ".data")

    # 现有裁剪 Runtime 算子并集不含 InstanceNormalization（GroupNorm 分解产物）。
    # 静态 shape 下将其重写为 ReduceMean/Sub/Mul/Sqrt/Div/Add 基础算子，使 FP32 模型
    # 可直接在 r6 Runtime 上运行端侧耗时探针。
    inits = {init.name: init for init in onnx_model.graph.initializer}
    # dynamo 的 GroupNorm 分解模式是 reshape 到 [N, G, 其余]（rank 3）再做
    # InstanceNormalization，因此归一化轴必须按实际 rank 推断，不能假设 [N,C,H,W]。
    inferred = onnx.shape_inference.infer_shapes(onnx_model)
    rank_map: dict[str, int] = {}
    for value in list(inferred.graph.value_info) + list(inferred.graph.input) + list(inferred.graph.output):
        rank_map[value.name] = len(value.type.tensor_type.shape.dim)
    new_nodes = []
    rewritten = 0
    for node in onnx_model.graph.node:
        if node.op_type != "InstanceNormalization":
            new_nodes.append(node)
            continue
        rewritten += 1
        x, scale_name, bias_name = node.input
        out = node.output[0]
        if x not in rank_map:
            raise SystemExit(f"cannot infer rank for InstanceNormalization input {x}")
        rank = rank_map[x]
        eps = 1e-5
        for attr in node.attribute:
            if attr.name == "epsilon":
                eps = float(attr.f)
        prefix = f"instnorm_rw_{rewritten}"
        # scale/bias 是 [C] 初始化器：离线 reshape 为 [1, C, 1...]（对齐实际 rank）
        target_shape = (1, -1) + (1,) * (rank - 2)
        for src_name, tag in ((scale_name, "scale"), (bias_name, "bias")):
            arr = numpy_helper.to_array(inits[src_name]).reshape(target_shape)
            onnx_model.graph.initializer.append(
                numpy_helper.from_array(arr, name=f"{prefix}_{tag}")
            )
        onnx_model.graph.initializer.append(
            numpy_helper.from_array(
                np.arange(2, rank, dtype=np.int64), name=f"{prefix}_axes"
            )
        )
        onnx_model.graph.initializer.append(
            numpy_helper.from_array(
                np.array(eps, dtype=np.float32), name=f"{prefix}_eps"
            )
        )
        n = [
            helper.make_node("ReduceMean", [x, f"{prefix}_axes"], [f"{prefix}_mean"], keepdims=1),
            helper.make_node("Sub", [x, f"{prefix}_mean"], [f"{prefix}_xc"]),
            helper.make_node("Mul", [f"{prefix}_xc", f"{prefix}_xc"], [f"{prefix}_sq"]),
            helper.make_node("ReduceMean", [f"{prefix}_sq", f"{prefix}_axes"], [f"{prefix}_var"], keepdims=1),
            helper.make_node("Add", [f"{prefix}_var", f"{prefix}_eps"], [f"{prefix}_vare"]),
            helper.make_node("Sqrt", [f"{prefix}_vare"], [f"{prefix}_std"]),
            helper.make_node("Div", [f"{prefix}_xc", f"{prefix}_std"], [f"{prefix}_norm"]),
            helper.make_node("Mul", [f"{prefix}_norm", f"{prefix}_scale"], [f"{prefix}_scaled"]),
            helper.make_node("Add", [f"{prefix}_scaled", f"{prefix}_bias"], [out]),
        ]
        new_nodes.extend(n)
    del onnx_model.graph.node[:]
    onnx_model.graph.node.extend(new_nodes)
    print(f"InstanceNormalization rewritten: {rewritten}")

    # exporter 会留下个别错误的 value_info 标注（如 cam 嵌入路径 (7) vs (768)），
    # 触发下游量化的 strict 形状推断冲突；value_info 是可选项，清空后由推断重建。
    # 同时剪掉未被任何节点引用的初始化器（exporter 残留，ORT 加载时告警）。
    del onnx_model.graph.value_info[:]
    used: set[str] = set()
    for node in onnx_model.graph.node:
        used.update(node.input)
    keep = [init for init in onnx_model.graph.initializer if init.name in used]
    removed = len(onnx_model.graph.initializer) - len(keep)
    del onnx_model.graph.initializer[:]
    onnx_model.graph.initializer.extend(keep)
    print(f"value_info cleared; unused initializers removed: {removed}")

    onnx.save_model(onnx_model, str(args.output))
    if data_sidecar.exists():
        data_sidecar.unlink()
    print(f"exported (self-contained): {args.output} ({args.output.stat().st_size} bytes)")

    onnx_model = onnx.load(str(args.output))
    onnx.checker.check_model(str(args.output))
    op_types = sorted({node.op_type for node in onnx_model.graph.node})
    print(f"op types ({len(op_types)}): {', '.join(op_types)}")

    import onnxruntime as ort  # noqa: E402

    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(20260807)
    worst = 0.0
    with torch.inference_mode():
        for run in range(args.parity_runs):
            image = rng.random((1, 3, size, size), dtype=np.float32)
            cam = np.zeros((1, cam_dim), dtype=np.float32)
            cam[0, :3] = rng.uniform(-0.1, 0.1, size=3).astype(np.float32)
            cam[0, 6] = 1.0
            torch_out = wrapper(torch.from_numpy(image), torch.from_numpy(cam)).numpy()
            ort_out = session.run(["rgb"], {"image": image, "cam": cam})[0]
            diff = float(np.abs(torch_out - ort_out).max())
            worst = max(worst, diff)
            print(f"parity run {run}: max abs diff = {diff:.3e}")
    print(f"worst max abs diff = {worst:.3e}")
    if worst >= 1e-3:
        raise SystemExit("FAIL: parity worse than 1e-3")
    print("PASS")


if __name__ == "__main__":
    main()
