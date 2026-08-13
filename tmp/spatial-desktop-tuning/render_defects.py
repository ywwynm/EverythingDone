#!/usr/bin/env python
"""渲染侧缺陷的客观指标：暗色轮廓条带、剪影锯齿、孤立散点、空洞。

定位剪影不靠猜：同一视角再渲一张"第二层染品红"，品红区就是第二层可见的地方，
它的边界**就是第一层的断边剪影**。于是三项指标都能落到确切位置上：

- 暗边：剪影内侧 1–3px 的亮度 减去 更内侧 6–12px 的亮度。负值＝存在暗色轮廓条带。
- 锯齿：剪影轮廓的周长 除以 形态学平滑后轮廓的周长。1.0＝光滑，越大越锯。
- 散点：第二层区域内，与 5×5 邻域中位数差异过大的孤立像素占比（马赛克／小方块）。
- 空洞：完全没被写到的像素占比。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

MAGENTA = np.array([255, 0, 229], np.float32)   # 与查看器 uTint 的染色一致


def second_layer_mask(tinted: np.ndarray) -> np.ndarray:
    d = np.abs(tinted.astype(np.float32) - MAGENTA).sum(2)
    return d < 90


def ring(mask: np.ndarray, lo: int, hi: int) -> np.ndarray:
    """掩膜**外侧**（即第一层一侧）距离在 [lo, hi) 的环。"""
    dist = cv2.distanceTransform((~mask).astype(np.uint8), cv2.DIST_L2, 5)
    return (~mask) & (dist >= lo) & (dist < hi)


def geom_roughness(mask: np.ndarray) -> float:
    """轮廓的几何粗糙度：原始周长 ÷ 形态学平滑后周长。

    注意这一项**对超采样不敏感**——它量的是断边掩膜的几何形状，而超采样改的是
    跨边界的明暗过渡，不改几何。用来判断断边判定本身是否在游走，别拿它验收抗锯齿。
    """
    m = mask.astype(np.uint8)
    smooth = cv2.morphologyEx(cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8)),
                              cv2.MORPH_OPEN, np.ones((7, 7), np.uint8))
    per = lambda a: sum(cv2.arcLength(c, True) for c in
                        cv2.findContours(a, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)[0])
    p0, p1 = per(m), per(smooth)
    return float(p0 / p1) if p1 > 1 else float("nan")


def step_energy(img: np.ndarray, mask: np.ndarray) -> float:
    """台阶能量：剪影环上的二阶导 RMS ÷ 内侧参照环上的同一量。

    锯齿在图像上的表现是**跨边界的硬台阶**——一阶导大不算问题（真实剪影本就有边），
    二阶导大才是台阶。除以内侧参照做归一化，抵消"这一带内容本来就更复杂"的影响。
    """
    g = cv2.cvtColor(img.astype(np.float32), cv2.COLOR_RGB2GRAY)
    lap = cv2.Laplacian(g, cv2.CV_32F, ksize=3)
    edge, ref = ring(mask, 0, 3), ring(mask, 6, 13)
    if edge.sum() < 64 or ref.sum() < 64:
        return float("nan")
    e = float(np.sqrt((lap[edge] ** 2).mean()))
    r = float(np.sqrt((lap[ref] ** 2).mean()))
    return e / max(r, 1e-6)


def speckle(img: np.ndarray, area: np.ndarray) -> float:
    g = cv2.cvtColor(img.astype(np.float32), cv2.COLOR_RGB2GRAY)
    med = cv2.medianBlur(g.astype(np.uint8), 5).astype(np.float32)
    return float(((np.abs(g - med) > 22) & area).sum() / max(area.sum(), 1))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True)
    ap.add_argument("--configs", nargs="+", required=True)
    ap.add_argument("--degs", nargs="+", type=int, required=True)
    args = ap.parse_args()

    print(f"{'配置':16s} {'边缘异常(级)':>12s} {'台阶能量':>9s} {'几何粗糙':>9s} "
          f"{'散点%':>7s} {'空洞%':>7s}")
    for cfg in args.configs:
        fringe, rough, step, spk, holes, n = [], [], [], [], [], 0
        for d in args.degs:
            p = args.dir / f"{cfg}-{d}.png"
            pt = args.dir / f"{cfg}-tint-{d}.png"
            if not p.is_file() or not pt.is_file():
                continue
            n += 1
            img = np.asarray(Image.open(p).convert("RGBA"))
            rgb = img[..., :3].astype(np.float32)
            holes.append(float((img[..., 3] < 8).mean()))
            hid = second_layer_mask(np.asarray(Image.open(pt).convert("RGB")))
            g = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
            inner, outer = ring(hid, 1, 4), ring(hid, 6, 13)
            if inner.sum() > 64 and outer.sum() > 64:
                fringe.append(float(np.median(g[inner]) - np.median(g[outer])))
            rough.append(geom_roughness(hid))
            step.append(step_energy(rgb, hid))
            spk.append(speckle(rgb, hid))
        if not n:
            print(f"{cfg:16s}  （无帧）")
            continue
        f = lambda a: float(np.nanmean(a)) if a else float("nan")
        print(f"{cfg:16s} {f(fringe):+12.2f} {f(step):9.3f} {f(rough):9.3f} "
              f"{100*f(spk):7.3f} {100*f(holes):7.3f}")


if __name__ == "__main__":
    main()
