#!/usr/bin/env python
"""量化分层成品路径（flow_coeffs_front / back）的形变与空间感。

meta.json 里的 `safeShapeMetrics` 描述的是 `flow_coeffs_safe`，而双层查看器根本不读
那个文件——它读 front/back。也就是说**成品实际走的两个场从来没有过形状门禁**。
本工具直接在查看器真正采样的映射上算：

    q(p) = p + flow(p, θ) · f          （guide 像素坐标，与着色器一致）
    J = I + ∇flow

- 非相似形变 = (σ1 − σ2) / (σ1 + σ2)，纯旋转/平移为 0，各向异性拉伸为正；
- 局部缩放   = |√(σ1σ2) − 1|；
- 橡皮形变   = 扣除主体区域最佳整体仿射后的非相似形变（把"一致的视角变化"排除）；
- 折返       = det(J) ≤ 0 的像素占比（映射不再单射，必然出现重影）。

空间感同时报：前/背景场在主体边界处的相对位移跨度（px@720）。三者要一起看——
历史上每次单独修其中一项都会打坏另外两项。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def jacobian_metrics(flow: np.ndarray, mask: np.ndarray) -> dict[str, float]:
    """flow 单位为 guide 像素，形状 (H, W, 2)。"""
    dxdx, dxdy = np.gradient(flow[..., 0], axis=(1, 0))
    dydx, dydy = np.gradient(flow[..., 1], axis=(1, 0))
    a, b = 1.0 + dxdx, dxdy
    c, d = dydx, 1.0 + dydy
    # 2x2 奇异值闭式解
    e, f_ = (a + d) / 2.0, (a - d) / 2.0
    g, h = (c + b) / 2.0, (c - b) / 2.0
    q = np.hypot(e, h)
    r = np.hypot(f_, g)
    s1, s2 = q + r, np.abs(q - r)
    det = a * d - b * c
    sel = mask & np.isfinite(s1) & np.isfinite(s2)
    nonsim = (s1 - s2) / np.maximum(s1 + s2, 1e-6)
    scale = np.abs(np.sqrt(np.maximum(s1 * s2, 0.0)) - 1.0)
    return {
        "nonSimP99": float(np.percentile(nonsim[sel], 99.0)),
        "scaleP99": float(np.percentile(scale[sel], 99.0)),
        "foldShare": float((det[sel] <= 0).mean()),
    }


def best_affine_residual(flow: np.ndarray, mask: np.ndarray) -> float:
    """扣除主体区最佳整体仿射后的非相似形变 p99（"橡皮"形变）。"""
    h, w = mask.shape
    yy, xx = np.mgrid[0:h, 0:w]
    sel = mask
    design = np.stack([xx[sel], yy[sel], np.ones(int(sel.sum()))], axis=1)
    residual = np.zeros_like(flow)
    for ch in (0, 1):
        coef, *_ = np.linalg.lstsq(design, flow[..., ch][sel], rcond=None)
        fitted = coef[0] * xx + coef[1] * yy + coef[2]
        residual[..., ch] = flow[..., ch] - fitted
    return jacobian_metrics(residual, mask)["nonSimP99"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=None)
    ap.add_argument("--directions", type=int, default=48)
    ap.add_argument("--strength", type=float, default=1.0)
    args = ap.parse_args()

    scenes = args.scenes or [
        p.name for p in sorted(args.assets.iterdir())
        if p.is_dir() and not p.name.endswith(("-baseline", ".tmp"))
    ]
    print(f"{'scene':22s} {'nonSim p99':>10s} {'scale p99':>10s} {'rubber p99':>11s} "
          f"{'fold%':>7s} {'相对视差 px@720':>16s}")
    for scene in scenes:
        base = args.assets / scene
        meta = json.loads((base / "meta.json").read_text(encoding="utf-8"))
        if not meta.get("layered"):
            print(f"{scene:22s} 单层，跳过")
            continue
        gw, gh = meta["guideWidth"], meta["guideHeight"]
        px720 = 720.0 / max(meta["centerWidth"], meta["centerHeight"]) * (
            max(meta["centerWidth"], meta["centerHeight"]) / max(gw, gh)
        )
        front = np.fromfile(base / "flow_coeffs_front.bin", dtype=np.float32).reshape(-1, gh, gw, 2)
        back = np.fromfile(base / "flow_coeffs_back.bin", dtype=np.float32).reshape(-1, gh, gw, 2)
        matte = np.asarray(Image.open(base / "matte.png").convert("L")).astype(np.float32) / 255.0
        subject = np.asarray(
            Image.fromarray((matte * 255).astype(np.uint8)).resize((gw, gh), Image.BILINEAR)
        ) > 128

        worst = {"nonSimP99": 0.0, "scaleP99": 0.0, "foldShare": 0.0}
        worst_rubber, spans = 0.0, []
        for k in range(args.directions):
            t = 2.0 * np.pi * k / args.directions
            cos_t, sin_t = float(np.cos(t)), float(np.sin(t))
            ff = (front[0] + front[1] * cos_t + front[2] * sin_t) * args.strength
            fb = (back[0] + back[1] * cos_t + back[2] * sin_t) * args.strength
            met = jacobian_metrics(ff, subject)
            for key in worst:
                worst[key] = max(worst[key], met[key])
            worst_rubber = max(worst_rubber, best_affine_residual(ff, subject))
            rel = np.linalg.norm((ff - fb)[subject], axis=-1)
            spans.append(float(np.percentile(rel, 95.0)) * px720)
        print(f"{scene:22s} {worst['nonSimP99']*100:9.1f}% {worst['scaleP99']*100:9.1f}% "
              f"{worst_rubber*100:10.1f}% {worst['foldShare']*100:6.2f}% "
              f"{np.median(spans):15.1f}")


if __name__ == "__main__":
    main()
