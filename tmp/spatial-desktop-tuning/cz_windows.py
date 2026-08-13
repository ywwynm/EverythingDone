#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 公平对照的**共用裁窗生成器**（两模型共用同一份，同一 zoom）。

依据 [research-2026-08-12-narrow-band-inpainting.md] 第 2 节：diffusers 的
`padding_mask_crop`、A1111「仅重绘蒙版区」、ComfyUI Crop-and-Stitch 是同一协议——
沿掩膜裁窗、放大到模型原生输入、补完在**像素域**贴回。

## 为什么不是"逐连通分量取 bbox"

工单写的是"分量 bbox 外扩、中心距 < 窗宽 50% 的分量合并"。**在我们的掩膜上这条行不通，
实测当场证伪**：显露带是一张沿剪影绕整个主体一圈的细网，00 场景 38.4% 的洞基本是
**一个**连通分量，bbox 就是整幅画面——按 bbox 裁出来的窗边长 540px，放大倍率 0.95，
比不裁还差。合并规则只能把窗变大，没有任何一条能把长条分量拆开。

所以改成**覆盖**问题：窗的边长由该处的**局部**带宽决定，用贪心把洞铺满，窗心间距
≥ 50% 窗宽（工单那条合并阈值在这里变成布点约束，语义一致：不允许出现两个几乎重合的
窗）。长带自然被拆成一串窗，碎片自然被邻窗吸收。

## 三条实现纪律

1. **带宽一律用局部宽度 = 带内距离变换 ×2**（D188 定的口径）。逐行/逐列计数在弯曲、
   分叉的带上不可靠，本项目两次都栽在这。
2. **同一窗对两个模型必须同一 zoom**。zoom 只由窗自己的带宽决定，本模块不接受任何
   后端参数——从结构上杜绝"给某个模型偷偷调窗"。
3. **统计一律按带像素加权**。按窗取中位会被大量只含十几个像素的碎片窗带偏（实测
   00 场景窗中位 zoom 报出 8.00，而按带像素加权是 2.46）。

## 上下文余量取 2×p90，不是 4×p90

工单写的是"外扩 max(4×该窗带宽 p90, 32px)"。实测四档比下来（九场景）：

| ctx_mult | 总窗数 | 带在模型输入里（latent px，九场景范围/中位） |
|---|---|---|
| 4.0 | 269 | 2.25–3.67 / 2.96 |
| **2.0** | **289** | **2.73–4.00 / 3.63** |
| 1.5 | 275 | 0.92–4.00 / 3.63（07 场景退化） |
| 1.0 | 275 | 0.89–4.00 / 3.63（同上） |

4×p90 会让**带最宽的那几处**把整个窗撑大，于是带的中位部分反而放大不上去——同一条带
里宽处与窄处对窗尺寸的要求是相反的。调研原文给的是"按**带宽** 4–8 倍"，没有指定分位；
我们的宽度分布下 2×p90 大致等于 4–8×p50，更贴原文。1.5 以下 07 场景出现"窗一大、
窗内 w50 反而更小"的正反馈退化，不取。
"""
from __future__ import annotations

import cv2
import numpy as np

MODEL_SIDE = 512          # LaMa ONNX 与 Moebius 的原生输入都是 512²（都被硬锁）
LATENT_DOWN = 8           # SDXL VAE 的下采样倍率 f8


def local_width(mask: np.ndarray) -> np.ndarray:
    """局部带宽 = 带内距离变换 ×2（D188 的正确口径）。返回逐像素宽度，带外为 0。"""
    return 2.0 * cv2.distanceTransform(mask.astype(np.uint8), cv2.DIST_L2, 5)


def _align_up(v: float, step: int = 8) -> int:
    return int(np.ceil(v / step) * step)


def _box_stats(width: np.ndarray, y0: int, x0: int, s: int) -> tuple[float, float, int]:
    v = width[y0:y0 + s, x0:x0 + s]
    v = v[v > 0]
    if v.size < 4:
        return 0.0, 0.0, int(v.size)
    return float(np.percentile(v, 50)), float(np.percentile(v, 90)), int(v.size)


def build_windows(hole: np.ndarray, band: np.ndarray, *,
                  ctx_mult: float = 2.0, ctx_min: int = 32,
                  zoom_target_px: float = 32.0,
                  zoom_clip: tuple[float, float] = (1.0, 8.0),
                  min_sep_frac: float = 0.5, probe: int = 128,
                  max_windows: int = 400, model_side: int = MODEL_SIDE,
                  verbose: bool = True) -> list[dict]:
    """贪心覆盖 `hole`，返回窗列表。每个窗：

        x0, y0, side   —— 原图坐标系里的正方形窗（边长对齐 8 的倍数）
        zoom           —— 达成的放大倍率 = model_side / side
        w50, w90       —— 窗内局部带宽分位（原图像素）
        w50_model      —— 放大后带宽在模型输入里的宽度 = w50 × zoom
        lat50          —— w50_model / 8，带在 latent 里占几个像素

    `hole` 是**送进模型的洞**（真带场景 = 带 ∪ 前景排除），`band` 是**只允许被改写的
    那条带**。窗按 hole 铺，带宽按 band 量——混用会让"前景排除膨胀越多、窗越大、zoom
    越小"这种反向依赖偷偷进来。
    """
    h, w = hole.shape
    width = local_width(band)
    zlo, zhi = zoom_clip

    def side_for(y0: int, x0: int, s_prev: int) -> tuple[int, float, float]:
        """由局部带宽定窗边长：既要放大到 zoom_want，又要留够上下文。"""
        w50, w90, _ = _box_stats(width, y0, x0, s_prev)
        if w50 <= 0:                       # 窗里没有带（纯前景排除区），给个上下文下限
            return _align_up(2 * ctx_min), 0.0, 0.0
        z_want = float(np.clip(zoom_target_px / w50, zlo, zhi))
        s_zoom = model_side / z_want
        s_ctx = 2.0 * max(ctx_mult * w90, ctx_min)   # 窗心到窗边至少留这么多上下文
        return _align_up(max(s_zoom, s_ctx)), w50, w90

    # 要盖满的是**带**，不是整个洞：洞里带外的部分（前景排除区）不会被贴回，只要它在
    # 某个窗内被正确标成洞就够了。第一版拿整个洞当覆盖目标，多开了一批只含前景排除区
    # 的窗，还是没盖满带（06 场景 79.4%）。
    uncovered = band.copy()
    out: list[dict] = []
    while uncovered.any() and len(out) < max_windows:
        # 贪心种子：未覆盖带像素最密处。密度用 probe 尺度的盒滤波，但**种子本身必须是
        # 一个未覆盖的带像素**——只按密度取会死循环：窗只清掉中心 side×side，而密度是在
        # 更大的 probe box 上算的，清完之后同一点仍是最大值，于是原地反复开同一个窗
        # （实测 06 场景在 (451,324) 连开 1996 个 64px 窗，覆盖率卡在 59.3%）。
        dens = cv2.blur(uncovered.astype(np.float32), (probe, probe))
        dens[~uncovered] = -1.0
        cy, cx = np.unravel_index(int(np.argmax(dens)), dens.shape)

        s = _align_up(probe)
        for _ in range(3):                              # 边长↔带宽互相依赖，迭代到稳定
            y0 = int(np.clip(cy - s // 2, 0, max(h - s, 0)))
            x0 = int(np.clip(cx - s // 2, 0, max(w - s, 0)))
            s2, w50, w90 = side_for(y0, x0, s)
            s2 = min(s2, min(h, w))
            if s2 == s:
                break
            s = s2
        s = min(_align_up(s), min(h, w))
        y0 = int(np.clip(cy - s // 2, 0, max(h - s, 0)))
        x0 = int(np.clip(cx - s // 2, 0, max(w - s, 0)))
        w50, w90, _ = _box_stats(width, y0, x0, s)

        # 工单的"窗心距 < 50% 窗宽就合并"在覆盖式布点里**不能照搬成拒绝规则**：拒绝会把
        # 种子丢掉、留下没有任何窗覆盖的带像素，那些像素只能退回原内容，比较当场失效
        # （第一版实测 00 场景只覆盖 56.1%、06 场景 79.4%）。这里一律开窗——种子取的是
        # 未覆盖带像素最密处，开完就把整个窗划掉，因此不会出现几乎重合的窗；真出现了也
        # 只是多跑一个窗，两个后端拿到的窗完全一样，单变量性不受影响。
        if not uncovered[y0:y0 + s, x0:x0 + s].any():      # 兜底：保证每轮都有进展
            uncovered[cy, cx] = False
            continue
        zoom = model_side / s
        sel = np.zeros((h, w), bool)
        sel[y0:y0 + s, x0:x0 + s] = True
        out.append({"x0": x0, "y0": y0, "side": int(s), "zoom": float(zoom),
                    "w50": w50, "w90": w90,
                    "w50_model": w50 * zoom, "w90_model": w90 * zoom,
                    "lat50": w50 * zoom / LATENT_DOWN,
                    "lat50_native": w50 / LATENT_DOWN,
                    "holePx": int((sel & hole).sum()), "bandPx": int((sel & band).sum())})
        uncovered[y0:y0 + s, x0:x0 + s] = False

    out.sort(key=lambda r: (r["y0"], r["x0"]))
    if verbose and out:
        st = window_stats_table(out, band)
        print(f"    裁窗 {len(out)} 个：边长 {st['sideMin']}–{st['sideMax']}px，"
              f"zoom 按带像素加权中位 {st['zoomBandMedian']:.2f}"
              f"（按窗 {st['zoomMedian']:.2f}），带在模型输入里 "
              f"{st['w50ModelBandMedian']:.1f}px = {st['lat50BandMedian']:.2f} latent px，"
              f"覆盖带 {100*coverage(out, hole, band):.1f}%")
    return out


def coverage(windows: list[dict], hole: np.ndarray, band: np.ndarray) -> float:
    """窗集合覆盖了带的多大比例。**低于 100% 必须报出来**——没被任何窗覆盖的带像素只能
    退回原内容，那部分不参与"模型补得好不好"的比较，不报就等于默认全覆盖。"""
    cov = np.zeros(hole.shape, bool)
    for r in windows:
        cov[r["y0"]:r["y0"] + r["side"], r["x0"]:r["x0"] + r["side"]] = True
    return float((cov & band).sum() / max(int(band.sum()), 1))


def _wmedian(v: np.ndarray, wt: np.ndarray) -> float:
    """加权中位数。带像素加权是本模块所有汇总量的默认口径（见模块头第 3 条）。"""
    if v.size == 0 or wt.sum() <= 0:
        return float("nan")
    o = np.argsort(v)
    v, wt = v[o], wt[o]
    c = np.cumsum(wt) / wt.sum()
    return float(v[int(np.searchsorted(c, 0.5))])


def window_stats_table(windows: list[dict], band: np.ndarray | None = None) -> dict:
    if not windows:
        return {"n": 0}
    f = lambda k: np.array([r[k] for r in windows], np.float64)
    bp = f("bandPx")
    return {
        "n": len(windows),
        "sideMin": int(min(r["side"] for r in windows)),
        "sideMax": int(max(r["side"] for r in windows)),
        "zoomMedian": float(np.median(f("zoom"))),
        "zoomMin": float(f("zoom").min()), "zoomMax": float(f("zoom").max()),
        "zoomBandMedian": _wmedian(f("zoom"), bp),
        "w50BandMedian": _wmedian(f("w50"), bp),
        "w50ModelBandMedian": _wmedian(f("w50_model"), bp),
        "lat50BandMedian": _wmedian(f("lat50"), bp),
        "lat50NativeBandMedian": _wmedian(f("lat50_native"), bp),
        "holePx": int(sum(r["holePx"] for r in windows)),
        "bandPx": int(sum(r["bandPx"] for r in windows)),
    }
