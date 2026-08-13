#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""自遮挡占比：逐条带像素判"遮挡物与它让出的那片背景是不是同一个物体"。

D176 定下的判据。这个脚本**不跑任何补全**，只用深度 + SAM 3 的实例栈，
用来先验证这个信号能不能把 00 型（自遮挡，`--use-matte` 该关）和 05 型
（独立物体，该开）分到两头。分不开，逐像素两遍补全一定也不成立。

逐带像素 p，**两侧都取带外的真实像素**：
- **遮挡侧** = 离 p 最近、逆深度与 `inv[p]`（p 自己所在的近表面）同层的带外像素 n(p)；
- **背景侧** = 离 p 最近、逆深度与 `inv_layer[p]`（传播出来的背景层级）同层的带外像素 b(p)；
- **同不同**：存在某个实例同时含 n(p) 与 b(p)。实例之间会重叠，所以不能压成单通道
  label 图再比 id——那样"人"和"袖子"两个重叠实例会被迫二选一。

第一版把遮挡侧直接取成 p 自己，**是错的**：带只有十几像素宽，横跨了 matte 的边界，
于是同一条带上 p 从"实例内"走到"实例外"，判定沿带宽分层（内绿、中红、外青），
一条带能给出三种答案。判定属于**剪影**而不是属于单个像素，所以两侧都必须退到带外去取。

同理，最终答案按**带的连通块**投票统一，不逐像素抖动。

会退化的方向要先想清楚：**实例越大越容易判成"同一个"**，极限是一个覆盖全图的实例
把所有带都判成自遮挡。所以同时打印实例的覆盖率分布，并且 SAM 侧已有 `--max-cover 0.70`。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from build_hidden_layer import (  # noqa: E402
    _nearest_at_level, decide_selfocclusion, disocclusion_mask, load_instances,
    propagate_background_depth)


def background_source(inv: np.ndarray, mask: np.ndarray, level: np.ndarray,
                      tol: float, bins: int = 24) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """带内每点 → 与 `level[p]` 同深度的最近带外像素的坐标（yy, xx）与距离。

    判定本身已经搬进 `build_hidden_layer.decide_selfocclusion`（构建时要用同一份），
    这里只留一层薄封装，保证统计脚本与实际构建**用的是同一段代码**。
    """
    return _nearest_at_level(inv.shape, inv, mask, level, tol, bins)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--scenes", nargs="+", required=True)
    ap.add_argument("--mask-tag", default="", help="用哪份带掩膜（产品档为空后缀）")
    ap.add_argument("--spots", default="", help="场景:名字:x,y;… 只看某几处的局部值")
    ap.add_argument("--spot-r", type=int, default=55)
    ap.add_argument("--radius", type=int, default=12, help="局部多数的半径（px）")
    ap.add_argument("--save-map", action="store_true",
                    help="把逐像素判定写成 selfocc_code.png 供查看器的诊断视图用："
                         "0=非带 / 85=同一物体（保守）/ 170=不同物体（激进）/ 255=遮挡侧无实例")
    args = ap.parse_args()

    spots: dict[str, list[tuple[str, int, int]]] = {}
    for item in filter(None, args.spots.split(";")):
        sc, name, xy = item.split(":")
        x, y = (int(float(v)) for v in xy.split(","))
        spots.setdefault(sc, []).append((name, x, y))

    print(f"{'场景':<20}{'带%':>7}{'自遮挡%':>9}{'无实例%':>9}{'实例数':>7}"
          f"{'实例覆盖中位%':>13}{'背景侧距离中位':>15}")
    for scene in args.scenes:
        geo = args.geometry / scene
        meta = json.loads((geo / "moge-meta.json").read_text(encoding="utf-8"))
        w, h = meta["width"], meta["height"]
        z = np.fromfile(geo / "depth_z.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        # 掩膜与背景层级**按构建时的算法现算**，不从 hidden_z.f32 反推——那份被
        # `max(z_layer, z*1.001)` 钳过，反推出来的 inv_layer 与构建时不是同一个量，
        # 统计脚本与实际构建会给出不同的判定（2026-08-11 实际发生过：05 一个 12.1%、
        # 一个 85.5%）。
        baseline = meta["hiddenLayer"]["maxBaseline"]
        mask = cv2.dilate(disocclusion_mask(inv, meta["fx"], baseline).astype(np.uint8),
                          np.ones((3, 3), np.uint8)) > 0
        inv_layer, bg_origin = propagate_background_depth(inv, mask, return_origin=True)
        tol = 3.0 / max(meta["fx"] * baseline, 1e-9)

        inst = load_instances(args.assets / meta["scene"] / "occluder_instances.npz")
        if inst is None:
            print(f"{scene:<20}  缺 occluder_instances.npz，先跑 segment_occluders.py")
            continue
        voted, decidable, same = decide_selfocclusion(inv, mask, meta["fx"], baseline,
                                                      inst, args.radius)
        anyI = decidable
        band = mask.copy()
        dd = np.zeros_like(inv)
        n = int(band.sum())
        if args.save_map:
            code = np.zeros((h, w), np.uint8)
            code[mask & voted] = 85            # 同一物体 -> 保守（留上下文）
            code[mask & ~voted] = 170          # 不同物体 -> 激进（排除）
            code[mask & ~anyI] = 255           # 遮挡侧没落进任何实例：规则无意见
            cv2.imwrite(str(geo / "selfocc_code.png"), code)

        cover = np.array([float(m.mean()) for m in inst])
        print(f"{scene:<20}{100*mask.mean():7.2f}{100*same[band].mean():9.1f}"
              f"{100*(~anyI[band]).mean():9.1f}{len(inst):7d}"
              f"{100*np.median(cover):13.2f}{np.median(dd[band]):15.1f}"
              f"   局部多数后 {100*voted[band].mean():5.1f}%")
        for name, x, y in spots.get(scene, []):
            box = np.zeros_like(mask)
            box[max(0, y - args.spot_r):y + args.spot_r,
                max(0, x - args.spot_r):x + args.spot_r] = True
            sel = band & box
            if sel.sum() < 32:
                print(f"    [{name}] 带内像素过少")
                continue
            print(f"    [{name}] 带内 {int(sel.sum())} px  自遮挡 {100*same[sel].mean():5.1f}%"
                  f"  无实例 {100*(~anyI[sel]).mean():5.1f}%"
                  f"  局部多数后 {100*voted[sel].mean():5.1f}%")


if __name__ == "__main__":
    main()
