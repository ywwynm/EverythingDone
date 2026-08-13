#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""crop-zoom 公平对照的**带真值评测台**：四档 × 两模型 × 受控变量，九场景并列。

为什么要有真值台：显露带背后的内容在照片里本就不存在，直接在真带上比只能看"像不像"。
沿用 D160 建的办法（`band_gt_eval.build_test_mask`）——把真带掩膜按 48px 块搬到纯背景
上，**局部形态（细网、宽度分布、沿断崖的走向）原样保留**，那里的真实像素就是真值。
这样"掩膜形态"这个决定难度的性质不变，同时拿到逐像素答案。

用法：

    python cz_bench.py --stage cfg      # 只在档 2 上选 Moebius 的 CFG
    python cz_bench.py --stage ladder   # 四档主矩阵
    python cz_bench.py --stage dilate   # 掩膜膨胀受控变量
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

import cz_inpaint as cz
import cz_metrics as M
from band_source import occluder_mask
from cz_gt import build_gt_mask
from cz_windows import local_width

SCENES = ["00_original_single", "01_original_double", "02_indoor", "03_office",
          "04_traffic", "05_near_object", "06_statue", "07_food", "08_person_pet"]
GEO = Path("qa/moge-geometry")
ASSETS = Path("assets")
OUT = Path("qa/cz-bench")


# --------------------------------------------------------------------------- 语料

def gt_case(scene: str, cache: dict) -> dict:
    """一个场景的真值算例：原图 + 搬运后的带掩膜（掩膜内的原始像素就是真值）。"""
    if scene in cache:
        return cache[scene]
    g, a = GEO / scene, ASSETS / scene
    image = np.asarray(Image.open(a / "center.jpg").convert("RGB"))
    band = np.asarray(Image.open(g / "hidden_mask_base.png").convert("L")) > 127
    h, w = band.shape
    shifted, info = build_gt_mask(band, occluder_mask(a, w, h))
    lat = local_width(shifted) / 8.0        # 每个测试像素自己的原生 latent 宽度
    cache[scene] = {"image": image, "mask": shifted, "lat": lat, "info": info,
                    "widthP50": float(np.median(local_width(shifted)[shifted]))}
    return cache[scene]


# --------------------------------------------------------------------------- 计划

def plan_runs(stage: str, cfg_best: float, dil_lama: int, dil_moeb: int,
              seeds: list[int]) -> list[dict]:
    runs = []
    if stage == "cfg":
        for c in (1.0, 2.0, 3.0):
            for s in seeds:
                runs.append({"ladder": "cropzoom", "backend": "moebius",
                             "cfg": c, "seed": s, "dilate": dil_moeb,
                             "key": f"cropzoom/moebius/cfg{c:g}/s{s}"})
    elif stage == "ladder":
        for lad in ("square", "tiled", "crop", "cropzoom"):
            runs.append({"ladder": lad, "backend": "lama", "cfg": 0, "seed": 0,
                         "dilate": dil_lama, "key": f"{lad}/lama"})
            for s in seeds:
                runs.append({"ladder": lad, "backend": "moebius", "cfg": cfg_best,
                             "seed": s, "dilate": dil_moeb, "key": f"{lad}/moebius/s{s}"})
    elif stage == "dilate":
        for d in (0, 8, 16):
            runs.append({"ladder": "cropzoom", "backend": "lama", "cfg": 0, "seed": 0,
                         "dilate": d, "key": f"cropzoom/lama/d{d}"})
            for s in seeds:
                runs.append({"ladder": "cropzoom", "backend": "moebius", "cfg": cfg_best,
                             "seed": s, "dilate": d, "key": f"cropzoom/moebius/d{d}/s{s}"})
    else:
        raise SystemExit(f"未知 stage {stage}")
    return runs


# --------------------------------------------------------------------------- 执行

def run_scene(scene: str, runs: list[dict], cache: dict, *, with_lpips: bool,
              save_dir: Path | None, batch: int = 1) -> list[dict]:
    case = gt_case(scene, cache)
    image, mask, lat = case["image"], case["mask"], case["lat"]
    print(f"\n=== {scene} ===  测试掩膜 {mask.sum()} px（{100*mask.mean():.2f}%），"
          f"局部宽度中位 {case['widthP50']:.1f}px = {case['widthP50']/8:.2f} latent px")

    # 同一 (档位, 膨胀) 的作业只构造一次：种子只改推理时的噪声，不改喂进去的图，
    # 每个种子各存一份 512² patch 会让内存翻五倍。
    bank: dict[tuple, tuple] = {}
    prepared = []
    for r in runs:
        k = (r["ladder"], r["dilate"])
        if k not in bank:
            bank[k] = cz.prepare_jobs(image, mask, mask, r["ladder"],
                                      dilate_model_px=r["dilate"], verbose=False)
        jobs, meta = bank[k]
        prepared.append((r, jobs, meta))

    # Moebius 的作业攒成一批，一次加载权重跑完（逐作业带自己的 cfg/seed）。
    # 分块提交：一次 np.stack 上千个 512² patch 会吃掉几个 GB。
    mb = [(i, k) for i, (r, jobs, _) in enumerate(prepared) if r["backend"] == "moebius"
          for k in range(len(jobs))]
    mb_out = {}
    if mb:
        t, done = time.time(), 0
        # 分块边界必须落在**运行边界**上：段内才允许批处理，跨段混批会改掉逐窗噪声。
        chunks, cur = [], []
        for e in mb:
            if cur and len(cur) >= 400 and e[0] != cur[-1][0]:
                chunks.append(cur)
                cur = []
            cur.append(e)
        if cur:
            chunks.append(cur)
        for chunk in chunks:
            flat = [prepared[i][1][k] for i, k in chunk]
            per = {"cfgs": np.array([prepared[i][0]["cfg"] for i, _ in chunk], np.float32),
                   "seeds": np.array([prepared[i][0]["seed"] for i, _ in chunk], np.int64),
                   "groups": np.array([i for i, _ in chunk], np.int64)}
            for (i, k), o in zip(chunk, cz.run_moebius(flat, per_job=per, batch=batch)):
                mb_out[(i, k)] = o
            done += len(chunk)
            print(f"  Moebius {done}/{len(mb)}  {time.time()-t:.0f}s "
                  f"（{(time.time()-t)/max(done,1):.2f}s/作业）", flush=True)

    rows = []
    for i, (r, jobs, meta) in enumerate(prepared):
        if not jobs:
            continue
        t = time.time()
        if r["backend"] == "lama":
            outs = cz.run_lama(jobs)
        else:
            outs = [mb_out[(i, k)] for k in range(len(jobs))]
        filled, st = cz.paste_back(image, mask, jobs, outs, verbose=False)
        m = M.band_metrics(filled, image, mask, with_lpips=with_lpips)
        zmap = M.effective_zoom_map(jobs, mask.shape)
        strat = M.stratified(filled, image, mask, lat)
        strat_model = M.stratified(filled, image, mask, lat * zmap)
        rows.append({"scene": scene, **{k: r[k] for k in ("key", "ladder", "backend", "cfg",
                                                          "seed", "dilate")},
                     **m, **st, "secs": time.time() - t,
                     "windows": len(jobs),
                     "bandLatNative": float(np.median((lat)[mask])),
                     "bandLatModel": float(np.median((lat * zmap)[mask])),
                     "strat": strat, "stratModel": strat_model,
                     "windowTable": meta.get("windowTable", {})})
        if save_dir is not None:
            p = save_dir / scene / (r["key"].replace("/", "_") + ".png")
            p.parent.mkdir(parents=True, exist_ok=True)
            Image.fromarray(np.clip(filled, 0, 255).astype(np.uint8)).save(p)
    return rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True, choices=("cfg", "ladder", "dilate"))
    ap.add_argument("--scenes", nargs="*", default=SCENES)
    ap.add_argument("--seeds", nargs="*", type=int, default=[0, 1, 2, 3, 4])
    ap.add_argument("--cfg-best", type=float, default=2.0)
    ap.add_argument("--dil-lama", type=int, default=0)
    ap.add_argument("--dil-moeb", type=int, default=8)
    ap.add_argument("--batch", type=int, default=1, help="段内批处理大小（见 run_moebius 文档）")
    ap.add_argument("--no-lpips", action="store_true")
    ap.add_argument("--save-images", action="store_true")
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    runs = plan_runs(args.stage, args.cfg_best, args.dil_lama, args.dil_moeb, args.seeds)
    out = args.out or (OUT / f"{args.stage}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    save_dir = (OUT / f"{args.stage}-img") if args.save_images else None
    print(f"stage={args.stage}  {len(runs)} 个配置 × {len(args.scenes)} 场景 "
          f"= {len(runs)*len(args.scenes)} 次补全")

    cache, rows = {}, []
    t0 = time.time()
    for sc in args.scenes:
        rows += run_scene(sc, runs, cache, with_lpips=not args.no_lpips,
                          save_dir=save_dir, batch=args.batch)
        out.write_text(json.dumps(rows, ensure_ascii=False, default=float), encoding="utf-8")
    print(f"\n总耗时 {time.time()-t0:.0f}s -> {out}")


if __name__ == "__main__":
    main()
