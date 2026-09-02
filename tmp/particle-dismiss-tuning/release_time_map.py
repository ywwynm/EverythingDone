# -*- coding: utf-8 -*-
"""把「每个像素在什么时刻被吃掉」画成一张图，参考与我们并排。

逐帧的面积曲线只能告诉我「某一刻总量差多少」，说不出**差在哪块材料上**。释放时刻
场把整个动画压成一张图：每个像素取它第一次不再与首帧一致的那一刻。两张场一并排，
「哪里先化、哪里最后化、等值线什么形状」一目了然，差异图还能直接指出是哪一块提前
或推后。

判据用最朴素的：与首帧的差 > TOL 的第一帧。两边内容、背景、位置完全相同，因此
同一个阈值对两边是同一件事。
"""
import glob
import os
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SP = Path(r"C:\Users\ywwynm\AppData\Local\Temp\claude"
          r"\E--projects-EverythingDone\ac79636d-7f99-4a3d-b44d-e3ce2391afd0\scratchpad")
OUT = HERE / "perframe"
CARD = (45, 95, 525, 567)
OURS_CARD = (50, 94, 50 + (CARD[2] - CARD[0]), 94 + (CARD[3] - CARD[1]))
T0, T1 = 3.28, 8.38
# 判据不能是「与源色差 > 阈值」。参考的粒子继承源色，亮区的粒子与亮区的源色差不到
# 阈值，于是亮区一律被判成「还没化」——残差图上正好是一张脸的形状，看上去像是场
# 本身让脸最后消失，其实是判据被内容带偏了。
#
# 改用**差值图的局部标准差**：完整表面与源逐位相同，差值处处为 0；一旦粒子化，
# 差值是散斑，局部 sd 立刻抬起来。亮区暗区一视同仁。
#
# 参考是压缩过的录屏，动画开始前的帧局部 sd 已有 1% 的像素超过 19，单帧阈值必然
# 误判；要求**连续 SD_RUN 帧都超阈**才算，孤立的压缩闪烁就被滤掉了。
SD_WINDOW = 5
SD_THRESHOLD = 22.0
SD_RUN = 4
# 局部 sd 只认散斑。一块材料若**干净地**变成背景（周围没有粒子飞过），差值图在那里
# 是一片平滑的 (bg - src)，局部 sd 反而很低，会被判成「从未化掉」——我们的覆盖率
# 只有 0.777、参考 0.911，差的正是这一类区域。补一条绝对判据取并集。
ABS_THRESHOLD = 45.0


def reference_frames():
    files = sorted(glob.glob(str(SP / "anim2" / "a_*.png")))
    out = []
    for f in files:
        t = int(os.path.basename(f)[2:7]) / 60.0
        if T0 - 1e-6 <= t <= T1 + 1e-6:
            out.append(((t - T0) / (T1 - T0), f))
    return out


def ours_frames(times):
    sys.path.insert(0, str(HERE))
    import render_curtain_model as R

    R.VIEW_W, R.VIEW_H = 580, 660
    R.SNAP_W, R.SNAP_H = CARD[2] - CARD[0], CARD[3] - CARD[1]
    src = (HERE / "render_reference_content_scene.py").read_text(encoding="utf-8")
    ns = {"__file__": str(HERE / "render_reference_content_scene.py")}
    exec(compile(src.replace("main()\n", ""), "refcontent", "exec"), ns)
    renderer = R.CurtainRenderer()
    # 场景参数必须与 render_reference_content_scene.py 保持一致，否则这里量的
    # 是另一套配置。角度按实测对齐参考的扫描方向。
    renderer.configure(R.Scenario("refcontent", ns["SCENARIO_ANGLE"], ns.get("SCENARIO_SEED", 42)))
    for n in times:
        yield np.asarray(renderer.composite(n).convert("RGB"), np.float32)


def local_sd(diff):
    m = cv2.blur(diff, (SD_WINDOW, SD_WINDOW))
    m2 = cv2.blur(diff * diff, (SD_WINDOW, SD_WINDOW))
    return np.sqrt(np.maximum(m2 - m * m, 0.0))


def release_map(frames, source, box):
    """每个像素第一次「连续 SD_RUN 帧局部 sd 超阈」的那一刻。"""
    x0, y0, x1, y1 = box
    h, w = y1 - y0, x1 - x0
    out = np.full((h, w), np.nan, np.float32)
    run = np.zeros((h, w), np.int16)
    start = np.zeros((h, w), np.float32)
    src_rgb = source[y0:y1, x0:x1]
    src = src_rgb.mean(2)
    for n, frame in frames:
        cur = frame[y0:y1, x0:x1]
        hot = (local_sd(cur.mean(2) - src) > SD_THRESHOLD) | (
            np.abs(cur - src_rgb).max(2) > ABS_THRESHOLD)
        start = np.where((run == 0) & hot, np.float32(n), start)
        run = np.where(hot, run + 1, 0)
        done = (run >= SD_RUN) & np.isnan(out)
        out[done] = start[done]
    return out


def colorize(field, label):
    """时刻 -> 颜色。未被吃掉的画成深灰，其余按时刻走一条冷到暖的色带。"""
    h, w = field.shape
    img = np.zeros((h, w, 3), np.float32)
    v = np.clip(np.nan_to_num(field, nan=1.05), 0.0, 1.05)
    known = ~np.isnan(field)
    t = v
    img[..., 0] = np.clip(1.6 * t - 0.25, 0, 1) * 255
    img[..., 1] = np.clip(1.5 - 1.9 * np.abs(t - 0.5) * 2.0, 0, 1) * 210
    img[..., 2] = np.clip(1.25 - 1.9 * t, 0, 1) * 255
    img[~known] = np.array([46, 46, 52], np.float32)
    out = Image.fromarray(img.astype(np.uint8))
    d = ImageDraw.Draw(out)
    d.rectangle((0, 0, 150, 20), fill=(0, 0, 0))
    d.text((6, 5), label, fill=(235, 235, 240))
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ref = reference_frames()
    times = [n for n, _ in ref]
    ref_src = np.asarray(Image.open(ref[0][1]).convert("RGB"), np.float32)

    rf = release_map(
        ((n, np.asarray(Image.open(p).convert("RGB"), np.float32)) for n, p in ref),
        ref_src, CARD)

    ours = list(ours_frames(times))
    of = release_map(zip(times, ours), ours[0], OURS_CARD)

    both = ~np.isnan(rf) & ~np.isnan(of)
    delta = np.where(both, of - rf, np.nan)

    h, w = rf.shape
    sheet = Image.new("RGB", (w * 3 + 16, h), (18, 18, 20))
    sheet.paste(colorize(rf, "reference"), (0, 0))
    sheet.paste(colorize(of, "ours"), (w + 8, 0))

    dm = np.zeros((h, w, 3), np.float32)
    dv = np.nan_to_num(delta, nan=0.0)
    dm[..., 0] = np.clip(dv / 0.25, 0, 1) * 255          # 我们更晚 = 红
    dm[..., 2] = np.clip(-dv / 0.25, 0, 1) * 255         # 我们更早 = 蓝
    dm[~both] = np.array([46, 46, 52], np.float32)
    dimg = Image.fromarray(dm.astype(np.uint8))
    d = ImageDraw.Draw(dimg)
    d.rectangle((0, 0, 260, 20), fill=(0, 0, 0))
    d.text((6, 5), "ours - reference  (red=later, blue=earlier)", fill=(235, 235, 240))
    sheet.paste(dimg, (2 * w + 16, 0))
    path = OUT / "release-map.jpg"
    sheet.save(path, quality=94)
    print("释放时刻场 ->", path)

    # 沿飞行轴与横风轴各取一条剖面，说明偏差是「整体快慢」还是「形状不同」
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    for name, axis in (("沿对角", (xx / w + yy / h) / 2.0),
                       ("横对角", (xx / w - yy / h + 1.0) / 2.0)):
        bins = np.clip((axis * 12).astype(int), 0, 11)
        line = []
        for b in range(12):
            m = (bins == b) & both
            if m.sum() > 300:
                line.append("%+.3f" % float(np.median(delta[m])))
            else:
                line.append("  .   ")
        print("  %s 12 段的时刻中位差(我们-参考)：%s" % (name, " ".join(line)))


if __name__ == "__main__":
    main()
