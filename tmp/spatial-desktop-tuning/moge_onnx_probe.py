#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""MoGe-2 ViT-S 的 ONNX 契约核对 + **可移植的内参恢复**（准备移植到 Android）。

Android 现在用的是 `depth_anything_3_small`：**相对深度、无内参**，所以只能做屏幕空间
位移场，这正是用户看到"像直接对图片做 warp"的根因。MoGe-2 给米制深度与内参，是真透视
重投影的前提（D147/D148 选它的理由）。

ONNX 只吐 `points / normal / mask / scale`，**内参不在图里**——`model.infer()` 里那步
`recover_focal_shift` 是 Python 侧做的，必须自己移植。原实现依赖 scipy 的 LM 最小二乘，
端上没有；但那个问题的结构很好：

    min_shift  Σ | f(shift) · xy/(z+shift) − uv |²
    其中 f(shift) = Σ(xy_proj·uv) / Σ|xy_proj|²   ← 对给定 shift 有**闭式**内解

于是只剩一个一维标量优化。这里用**黄金分割搜索**替掉 LM：无导数、无依赖、确定性，
Kotlin 里几十行就能写完。本脚本验证它与 scipy 版给出的 fx 是否一致。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

ONNX = Path("E:/projects/EverythingDone/tmp/MoGe-research/moge-2-vits-normal.onnx")


def normalized_view_plane_uv(width: int, height: int) -> np.ndarray:
    """与 MoGe `normalized_view_plane_uv_numpy` 同式：按对角线归一、中心为原点。"""
    aspect = width / height
    span_x = aspect / (1 + aspect ** 2) ** 0.5
    span_y = 1 / (1 + aspect ** 2) ** 0.5
    u = np.linspace(-span_x * (1 - 1 / width), span_x * (1 - 1 / width), width, dtype=np.float32)
    v = np.linspace(-span_y * (1 - 1 / height), span_y * (1 - 1 / height), height, dtype=np.float32)
    return np.stack(np.meshgrid(u, v, indexing="xy"), axis=-1)


def focal_for_shift(uv: np.ndarray, xy: np.ndarray, z: np.ndarray, shift: float) -> float:
    proj = xy / (z + shift)[:, None]
    denom = float((proj * proj).sum())
    return float((proj * uv).sum() / denom) if denom > 1e-12 else 0.0


def residual(uv: np.ndarray, xy: np.ndarray, z: np.ndarray, shift: float) -> float:
    proj = xy / (z + shift)[:, None]
    f = focal_for_shift(uv, xy, z, shift)
    return float(((f * proj - uv) ** 2).sum())


def solve_focal_shift_golden(uv, xy, z, iterations: int = 60) -> tuple[float, float]:
    """黄金分割搜索 shift。区间取 [-0.9·z_min, 3·z_span]，覆盖 MoGe 实际的 shift 量级；
    目标函数在该区间上单峰（残差随 shift 先降后升），无导数即可收敛。"""
    lo = -0.9 * float(z.min())
    hi = lo + 4.0 * float(np.percentile(z, 95) - np.percentile(z, 5) + 1e-3)
    phi = (5 ** 0.5 - 1) / 2
    a, b = lo, hi
    c, d = b - phi * (b - a), a + phi * (b - a)
    fc, fd = residual(uv, xy, z, c), residual(uv, xy, z, d)
    for _ in range(iterations):
        if fc < fd:
            b, d, fd = d, c, fc
            c = b - phi * (b - a)
            fc = residual(uv, xy, z, c)
        else:
            a, c, fc = c, d, fd
            d = a + phi * (b - a)
            fd = residual(uv, xy, z, d)
    shift = (a + b) / 2
    return focal_for_shift(uv, xy, z, shift), shift


def masked_downsample(points, uv, mask, size=(64, 64)):
    """按 MoGe 的 masked_nearest_resize 语义取样：每个目标格取其覆盖区内的有效点。"""
    h, w = points.shape[:2]
    ys = np.linspace(0, h - 1, size[1]).astype(int)
    xs = np.linspace(0, w - 1, size[0]).astype(int)
    grid = np.ix_(ys, xs)
    p, u, m = points[grid], uv[grid], mask[grid]
    return p[m], u[m]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/assets/00_original_single/center.jpg"))
    ap.add_argument("--num-tokens", type=int, default=1800)
    ap.add_argument("--compare-torch", action="store_true", help="与 PyTorch infer() 对拍")
    args = ap.parse_args()

    import cz_inpaint as cz
    ort = cz._import_ort()
    sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    print("输入", [(i.name, i.shape) for i in sess.get_inputs()])

    image = np.asarray(Image.open(args.image).convert("RGB")).astype(np.float32) / 255.0
    h, w = image.shape[:2]
    feed = {"image": image.transpose(2, 0, 1)[None].astype(np.float32),
            "num_tokens": np.array(args.num_tokens, dtype=np.int64)}
    out = sess.run(None, feed)
    names = [o.name for o in sess.get_outputs()]
    got = dict(zip(names, out))
    points = np.asarray(got["points"])[0]
    mask = np.asarray(got["mask"])[0] > 0.5
    scale = float(np.asarray(got["scale"]).reshape(-1)[0])
    print(f"points {points.shape}  mask 有效 {100*mask.mean():.1f}%  scale {scale:.4f}")

    uv = normalized_view_plane_uv(points.shape[1], points.shape[0])
    p, u = masked_downsample(points, uv, mask)
    print(f"下采样有效点 {len(p)}")
    focal, shift = solve_focal_shift_golden(u, p[:, :2], p[:, 2])
    # MoGe 的 focal 在 normalized view plane 上：u 的跨度是 2·aspect/√(1+aspect²)，
    # 对应整幅宽度，因此 1 单位 u = width / u_span = **半对角线**像素数。
    # （第一版写成 ×对角线，结果整整大了一倍，与 PyTorch 对不上才查出来。）
    hh, ww = points.shape[0], points.shape[1]
    diag = (hh ** 2 + ww ** 2) ** 0.5
    fx_px = fy_px = focal * diag / 2.0
    print(f"\n黄金分割解：focal(归一化) {focal:.5f}  shift {shift:.5f}")
    print(f"  等效 fx = fy = {fx_px:.1f} px（半对角线 {diag/2:.0f}）  cx={ww/2:.1f} cy={hh/2:.1f}")

    if args.compare_torch:
        import torch
        from moge.model.v2 import MoGeModel
        m = MoGeModel.from_pretrained("Ruicheng/moge-2-vits-normal").eval()
        with torch.no_grad():
            r = m.infer(torch.tensor(image).permute(2, 0, 1), num_tokens=args.num_tokens)
        Kn = r["intrinsics"].cpu().numpy()
        print(f"\nPyTorch infer(): fx_norm {Kn[0,0]:.5f} → fx {Kn[0,0]*w:.1f} px, "
              f"fy {Kn[1,1]*h:.1f} px, cx {Kn[0,2]*w:.1f}, cy {Kn[1,2]*h:.1f}")
        print(f"  ONNX+黄金分割 fx = {fx_px:.1f} px")
        print(f"  相对误差 {100*abs(fx_px - Kn[0,0]*w)/max(Kn[0,0]*w,1e-6):.3f}%")
        # 深度也要对：points 的 z 加上 shift、乘 scale 才是米制 Z
        zt = r["depth"].cpu().numpy()
        zo = (points[..., 2] + shift) * scale
        good = np.isfinite(zt) & np.isfinite(zo) & (zt > 0)
        rel = np.abs(zo[good] - zt[good]) / zt[good]
        print(f"  逐像素 Z 相对误差：中位 {100*np.median(rel):.3f}%  p99 {100*np.percentile(rel,99):.3f}%")


if __name__ == "__main__":
    main()
