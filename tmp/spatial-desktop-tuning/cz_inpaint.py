#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 公平对照的执行层：四个档位 × 两个后端，**同一裁窗、同一 zoom、同一贴回**。

档位（`--ladder`）：

| 档 | 名字 | 模型看到的东西 | 用途 |
|---|---|---|---|
| 0a | `square` | 整幅反射填充成方形后缩到 512 | 文献意义上的"整图直喂"，也是 D160 的对照组之一 |
| 0b | `tiled` | 512 原生分块、重叠 128、Hanning 窗 | **D160 与产线的实际口径**（复现基线，见下） |
| 1 | `crop` | 沿带裁窗、不放大、反射填充到 512 | 把"裁"的贡献单独分出来 |
| 2 | `cropzoom` | 同一个窗放大到 512 | 把"放大"的贡献单独分出来 |

**前提纠正（2026-08-12 本轮核对）**：调研文档把 D160 记成"整图缩到 512 直喂"，
实况是 D160 两个模型**都跑的 1:1 分块**（D160 原文："排除了所有能想到的偏袒：
1:1 分块、CFG 扫 1–16……"），`moebius_infer.py --mode square` 只是它的对照组。
差别很要紧：带在原生分辨率下本来就只有 4.4–16.8px 宽（≈0.55–2.1 个 latent 像素），
**不缩图也照样落在亚 latent 像素区间**——所以能改变这一点的只有放大，不是"别缩图"。
`tiled` 档因此是真正要打败的基线，`square` 档只是把文献那个读法也一起量掉。

档 1 与档 2 的窗内容**逐像素相同**，只有重采样不同——这是把 zoom 单独隔离出来的
唯一办法。档 1 因此拿到的真实上下文比档 0b 少（窗外是反射填充），这个代价由
0b→1 那一格量出来，不与放大的收益混在一起。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from cz_windows import MODEL_SIDE, build_windows, coverage, local_width, window_stats_table

ROOT = Path("E:/projects/EverythingDone")
LAMA_ONNX = ROOT / "build/spatial-model-poc/artifacts/big_lama_places2_512_fp32.onnx"

# 放大统一用 Lanczos4，缩回统一用 AREA（带抗混叠的正确降采样）。两个后端共用，
# 不给任何一边留重采样上的便宜。
UP, DOWN = cv2.INTER_LANCZOS4, cv2.INTER_AREA


# --------------------------------------------------------------------------- 作业构造

def _mask_resize(m: np.ndarray, side: int) -> np.ndarray:
    """掩膜重采样按面积占比过半判定，比 NEAREST 稳（细带在降采样时不会整条消失）。"""
    r = cv2.resize(m.astype(np.float32), (side, side), interpolation=cv2.INTER_LINEAR)
    return r > 0.5


def _dilate(m: np.ndarray, px: int) -> np.ndarray:
    if px <= 0:
        return m
    k = 2 * px + 1
    return cv2.dilate(m.astype(np.uint8), np.ones((k, k), np.uint8)) > 0


def _hann_window(side: int, overlap: int) -> np.ndarray:
    ramp = np.hanning(overlap * 2)[:overlap]
    v = np.ones(side, np.float32)
    v[:overlap] = ramp
    v[-overlap:] = ramp[::-1]
    return np.outer(v, v).astype(np.float32)


def _ramp_window(side: int, feather: int) -> np.ndarray:
    f = max(1, min(feather, side // 2))
    ramp = 0.5 - 0.5 * np.cos(np.pi * (np.arange(f) + 0.5) / f)
    v = np.ones(side, np.float32)
    v[:f] = ramp
    v[-f:] = ramp[::-1]
    return np.outer(v, v).astype(np.float32)


def prepare_jobs(image: np.ndarray, hole: np.ndarray, band: np.ndarray, ladder: str, *,
                 fg: np.ndarray | None = None, shield_fg: bool = False,
                 dilate_model_px: int = 0, tile_overlap: int = 128,
                 feather: int = 8, verbose: bool = True) -> tuple[list[dict], dict]:
    """把一次补全拆成若干 512² 的作业。返回 (jobs, meta)。

    `dilate_model_px` 在**模型输入尺度**上膨胀（8px = 1 个 latent 像素）。放在这里而不是
    原图上，是因为放大之后 8px 原图膨胀会变成 32px 模型膨胀——两个档位的"膨胀量"就不是
    同一个量了，受控变量当场失效。
    """
    h, w = image.shape[:2]
    jobs: list[dict] = []
    meta: dict = {"ladder": ladder, "shieldFg": bool(shield_fg),
                  "dilateModelPx": int(dilate_model_px)}

    if ladder == "square":
        side = max(w, h)
        pl, pt = (side - w) // 2, (side - h) // 2
        pad = ((pt, side - h - pt), (pl, side - w - pl))
        img_p = np.pad(image, pad + ((0, 0),), mode="reflect")
        hole_p = np.pad(hole.astype(np.uint8), pad)
        if shield_fg and fg is not None:
            hole_p |= np.pad(fg.astype(np.uint8), pad)
        patch = cv2.resize(img_p, (MODEL_SIDE, MODEL_SIDE), interpolation=DOWN)
        m = _dilate(_mask_resize(hole_p > 0, MODEL_SIDE), dilate_model_px)
        bnd = _mask_resize(np.pad(band.astype(np.uint8), pad) > 0, MODEL_SIDE)
        jobs.append({"patch": patch.astype(np.uint8), "mask": m, "bandIn": bnd,
                     "place": ("square", pl, pt, side), "weight": None})
        meta["windows"] = 1
        meta["coverage"] = 1.0

    elif ladder == "tiled":
        tile, stride = MODEL_SIDE, MODEL_SIDE - tile_overlap
        pad_r = (tile - w) if w <= tile else (-(-max(w - tile, 0) // stride) * stride + tile - w)
        pad_b = (tile - h) if h <= tile else (-(-max(h - tile, 0) // stride) * stride + tile - h)
        img_p = np.pad(image, ((0, max(pad_b, 0)), (0, max(pad_r, 0)), (0, 0)), mode="reflect")
        hole_p = np.pad(hole.astype(np.uint8), ((0, max(pad_b, 0)), (0, max(pad_r, 0))))
        if shield_fg and fg is not None:
            hole_p |= np.pad(fg.astype(np.uint8), ((0, max(pad_b, 0)), (0, max(pad_r, 0))))
        ph, pw = img_p.shape[:2]
        band_p = np.pad(band.astype(np.uint8),
                        ((0, max(pad_b, 0)), (0, max(pad_r, 0)))) > 0
        win = _hann_window(tile, tile_overlap)
        for y in range(0, max(ph - tile, 0) + 1, stride):
            for x in range(0, max(pw - tile, 0) + 1, stride):
                m = hole_p[y:y + tile, x:x + tile] > 0
                if not m.any():
                    continue
                jobs.append({"patch": img_p[y:y + tile, x:x + tile].astype(np.uint8),
                             "mask": _dilate(m, dilate_model_px),
                             "bandIn": band_p[y:y + tile, x:x + tile],
                             "place": ("tile", x, y, tile), "weight": win})
        meta["windows"] = len(jobs)
        meta["coverage"] = 1.0

    elif ladder in ("crop", "cropzoom"):
        wins = build_windows(hole, band, verbose=verbose)
        meta["windowTable"] = window_stats_table(wins)
        meta["coverage"] = coverage(wins, hole, band)
        meta["windows"] = len(wins)
        for r in wins:
            s, x0, y0 = r["side"], r["x0"], r["y0"]
            sub_img = image[y0:y0 + s, x0:x0 + s]
            sub_hole = hole[y0:y0 + s, x0:x0 + s].copy()
            sub_band = band[y0:y0 + s, x0:x0 + s]
            if shield_fg and fg is not None:
                sub_hole |= fg[y0:y0 + s, x0:x0 + s]
            if ladder == "cropzoom":
                patch = cv2.resize(sub_img, (MODEL_SIDE, MODEL_SIDE),
                                   interpolation=UP if s < MODEL_SIDE else DOWN)
                m = _mask_resize(sub_hole, MODEL_SIDE)
                bnd = _mask_resize(sub_band, MODEL_SIDE)
                zoom = MODEL_SIDE / s
            else:
                # 档 1：窗内容逐像素不动，反射填充到 512（窗大于 512 时取中心 512）
                patch = np.zeros((MODEL_SIDE, MODEL_SIDE, 3), np.uint8)
                m = np.zeros((MODEL_SIDE, MODEL_SIDE), bool)
                if s <= MODEL_SIDE:
                    pl = (MODEL_SIDE - s) // 2
                    pr = MODEL_SIDE - s - pl
                    patch = np.pad(sub_img, ((pl, pr), (pl, pr), (0, 0)), mode="reflect")
                    m = np.pad(sub_hole.astype(np.uint8), ((pl, pr), (pl, pr))) > 0
                    bnd = np.pad(sub_band.astype(np.uint8), ((pl, pr), (pl, pr))) > 0
                    inner = (pl, pl, s)
                else:
                    c = (s - MODEL_SIDE) // 2
                    patch = sub_img[c:c + MODEL_SIDE, c:c + MODEL_SIDE]
                    m = sub_hole[c:c + MODEL_SIDE, c:c + MODEL_SIDE]
                    bnd = sub_band[c:c + MODEL_SIDE, c:c + MODEL_SIDE]
                    inner = (-c, -c, s)
                zoom = 1.0
                r = dict(r, _inner=inner)
            jobs.append({"patch": patch.astype(np.uint8),
                         "mask": _dilate(m, dilate_model_px),
                         "bandIn": bnd,
                         "place": (ladder, x0, y0, s),
                         "win": r, "zoom": zoom,
                         "weight": _ramp_window(s, feather)})
    else:
        raise SystemExit(f"未知档位 {ladder}")

    # 实际喂进模型的带宽（判读规则要核的那个数）：在模型输入尺度上重量一次，
    # 不用"原图带宽 × zoom"推算——重采样与膨胀都会改变它。
    # 按洞像素加权取中位：按作业取中位会被大量只含十几个像素的碎片窗带偏
    # （实测 00 场景档 1 按作业报 2.8px，按洞像素加权是 8.7px）。
    ws, wt = [], []
    for j in jobs:
        lw = local_width(j["mask"])
        v = lw[lw > 0]
        if v.size >= 16:
            ws.append(float(np.percentile(v, 50)))
            wt.append(float(v.size))
    if ws:
        o = np.argsort(ws)
        a, b = np.array(ws)[o], np.array(wt)[o]
        meta["modelHoleW50"] = float(a[int(np.searchsorted(np.cumsum(b) / b.sum(), 0.5))])
    else:
        meta["modelHoleW50"] = float("nan")
    meta["modelHoleLat50"] = meta["modelHoleW50"] / 8.0
    if verbose:
        # 洞宽 ≠ 带宽：洞是「带 ∪ 前景排除」，比带宽得多。判读规则要核的是**带**在模型
        # 输入里的宽度，那个数在 windowTable.w50ModelBandMedian 里，两者不能混着报。
        print(f"    档 {ladder}：{len(jobs)} 个作业，模型输入里的洞宽中位 "
              f"{meta['modelHoleW50']:.1f}px（{meta['modelHoleLat50']:.2f} latent px）"
              f"，覆盖带 {100*meta['coverage']:.1f}%")
    return jobs, meta


# --------------------------------------------------------------------------- 后端

_LAMA_SESSION: dict = {}


def _import_ort():
    """导入 onnxruntime，并先把 torch 自带的 CUDA/cuDNN DLL 目录挂上。

    `spatialtuning` 环境里装的是 `onnxruntime-gpu`，但它不带 CUDA 运行时；本机的
    CUDA 12.8 + cuDNN 9 是 torch 2.11.0+cu128 随包带的。不先 `add_dll_directory`，
    `CUDAExecutionProvider` 会在建 session 时静默回落到 CPU——**回落是静默的**，
    只看跑通与否发现不了，只能看单窗耗时。
    """
    import os
    import torch
    lib = os.path.join(os.path.dirname(torch.__file__), "lib")
    if os.path.isdir(lib):
        try:
            os.add_dll_directory(lib)
        except (OSError, AttributeError):
            pass
    import onnxruntime as ort
    return ort


def run_lama(jobs: list[dict], model: Path = LAMA_ONNX, providers=None) -> list[np.ndarray]:
    ort = _import_ort()
    key = (str(model), tuple(providers) if providers else None)
    if key not in _LAMA_SESSION:      # 扫描要跑上千次，别每次重建 session
        want = list(providers) if providers else ["CUDAExecutionProvider", "CPUExecutionProvider"]
        want = [p for p in want if p in ort.get_available_providers()] or ["CPUExecutionProvider"]
        s = ort.InferenceSession(str(model), providers=want)
        got = s.get_providers()
        if "CUDAExecutionProvider" in want and "CUDAExecutionProvider" not in got:
            print(f"  [警告] Big-LaMa 回落到 {got}——CUDA EP 没起来")
        _LAMA_SESSION[key] = s
    sess = _LAMA_SESSION[key]
    names = [i.name for i in sess.get_inputs()]
    outs = []
    for j in jobs:
        o = sess.run(None, {
            names[0]: (j["patch"].astype(np.float32) / 255.0).transpose(2, 0, 1)[None],
            names[1]: j["mask"][None, None].astype(np.float32),
        })[0][0].transpose(1, 2, 0)
        if o.max() <= 1.5:
            o = o * 255.0
        outs.append(np.clip(o, 0, 255).astype(np.float32))
    return outs


def run_moebius(jobs: list[dict], *, steps: int = 20, cfg: float = 2.0, seed: int = 0,
                per_job: dict | None = None, batch: int = 1,
                worker: Path | None = None) -> list[np.ndarray]:
    """子进程调用 Moebius（仓库有顶层 utils.py，直接 import 会污染本管线的模块空间）。

    **批处理只允许在「同一次运行」内部做**（`per_job["groups"]` 标段）。Moebius 的
    pipeline 每次 `__call__` 只 `torch.manual_seed` 一次、再对整个 batch 一起
    `randn_like`，所以逐窗噪声取决于 (batch 组成, 位置)。同一段在不同配置下作业列表逐个
    相同、顺序相同、batch 大小相同 ⇒ 每个窗拿到的噪声跨配置一致，单变量成立；跨段混批
    则不成立。不给 groups 时按逐作业分段（等价 batch=1）。
    """
    worker = worker or (ROOT / "tmp/spatial-desktop-tuning/cz_moebius_worker.py")
    with tempfile.TemporaryDirectory(prefix="cz_moebius_") as tmp:
        tmp = Path(tmp)
        payload = {"patches": np.stack([j["patch"] for j in jobs]),
                   "masks": np.stack([j["mask"].astype(np.uint8) for j in jobs])}
        payload.update(per_job or {})       # cfgs / seeds / steps / groups，逐作业
        # 不压缩：几百个 512² patch 压一次要几十秒，比省下的 IO 贵得多
        np.savez(tmp / "in.npz", **payload)
        env = dict(os.environ, PYTHONPATH=str(ROOT / "tmp/Moebius"))
        cmd = [sys.executable, str(worker), "--in", str(tmp / "in.npz"),
               "--out", str(tmp / "out.npz"), "--steps", str(steps),
               "--cfg", str(cfg), "--seed", str(seed), "--batch", str(batch)]
        p = subprocess.run(cmd, env=env, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if p.returncode != 0:
            raise RuntimeError(f"Moebius 失败：\n{p.stdout[-3000:]}\n{p.stderr[-3000:]}")
        return list(np.load(tmp / "out.npz")["outputs"].astype(np.float32))


# --------------------------------------------------------------------------- 贴回

def hist_match(src: np.ndarray, ref: np.ndarray, sel: np.ndarray) -> np.ndarray:
    """逐通道 CDF 直方图匹配，映射只在 `sel`（窗内未修改区）上统计。

    Big-LaMa 在洞外逐位透传，这一步对它是恒等；Moebius 走 VAE 往返，洞外实测有
    7 级量级的漂移（D160）——同一段代码同时作用于两边，量出来的差就是 VAE 色漂本身。
    """
    if sel.sum() < 1024:
        return src.copy()
    out = src.copy()
    grid = np.arange(256, dtype=np.float64)
    for c in range(3):
        s, r = src[..., c][sel], ref[..., c][sel]
        hs = np.bincount(np.clip(np.round(s), 0, 255).astype(np.int32), minlength=256).astype(np.float64)
        hr = np.bincount(np.clip(np.round(r), 0, 255).astype(np.int32), minlength=256).astype(np.float64)
        cs, cr = np.cumsum(hs) / max(hs.sum(), 1), np.cumsum(hr) / max(hr.sum(), 1)
        lut = np.interp(cs, cr, grid)
        out[..., c] = np.interp(src[..., c], grid, lut)
    return out


def paste_back(image: np.ndarray, band: np.ndarray, jobs: list[dict],
               outputs: list[np.ndarray], *, color_align: bool = True,
               verbose: bool = True) -> tuple[np.ndarray, dict]:
    """像素域贴回：缩回窗尺寸 → 窗级色彩对齐 → 按窗权重累加 → **带外一律恢复原像素**。

    带边不做羽化，与 `_base` 线的 `np.where(mask, c, image)` 完全一致（D133 纪律）。
    羽化只用在**窗与窗的重叠**上——那是 crop-and-stitch 协议里羽化本来的位置。
    两个后端走的是同一段代码，所以这个取舍不影响对照的单变量性。
    """
    h, w = image.shape[:2]
    accum = np.zeros((h, w, 3), np.float32)
    weight = np.zeros((h, w), np.float32)
    drift_before, drift_after = [], []

    for j, o in zip(jobs, outputs):
        kind, a, b, s = j["place"]
        if kind == "square":
            pl, pt, side = a, b, s
            full = cv2.resize(o, (side, side), interpolation=UP if side > MODEL_SIDE else DOWN)
            ref = np.pad(image, ((pt, side - h - pt), (pl, side - w - pl), (0, 0)), mode="reflect")
            sel = ~_mask_resize_full(j["mask"], side)
            if color_align:
                drift_before.append(float(np.abs(full[sel] - ref[sel]).mean()))
                full = hist_match(full, ref.astype(np.float32), sel)
                drift_after.append(float(np.abs(full[sel] - ref[sel]).mean()))
            accum += full[pt:pt + h, pl:pl + w]
            weight += 1.0
            continue

        if kind == "tile":
            x0, y0, side = a, b, s
            sub = o
            ref = j["patch"].astype(np.float32)
            sel = ~j["mask"]
        else:
            x0, y0, side = a, b, s
            if kind == "cropzoom":
                sub = cv2.resize(o, (side, side), interpolation=DOWN if side < MODEL_SIDE else UP)
                ref = image[y0:y0 + side, x0:x0 + side].astype(np.float32)
                sel = ~_mask_resize_full(j["mask"], side)
            else:
                iy, ix, ss = j["win"]["_inner"][1], j["win"]["_inner"][0], side
                if ss <= MODEL_SIDE:
                    sub = o[iy:iy + ss, ix:ix + ss]
                    m = j["mask"][iy:iy + ss, ix:ix + ss]
                else:
                    # 窗比 512 还大时档 1 只能取中心 512 推理，窗的其余部分保持原像素
                    c = (ss - MODEL_SIDE) // 2
                    sub = image[y0:y0 + ss, x0:x0 + ss].astype(np.float32).copy()
                    sub[c:c + MODEL_SIDE, c:c + MODEL_SIDE] = o
                    m = np.zeros((ss, ss), bool)
                    m[c:c + MODEL_SIDE, c:c + MODEL_SIDE] = j["mask"]
                ref = image[y0:y0 + ss, x0:x0 + ss].astype(np.float32)
                sel = ~m
        if color_align and sel.sum() >= 1024:
            drift_before.append(float(np.abs(sub[sel] - ref[sel]).mean()))
            sub = hist_match(sub, ref, sel)
            drift_after.append(float(np.abs(sub[sel] - ref[sel]).mean()))
        wgt = j["weight"] if j["weight"] is not None else np.ones(sub.shape[:2], np.float32)
        if wgt.shape != sub.shape[:2]:
            wgt = cv2.resize(wgt, (sub.shape[1], sub.shape[0]), interpolation=cv2.INTER_LINEAR)
        y1, x1 = min(y0 + sub.shape[0], h), min(x0 + sub.shape[1], w)
        accum[y0:y1, x0:x1] += sub[:y1 - y0, :x1 - x0] * wgt[:y1 - y0, :x1 - x0, None]
        weight[y0:y1, x0:x1] += wgt[:y1 - y0, :x1 - x0]

    filled = np.where(weight[..., None] > 1e-6,
                      accum / np.maximum(weight, 1e-6)[..., None],
                      image.astype(np.float32))
    uncovered = int((band & (weight <= 1e-6)).sum())
    out = np.where(band[..., None], filled, image.astype(np.float32))
    stats = {
        "uncoveredBandPx": uncovered,
        "uncoveredBandFrac": uncovered / max(int(band.sum()), 1),
        "driftBefore": float(np.mean(drift_before)) if drift_before else 0.0,
        "driftAfter": float(np.mean(drift_after)) if drift_after else 0.0,
    }
    if verbose:
        print(f"    贴回：窗外未覆盖的带像素 {uncovered}（{100*stats['uncoveredBandFrac']:.2f}%），"
              f"色彩对齐前后洞外色差 {stats['driftBefore']:.2f} -> {stats['driftAfter']:.2f} 级")
    return out, stats


def _mask_resize_full(m: np.ndarray, side: int) -> np.ndarray:
    r = cv2.resize(m.astype(np.float32), (side, side), interpolation=cv2.INTER_LINEAR)
    return r > 0.5


# --------------------------------------------------------------------------- CLI

def run_one(image, hole, band, ladder, backend, *, fg=None, shield_fg=False,
            dilate_model_px=0, steps=20, cfg=2.0, seed=0, color_align=True,
            lama_providers=None, verbose=True):
    jobs, meta = prepare_jobs(image, hole, band, ladder, fg=fg, shield_fg=shield_fg,
                              dilate_model_px=dilate_model_px, verbose=verbose)
    if not jobs:
        return image.astype(np.float32), dict(meta, empty=True)
    if backend == "lama":
        outs = run_lama(jobs, providers=lama_providers)
    elif backend == "moebius":
        outs = run_moebius(jobs, steps=steps, cfg=cfg, seed=seed)
    else:
        raise SystemExit(f"未知后端 {backend}")
    filled, st = paste_back(image, band, jobs, outs, color_align=color_align, verbose=verbose)
    meta.update(st)
    meta["backend"] = backend
    return filled, meta


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--hole", type=Path, required=True, help="送进模型的洞（白=洞）")
    ap.add_argument("--band", type=Path, default=None, help="只允许改写的带；缺省=hole")
    ap.add_argument("--fg", type=Path, default=None, help="前景（遮挡物）掩膜，供窗内屏蔽")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--ladder", choices=("square", "tiled", "crop", "cropzoom"), required=True)
    ap.add_argument("--backend", choices=("lama", "moebius"), required=True)
    ap.add_argument("--shield-fg", action="store_true")
    ap.add_argument("--dilate-model-px", type=int, default=0)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--cfg", type=float, default=2.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--no-color-align", action="store_true")
    ap.add_argument("--meta-out", type=Path, default=None)
    args = ap.parse_args()

    image = np.asarray(Image.open(args.image).convert("RGB"))
    hole = np.asarray(Image.open(args.hole).convert("L")) > 127
    band = np.asarray(Image.open(args.band).convert("L")) > 127 if args.band else hole
    fg = np.asarray(Image.open(args.fg).convert("L")) > 127 if args.fg else None
    filled, meta = run_one(image, hole, band, args.ladder, args.backend, fg=fg,
                           shield_fg=args.shield_fg, dilate_model_px=args.dilate_model_px,
                           steps=args.steps, cfg=args.cfg, seed=args.seed,
                           color_align=not args.no_color_align)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(filled, 0, 255).astype(np.uint8)).save(args.out)
    if args.meta_out:
        args.meta_out.write_text(json.dumps(meta, ensure_ascii=False, indent=2, default=float),
                                 encoding="utf-8")
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
