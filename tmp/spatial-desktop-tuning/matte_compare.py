# -*- coding: utf-8 -*-
"""matte 选型对照：多个 BiRefNet 变体在人物场景上的原始轮廓全尺寸叠加。

D129 教训：小图 IoU 会把气球圈误读成紧贴人形，验收一律全尺寸轮廓叠加目检。
输出 qa/matte-compare/<scene>-<model>.jpg（原图 + 彩色轮廓）与终端统计。
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(r"E:\projects\EverythingDone\tmp\spatial-desktop-tuning")
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

MODELS = {
    "distill3m": r"E:\projects\EverythingDone\tmp\birefnet-lite\onnx\model.onnx",
    "tiny214m": r"E:\projects\EverythingDone\tmp\birefnet-official\BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx",
    "portrait": r"E:\projects\EverythingDone\tmp\birefnet-official\BiRefNet-portrait-epoch_150.onnx",
}
COLORS = {"distill3m": (0, 255, 0), "tiny214m": (255, 160, 0), "portrait": (0, 0, 255)}


def run_model(path: str, img_rgb: np.ndarray) -> tuple[np.ndarray, float]:
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    inp = session.get_inputs()[0]
    side_h = inp.shape[2] if isinstance(inp.shape[2], int) else 1024
    side_w = inp.shape[3] if isinstance(inp.shape[3], int) else 1024
    x = cv2.resize(img_rgb, (side_w, side_h), interpolation=cv2.INTER_AREA)
    x = ((x.astype(np.float32) / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None]
    started = time.perf_counter()
    out = session.run(None, {inp.name: x})
    seconds = time.perf_counter() - started
    logits = np.squeeze(out[-1]).astype(np.float32)  # BiRefNet 多输出时最后一个为最终图
    if logits.ndim == 3:
        logits = logits[-1]
    alpha = 1.0 / (1.0 + np.exp(-logits)) if logits.min() < -0.1 or logits.max() > 1.1 else logits
    h, w = img_rgb.shape[:2]
    return cv2.resize(alpha, (w, h), interpolation=cv2.INTER_LINEAR), seconds


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", type=str, default="00_original_single,01_original_double,08_person_pet")
    parser.add_argument("--models", type=str, default="distill3m,tiny214m,portrait")
    args = parser.parse_args()
    out_dir = ROOT / "qa" / "matte-compare"
    out_dir.mkdir(parents=True, exist_ok=True)

    for scene in args.scenes.split(","):
        scene = scene.strip()
        img_bgr = cv2.imread(str(ROOT / "assets" / scene / "center.jpg"))
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
        overlay = img_bgr.copy()
        for name in args.models.split(","):
            name = name.strip()
            path = MODELS[name]
            if not Path(path).is_file():
                print(f"[{scene}] {name}: missing, skip")
                continue
            alpha, seconds = run_model(path, img_rgb)
            heat = cv2.applyColorMap(
                np.rint(np.clip(alpha, 0.0, 1.0) * 255.0).astype(np.uint8),
                cv2.COLORMAP_TURBO,
            )
            cv2.imwrite(str(out_dir / f"{scene}-{name}-alpha.png"), heat)
            core = (alpha > 0.5).astype(np.uint8)
            edge = cv2.dilate(core, np.ones((3, 3), np.uint8)) - cv2.erode(core, np.ones((3, 3), np.uint8))
            overlay[edge > 0] = COLORS[name]
            single = img_bgr.copy()
            single[edge > 0] = COLORS[name]
            cv2.imwrite(str(out_dir / f"{scene}-{name}.jpg"), single)
            print(f"[{scene}] {name}: fg {core.mean():.3f}  {seconds:.1f}s")
        cv2.imwrite(str(out_dir / f"{scene}-ALL.jpg"), overlay)
    print("done ->", out_dir)


if __name__ == "__main__":
    main()
