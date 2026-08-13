#!/usr/bin/env python
"""带真值的补全评测：把遮挡带掩膜平移到真正是背景的区域，那里的原始像素就是真值。

此前所有补全比较都没有真值——遮挡带背后的内容在照片里本就不存在，只能看"像不像"。
本脚本绕开这一点：**掩膜形态照搬真带**（同样是沿深度断崖的细网，67.5% 宽度 ≤8px），
只把它整体平移到一块纯背景上。于是既保留了"这正是模型最不擅长的掩膜形态"这个关键
性质，又拿到了逐像素真值。

评测口径：只在掩膜内比。PSNR 看整体保真，SSIM 看结构，梯度相关看**结构是否续接**
（这条最贴合我们的病症——纹理能量早已达标，缺的是形状，见 D157）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def build_test_mask(mask: np.ndarray, is_bg: np.ndarray, block: int = 48,
                    seed: int = 20260811, image: np.ndarray | None = None,
                    structured: bool = False) -> np.ndarray:
    """按块搬运：把真带切成 block×block 的小块，逐块盖到纯背景块上。

    整块平移行不通——带占画面 22% 且沿剪影分散，找不到与之不重叠的等大背景区。
    按块搬运保留的是**局部形态**（细网、宽度分布、沿断崖的走向），而这正是决定
    补全难度的性质；全局位置无关紧要。
    """
    h, w = mask.shape
    rng = np.random.default_rng(seed)
    # 候选目标块：整块都在背景上（含 4px 余量，避免贴到剪影污染带）
    safe = cv2.erode(is_bg.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
    integral = cv2.integral(safe.astype(np.uint8))
    targets = []
    for y in range(0, h - block, block):
        for x in range(0, w - block, block):
            s = (integral[y + block, x + block] - integral[y, x + block]
                 - integral[y + block, x] + integral[y, x])
            if s == block * block:
                targets.append((y, x))
    rng.shuffle(targets)
    if structured and image is not None:
        # 只按"是纯背景"选块，选到的多半是平坦墙面／桌面——而平坦区本来就该平滑，
        # PSNR 天然偏袒糊。用户抱怨的是**有结构的背景**（枝条、窗框、椅背）补不上，
        # 所以按边缘能量排序取前一半，把评测放到真正见分晓的地方。
        g = cv2.cvtColor(image.astype(np.float32), cv2.COLOR_RGB2GRAY)
        e = np.abs(cv2.Sobel(g, cv2.CV_32F, 1, 0, 3)) + np.abs(cv2.Sobel(g, cv2.CV_32F, 0, 1, 3))
        targets.sort(key=lambda t: -float(e[t[0]:t[0] + block, t[1]:t[1] + block].mean()))
        targets = targets[:max(1, len(targets) // 2)]
        print(f"  结构筛选：保留边缘能量最高的 {len(targets)} 块")

    # 源块：带覆盖率落在 5%–60% 的块（太空测不出东西，太满不像真带）
    sources = []
    for y in range(0, h - block, block):
        for x in range(0, w - block, block):
            cov = mask[y:y + block, x:x + block].mean()
            if 0.05 <= cov <= 0.60:
                sources.append((y, x))
    rng.shuffle(sources)

    out = np.zeros_like(mask)
    for (ty, tx), (sy, sx) in zip(targets, sources):
        out[ty:ty + block, tx:tx + block] = mask[sy:sy + block, sx:sx + block]
    print(f"可用背景块 {len(targets)}，源块 {len(sources)}，实际搬运 "
          f"{min(len(targets), len(sources))} 块（{block}px）")
    return out & safe


def grad_corr(a: np.ndarray, b: np.ndarray, m: np.ndarray) -> float:
    """梯度场相关：结构续接得对不对。纹理能量达标但形状错位时，这一项会掉下来。"""
    ga = np.stack([cv2.Sobel(a, cv2.CV_32F, 1, 0, 3), cv2.Sobel(a, cv2.CV_32F, 0, 1, 3)], -1)
    gb = np.stack([cv2.Sobel(b, cv2.CV_32F, 1, 0, 3), cv2.Sobel(b, cv2.CV_32F, 0, 1, 3)], -1)
    x, y = ga[m].ravel(), gb[m].ravel()
    if x.size < 16:
        return float("nan")
    x, y = x - x.mean(), y - y.mean()
    d = np.sqrt((x * x).sum() * (y * y).sum())
    return float((x * y).sum() / d) if d > 0 else float("nan")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--geo", type=Path, required=True)
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--mask", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--emit-only", action="store_true",
                    help="只产出平移后的掩膜与真值裁剪，供各后端跑补全")
    ap.add_argument("--candidates", nargs="*", default=[],
                    help="待评的补全结果 png（都必须是在平移掩膜上跑出来的）")
    ap.add_argument("--structured", action="store_true",
                    help="只在边缘能量高的一半背景块上评测")
    ap.add_argument("--rect", type=int, default=0,
                    help="对照组：改用该边长的大块矩形掩膜，验证失利是否源于掩膜形态")
    args = ap.parse_args()

    image = np.asarray(Image.open(args.image).convert("RGB")).astype(np.float32)
    mask = np.asarray(Image.open(args.mask).convert("L")) > 127
    meta = json.loads((args.geo / "moge-meta.json").read_text(encoding="utf-8"))
    h, w = image.shape[:2]
    z = np.fromfile(args.geo / "depth_z.f32", dtype=np.float32).reshape(h, w)

    # 背景 = 深度在远侧一半，且不属于原遮挡带（那里本来就是遮挡物）
    far = z >= np.percentile(z, 55.0)
    is_bg = far & (~cv2.dilate(mask.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool))

    args.out.mkdir(parents=True, exist_ok=True)
    if args.rect:
        # 对照组：把细网换成大块矩形，其余一切不变。若 Moebius 在这里反超，
        # 就证明它的失利是**掩膜形态**造成的，而不是我调用错了——这一条不做，
        # "LaMa 更好"这个结论就区分不开"模型不行"和"我用错了"。
        safe = cv2.erode(is_bg.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
        shifted = np.zeros_like(mask)
        side = args.rect
        placed = 0
        for y in range(0, h - side, side):
            for x in range(0, w - side, side):
                if safe[y:y + side, x:x + side].all():
                    shifted[y + 4:y + side - 4, x + 4:x + side - 4] = True
                    placed += 1
        print(f"矩形对照：{placed} 个 {side-8}px 方块")
    else:
        shifted = build_test_mask(mask, is_bg, image=image, structured=args.structured)
    print(f"测试掩膜 {shifted.sum()} px（{100*shifted.mean():.2f}%），全部落在纯背景上")
    Image.fromarray((shifted * 255).astype(np.uint8), mode="L").save(args.out / "gt_mask.png")
    Image.fromarray(image.astype(np.uint8)).save(args.out / "gt_image.png")

    if args.emit_only:
        return

    grey = lambda a: cv2.cvtColor(a.astype(np.float32), cv2.COLOR_RGB2GRAY)
    gt, gtg = image, grey(image)
    print(f"\n{'候选':28s} {'PSNR↑':>7s} {'去偏PSNR↑':>9s} {'梯度相关↑':>9s} {'亮度差':>7s} {'HF比':>6s}")
    hf = lambda a, m: float(np.sqrt((cv2.Laplacian(grey(a), cv2.CV_32F, 3)[m] ** 2).mean()))
    rows = []
    for p in args.candidates:
        p = Path(p)
        a = np.asarray(Image.open(p).convert("RGB")).astype(np.float32)
        d = a[shifted] - gt[shifted]
        mse = float((d ** 2).mean())
        # 去偏 PSNR：先扣掉逐通道均值偏移再算。产线本来就有全局标量校色（D159），
        # 所以整体亮度差是可修的，不该计入模型的结构成绩。
        mse_db = float(((d - d.mean(0)) ** 2).mean())
        rows.append((p.stem,
                     10 * np.log10(255.0 ** 2 / max(mse, 1e-9)),
                     10 * np.log10(255.0 ** 2 / max(mse_db, 1e-9)),
                     grad_corr(grey(a), gtg, shifted),
                     grey(a)[shifted].mean() - gtg[shifted].mean(),
                     hf(a, shifted) / hf(gt, shifted)))
    for name, psnr, psnr_db, gc, lvl, hfr in sorted(rows, key=lambda r: -r[2]):
        print(f"{name:28s} {psnr:7.2f} {psnr_db:9.2f} {gc:9.3f} {lvl:+7.1f} {hfr:6.2f}")


if __name__ == "__main__":
    main()
