#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""定分辨率之前必须先回答：**MoGe 的几何细节到底由什么决定？**

输出分辨率等于输入分辨率，但 transformer 内部按 `num_tokens` 决定 patch 网格
（patch 14，1800 tokens ≈ 42×43 patch ≈ 588×602 px）。所以喂更大的图很可能只是把
同一份低频结果插值上去——那样"用高分辨率"就是白花时间。

两个自变量分别扫：输入分辨率、num_tokens。判据不是"看着更清楚"，而是可核对的量：

- **断崖锐度**：深度梯度幅值的 p99.9（真细节增加时，强边会更陡）；
- **有效带宽**：把深度降采样到 256 长边再升回来，与原图的差（插值出来的高分辨率
  在这项上接近 0——因为它本来就没有 256 以上的真实内容）；
- 耗时。
"""
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from moge_onnx_probe import ONNX, normalized_view_plane_uv, solve_focal_shift_golden, masked_downsample
import cz_inpaint as cz


def align14(v):
    return max(14, int(v) // 14 * 14)


def run(sess, img_pil, long_edge, tokens):
    w0, h0 = img_pil.size
    s = long_edge / max(w0, h0)
    # 两边**同比**缩放后再各自对齐到 14 —— 与端上当前实现不同，端上是先缩后截，
    # 会引入各向异性（见 followups）。这里先按正确口径量，免得把两件事混在一起。
    tw, th = align14(round(w0 * s)), align14(round(h0 * s))
    a = np.asarray(img_pil.resize((tw, th), Image.LANCZOS)).astype(np.float32) / 255.0
    t0 = time.perf_counter()
    o = dict(zip([x.name for x in sess.get_outputs()],
                 sess.run(None, {"image": a.transpose(2, 0, 1)[None].astype(np.float32),
                                 "num_tokens": np.array(tokens, dtype=np.int64)})))
    dt = time.perf_counter() - t0
    pts = np.asarray(o["points"])[0]
    mask = np.asarray(o["mask"])[0] > 0.5
    scale = float(np.asarray(o["scale"]).reshape(-1)[0])
    uv = normalized_view_plane_uv(tw, th)
    p, u = masked_downsample(pts, uv, mask)
    focal, shift = solve_focal_shift_golden(u, p[:, :2], p[:, 2])
    z = (pts[..., 2] + shift) * scale
    z = np.where(mask & np.isfinite(z), z, np.nan)
    inv = 1.0 / np.clip(z, 1e-3, None)
    return dict(w=tw, h=th, ms=dt * 1000.0, inv=inv,
                fx=focal * ((tw ** 2 + th ** 2) ** 0.5) / 2.0)


def sharpness(inv):
    g = np.hypot(*np.gradient(np.nan_to_num(inv, nan=np.nanmedian(inv))))
    return float(np.percentile(g, 99.9))


def effective_bandwidth(inv):
    """降采样到 256 长边再升回来，与原图的相对差。纯插值出来的高分辨率≈0。"""
    a = np.nan_to_num(inv, nan=np.nanmedian(inv))
    h, w = a.shape
    s = 256.0 / max(w, h)
    small = np.asarray(Image.fromarray(a).resize(
        (max(2, int(w * s)), max(2, int(h * s))), Image.BILINEAR))
    back = np.asarray(Image.fromarray(small).resize((w, h), Image.BILINEAR))
    denom = float(np.percentile(a, 95) - np.percentile(a, 5))
    return float(np.mean(np.abs(a - back)) / max(denom, 1e-9))


if __name__ == "__main__":
    ort = cz._import_ort()
    providers = ["CUDAExecutionProvider", "CPUExecutionProvider"] \
        if "CUDAExecutionProvider" in ort.get_available_providers() else ["CPUExecutionProvider"]
    sess = ort.InferenceSession(str(ONNX), providers=providers)
    print("EP:", sess.get_providers()[0])
    im = Image.open("tmp/spatial-desktop-tuning/assets/00_original_single/center.jpg").convert("RGB")
    print(f"原图 {im.size}\n")

    print("=== 扫输入分辨率（num_tokens 固定 1800）===")
    print(f"{'长边':>6}{'实际':>12}{'耗时ms':>9}{'fx/w':>9}{'断崖锐度':>11}{'有效带宽':>11}")
    for le in (518, 720, 1024, 1440):
        r = run(sess, im, le, 1800)
        print(f"{le:>6}{f'{r[chr(119)]}x{r[chr(104)]}':>12}{r['ms']:>9.0f}"
              f"{r['fx']/r['w']:>9.4f}{sharpness(r['inv']):>11.4f}"
              f"{effective_bandwidth(r['inv']):>11.5f}")

    print("\n=== 扫 num_tokens（输入固定 720 长边）===")
    print(f"{'tokens':>7}{'实际':>12}{'耗时ms':>9}{'fx/w':>9}{'断崖锐度':>11}{'有效带宽':>11}")
    for tk in (1800, 3600, 7200):
        r = run(sess, im, 720, tk)
        print(f"{tk:>7}{f'{r[chr(119)]}x{r[chr(104)]}':>12}{r['ms']:>9.0f}"
              f"{r['fx']/r['w']:>9.4f}{sharpness(r['inv']):>11.4f}"
              f"{effective_bandwidth(r['inv']):>11.5f}")
