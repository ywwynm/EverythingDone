# -*- coding: utf-8 -*-
"""逐帧、逐像素地把我们的动画与参考对齐比较。

不是抽几帧看看：参考在窗口 [T0, T1] 内的**每一帧**都参与，我们的着色器按同一批
归一化时刻逐帧重渲。两边的卡片内容、背景、位置完全相同（frames-refcontent 就是
把参考首帧的卡片区域当快照、参考末帧当背景），因此逐像素相减是有意义的。

输出三样东西：

1. `perframe.csv`  逐帧的差异分解，供定位「哪一段、哪一类差得最多」。
2. `worst-*.jpg`   差得最多的若干帧的三联图（参考 / 我们 / 差异），供**目视**判断
                   差异是什么性质——数值只用来挑帧，不用来下结论。
3. `profile.jpg`   沿飞行轴的一维剖面随时间的演化图（参考与我们叠在一起），用来看
                   前沿位置、粒子带宽度、密度衰减这三件事的整体偏差。

差异按三层分解，避免「一个总数」掩盖掉性质完全不同的偏差：

- **表面层**：与首帧一致的像素 = 尚未被吃掉的完整表面。两边的这块面积与形状差多少。
- **粒子层**：既不同于首帧、也不同于背景的像素 = 正在飞的粒子。
- **亮度**：只在两边都判为粒子的地方比亮度，避免把「有没有粒子」的差异算成「亮度」。
"""
import csv
import glob
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
SP = Path(r"C:\Users\ywwynm\AppData\Local\Temp\claude"
          r"\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad")
OUT = HERE / "perframe"
CARD = (45, 95, 525, 567)
# 我们的画面里卡片不在同一个位置：CurtainRenderer 把快照居中，580x660 的视图里
# 落在 (50, 94)。用参考的框去裁我们的图会差 5 像素——逐像素比较下这点偏移会把
# 差异全部堆到轮廓上，首帧的 MAE 就有 20（实际两张图肉眼完全一致）。
OURS_CARD = (50, 94, 50 + (CARD[2] - CARD[0]), 94 + (CARD[3] - CARD[1]))
T0, T1 = 3.28, 8.38
TOL = 26.0          # 与首帧/背景的差异阈值，与既有分析脚本一致


def reference_frames():
    files = sorted(glob.glob(str(SP / "anim2" / "a_*.png")))
    if not files:
        raise SystemExit("找不到参考帧序列")
    out = []
    for f in files:
        t = int(os.path.basename(f)[2:7]) / 60.0
        if T0 - 1e-6 <= t <= T1 + 1e-6:
            out.append(((t - T0) / (T1 - T0), f))
    return out


def render_ours(times):
    """按参考的归一化时刻逐帧重渲我们的动画。"""
    sys.path.insert(0, str(HERE))
    import render_curtain_model as R

    R.VIEW_W, R.VIEW_H = 580, 660
    R.SNAP_W, R.SNAP_H = CARD[2] - CARD[0], CARD[3] - CARD[1]
    src = (HERE / "render_reference_content_scene.py").read_text(encoding="utf-8")
    src = src.replace("main()\n", "")
    ns = {"__file__": str(HERE / "render_reference_content_scene.py")}
    exec(compile(src, "refcontent", "exec"), ns)
    renderer = R.CurtainRenderer()
    renderer.configure(R.Scenario("refcontent", 242.0, 42))
    for n in times:
        yield np.asarray(renderer.composite(n).convert("RGB"), np.float32)


def masks(frame, source, background):
    intact = np.abs(frame - source).max(2) <= TOL
    empty = np.abs(frame - background).max(2) <= TOL
    return intact, (~intact) & (~empty)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ref = reference_frames()
    times = [n for n, _ in ref]
    print("参考窗口内 %d 帧，归一化 %.4f - %.4f" % (len(ref), times[0], times[-1]))

    x0, y0, x1, y1 = CARD
    ox0, oy0, ox1, oy1 = OURS_CARD
    ref_src = np.asarray(Image.open(ref[0][1]).convert("RGB"), np.float32)
    ref_bg = np.asarray(Image.open(SP / "bg.png").convert("RGB"), np.float32)

    ours_iter = render_ours(times)
    first_ours = None
    rows = []
    keep = []           # (帧序, 归一化时刻, 参考路径, 我们的帧) 供后面挑最差的
    for k, ((n, path), ours) in enumerate(zip(ref, ours_iter)):
        rf = np.asarray(Image.open(path).convert("RGB"), np.float32)
        if first_ours is None:
            first_ours = ours.copy()
            our_bg = None
        rc = rf[y0:y1, x0:x1]
        oc = ours[oy0:oy1, ox0:ox1]
        ri, rp = masks(rc, ref_src[y0:y1, x0:x1], ref_bg[y0:y1, x0:x1])
        oi, op = masks(oc, first_ours[oy0:oy1, ox0:ox1], ref_bg[y0:y1, x0:x1])
        both = rp & op
        lum_r = rc.mean(2)
        lum_o = oc.mean(2)
        rows.append({
            "frame": k,
            "n": round(n, 5),
            "ref_intact": round(float(ri.mean()), 5),
            "our_intact": round(float(oi.mean()), 5),
            "intact_iou": round(float((ri & oi).sum() / max((ri | oi).sum(), 1)), 5),
            "ref_particle": round(float(rp.mean()), 5),
            "our_particle": round(float(op.mean()), 5),
            "particle_iou": round(float((rp & op).sum() / max((rp | op).sum(), 1)), 5),
            "lum_bias": round(float((lum_o[both] - lum_r[both]).mean())
                              if both.sum() > 200 else float("nan"), 3),
            "rgb_mae": round(float(np.abs(oc - rc).mean()), 3),
        })
        keep.append((k, n, path, ours))

    with open(OUT / "perframe.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("逐帧表 ->", OUT / "perframe.csv")

    # 差得最多的帧：按表面面积差与粒子面积差各挑几帧，再加整体 MAE 最大的几帧。
    def worst(key, count=3, reverse=True):
        order = sorted(range(len(rows)),
                       key=lambda i: rows[i][key] if rows[i][key] == rows[i][key] else -1,
                       reverse=reverse)
        return order[:count]

    picks = []
    for key in ("rgb_mae",):
        picks += worst(key, 4)
    picks += worst("intact_iou", 3, reverse=False)
    picks += worst("particle_iou", 3, reverse=False)
    seen, ordered = set(), []
    for i in picks:
        if i not in seen:
            seen.add(i)
            ordered.append(i)

    W, H = x1 - x0, y1 - y0
    for i in ordered[:10]:
        k, n, path, ours = keep[i]
        rf = np.asarray(Image.open(path).convert("RGB"), np.uint8)
        diff = np.abs(ours[oy0:oy1, ox0:ox1]
                      - np.asarray(Image.open(path).convert("RGB"),
                                   np.float32)[y0:y1, x0:x1])
        d = np.clip(diff.max(2) / 90.0, 0, 1)
        heat = np.zeros((H, W, 3), np.float32)
        heat[..., 0] = d * 255
        heat[..., 1] = d * d * 120
        sheet = Image.new("RGB", (W * 3 + 16, H), (20, 20, 20))
        sheet.paste(Image.fromarray(rf).crop(CARD), (0, 0))
        sheet.paste(Image.fromarray(ours.astype(np.uint8)).crop(OURS_CARD), (W + 8, 0))
        sheet.paste(Image.fromarray(heat.astype(np.uint8)), (2 * W + 16, 0))
        sheet.save(OUT / ("worst-%03d-n%.3f.jpg" % (k, n)), quality=92)
    print("最差帧三联图 -> %s（%d 张）" % (OUT, len(ordered[:10])))

    for r in rows[::max(len(rows) // 24, 1)]:
        print("  n=%.3f 表面 参考%.3f/我们%.3f IoU%.3f  粒子 参考%.3f/我们%.3f "
              "IoU%.3f  亮度偏差%+.1f  MAE%.1f"
              % (r["n"], r["ref_intact"], r["our_intact"], r["intact_iou"],
                 r["ref_particle"], r["our_particle"], r["particle_iou"],
                 r["lum_bias"], r["rgb_mae"]))


main()
