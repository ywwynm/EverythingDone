# -*- coding: utf-8 -*-
"""在三列对照视频下方再拼一行「粒子群轮廓」对照。

对粒子掩码做高斯模糊得到局部密度，取几条等密度线画出来：低密度线勾出粒子群的
外缘，高密度线勾出浓区——两者一起才能看出「密度有大有小」和「边缘是不是曲线」。

输出 comparison-outline.mp4：上半是三列原画，下半是同尺寸的轮廓三列。
"""
import subprocess
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
CLIPS = HERE / "cloth-motion-prototype"
OUT = CLIPS / "comparison-outline.mp4"
STILL = HERE / "gap-analysis-20260830" / "17-轮廓对照.jpg"
NAMES = (("reference", "reference-normalized.mp4"),
         ("our anim / ref content", "refcontent-normalized.mp4"),
         ("desktop canonical", "model-normalized.mp4"),
         ("desktop attachment", "attachment-normalized.mp4"),
         ("device SM-S9180", "device-normalized.mp4"))
LEVELS = ((0.05, (150, 150, 150), 1), (0.16, (90, 210, 250), 1), (0.34, (60, 120, 255), 2))


def read_frames(path):
    cap = cv2.VideoCapture(str(path))
    out = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        out.append(f)
    cap.release()
    return out


def source_edges(first, width):
    """首帧里的强边缘（对话框边框、文字、图标）向外扩一圈。

    这些位置在原物消失后必然产生剧烈差异，任何基于「与首帧不同」的判据都会把
    它们画成轮廓——屏幕上就是一圈对话框的矩形边和一行行文字的框。它们只有几像素
    宽，直接按位置排除。
    """
    g = cv2.GaussianBlur(first.mean(2).astype(np.uint8), (3, 3), 0)
    e = cv2.Canny(g, 40, 110)
    k = max(3, int(round(width * 0.010)) | 1)
    return cv2.dilate(e, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))) > 0


def cloud_mask(a, first, last, released, edges):
    """粒子 = 与末帧不同、所在格子已经被吃掉过、且不在首帧强边缘上。

    几种更朴素的判据都会失败：

    - 只看颜色：对话框是白的，粒子飞到原对话框区域上方时颜色与完整表面一样，
      会被当成完整表面排除掉。
    - 只在对话框区域内判：对话框背后的遮罩覆盖整屏、消散后消失，「与末帧不同」
      在整屏都成立，轮廓会变成整块面板。
    - 用「相对首帧的局部结构」：首帧里对话框的边框与文字是强边缘，原物消失后
      那里必然剧烈变化，轮廓上会画出对话框的矩形边和一行行文字的框。
    """
    return ((np.abs(a - last).max(2) > 26) & released & ~edges).astype(np.float32)


def main():
    # 必须分别读三个已归一化的分片，不能把合成图按宽度三等分：三列的宽高比不同
    # （参考是手机屏、桌面 1280x900、真机按云的范围裁），等分的切线会落在面板
    # 内部，轮廓上于是出现整条竖直的"直边"——那是面板边界，不是云。
    clips = []
    for label, fn in NAMES:
        path = CLIPS / fn
        if not path.exists():
            print("跳过缺失的分片:", fn)
            continue
        fr = read_frames(path)
        if not fr:
            raise SystemExit("读不到 " + fn)
        clips.append(fr)
    if not clips:
        raise SystemExit("没有可用的分片")
    n = min(len(fr) for fr in clips)
    height = min(fr[0].shape[0] for fr in clips)

    def fit(img):
        h, w = img.shape[:2]
        return cv2.resize(img, (max(1, int(round(w * height / h))), height),
                          interpolation=cv2.INTER_AREA)

    prepared = [[fit(f) for f in fr[:n]] for fr in clips]
    firsts = [p[0].astype(np.float32) for p in prepared]
    lasts = [p[-1].astype(np.float32) for p in prepared]
    edges = [source_edges(f, f.shape[1]) for f in firsts]
    # 「被吃掉过」逐帧累积：一个像素一旦露出过背景（或颜色剧变），此后出现在
    # 那里的一切都是粒子。它自己的粒子刚释放时会盖住原位，因此这个标记会晚
    # 几帧才置位——可以接受，比误把粒子当成完整表面要好。
    released = [np.zeros(f.shape[:2], bool) for f in firsts]

    sheets = []
    for i in range(n):
        tops, bots = [], []
        for j, (frames, first, last) in enumerate(zip(prepared, firsts, lasts)):
            f = frames[i]
            w = f.shape[1]
            a = f.astype(np.float32)
            lum0 = first.mean(2)
            released[j] |= ((a.mean(2) < 0.55 * np.maximum(lum0, 1.0))
                            | (np.abs(a - first).max(2) > 110))
            blur = max(5, int(round(w * 0.022)) | 1)
            dens = cv2.GaussianBlur(cloud_mask(a, first, last, released[j], edges[j]),
                                    (blur, blur), 0)
            canvas = np.zeros_like(f)
            for level, colour, thick in LEVELS:
                m = cv2.morphologyEx((dens >= level).astype(np.uint8), cv2.MORPH_OPEN,
                                     cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
                cs, _ = cv2.findContours(m, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
                cs = [c for c in cs if cv2.contourArea(c) > w * w * 0.0006]
                cv2.drawContours(canvas, cs, -1, colour, thick, cv2.LINE_AA)
            tops.append(f)
            bots.append(canvas)
        sheets.append(np.vstack([np.hstack(tops), np.hstack(bots)]))

    # libx264 + yuv420p 要求宽高都是偶数；分片数变化时合成宽度可能变成奇数。
    hh, ww, _ = sheets[0].shape
    if ww % 2 or hh % 2:
        sheets = [s_[:hh - hh % 2, :ww - ww % 2] for s_ in sheets]
    hh, ww, _ = sheets[0].shape
    proc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24",
         "-s", "%dx%d" % (ww, hh), "-r", "60", "-i", "-",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
         "-movflags", "+faststart", str(OUT)],
        stdin=subprocess.PIPE)
    for sheet in sheets:
        proc.stdin.write(sheet.tobytes())
    proc.stdin.close()
    proc.wait()
    print("轮廓对照 ->", OUT)

    picks = [int(len(sheets) * r) for r in (0.30, 0.45, 0.60)]
    # cv2.imwrite 在 Windows 上写不了非 ASCII 路径，用 PIL 写。
    Image.fromarray(np.vstack([sheets[i] for i in picks])[:, :, ::-1]).save(STILL, quality=92)
    print("轮廓静帧 ->", STILL)


main()
