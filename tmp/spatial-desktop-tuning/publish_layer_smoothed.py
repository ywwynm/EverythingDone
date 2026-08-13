#!/usr/bin/env python
"""把"逐层平滑 + 层间平移增益"的候选场发布成并列场景，供查看器直接对照。

2026-08-10 D141 的两条实测结论：
  1. 形变由**层内**场的平滑度决定；各自域内（前景用主体掩膜、背景用背景掩膜）做归一化
     卷积平滑，σ_front=96 / σ_back=192 时 00 场景三项形变全部进 D109 契约
     （主体 0.9%、背景 1.5%、四角 1.0%），零折返。
  2. 空间感由**层间**相对平移决定，而常量向量的梯度为零——给前景场加 k 倍的层间平均
     相对平移，形变三项一个数都不变，相对视差从 8.2 线性升到 15.4 px@720。

因此不改生成器、不动默认资产，只复制一份场景目录换掉两个系数文件，让用户在同一个
查看器里切换比较（"先验证再铺开"）。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def domain_blur(field: np.ndarray, weight: np.ndarray, sigma: float) -> np.ndarray:
    """归一化卷积：只用本层域内的样本平滑，避免把对方层的外推值拉进来。"""
    out = field.copy()
    w = weight.astype(np.float32)
    den = cv2.GaussianBlur(w, (0, 0), sigma)
    for k in range(field.shape[0]):
        for ch in (0, 1):
            num = cv2.GaussianBlur(field[k, ..., ch] * w, (0, 0), sigma)
            out[k, ..., ch] = num / np.maximum(den, 1e-6)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scene", default="00_original_single")
    ap.add_argument("--suffix", default="_smooth")
    ap.add_argument("--sigma-front", type=float, default=96.0)
    ap.add_argument("--sigma-back", type=float, default=192.0)
    ap.add_argument("--gain", type=float, default=2.0, help="层间平移增益 k（零形变代价）")
    args = ap.parse_args()

    src = args.assets / args.scene
    dst = args.assets / f"{args.scene}{args.suffix}"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    meta = json.loads((src / "meta.json").read_text(encoding="utf-8"))
    gw, gh = meta["guideWidth"], meta["guideHeight"]
    front = np.fromfile(src / "flow_coeffs_front.bin", dtype=np.float32).reshape(-1, gh, gw, 2)
    back = np.fromfile(src / "flow_coeffs_back.bin", dtype=np.float32).reshape(-1, gh, gw, 2)
    matte = np.asarray(Image.open(src / "matte.png").convert("L")).astype(np.float32) / 255.0
    subject = np.asarray(
        Image.fromarray((matte * 255).astype(np.uint8)).resize((gw, gh), Image.BILINEAR)
    ) > 128

    sf = domain_blur(front, subject, args.sigma_front)
    sb = domain_blur(back, ~subject, args.sigma_back)

    # 层间平移增益：逐方向取主体域内的平均相对平移，按 k 倍加回前景场。整幅常量向量，
    # 梯度恒为零，因此不改变任何形变指标。傅里叶基下等价于对三张系数图各加一个常量。
    if args.gain:
        harmonics = [(0, 1.0, 0.0), (1, 1.0, 0.0), (2, 0.0, 1.0)]
        directions = 48
        acc = np.zeros((front.shape[0], 2), dtype=np.float64)
        for i in range(directions):
            t = 2.0 * np.pi * i / directions
            cos_t, sin_t = float(np.cos(t)), float(np.sin(t))
            f = sf[0] + sf[1] * cos_t + sf[2] * sin_t
            b = sb[0] + sb[1] * cos_t + sb[2] * sin_t
            d = (f - b)[subject].mean(axis=0)
            # 投影回傅里叶基：C0 收常数项，C1c/C1s 收 cos/sin 分量
            acc[0] += d / directions
            acc[1] += d * cos_t * 2.0 / directions
            acc[2] += d * sin_t * 2.0 / directions
        for k, _, _ in harmonics:
            sf[k, ..., 0] += args.gain * acc[k][0]
            sf[k, ..., 1] += args.gain * acc[k][1]

    sf.astype(np.float32).tofile(dst / "flow_coeffs_front.bin")
    sb.astype(np.float32).tofile(dst / "flow_coeffs_back.bin")
    meta["generatedAt"] = meta.get("generatedAt", "") + f" +smooth{int(args.sigma_front)}/" \
                                                        f"{int(args.sigma_back)} k{args.gain:g}"
    meta["layerSmoothSigmaFront"] = args.sigma_front
    meta["layerSmoothSigmaBack"] = args.sigma_back
    meta["interLayerGain"] = args.gain
    (dst / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    index_path = args.assets / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if dst.name not in index["scenes"]:
        index["scenes"].append(dst.name)
        index["scenes"].sort()
        index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已发布 {dst.name}（σ前 {args.sigma_front:g} / σ后 {args.sigma_back:g} / k {args.gain:g}）")


if __name__ == "__main__":
    main()
