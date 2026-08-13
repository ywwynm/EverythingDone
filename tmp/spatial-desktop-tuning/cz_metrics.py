#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 对照的带内指标。**统计区域一律是「带 + 外扩 2px 环」**（工单口径）。

四项主指标：
- **L1 / PSNR / 去偏 PSNR**：逐像素保真。去偏先扣掉逐通道均值偏移——产线本来就有全局
  标量校色（D159），整体亮度差是可修的，不该计进模型的结构成绩。
- **梯度相关**：结构续接得对不对。纹理能量早已达标、缺的是形状（D157），这一项是
  唯一直接量形状的。
- **LPIPS**：感知距离，按含带的 64px 块算再平均。**只作参考**，与 FID 一样不参与结论
  ——D160 的教训是这类榜单指标的排序不迁移到细带补全。

另有**分层统计**：按每个带像素自己的局部宽度（换算成 latent 像素）分箱。这是本轮要
产出的那条文献空白曲线——「带宽多窄开始失效」。
"""
from __future__ import annotations

import cv2
import numpy as np

BINS = [(0.0, 0.5), (0.5, 1.0), (1.0, 1.5), (1.5, 2.0),
        (2.0, 3.0), (3.0, 4.0), (4.0, 6.0), (6.0, 1e9)]
BIN_LABEL = ["<0.5", "0.5-1", "1-1.5", "1.5-2", "2-3", "3-4", "4-6", ">=6"]

_LPIPS = None
_LPIPS_DEV = "cpu"


def eval_region(mask: np.ndarray, ring: int = 2) -> np.ndarray:
    k = 2 * ring + 1
    return cv2.dilate(mask.astype(np.uint8), np.ones((k, k), np.uint8)) > 0


def _grey(a):
    return cv2.cvtColor(a.astype(np.float32), cv2.COLOR_RGB2GRAY)


def grad_corr(a: np.ndarray, b: np.ndarray, m: np.ndarray) -> float:
    ga = np.stack([cv2.Sobel(a, cv2.CV_32F, 1, 0, 3), cv2.Sobel(a, cv2.CV_32F, 0, 1, 3)], -1)
    gb = np.stack([cv2.Sobel(b, cv2.CV_32F, 1, 0, 3), cv2.Sobel(b, cv2.CV_32F, 0, 1, 3)], -1)
    x, y = ga[m].ravel(), gb[m].ravel()
    if x.size < 16:
        return float("nan")
    x, y = x - x.mean(), y - y.mean()
    d = np.sqrt((x * x).sum() * (y * y).sum())
    return float((x * y).sum() / d) if d > 0 else float("nan")


def lpips_tiles(a: np.ndarray, gt: np.ndarray, mask: np.ndarray,
                tile: int = 64, min_cov: float = 0.05) -> float:
    """按含带的 64px 块算 LPIPS 再平均。整幅算没有意义——带只占十几个百分点，
    未修改区会把差异稀释掉。"""
    global _LPIPS, _LPIPS_DEV
    import torch
    import lpips as _lp
    if _LPIPS is None:
        _LPIPS_DEV = "cuda" if torch.cuda.is_available() else "cpu"
        _LPIPS = _lp.LPIPS(net="alex", verbose=False).to(_LPIPS_DEV)
        _LPIPS.eval()
    h, w = mask.shape
    ta, tb = [], []
    for y in range(0, h - tile + 1, tile // 2):
        for x in range(0, w - tile + 1, tile // 2):
            if mask[y:y + tile, x:x + tile].mean() < min_cov:
                continue
            ta.append(a[y:y + tile, x:x + tile])
            tb.append(gt[y:y + tile, x:x + tile])
    if not ta:
        return float("nan")
    to = lambda s: torch.from_numpy(
        (np.stack(s).astype(np.float32) / 127.5 - 1.0).transpose(0, 3, 1, 2)).to(_LPIPS_DEV)
    vals = []
    with torch.no_grad():
        for i in range(0, len(ta), 256):
            vals.append(_LPIPS(to(ta[i:i + 256]), to(tb[i:i + 256])).mean().item())
    return float(np.mean(vals))


def band_metrics(pred: np.ndarray, gt: np.ndarray, band: np.ndarray, *,
                 ring: int = 2, with_lpips: bool = True) -> dict:
    m = eval_region(band, ring)
    pred, gt = pred.astype(np.float32), gt.astype(np.float32)
    d = pred[m] - gt[m]
    mse = float((d ** 2).mean())
    mse_db = float(((d - d.mean(0)) ** 2).mean())
    hf = lambda a, s: float(np.sqrt((cv2.Laplacian(_grey(a), cv2.CV_32F, 3)[s] ** 2).mean()))
    out = {
        "px": int(m.sum()),
        "L1": float(np.abs(d).mean()),
        "PSNR": 10 * np.log10(255.0 ** 2 / max(mse, 1e-9)),
        "PSNRdb": 10 * np.log10(255.0 ** 2 / max(mse_db, 1e-9)),
        "gradCorr": grad_corr(_grey(pred), _grey(gt), m),
        "HFratio": hf(pred, m) / max(hf(gt, m), 1e-6),
        "levelBias": float(_grey(pred)[m].mean() - _grey(gt)[m].mean()),
    }
    # 严口径：只算带本身，不含 2px 环。两个口径都报，避免"环里全是未修改像素"把
    # 差异稀释掉这种质疑。
    ds = pred[band] - gt[band]
    out["L1strict"] = float(np.abs(ds).mean())
    out["PSNRstrict"] = 10 * np.log10(255.0 ** 2 / max(float((ds ** 2).mean()), 1e-9))
    if with_lpips:
        out["LPIPS"] = lpips_tiles(pred, gt, m)
    return out


def stratified(pred: np.ndarray, gt: np.ndarray, band: np.ndarray,
               lat_width: np.ndarray) -> list[dict]:
    """按每个带像素自己的 latent 宽度分箱统计。`lat_width` 逐像素，带外可为任意值。"""
    pred, gt = pred.astype(np.float32), gt.astype(np.float32)
    rows = []
    for (lo, hi), lab in zip(BINS, BIN_LABEL):
        sel = band & (lat_width >= lo) & (lat_width < hi)
        n = int(sel.sum())
        if n < 64:
            rows.append({"bin": lab, "px": n, "L1": float("nan"), "PSNR": float("nan")})
            continue
        d = pred[sel] - gt[sel]
        rows.append({"bin": lab, "px": n,
                     "L1": float(np.abs(d).mean()),
                     "PSNR": 10 * np.log10(255.0 ** 2 / max(float((d ** 2).mean()), 1e-9))})
    return rows


def effective_zoom_map(jobs: list[dict], shape: tuple[int, int]) -> np.ndarray:
    """逐像素的**实际生效放大倍率**（按贴回权重加权）。用来把质量对"模型输入里的
    latent 宽度"作图——那才是因果变量，原图带宽只是它的代理。"""
    h, w = shape
    num = np.zeros((h, w), np.float32)
    den = np.zeros((h, w), np.float32)
    for j in jobs:
        kind, x0, y0, s = j["place"]
        if kind in ("square", "tile"):
            z = 512.0 / max(*shape) if kind == "square" else 1.0
            num += z
            den += 1.0
            continue
        wgt = j["weight"]
        if wgt is None:
            wgt = np.ones((s, s), np.float32)
        if wgt.shape != (s, s):
            wgt = cv2.resize(wgt, (s, s), interpolation=cv2.INTER_LINEAR)
        y1, x1 = min(y0 + s, h), min(x0 + s, w)
        num[y0:y1, x0:x1] += wgt[:y1 - y0, :x1 - x0] * j.get("zoom", 1.0)
        den[y0:y1, x0:x1] += wgt[:y1 - y0, :x1 - x0]
    return np.where(den > 1e-6, num / np.maximum(den, 1e-6), 1.0)
