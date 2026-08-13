"""软 α 通路第一段：把 BiRefNet_lite 的**未硬化** α 落盘成 `matte_soft.png`。

为什么要单独一张图（2026-08-11）：
`matte.png` 有两个互相冲突的消费者。`segment_occluders.py` 要的是**二值区域标签**
（遮挡物身份，与 SAM 3 取并集）；软边界要的是**连续覆盖率**。`generate_assets.py`
为前者服务，对模型 α 连做两次硬化——guide 档 `smoothstep((α−0.35)/0.30)`（第 638
行）、hr 档 `decision_hr≥0.95→1 / ≤0.05→0`（第 1389 行）——落盘后剪影环内中间值
只剩 0.07%–1.24%，而模型原生输出是 8.81%–26.90%。同一张图满足不了两个相反的要求，
所以软 α 走独立产物，`matte.png` 的二值语义原地不动。

本脚本刻意**不做任何**卫生层/收窄/亮远剥离：
- 收窄与剥离正是把发缘、肩缘的软过渡删掉的那两步，而那里恰好是本任务的对象；
- 远处的孤立误检由下一段（沿断边生成过渡带）在空间上排除——α 只在过渡带内被查询，
  带外不参与，因此不需要在这里先验地裁剪。
未裁剪的模型原生输出（1024²、16-bit）一并落盘，任何后续判断都能回到它复核。

推理契约与 `generate_assets.run_matting_alpha` 的 birefnet 分支逐项一致：方形 1024²
INTER_AREA、ImageNet 归一化、取 `outputs[-1]`（多输出 ONNX 的最后一路才是最终融合
结果，第 0 路是粗预测）、sigmoid。输入取 `center.jpg` 而非语料原图——D131 定案：
所有推理派生量以最终编码后的中心图为准。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32)

SCENES = [
    "00_original_single", "01_original_double", "02_indoor", "03_office",
    "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet",
]


def run_birefnet(sess: ort.InferenceSession, image: np.ndarray) -> np.ndarray:
    """返回 1024² 上的原生 α，不做任何后处理。"""
    resized = cv2.resize(image, (1024, 1024), interpolation=cv2.INTER_AREA)
    tensor = ((resized.astype(np.float32) / 255.0 - IMAGENET_MEAN)
              / IMAGENET_STD).transpose(2, 0, 1)[None]
    logits = sess.run(None, {sess.get_inputs()[0].name: tensor})[-1].reshape(1024, 1024)
    return (1.0 / (1.0 + np.exp(-logits.astype(np.float32)))).astype(np.float32)


def ring_soft(alpha: np.ndarray, ring_px: int = 8,
              lo: float = 0.05, hi: float = 0.95) -> tuple[float, float, int]:
    """剪影环内中间值 α 占比。

    判据取环而不取全图：硬边也有 1px 的插值台阶，全图占比被大片纯 0/1 稀释，
    两种边分不开。环 = 二值核（α>0.5）的 dilate−erode，宽 2·ring_px；硬边填不满
    这个环，软边才填得满。
    """
    core = (alpha > 0.5).astype(np.uint8)
    k = np.ones((2 * ring_px + 1, 2 * ring_px + 1), np.uint8)
    ring = (cv2.dilate(core, k) - cv2.erode(core, k)) > 0
    mid = (alpha > lo) & (alpha < hi)
    n = int(ring.sum())
    return (100.0 * float(mid.mean()),
            100.0 * float((mid & ring).sum()) / n if n else 0.0,
            n)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--model", type=Path, default=Path("tmp/birefnet-lite/onnx/model.onnx"))
    ap.add_argument("--ring-px", type=int, default=8,
                    help="统计用的剪影环半宽（不影响落盘内容）")
    ap.add_argument("--probe-dir", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/matte-soft-probe"),
                    help="未裁剪原始输出与统计的存放处")
    args = ap.parse_args()

    args.probe_dir.mkdir(parents=True, exist_ok=True)
    sess = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])

    print(f"{'场景':<22} {'尺寸':>10} {'软α前景%':>9} "
          f"{'环内软%':>8} {'matte环内软%':>12} {'倍数':>6}")
    print("-" * 74)
    stats: dict[str, dict] = {}
    for scene in args.scenes:
        scene_dir = args.assets / scene
        center_p = scene_dir / "center.jpg"
        if not center_p.is_file():
            print(f"{scene:<22} 缺 center.jpg，跳过")
            continue
        center = np.asarray(Image.open(center_p).convert("RGB"))
        h, w = center.shape[:2]

        raw = run_birefnet(sess, center)
        # 未裁剪原始输出：模型原生 1024²、16-bit、不阈值。任何后续结论都能回到它复核。
        Image.fromarray(np.rint(raw * 65535.0).astype(np.uint16)) \
            .save(args.probe_dir / f"{scene}_soft_raw1024.png")

        soft = np.clip(cv2.resize(raw, (w, h), interpolation=cv2.INTER_LINEAR), 0.0, 1.0)
        Image.fromarray(np.rint(soft * 255.0).astype(np.uint8), mode="L") \
            .save(scene_dir / "matte_soft.png")

        g_soft, r_soft, n_ring = ring_soft(soft, args.ring_px)
        hard_p = scene_dir / "matte.png"
        if hard_p.is_file():
            # `matte.png` 在 8 个场景上还是 long-edge 512 时代的旧分辨率（只有 00 是
            # 当前的 720）。对照统计前按最近邻放到同一尺寸，避免把重采样引入的中间值
            # 记到硬 matte 头上。
            hard = np.asarray(Image.open(hard_p).convert("L").resize(
                (w, h), Image.NEAREST)).astype(np.float32) / 255.0
            _, r_hard, _ = ring_soft(hard, args.ring_px)
        else:
            r_hard = float("nan")
        ratio = r_soft / r_hard if r_hard > 0 else float("inf")
        print(f"{scene:<22} {w:>4}×{h:<5} {100 * float((soft > 0.5).mean()):>9.2f} "
              f"{r_soft:>8.2f} {r_hard:>12.2f} {ratio:>6.0f}×")
        stats[scene] = {
            "width": w, "height": h,
            "fgFrac": float((soft > 0.5).mean()),
            "ringSoftPct": r_soft, "ringSoftPctHard": r_hard,
            "globalSoftPct": g_soft, "ringPx": n_ring,
        }

    (args.probe_dir / "soft_matte_stats.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n`matte_soft.png` 已写入各场景资产目录；"
          f"未裁剪原生输出与统计在 {args.probe_dir}")


if __name__ == "__main__":
    main()
