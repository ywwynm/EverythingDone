#!/usr/bin/env python
"""导出 MoGe-2 的度量几何，供网页点云查看器做真实相机位移渲染。

只导出重投影必需的三样：逐像素 Z（float32）、像素单位内参、支点深度。颜色直接用
资产里已有的 center.jpg，不重复落盘。

网页端按 D146 验证过的同一套公式渲染：
    X = (u − cx)·Z / fx,  Y = (v − cy)·Z / fy
    u' = fx·(X − tx)/Z + cx + fx·tx/Z0     （末项是绕支点转的补偿，等价于减 1/Z0）
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scenes", nargs="*", default=None)
    ap.add_argument("--model", default="Ruicheng/moge-2-vitl-normal")
    ap.add_argument("--suffix", default="",
                    help="导出目录后缀，用于把不同规模的变体并列进同一个查看器对照")
    ap.add_argument("--out", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    args = ap.parse_args()

    from moge.model.v2 import MoGeModel

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MoGeModel.from_pretrained(args.model).to(device).eval()
    args.out.mkdir(parents=True, exist_ok=True)

    scenes = args.scenes or [
        p.name for p in sorted(args.assets.iterdir())
        if p.is_dir() and (p / "center.jpg").is_file() and "baseline" not in p.name
    ]
    index = []
    for scene in scenes:
        base = args.assets / scene
        image = np.asarray(Image.open(base / "center.jpg").convert("RGB")).astype(np.float32) / 255.0
        h, w = image.shape[:2]
        tensor = torch.tensor(image, dtype=torch.float32, device=device).permute(2, 0, 1)
        with torch.no_grad():
            out = model.infer(tensor)
        depth = out["depth"].cpu().numpy().astype(np.float32)
        valid = out["mask"].cpu().numpy() if "mask" in out else np.isfinite(depth)
        Kn = out["intrinsics"].cpu().numpy()
        fx, fy = float(Kn[0, 0] * w), float(Kn[1, 1] * h)
        cx, cy = float(Kn[0, 2] * w), float(Kn[1, 2] * h)

        # 无效／非有限处置远：Z 给一个远平面值，点仍参与光栅化但几乎不动，
        # 好过留空洞——空洞与真实遮挡混在一起会干扰判读。
        finite = valid & np.isfinite(depth) & (depth > 1e-6)
        far = float(np.percentile(depth[finite], 99.0)) if finite.any() else 10.0
        z = np.where(finite, depth, far).astype(np.float32)

        matte_path = base / "matte.png"
        if matte_path.is_file():
            # 各场景的 matte 分辨率不一致（部分仍是 guide 尺寸），统一对齐到深度图
            matte_image = Image.open(matte_path).convert("L").resize((w, h), Image.BILINEAR)
            matte = np.asarray(matte_image).astype(np.float32) / 255.0
            pivot = float(np.median(z[matte > 0.5])) if (matte > 0.5).any() else float(np.median(z))
        else:
            pivot = float(np.median(z))

        scene_dir = args.out / f"{scene}{args.suffix}"
        scene_dir.mkdir(parents=True, exist_ok=True)
        z.tofile(scene_dir / "depth_z.f32")
        inv = 1.0 / np.maximum(z, 1e-6)
        span_inv = float(np.percentile(inv, 95.0) - np.percentile(inv, 5.0))
        meta = {
            "scene": scene, "width": w, "height": h,
            "fx": fx, "fy": fy, "cx": cx, "cy": cy,
            "pivotZ": pivot,
            "invDepthSpanP5P95": span_inv,
            # 达到 1 px@长边 位移所需的基线，网页端据此把"目标视差"换算成真实基线
            "baselinePerPixel": 1.0 / max(fx * span_inv, 1e-9),
            "model": args.model,
            "validRatio": float(finite.mean()),
        }
        (scene_dir / "moge-meta.json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        index.append(f"{scene}{args.suffix}")
        print(f"{scene + args.suffix:30s} {w}x{h}  fx {fx:6.1f}  Z中位(主体) {pivot:5.3f}  "
              f"有效 {100*finite.mean():5.1f}%")
    # 合并已有条目，便于分多次导出不同变体后在同一个下拉里对照
    index_path = args.out / "index.json"
    existing = json.loads(index_path.read_text(encoding="utf-8"))["scenes"] if index_path.is_file() else []
    merged = sorted(set(existing) | set(index))
    index_path.write_text(json.dumps({"scenes": merged}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"共 {len(index)} 个场景 -> {args.out}")


if __name__ == "__main__":
    main()
