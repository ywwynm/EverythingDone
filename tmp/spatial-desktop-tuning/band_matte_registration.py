#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""过渡带半宽 k 该取多少：量**深度断边**与**软 α 剪影**之间的错配距离。

工单把 k 写成"先取 8–16px"。但 α 的斜坡本身只有 1.4–3.2px（D183），所以 k 真正要
覆盖的不是斜坡宽度，而是**两个模型的边落在不同位置**这件事——MoGe 的深度断崖与
BiRefNet 的 α=0.5 等值线不会重合。k 小于错配量，前景侧那一排污染像素就出不了带、
拿不到软 α；k 过大则把纯前景也拖进混合，边缘发虚。

断边判据与查看器逐字一致：逆深度跳变 > sepPx / (fx · maxBaseline)，
maxBaseline = 60 · max(w,h)/720 · baselinePerPixel，sepPx = 1.5（D154）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

SCENES = [
    "00_original_single", "01_original_double", "02_indoor", "03_office",
    "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet",
]


def near_side_mask(z: np.ndarray, jump: float) -> np.ndarray:
    """断边**近侧**（Z 更小）那一排像素，与查看器 `decontaminate` 的 `bad` 同义。"""
    inv = 1.0 / np.maximum(z, 1e-6)
    near = np.zeros(z.shape, bool)
    dr = np.abs(inv[:, :-1] - inv[:, 1:]) > jump
    left_closer = z[:, :-1] < z[:, 1:]
    near[:, :-1] |= dr & left_closer
    near[:, 1:] |= dr & ~left_closer
    dd = np.abs(inv[:-1, :] - inv[1:, :]) > jump
    up_closer = z[:-1, :] < z[1:, :]
    near[:-1, :] |= dd & up_closer
    near[1:, :] |= dd & ~up_closer
    return near


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/matte-soft-probe"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--sep-px", type=float, default=1.5)
    args = ap.parse_args()

    print(f"{'场景':<22} {'断边近侧px':>10} {'其中在matte内':>12} "
          f"{'到α=0.5中位':>11} {'p90':>6} {'p99':>6}")
    print("-" * 76)
    stats = {}
    for scene in args.scenes:
        gdir = args.geometry / scene
        meta_p = gdir / "moge-meta.json"
        if not meta_p.is_file():
            continue
        meta = json.loads(meta_p.read_text(encoding="utf-8"))
        w, h = meta["width"], meta["height"]
        z = np.fromfile(gdir / "depth_z.f32", dtype=np.float32).reshape(h, w)
        soft_p = args.assets / scene / "matte_soft.png"
        if not soft_p.is_file():
            continue
        soft = cv2.imread(str(soft_p), cv2.IMREAD_GRAYSCALE).astype(np.float32) / 255.0

        max_baseline = 60.0 * max(w, h) / 720.0 * meta["baselinePerPixel"]
        jump = args.sep_px / max(meta["fx"] * max_baseline, 1e-9)
        near = near_side_mask(z, jump)

        core = (soft > 0.5).astype(np.uint8)
        if core.sum() == 0:
            print(f"{scene:<22} {int(near.sum()):>10}  无主体（软 α 全 0），跳过")
            continue
        # 到 α=0.5 等值线的距离：等值线取核的 1px 边界，距离变换在其补集上算
        contour = ((cv2.dilate(core, np.ones((3, 3), np.uint8)) - core) > 0)
        dist = cv2.distanceTransform((~contour).astype(np.uint8), cv2.DIST_L2, 3)

        # 只看**贴着主体**的那部分断边：远处（桌沿对地板之类）与 matte 无关，
        # 把它们算进来会把中位数拉到几百像素，量的就不是错配了。
        near_subject = near & (cv2.dilate(core, np.ones((41, 41), np.uint8)) > 0)
        d = dist[near_subject]
        if d.size == 0:
            continue
        med, p90, p99 = (float(np.median(d)), float(np.percentile(d, 90)),
                         float(np.percentile(d, 99)))
        print(f"{scene:<22} {int(near.sum()):>10} {int(near_subject.sum()):>12} "
              f"{med:>11.2f} {p90:>6.2f} {p99:>6.2f}")
        stats[scene] = {"nearSidePx": int(near.sum()),
                        "nearSideNearSubject": int(near_subject.sum()),
                        "distMedian": med, "distP90": p90, "distP99": p99}

    (args.out / "band_matte_registration.json").write_text(
        json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
