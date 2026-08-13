#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把端上落盘的屏幕空间位移基拉出来，直接验 D204 立下的两条硬判据：

1. **交叉项恒为零**——水平视点位移只产生水平像素位移；
2. **两个主项同源**——`horizontalX/(fx/W)` 与 `verticalY/(fy/H)` 必须是同一个标量场。

用户 2026-08-12 反馈端上"像直接对图片做 warp"，当时实测那份基两条都不满足。这份脚本是
"跟桌面端一致"这句话的可核对版本：不看截图，直接看系数。

格式（SpatialDerivativeStore.writeMotionBasis）：zlib deflate 流，大端，
long magic / int width / int height / 之后每像素 4 个 float（hX, hY, vX, vY）。
"""
import argparse
import json
import struct
import zlib
from pathlib import Path

import numpy as np


def read_basis(path: Path):
    raw = zlib.decompress(path.read_bytes())
    magic, w, h = struct.unpack(">qii", raw[:16])
    body = np.frombuffer(raw[16:16 + w * h * 16], dtype=">f4").reshape(h, w, 4)
    assert len(raw) == 16 + w * h * 16, f"尾随数据 {len(raw) - 16 - w * h * 16} 字节"
    return magic, w, h, [np.ascontiguousarray(body[..., i]).astype(np.float64) for i in range(4)]


def audit(directory: Path, name: str):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    path = directory / "motion-basis.f32z"
    magic, w, h, (hx, hy, vx, vy) = read_basis(path)

    print(f"\n=== {name} ===")
    print(f"  renderer     {manifest['renderer']}  schema {manifest['schemaVersion']}")
    print(f"  深度模型      {manifest['modelId']}   补全 {manifest['inpaintingModelId']}")
    print(f"  基尺寸        {w}x{h}   幅度包络 {manifest['viewEnvelopeAmplitudes'][0]:.4f}"
          f"（真透视档：单位是米）")

    span = lambda a: float(a.max() - a.min())
    print(f"  [1] 交叉项     horizontalY 跨度 {span(hy):.6g}   verticalX 跨度 {span(vx):.6g}")
    cross_ok = span(hy) == 0.0 and span(vx) == 0.0
    print(f"      -> {'恒为零，合格' if cross_ok else '不为零，仍是拟合产物'}")

    # 主项同源：两者除掉各自的 fx/W、fy/H 后必须逐像素相等。fx/fy 不在 manifest 里，
    # 但同源性可以不依赖内参检验——两个场的**相关系数**与**比值恒定性**即可判定。
    a, b = hx.ravel(), vy.ravel()
    finite = np.isfinite(a) & np.isfinite(b)
    a, b = a[finite], b[finite]
    if a.std() < 1e-12 or b.std() < 1e-12:
        print("  [2] 主项同源   两个主项之一是常数场，无法判定")
        return
    corr = float(np.corrcoef(a, b)[0, 1])
    nz = np.abs(b) > 1e-9
    ratio = a[nz] / b[nz]
    rel_spread = float((np.percentile(ratio, 99) - np.percentile(ratio, 1))
                       / max(abs(np.median(ratio)), 1e-12))
    print(f"  [2] 主项同源   相关系数 {corr:.8f}   比值 p1-p99 相对跨度 {rel_spread:.3e}")
    same_ok = corr > 0.999999 and rel_spread < 1e-4
    print(f"      -> {'同源，合格' if same_ok else '不同源，两项各走各的'}")
    print(f"      比值中位 {np.median(ratio):.6f} = (fx/W)/(fy/H) = (fx·H)/(fy·W)")

    # 标量场本身应当是 1/Z0 - 1/Z 的形状：支点处为零，且有正有负
    s = hx / (np.median(ratio) if abs(np.median(ratio)) > 1e-12 else 1.0)
    print(f"  标量场        min {hx.min():+.6g}  中位 {np.median(hx):+.6g}  max {hx.max():+.6g}")
    zero_frac = float(np.mean(np.abs(hx) < 1e-7))
    print(f"      支点附近(|·|<1e-7) 占比 {100 * zero_frac:.2f}%，正 {100*np.mean(hx>0):.1f}% "
          f"负 {100*np.mean(hx<0):.1f}%")


def audit_surfels(directory, name):
    """surfel 标量才是真正出像素的那一份（D210）。按 shader 的公式把它换算回位移，
    与同目录运动基逐点比对——两者必须等价，否则真透视只在磁盘上。"""
    import struct as _s
    import zlib as _z
    p = directory / "depth-surfels.f32z"
    if not p.is_file():
        print("  （无 surfel 数据）")
        return
    raw = _z.decompress(p.read_bytes())
    magic, w, h = _s.unpack(">qii", raw[:16])
    guard, background, parallax = _s.unpack(">fff", raw[16:28])
    scal = np.frombuffer(raw[28:28 + w * h * 4], dtype=">f4").astype(np.float64)
    print(f"  surfel        {w}x{h}  guard {guard:.3f}  底板标量 {background:+.4f}  P {parallax:.2f}")
    print(f"      标量       min {scal.min():+.4f}  中位 {np.median(scal):+.4f}  max {scal.max():+.4f}")

    _, bw, bh, (hx, hy, vx, vy) = read_basis(directory / "motion-basis.f32z")
    if (bw, bh) != (w, h):
        print(f"      ！尺寸不符：基 {bw}x{bh} vs surfel {w}x{h}")
        return
    # shader：Δu = −(视点·幅度)·scalar/(W·P)；基：Δu = 视点·幅度·hx
    shader_hx = -scal / (w * parallax)
    ok = np.allclose(shader_hx, hx.ravel(), rtol=2e-2, atol=1e-6)
    rel = np.abs(shader_hx - hx.ravel()) / np.maximum(np.abs(hx.ravel()), 1e-9)
    finite = np.isfinite(rel) & (np.abs(hx.ravel()) > 1e-6)
    print(f"      与基等价    {'是' if ok else '否'}   "
          f"相对差 中位 {100*np.median(rel[finite]):.3f}%  p99 {100*np.percentile(rel[finite],99):.3f}%")
    if not ok:
        print("      ！surfel 标量与运动基不等价——屏幕上跑的不是真透视")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path, help="拉下来的 derivatives 根目录")
    args = ap.parse_args()
    for d in sorted(args.root.iterdir()):
        if (d / "motion-basis.f32z").is_file() and (d / "manifest.json").is_file():
            audit(d, d.name[:12])
            audit_surfels(d, d.name[:12])
