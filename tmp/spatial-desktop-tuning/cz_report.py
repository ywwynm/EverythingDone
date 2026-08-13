#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 `cz_bench.py` 的 JSON 汇成报告表。

两条纪律（D194 教训）：
- **九场景并列**，不得只报被治的场景；
- 扩散档跨种子取**中位数并报 IQR**，回归档只有一次运行，直接报。
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

SC = ["00", "01", "02", "03", "04", "05", "06", "07", "08"]


def agg(vals: list[float]) -> tuple[float, float]:
    v = np.array([x for x in vals if np.isfinite(x)], np.float64)
    if v.size == 0:
        return float("nan"), float("nan")
    return float(np.median(v)), float(np.percentile(v, 75) - np.percentile(v, 25))


def group(rows: list[dict], keys: tuple[str, ...]) -> dict:
    """按 keys 聚合成 {配置: {场景: [各种子的值]}}。"""
    out = defaultdict(lambda: defaultdict(list))
    for r in rows:
        k = tuple(r[x] for x in keys)
        out[k][r["scene"][:2]].append(r)
    return out


def table(rows: list[dict], keys: tuple[str, ...], metric: str, *, lower_better: bool,
          title: str) -> None:
    g = group(rows, keys)
    print(f"\n## {title}（{metric}，{'越低越好' if lower_better else '越高越好'}）\n")
    print("| 配置 | " + " | ".join(SC) + " | 九场景中位 |")
    print("|---" * (len(SC) + 2) + "|")
    lines = []
    for k, per in sorted(g.items(), key=lambda kv: str(kv[0])):
        cells, alls = [], []
        for s in SC:
            rs = per.get(s, [])
            if not rs:
                cells.append("—")
                continue
            m, iqr = agg([r[metric] for r in rs])
            alls.append(m)
            cells.append(f"{m:.2f}" if len(rs) == 1 else f"{m:.2f}±{iqr/2:.2f}")
        ov = np.median([x for x in alls if np.isfinite(x)]) if alls else float("nan")
        lines.append((ov, "/".join(str(x) for x in k), cells))
    for ov, name, cells in sorted(lines, key=lambda t: t[0] if lower_better else -t[0]):
        print(f"| {name} | " + " | ".join(cells) + f" | **{ov:.2f}** |")


def strat_table(rows: list[dict], keys: tuple[str, ...], field: str, title: str) -> None:
    """带宽分层：把九场景同一箱的像素合并（按像素数加权），报 L1。"""
    g = group(rows, keys)
    from cz_metrics import BIN_LABEL
    print(f"\n## {title}（分层 L1，越低越好；括号内为该箱像素数）\n")
    print("| 配置 | " + " | ".join(BIN_LABEL) + " |")
    print("|---" * (len(BIN_LABEL) + 1) + "|")
    for k, per in sorted(g.items(), key=lambda kv: str(kv[0])):
        acc = {b: [0.0, 0.0] for b in BIN_LABEL}
        for rs in per.values():
            for r in rs:
                for row in r[field]:
                    if np.isfinite(row["L1"]) and row["px"] > 0:
                        acc[row["bin"]][0] += row["L1"] * row["px"]
                        acc[row["bin"]][1] += row["px"]
        cells = []
        for b in BIN_LABEL:
            s, n = acc[b]
            cells.append(f"{s/n:.2f} ({int(n/max(1,len(next(iter(per.values()))))):,})"
                         if n > 0 else "—")
        print(f"| {'/'.join(str(x) for x in k)} | " + " | ".join(cells) + " |")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("json", type=Path)
    ap.add_argument("--keys", nargs="*", default=None)
    ap.add_argument("--metrics", nargs="*",
                    default=["L1", "PSNRdb", "gradCorr", "LPIPS"])
    ap.add_argument("--strat", action="store_true")
    args = ap.parse_args()

    rows = json.loads(args.json.read_text(encoding="utf-8"))
    keys = tuple(args.keys) if args.keys else ("ladder", "backend")
    lower = {"L1", "LPIPS", "L1strict"}
    for m in args.metrics:
        if m not in rows[0]:
            continue
        table(rows, keys, m, lower_better=m in lower, title=f"{'/'.join(keys)} × {m}")
    # 窗几何与"放大到底生效没有"的核实数据。
    # **这里必须用 `bandLatModel`**（逐像素实际生效放大倍率算出来的），不能用
    # `windowTable.lat50BandMedian`——后者是裁窗生成器给出的**理论** zoom，`crop` 档
    # 根本没有执行那个放大（实际 zoom=1），照搬会把档 1 报成和档 2 一样宽。
    print("\n## 带在模型输入里的实际宽度（判读规则要核的那个数）\n")
    print("| 配置 | 场景 | 窗数 | 原生 latent px | **实际**模型输入 latent px | 覆盖 |")
    print("|---|---|---|---|---|---|")
    seen = set()
    for r in rows:
        k = (r["ladder"], r["backend"], r.get("dilate"), r["scene"][:2])
        if k in seen:
            continue
        seen.add(k)
        print(f"| {r['ladder']}/{r['backend']}/d{r.get('dilate')} | {r['scene'][:2]} | "
              f"{r.get('windows','—')} | {r.get('bandLatNative', float('nan')):.2f} | "
              f"**{r.get('bandLatModel', float('nan')):.2f}** | "
              f"{100*(1-r.get('uncoveredBandFrac',0)):.1f}% |")
    if args.strat:
        strat_table(rows, keys, "strat", "按**原生**带宽分层")
        strat_table(rows, keys, "stratModel", "按**模型输入里**带宽分层")


if __name__ == "__main__":
    main()
