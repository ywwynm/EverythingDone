#!/usr/bin/env python
"""测算 OVIE keyview 环带对"运动包络会真实采到的显露区"的覆盖率。

generate_assets.py 第 1423 行把环带合并限定在 lama 后端（`ring_keys = ... else ()`），
SDXL 后端下 plate 的近剪影环 100% 是生成内容。把环带改到 SDXL **之后**合并之前，
先确认 16 个 keyview 到底能覆盖多少——覆盖不足的话合并只会把一条缝换成若干条。

判据与生成器一致：keyview 自身主体（key_mattes.png）按背景场对齐回零位后仍为空的
位置，才算该视角真实拍到的显露内容。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=None)
    args = ap.parse_args()

    scenes = args.scenes or [p.name for p in sorted(args.assets.iterdir()) if p.is_dir()]
    for scene in scenes:
        base = args.assets / scene
        meta = json.loads((base / "meta.json").read_text(encoding="utf-8"))
        if not meta.get("layered"):
            print(f"{scene:22s} 单层，跳过")
            continue
        gw, gh, kc = meta["guideWidth"], meta["guideHeight"], meta["keyCount"]
        band_px = meta.get("occlusionBandPx", 12.0)

        matte = np.asarray(Image.open(base / "matte.png").convert("L")).astype(np.float32) / 255.0
        core = cv2.resize(matte, (gw, gh), interpolation=cv2.INTER_AREA) > 0.5
        # 运动包络会采到的显露区 = 剪影内、距边界 band_px 以内
        dist_in = cv2.distanceTransform(core.astype(np.uint8), cv2.DIST_L2, 3)
        need = core & (dist_in <= band_px)

        coeffs = np.fromfile(base / "flow_coeffs_back.bin", dtype=np.float32).reshape(-1, gh, gw, 2)
        key_atlas = np.asarray(Image.open(base / "key_mattes.png").convert("L")).astype(np.float32) / 255.0
        gx, gy = np.meshgrid(np.arange(gw, dtype=np.float32), np.arange(gh, dtype=np.float32))

        covered = np.zeros((gh, gw), dtype=bool)
        per_key = []
        for k in range(kc):
            # 注意用 python float 而非 np.float64 标量：NEP 50 下后者会把 float32
            # 系数提升成 float64，cv2.remap 只接受 CV_32F 的映射表。
            t = 2.0 * np.pi * k / kc
            flow = coeffs[0] + coeffs[1] * float(np.cos(t)) + coeffs[2] * float(np.sin(t))
            key_alpha = key_atlas[k * gh : (k + 1) * gh]
            zero_alpha = cv2.remap(
                key_alpha, gx - flow[..., 0], gy - flow[..., 1],
                interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE,
            )
            reveal = need & (zero_alpha < 0.3)
            covered |= reveal
            per_key.append(reveal.sum())

        pct = 100.0 * covered.sum() / max(need.sum(), 1)
        print(
            f"{scene:22s} band {band_px:4.1f}px  需覆盖 {int(need.sum()):6d}  "
            f"OVIE 覆盖 {int(covered.sum()):6d} = {pct:5.1f}%  "
            f"单 key 中位 {int(np.median(per_key)):5d}"
        )


if __name__ == "__main__":
    main()
