#!/usr/bin/env python
"""用 SAM 3 把**所有遮挡物**分割出来，产出 `occluders.png` 供补全时排除源区。

## 为什么需要它

D171 实测：`matte.png` 只覆盖**显著主体**，因此"源区排除前景"只在 00/01/03/08 这类
"遮挡物就是主体"的场景上有效（抄袭度 −21%～−50%），在 04 街景、06 岩石、05 玫瑰蜡烛、
07 食物这些**遮挡物分散**的场景上纹丝不动（±1%～−5%）。

而排除的判据是**语义**不是深度：蛋糕远侧那一块深度上和背景差不多，但它看起来还是蛋糕，
补全模型抄的是外观。所以必须上分割。

## 为什么用框提示而不是文本

SAM 3（transformers 的 `Sam3Model`）支持文本概念与**框**两种提示。我们不需要语义类别，
只需要"把深度已经点到的那个物体补完整"——深度判据已经定位了遮挡物，缺的是完整范围。
因此对每个深度连通块取外接框喂进去，让 SAM 3 补出完整实例。

## 权重

`facebook/sam3` 是 gated；镜像 `1038lab/sam3` 只有裸权重。实测把键名前缀
`detector_model.` 去掉后与 `Sam3Model(Sam3Config())` **1468/1468 完全匹配、零形状冲突**，
即默认配置就是发布配置。许可是 Meta 自定义的 SAM License：可商用、无 MAU 上限，
但不可改成 MIT/Apache 再分发、需署名。3.44GB，**桌面验证用；端侧需另选小模型**。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from build_hidden_layer import (  # noqa: E402
    disocclusion_mask, propagate_background_depth, exclude_foreground)


def load_sam3(weights: Path, device: str):
    from safetensors.torch import load_file
    from transformers import Sam3Config, Sam3Model
    model = Sam3Model(Sam3Config())
    raw = load_file(str(weights))
    sd = {k[len("detector_model."):]: v for k, v in raw.items()
          if k.startswith("detector_model.")}
    missing, unexpected = model.load_state_dict(sd, strict=True), None
    model.eval().to(device)
    return model


def seed_boxes(fg: np.ndarray, min_area: int, pad: int, limit: int) -> list[list[float]]:
    """深度判为前景的连通块 → 外接框。框略微外扩，好让 SAM 把整个物体框进去。"""
    n, lab, stats, _ = cv2.connectedComponentsWithStats(fg.astype(np.uint8), 8)
    h, w = fg.shape
    out = []
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if area < min_area:
            continue
        # 必须转成 Python float：processor 的嵌套列表校验不接受 numpy 标量
        out.append((int(area), [float(max(0, x - pad)), float(max(0, y - pad)),
                                float(min(w - 1, x + bw + pad)),
                                float(min(h - 1, y + bh + pad))]))
    out.sort(key=lambda t: -t[0])
    return [b for _, b in out[:limit]]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--geometry", type=Path,
                    default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--scenes", nargs="+", required=True)
    ap.add_argument("--weights", type=Path, default=Path("tmp/sam3/sam3.safetensors"))
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--min-area", type=int, default=200)
    ap.add_argument("--pad", type=int, default=6)
    ap.add_argument("--limit", type=int, default=48, help="最多送多少个框")
    ap.add_argument("--score", type=float, default=0.30)
    ap.add_argument("--mode", choices=("seed", "grid", "text", "all"), default="all",
                    help="种子来源：seed=深度连通块外接框；grid=密集框网格；"
                         "text=SAM3 文本概念（穷举实例）；all=三者并集")
    ap.add_argument("--grid", type=int, default=6, help="密集框网格的边长格数")
    ap.add_argument("--concepts", nargs="+",
                    default=["object", "person", "flower", "candle", "plant",
                             "furniture", "food", "vehicle", "statue", "container"],
                    help="文本概念列表。SAM 3 会穷举每个概念的所有实例")
    ap.add_argument("--out-suffix", default="",
                    help="产物文件名后缀（occluders<suffix>.png / occluder_instances<suffix>.npz）。"
                         "留空会覆盖产品资产，跑 seed 以外的模式时务必给一个后缀。")
    ap.add_argument("--max-cover", type=float, default=0.70,
                    help="单个掩膜覆盖率超过这个比例就丢弃（多半是把背景整个圈了）")
    args = ap.parse_args()

    from transformers import AutoTokenizer, Sam3ImageProcessor, Sam3Processor
    # 只给框、不给文本时，Sam3Processor 会用字面量 "visual" 当提示，所以仍需 tokenizer。
    # facebook/sam3 是 gated，tokenizer 从未 gated 的 ONNX 导出仓库取（只有几 MB）。
    tok = AutoTokenizer.from_pretrained(str(args.weights.parent / "tok"))
    proc = Sam3Processor(image_processor=Sam3ImageProcessor(), tokenizer=tok)
    model = load_sam3(args.weights, args.device)
    print(f"SAM 3 已加载（{args.weights}）")

    for scene in args.scenes:
        geo = args.geometry / scene
        meta = json.loads((geo / "moge-meta.json").read_text(encoding="utf-8"))
        w, h = meta["width"], meta["height"]
        z = np.fromfile(geo / "depth_z.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        baseline = meta["hiddenLayer"]["maxBaseline"]
        mask = cv2.dilate(disocclusion_mask(inv, meta["fx"], baseline).astype(np.uint8),
                          np.ones((3, 3), np.uint8)) > 0
        inv_layer = propagate_background_depth(inv, mask)
        # 只要深度判据点到的种子（不含 matte）——SAM 负责把它们补完整
        fg = exclude_foreground(inv, mask, inv_layer, meta["fx"], baseline, -1, 3.0) & (~mask)
        img = Image.open(args.assets / meta["scene"] / "center.jpg").convert("RGB")
        bg_level = float(np.median(inv_layer[mask])) if mask.any() else float(np.median(inv))

        # 逐实例的掩膜另存一份（`occluder_instances.npz`）。`occluders.png` 是并集后的
        # 二值图，丢掉了**身份**——而"遮挡物与它让出的背景是不是同一个物体"这条判据
        # （D176）要的恰恰是身份。实例之间会重叠，所以不能压成单通道 label 图，
        # 判"同不同"用的是"**存在某个实例同时含这两点**"。
        instances: list[np.ndarray] = []

        def collect(**call):
            """跑一次 SAM 3，收下"整体确实比带内背景层级更近"的实例。"""
            enc = proc(images=img, return_tensors="pt", **call).to(args.device)
            with torch.no_grad():
                out = model(**enc)
            res = proc.post_process_instance_segmentation(
                out, threshold=args.score, target_sizes=[(h, w)])[0]
            u = np.zeros((h, w), bool)
            n = 0
            for mk in (res["masks"] if isinstance(res, dict) else res.masks):
                mm = np.asarray(mk.cpu()).astype(bool)
                if mm.mean() > args.max_cover or mm.sum() < args.min_area:
                    continue
                # 判"这个实例是不是遮挡物"要跟**它自己那条带的背景**比，不能跟全局中位比。
                # 全局阈值是 D171 那个坑的同款：05 的全局带背景中位是 1.98 m，
                # 而左侧玫瑰 2.21 m、蜡烛 2.28 m、顶部花藤 2.35 m——它们各自明明都挡着
                # 更远的墙，却因为"比全局中位远"被一律丢掉，覆盖恒为 0.0%
                # （2026-08-11 用户点名"补全原始输出里前景还在"，根因就是这里）。
                touch = cv2.dilate(mm.astype(np.uint8), np.ones((21, 21), np.uint8)) > 0
                near_band = touch & mask
                local_bg = float(np.median(inv_layer[near_band])) if near_band.sum() >= 64 \
                    else bg_level
                if float(np.median(inv[mm])) <= local_bg:
                    continue
                u |= mm
                instances.append(mm)
                n += 1
            return u, n

        union = np.zeros((h, w), bool)
        kept, boxes = 0, []
        if args.mode in ("seed", "all"):
            # 从深度连通块取外接框。缺点是场景间数量差异极大（06 只有 1 个，00 有 21 个）
            boxes = seed_boxes(fg, args.min_area, args.pad, args.limit)
            if boxes:
                u, n = collect(input_boxes=[boxes]); union |= u; kept += n
        if args.mode in ("grid", "all"):
            # 不依赖词表的密集框网格：整幅均匀铺框，让 SAM 自己决定框里有没有东西。
            # 与"从深度取框"互补——后者只在深度已经点到的地方才有种子。
            g = args.grid
            gb = [[float(x * w / g), float(y * h / g),
                   float((x + 1) * w / g), float((y + 1) * h / g)]
                  for y in range(g) for x in range(g)]
            u, n = collect(input_boxes=[gb]); union |= u; kept += n
        if args.mode in ("text", "all"):
            # SAM 3 的看家本事：一个短语穷举该概念的**所有实例**。用一组通用名词覆盖
            # 常见遮挡物，不需要每个场景手写词表。
            for phrase in args.concepts:
                u, n = collect(text=phrase); union |= u; kept += n
        union = cv2.morphologyEx(union.astype(np.uint8), cv2.MORPH_CLOSE,
                                 np.ones((5, 5), np.uint8)) > 0
        # 与 matte 取并集。两者互补且都不完整：SAM 的种子来自深度连通块，场景不同差异很大
        # （06 只出了 1 个框、覆盖 10.6%，而 matte 有 39.9%）；matte 则只覆盖显著主体
        # （04 街景是空的，SAM 能给出 8.5%）。并集严格不劣于任一方。
        mt_p = args.assets / meta["scene"] / "matte.png"
        mt = (np.asarray(Image.open(mt_p).convert("L").resize((w, h))) > 127) if mt_p.is_file() \
            else np.zeros((h, w), bool)
        both = union | mt
        Image.fromarray((both * 255).astype(np.uint8), mode="L").save(
            args.assets / meta["scene"] / f"occluders{args.out_suffix}.png")
        # matte 也算一个实例（显著主体），它常常正是"手与衣袖同属一体"的那个
        stack = instances + ([mt] if mt.any() else [])
        if stack:
            np.savez_compressed(args.assets / meta["scene"]
                                / f"occluder_instances{args.out_suffix}.npz",
                                masks=np.packbits(np.stack(stack), axis=None),
                                shape=np.array([len(stack), h, w], np.int32))
        print(f"{scene}: 种子框 {len(boxes)} → 采纳实例 {kept}；SAM {100*union.mean():5.2f}%"
              f" ∪ matte {100*mt.mean():5.2f}% = {100*both.mean():5.2f}%"
              f"；实例栈 {len(stack)} 个")


if __name__ == "__main__":
    main()
