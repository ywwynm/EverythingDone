#!/usr/bin/env python
"""几何来源二值实验：DA3 归一化相对视差 vs MoGe-2 度量点图，同跨度下比形变。

D145 的诊断：相机横移时像点位移 Δx = f·t/Z ∝ 1/Z。我们现在的位移是

    flow = (归一化(1/Z_DA3) − 中位数) × depth_scale_px × 方向

而物理正确的是（绕深度 Z0 处的支点转，使主体大致不动）

    Δu = −fx · tx · (1/Z − 1/Z0),   Δv = −fy · ty · (1/Z − 1/Z0)

两者是同一个函数形式：常数 × (1/Z − 常数)。**唯一的差别是 1/Z 准不准。**
因此把两条 1/Z 在**同一目标跨度**下对齐，再比形变，就能二值判定形变是不是深度精度造成的：

- MoGe 版形变显著下降 → 路线改为"正确几何 + 重投影"；
- 两版一样差 → 单目几何精度不足以支撑重投影，必须走生成式新视角。

形变判据沿用 `layered_shape_audit.py` 的 Jacobian 非相似形变（对同一映射、同一统计域），
统计域分主体／背景／四角，与 D140 那张表可直接对照。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image


def nonsim_metrics(flow: np.ndarray, mask: np.ndarray) -> dict[str, float]:
    """flow 单位为像素，形状 (H, W, 2)。与 layered_shape_audit 同式。"""
    dxdx, dxdy = np.gradient(flow[..., 0], axis=(1, 0))
    dydx, dydy = np.gradient(flow[..., 1], axis=(1, 0))
    a, b, c, d = 1.0 + dxdx, dxdy, dydx, 1.0 + dydy
    e, f_ = (a + d) / 2.0, (a - d) / 2.0
    g, h = (c + b) / 2.0, (c - b) / 2.0
    q, r = np.hypot(e, h), np.hypot(f_, g)
    s1, s2 = q + r, np.abs(q - r)
    det = a * d - b * c
    nonsim = (s1 - s2) / np.maximum(s1 + s2, 1e-6)
    scale = np.abs(np.sqrt(np.maximum(s1 * s2, 0.0)) - 1.0)
    sel = mask & np.isfinite(nonsim)
    if sel.sum() < 64:
        return {"nonSimP99": float("nan"), "scaleP99": float("nan"), "foldShare": float("nan")}
    return {
        "nonSimP99": float(np.percentile(nonsim[sel], 99.0)),
        "scaleP99": float(np.percentile(scale[sel], 99.0)),
        "foldShare": float((det[sel] <= 0).mean()),
    }


def field_from_inverse_depth(inv: np.ndarray, pivot: float, direction: np.ndarray,
                             target_span_px: float, mask: np.ndarray) -> np.ndarray:
    """把任意 1/Z 场化成"绕 pivot 转、在统计域内跨度 = target_span_px"的位移场。

    两条路线用同一函数，保证只有 1/Z 本身不同，幅度与支点选择不构成变量。
    """
    centered = inv - pivot
    span = float(np.percentile(centered[mask], 95.0) - np.percentile(centered[mask], 5.0))
    gain = target_span_px / max(abs(span), 1e-9)
    return (centered[..., None] * gain) * direction


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=["00_original_single"])
    ap.add_argument("--model", default="Ruicheng/moge-2-vitl-normal")
    ap.add_argument("--span-px720", type=float, default=24.0)
    ap.add_argument("--directions", type=int, default=48)
    ap.add_argument("--out", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/moge-probe"))
    args = ap.parse_args()

    from moge.model.v2 import MoGeModel

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MoGeModel.from_pretrained(args.model).to(device).eval()
    args.out.mkdir(parents=True, exist_ok=True)

    print(f"{'scene':22s} {'源':>10s} {'主体 p99':>10s} {'背景 p99':>10s} {'四角 p99':>10s} {'折返':>7s}")
    for scene in args.scenes:
        base = args.assets / scene
        meta = json.loads((base / "meta.json").read_text(encoding="utf-8"))
        gw, gh = meta["guideWidth"], meta["guideHeight"]
        image = np.asarray(Image.open(base / "center.jpg").convert("RGB"))
        px720 = 720.0 / max(gw, gh)
        target = args.span_px720 / px720   # 目标跨度换算到 guide 像素

        tensor = torch.tensor(image / 255.0, dtype=torch.float32, device=device).permute(2, 0, 1)
        with torch.no_grad():
            out = model.infer(tensor)
        depth = out["depth"].cpu().numpy()
        valid = out["mask"].cpu().numpy() if "mask" in out else np.isfinite(depth)
        intrinsics = out["intrinsics"].cpu().numpy()
        fx = float(intrinsics[0, 0])
        # 无效／无穷远处的 1/Z 取 0（远平面），避免 inf 污染梯度
        inv_moge = np.where(valid & np.isfinite(depth) & (depth > 1e-6), 1.0 / np.maximum(depth, 1e-6), 0.0)
        inv_moge = cv2.resize(inv_moge.astype(np.float32), (gw, gh), interpolation=cv2.INTER_LINEAR)
        np.save(args.out / f"{scene}-moge-invdepth.npy", inv_moge)
        Image.fromarray(
            # NumPy 2 移除了 ndarray.ptp()，只保留函数形式
            (np.clip((inv_moge - inv_moge.min()) / max(float(np.ptp(inv_moge)), 1e-9), 0, 1) * 255).astype(np.uint8)
        ).save(args.out / f"{scene}-moge-invdepth.png")

        # 现行路线的 1/Z：直接读已发布的 disp（背景域 disp_back、主体域 disp_front，
        # 各自域内是未外推的真实值），与 generate_assets 的 disparity 同源。
        dfront = np.asarray(Image.open(base / "disp_front.png").convert("L")).astype(np.float32) / 255.0
        dback = np.asarray(Image.open(base / "disp_back.png").convert("L")).astype(np.float32) / 255.0
        matte = np.asarray(Image.open(base / "matte.png").convert("L")).astype(np.float32) / 255.0
        core = cv2.resize(matte, (gw, gh), interpolation=cv2.INTER_AREA)
        subject = core > 0.5
        inv_da3 = np.where(subject, dfront, dback)

        bg = ~subject
        corner = np.zeros((gh, gw), bool)
        cw, ch = gw // 5, gh // 5
        for sy, sx in [(0, 0), (0, gw - cw), (gh - ch, 0), (gh - ch, gw - cw)]:
            corner[sy:sy + ch, sx:sx + cw] = True
        corner &= bg

        for label, inv in (("DA3 现行", inv_da3), ("MoGe-2", inv_moge)):
            pivot = float(np.median(inv[subject])) if subject.any() else float(np.median(inv))
            worst = {"subject": 0.0, "bg": 0.0, "corner": 0.0, "fold": 0.0}
            for k in range(args.directions):
                t = 2.0 * np.pi * k / args.directions
                direction = np.array([np.cos(t), np.sin(t)], dtype=np.float32)
                flow = field_from_inverse_depth(inv, pivot, direction, target, np.ones_like(subject, bool))
                ms = nonsim_metrics(flow, subject)
                mb = nonsim_metrics(flow, bg)
                mc = nonsim_metrics(flow, corner)
                worst["subject"] = max(worst["subject"], ms["nonSimP99"])
                worst["bg"] = max(worst["bg"], mb["nonSimP99"])
                worst["corner"] = max(worst["corner"], mc["nonSimP99"])
                worst["fold"] = max(worst["fold"], mb["foldShare"])
            print(f"{scene:22s} {label:>10s} {worst['subject']*100:9.1f}% "
                  f"{worst['bg']*100:9.1f}% {worst['corner']*100:9.1f}% {worst['fold']*100:6.2f}%")
        print(f"{'':22s} {'fx':>10s} {fx:9.3f}   （MoGe 归一化内参）")


if __name__ == "__main__":
    main()
