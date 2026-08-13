#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""软 α 通路的放大巡检：原图 / 硬 matte / 软 α 三列并排。

选区不靠肉眼（`zoom_matrix.py` 同一套规则）：打分 = **剪影环密度 × 原图边缘能量**，
非极大抑制取前 N 块。环取软 α 的二值核 dilate−erode——软过渡只可能出现在那里。

配色与查看器 `matteSoft` 档逐项一致：α≤0.05 与 α≥0.95 压成暗底（前景加一点蓝，
好认里外），**只有中间值上彩**（青→黄→红）。这一档回答"软过渡有多宽、在哪儿"，
纯 0/1 的地方不该抢眼。硬 matte 用同一套色，两列一比即可看出被硬化丢掉了多少。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from zoom_matrix import crop, label, pick_regions

SCENES = [
    "00_original_single", "01_original_double", "02_indoor", "03_office",
    "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet",
]


def alpha_view(alpha: np.ndarray, base_bgr: np.ndarray) -> np.ndarray:
    """覆盖率伪彩（BGR）。与查看器同色。"""
    out = (base_bgr.astype(np.float32) * 0.28)
    hard_fg = alpha >= 0.95
    out[hard_fg, 0] += 60.0                       # 纯前景加一点蓝，与纯背景区分
    mid = (alpha > 0.05) & (alpha < 0.95)
    t = np.clip((alpha - 0.05) / 0.90, 0.0, 1.0)
    lo, u = t < 0.5, np.where(t < 0.5, t * 2.0, (t - 0.5) * 2.0)
    b = np.where(lo, 255.0 * (1.0 - u), 40.0 * u)
    g = np.where(lo, 220.0 + 10.0 * u, 230.0 * (1.0 - u) + 40.0 * u)
    r = np.where(lo, 255.0 * u, 255.0)
    for ch, v in enumerate((b, g, r)):
        out[..., ch] = np.where(mid, v, out[..., ch])
    return np.clip(out, 0, 255).astype(np.uint8)


def load_alpha(path: Path, size: tuple[int, int]) -> np.ndarray | None:
    """读灰度 α 并对齐到 size=(w,h)。**最近邻**——双线性会在硬边两侧造出本不存在的
    中间值，那测的是缩放器不是模型（`matte.png` 有 8 个场景还是 long-edge 512 的旧尺寸）。"""
    if not path.is_file():
        return None
    img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if img is None:
        return None
    if (img.shape[1], img.shape[0]) != size:
        img = cv2.resize(img, size, interpolation=cv2.INTER_NEAREST)
    return img.astype(np.float32) / 255.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--out", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/matte-soft-probe"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--tile", type=int, default=120)
    ap.add_argument("--zoom", type=int, default=3)
    ap.add_argument("--count", type=int, default=6, help="每个场景自动选几块")
    ap.add_argument("--min-sep", type=int, default=90)
    ap.add_argument("--spots", default="",
                    help="场景:名字:x,y;… 手工点名，追加在自动块之后，不替换")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    manual: dict[str, list[tuple[str, int, int]]] = {}
    for item in filter(None, (s.strip() for s in args.spots.split(";"))):
        scene, name, xy = item.split(":")
        x, y = xy.split(",")
        manual.setdefault(scene, []).append((name, int(x), int(y)))

    for scene in args.scenes:
        scene_dir = args.assets / scene
        center = cv2.imread(str(scene_dir / "center.jpg"), cv2.IMREAD_COLOR)
        if center is None:
            print(f"{scene}: 缺 center.jpg，跳过")
            continue
        h, w = center.shape[:2]
        soft = load_alpha(scene_dir / "matte_soft.png", (w, h))
        hard = load_alpha(scene_dir / "matte.png", (w, h))
        if soft is None:
            print(f"{scene}: 缺 matte_soft.png，跳过")
            continue

        core = (soft > 0.5).astype(np.uint8)
        k = np.ones((17, 17), np.uint8)
        ring = ((cv2.dilate(core, k) - cv2.erode(core, k)) > 0)
        if not ring.any():
            print(f"{scene}: 无主体（软 α 全 0），跳过")
            continue

        picks = [(x, y, f"auto{i}") for i, (x, y, _) in
                 enumerate(pick_regions(ring, center, args.tile, args.count, args.min_sep))]
        picks += [(x, y, name) for name, x, y in manual.get(scene, [])]

        soft_view = alpha_view(soft, center)
        hard_view = (alpha_view(hard, center) if hard is not None
                     else np.zeros_like(center))
        rows = []
        for x, y, name in picks:
            cols = [label(crop(center, x, y, args.tile, args.zoom), f"{name} ({x},{y}) center"),
                    label(crop(hard_view, x, y, args.tile, args.zoom),
                          "matte.png hard" if hard is not None else "matte.png MISSING"),
                    label(crop(soft_view, x, y, args.tile, args.zoom), "matte_soft.png")]
            rows.append(np.concatenate(cols, axis=1))
        sheet = np.concatenate(rows, axis=0)
        out_p = args.out / f"{scene}_matte_compare.png"
        cv2.imwrite(str(out_p), sheet)
        print(f"{scene}: {len(picks)} 块 -> {out_p}")


if __name__ == "__main__":
    main()
