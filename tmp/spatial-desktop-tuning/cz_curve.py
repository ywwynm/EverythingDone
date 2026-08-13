#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""「带宽（latent 像素）vs 补全质量」失效阈值曲线——本轮要产出的那条文献空白数据。

两幅：
- 左：横轴 = 带像素**原生**局部宽度换算的 latent 像素（图像自身的属性）；
- 右：横轴 = 该像素**在模型输入里**的 latent 宽度（放大之后的实际值，才是因果变量）。

样本不足的箱**不画点、画斜纹并标注缺多少**——缺数据必须看起来像缺数据，不能用相邻箱
连线糊过去（本项目此前吃过这个亏）。
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                                    # noqa: E402
from matplotlib import font_manager                                # noqa: E402

from cz_metrics import BIN_LABEL, BINS                             # noqa: E402

MIN_PX = 2000            # 每箱（九场景合并后）少于这么多像素就不出数
COLORS = {"lama": "#1f77b4", "moebius": "#d62728"}
STYLES = {"square": ":", "tiled": "-.", "crop": "--", "cropzoom": "-"}


def _cn_font():
    for n in ("Microsoft YaHei", "SimHei", "DengXian"):
        try:
            font_manager.findfont(n, fallback_to_default=False)
            return n
        except Exception:
            continue
    return None


def collect(rows: list[dict], field: str) -> dict:
    """{(ladder, backend): {bin: (L1, px)}}，九场景按像素数加权合并，多种子取中位。"""
    acc = defaultdict(lambda: defaultdict(list))
    for r in rows:
        for b in r[field]:
            if b["px"] > 0 and np.isfinite(b["L1"]):
                acc[(r["ladder"], r["backend"])][b["bin"]].append((b["L1"], b["px"], r["seed"]))
    out = {}
    for k, per in acc.items():
        out[k] = {}
        for b, items in per.items():
            byseed = defaultdict(list)
            for l1, px, sd in items:
                byseed[sd].append((l1, px))
            vals = [sum(l * p for l, p in v) / sum(p for _, p in v) for v in byseed.values()]
            px = sum(p for _, p in next(iter(byseed.values())))
            out[k][b] = (float(np.median(vals)), int(px))
    return out


def panel(ax, data: dict, title: str, xlabel: str) -> None:
    x = np.arange(len(BIN_LABEL))
    for (lad, back), per in sorted(data.items()):
        y = [per.get(b, (np.nan, 0))[0] if per.get(b, (0, 0))[1] >= MIN_PX else np.nan
             for b in BIN_LABEL]
        ax.plot(x, y, STYLES.get(lad, "-"), color=COLORS.get(back, "#666"),
                marker="o", ms=4, lw=1.8, label=f"{lad} · {back}")
    # 样本不足的箱画斜纹并标注
    any_px = {b: max((per.get(b, (0, 0))[1] for per in data.values()), default=0)
              for b in BIN_LABEL}
    for i, b in enumerate(BIN_LABEL):
        if any_px[b] < MIN_PX:
            ax.axvspan(i - 0.5, i + 0.5, facecolor="none", edgecolor="#999",
                       hatch="///", alpha=0.55, lw=0)
            ax.text(i, ax.get_ylim()[1], f"样本不足\n{any_px[b]}px", ha="center",
                    va="top", fontsize=7, color="#666")
    ax.set_xticks(x)
    ax.set_xticklabels(BIN_LABEL)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("带内 L1（级，越低越好）")
    ax.set_title(title, fontsize=10)
    ax.grid(alpha=0.25)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("json", type=Path)
    ap.add_argument("--out", type=Path, default=Path("qa/cz-bench/width-vs-quality.png"))
    ap.add_argument("--csv", type=Path, default=Path("qa/cz-bench/width-vs-quality.csv"))
    args = ap.parse_args()

    f = _cn_font()
    if f:
        plt.rcParams["font.sans-serif"] = [f]
        plt.rcParams["axes.unicode_minus"] = False
    rows = json.loads(args.json.read_text(encoding="utf-8"))
    nat, mod = collect(rows, "strat"), collect(rows, "stratModel")

    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=True)
    panel(axes[0], nat, "按**原生**带宽分层（图像自身的属性）", "带局部宽度 / 8 = latent 像素")
    panel(axes[1], mod, "按**模型输入里**的带宽分层（放大后的实际值）", "模型输入里的 latent 像素")
    axes[1].legend(fontsize=8, loc="upper right")
    fig.suptitle("窄带补全的失效阈值：带宽（latent 像素）vs 带内 L1"
                 "（九场景合并，扩散档跨种子取中位）", fontsize=11)
    fig.tight_layout()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, dpi=150)

    lines = ["axis,ladder,backend,bin,L1,px"]
    for name, data in (("native", nat), ("model", mod)):
        for (lad, back), per in sorted(data.items()):
            for b in BIN_LABEL:
                if b in per:
                    lines.append(f"{name},{lad},{back},{b},{per[b][0]:.4f},{per[b][1]}")
    args.csv.write_text("\n".join(lines), encoding="utf-8")
    print(f"-> {args.out}\n-> {args.csv}")


if __name__ == "__main__":
    main()
