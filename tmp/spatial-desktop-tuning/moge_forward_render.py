#!/usr/bin/env python
"""用 MoGe-2 的度量点图做真实相机位移的前向投影渲染。

上一版探针用"位移场的 Jacobian 非相似形变"判几何好坏是错的：物理正确的重投影在遮挡
边界上本来就是阶跃，梯度无穷大，任何这类判据都会给出接近 100% —— 那是正确的遮挡，
不是形变。D140 犯过同一个错。

正确的判法是把点云按真实相机位移投影出来看：**刚体场景 + 正确几何 ⇒ 人脸/直线保持形状，
只在被遮挡处出现空洞**。所以：
- 人脸、四角、玻璃杯保持形状、只有空洞 → 几何够用，问题在表示（二维 warp）而非深度精度；
- 人脸鼓包/弯折 → 单目几何精度不足以支撑重投影。

空洞不填，故意留黑——这一步只判几何，补全是另一件事，混在一起就会像前几轮那样互相掩盖。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image


def render_points(points: np.ndarray, colors: np.ndarray, K: np.ndarray,
                  translation: np.ndarray, width: int, height: int,
                  point_radius: int = 1) -> np.ndarray:
    """z-buffer 前向投影。points (N,3) 相机坐标，K 为像素单位内参。"""
    moved = points - translation[None, :]
    z = moved[:, 2]
    keep = z > 1e-6
    moved, cols = moved[keep], colors[keep]
    z = moved[:, 2]
    u = (K[0, 0] * moved[:, 0] / z + K[0, 2])
    v = (K[1, 1] * moved[:, 1] / z + K[1, 2])
    xi = np.rint(u).astype(np.int64)
    yi = np.rint(v).astype(np.int64)

    canvas = np.zeros((height, width, 3), dtype=np.float32)
    zbuf = np.full((height, width), np.inf, dtype=np.float32)
    for dy in range(-point_radius, point_radius + 1):
        for dx in range(-point_radius, point_radius + 1):
            xx, yy = xi + dx, yi + dy
            ok = (xx >= 0) & (xx < width) & (yy >= 0) & (yy < height)
            flat = yy[ok] * width + xx[ok]
            zz = z[ok]
            cc = cols[ok]
            # 逐像素取最小 z：先按 z 降序写入，最后写的（最小 z）胜出
            order = np.argsort(-zz)
            flat, zz, cc = flat[order], zz[order], cc[order]
            zb = zbuf.reshape(-1)
            cb = canvas.reshape(-1, 3)
            better = zz < zb[flat]
            zb[flat[better]] = zz[better]
            cb[flat[better]] = cc[better]
    return np.clip(canvas, 0, 1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--scene", default="00_original_single")
    ap.add_argument("--model", default="Ruicheng/moge-2-vitl-normal")
    ap.add_argument("--span-px720", type=float, default=24.0,
                    help="主体域 p5-p95 视差跨度目标，与现行资产对齐便于同强度对照")
    ap.add_argument("--degrees", nargs="*", type=float, default=[0, 90, 180, 250])
    ap.add_argument("--out", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/moge-probe"))
    args = ap.parse_args()

    from moge.model.v2 import MoGeModel

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MoGeModel.from_pretrained(args.model).to(device).eval()
    args.out.mkdir(parents=True, exist_ok=True)

    base = args.assets / args.scene
    image = np.asarray(Image.open(base / "center.jpg").convert("RGB")).astype(np.float32) / 255.0
    h, w = image.shape[:2]
    tensor = torch.tensor(image, dtype=torch.float32, device=device).permute(2, 0, 1)
    with torch.no_grad():
        out = model.infer(tensor)
    points = out["points"].cpu().numpy().reshape(-1, 3)
    depth = out["depth"].cpu().numpy()
    valid = out["mask"].cpu().numpy().reshape(-1) if "mask" in out else np.isfinite(depth).reshape(-1)
    Kn = out["intrinsics"].cpu().numpy()
    # MoGe 的内参是归一化的（相对图像宽高），换成像素单位
    K = np.array([[Kn[0, 0] * w, 0, Kn[0, 2] * w],
                  [0, Kn[1, 1] * h, Kn[1, 2] * h],
                  [0, 0, 1.0]], dtype=np.float64)

    colors = image.reshape(-1, 3)
    finite = valid & np.isfinite(points).all(axis=1) & (points[:, 2] > 1e-6)
    points, colors = points[finite], colors[finite]

    matte = np.asarray(Image.open(base / "matte.png").convert("L")).astype(np.float32) / 255.0
    subject = (matte.reshape(-1) > 0.5)[finite]

    # 主体中位深度作支点：绕它转，主体大致不动，与现行资产的"减中位数"语义一致
    z_pivot = float(np.median(points[subject, 2])) if subject.any() else float(np.median(points[:, 2]))
    inv = 1.0 / points[:, 2]
    span_inv = float(np.percentile(inv, 95) - np.percentile(inv, 5))
    px720 = 720.0 / max(w, h)
    target_px = args.span_px720 / px720
    # Δu = fx·t·(1/Z − 1/Z0)；令 p5–p95 的 Δu 跨度等于目标
    baseline = target_px / max(K[0, 0] * span_inv, 1e-9)
    print(f"{args.scene}: fx={K[0,0]:.1f}px  Z 中位(主体)={z_pivot:.3f}  "
          f"1/Z 跨度={span_inv:.4f}  基线={baseline*100:.2f}cm(若 Z 为米)")

    frames = {}
    for deg in args.degrees:
        rad = np.deg2rad(deg)
        t = np.array([baseline * np.cos(rad), baseline * np.sin(rad), 0.0])
        # 绕支点转：补偿一个使支点深度不动的平移量，等价于"减 1/Z0"
        shift = np.array([K[0, 0] * t[0] / z_pivot, K[1, 1] * t[1] / z_pivot, 0.0])
        img = render_points(points, colors, K, t, w, h)
        # 把支点补偿做成整幅平移，保持主体居中（纯平移，不影响形状判定）
        M = np.float32([[1, 0, shift[0]], [0, 1, shift[1]]])
        import cv2
        img = cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_LINEAR, borderValue=0)
        name = f"{args.scene}-moge-fwd-{int(deg):03d}.png"
        Image.fromarray((img * 255).astype(np.uint8)).save(args.out / name)
        frames[deg] = name
        hole = float((img.sum(2) == 0).mean())
        print(f"  θ={deg:5.0f}°  空洞占比 {hole*100:5.2f}%  -> {name}")
    print("空洞是被遮挡区，本步不填；只看人脸/直线/四角是否保持形状。")


if __name__ == "__main__":
    main()
