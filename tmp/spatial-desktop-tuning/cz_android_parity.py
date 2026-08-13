#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""端侧移植的等价性核对：把 `SpatialInpaintingEngine.runLamaTiled` +
`SpatialInpaintingTiling` 的算法**逐行照抄成 Python**，与桌面 `inpaint_onnx_tiled`
在同一张图、同一掩膜上对拍。

没有真机可测的情况下，这是唯一能证明"端上产出的第二层与网页端验收过的那一版是同一个
东西"的办法。照抄的对象是 Kotlin 实现，不是桌面实现——反过来抄等于自己证明自己。

已知的、有意保留的差别只有一处：桌面落盘时是 `astype(np.uint8)`（**截断**），
Kotlin 用 `roundToInt()`（**四舍五入**），最大差 1 级。四舍五入更正确，不复刻截断。
本脚本按 Kotlin 的口径出整数，并单独报出截断口径的差异供核对。
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image

import cz_inpaint as cz
from build_hidden_layer import inpaint_onnx_tiled

TILE, OVERLAP = 512, 128


# --------------------------------------------------- 照抄 SpatialInpaintingTiling

def plan(width: int, height: int, tile: int = TILE, overlap: int = OVERLAP):
    stride = tile - overlap

    def padded_extent(extent):
        if extent <= tile:
            return tile
        steps = -(-(extent - tile) // stride)
        return tile + steps * stride

    def origins(padded):
        last = max(padded - tile, 0)
        return [i * stride for i in range(last // stride + 1)]

    pw, ph = padded_extent(width), padded_extent(height)
    return pw, ph, origins(pw), origins(ph)


def reflect_index(index: int, extent: int) -> int:
    if extent <= 1:
        return 0
    period = 2 * extent - 2
    value = index % period
    if value < 0:
        value += period
    return value if value < extent else period - value


def window(tile: int = TILE, overlap: int = OVERLAP) -> np.ndarray:
    line = np.ones(tile, np.float32)
    if overlap > 0:
        span = 2 * overlap - 1
        for i in range(overlap):
            v = np.float32(0.5 - 0.5 * math.cos(2.0 * math.pi * i / span))
            line[i] = v
            line[tile - 1 - i] = v
    return np.outer(line, line).astype(np.float32)


# --------------------------------------------------- 照抄 runLamaTiled

def run_lama_tiled_kotlin(image: np.ndarray, hidden: np.ndarray, cond: np.ndarray,
                          sess, names) -> np.ndarray:
    h, w = image.shape[:2]
    pw, ph, xs, ys = plan(w, h)
    win = window()
    accum = np.zeros((3, h, w), np.float32)
    weights = np.zeros((h, w), np.float32)
    executed = 0

    for oy in ys:
        for ox in xs:
            if not cond[oy:min(oy + TILE, h), ox:min(ox + TILE, w)].any():
                continue
            executed += 1
            sy = np.array([y if (oy + y) < h else reflect_index(oy + y, h)
                           for y in range(TILE)])
            sy = np.where(np.arange(TILE) + oy < h, np.arange(TILE) + oy, sy)
            sy = np.array([(oy + y) if (oy + y) < h else reflect_index(oy + y, h)
                           for y in range(TILE)])
            sx = np.array([(ox + x) if (ox + x) < w else reflect_index(ox + x, w)
                           for x in range(TILE)])
            patch = image[np.ix_(sy, sx)].astype(np.float32) / 255.0
            inside = np.outer((np.arange(TILE) + oy) < h, (np.arange(TILE) + ox) < w)
            m = np.where(inside, cond[np.ix_(sy, sx)], False).astype(np.float32)
            out = sess.run(None, {names[0]: patch.transpose(2, 0, 1)[None],
                                  names[1]: m[None, None]})[0][0]
            ey, ex = min(oy + TILE, h), min(ox + TILE, w)
            sub = win[:ey - oy, :ex - ox]
            weights[oy:ey, ox:ex] += sub
            accum[:, oy:ey, ox:ex] += out[:, :ey - oy, :ex - ox] * sub
    assert executed > 0

    result = image.astype(np.float32).copy()
    ok = weights > 1e-6
    for c in range(3):
        v = np.where(ok, accum[c] / np.maximum(weights, 1e-6), image[..., c])
        result[..., c] = np.where(hidden & ok, v, image[..., c])
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", default="00_original_single")
    ap.add_argument("--base", default="_b45")
    args = ap.parse_args()

    g, a = Path("qa/moge-geometry") / args.scene, Path("assets") / args.scene
    image = np.asarray(Image.open(a / "center.jpg").convert("RGB"))
    band = np.asarray(Image.open(g / f"hidden_mask{args.base}.png").convert("L")) > 127
    hole = np.asarray(Image.open(g / f"hidden_paint{args.base}.png").convert("L")) > 127

    ort = cz._import_ort()
    sess = ort.InferenceSession(str(cz.LAMA_ONNX), providers=["CPUExecutionProvider"])
    names = [i.name for i in sess.get_inputs()]

    print(f"{args.scene}  {image.shape[1]}x{image.shape[0]}  带 {int(band.sum())} px")
    pw, ph, xs, ys = plan(image.shape[1], image.shape[0])
    print(f"  Kotlin 分块：padded {pw}x{ph}，块起点 x={xs} y={ys}")

    kot = run_lama_tiled_kotlin(image, band, hole, sess, names)
    desk = inpaint_onnx_tiled(image.astype(np.float32), hole, cz.LAMA_ONNX)
    desk = np.where(band[..., None], desk, image.astype(np.float32))

    d = np.abs(kot - desk)
    print(f"  浮点域最大差 {d.max():.6f} 级，平均 {d.mean():.8f} 级")
    ki = np.clip(np.rint(kot), 0, 255).astype(np.uint8)          # Kotlin：四舍五入
    di = np.clip(desk, 0, 255).astype(np.uint8)                  # 桌面：截断
    di_r = np.clip(np.rint(desk), 0, 255).astype(np.uint8)
    print(f"  整数域（Kotlin 四舍五入 vs 桌面截断）最大差 {int(np.abs(ki.astype(int)-di.astype(int)).max())} 级，"
          f"不同像素占比 {100*(ki != di).mean():.3f}%")
    print(f"  整数域（同取四舍五入）最大差 {int(np.abs(ki.astype(int)-di_r.astype(int)).max())} 级，"
          f"不同像素占比 {100*(ki != di_r).mean():.3f}%")


if __name__ == "__main__":
    main()
