"""任务一 (a)：把现用 BiRefNet_lite 的未硬化原始 α 落盘，量它本来有多软。

对照对象是管线落盘的 `matte.png`（经 smoothstep 重映射 + 0.95/0.05 硬切）。
原始输出按"未裁剪原样"存 16-bit PNG，不做任何阈值。
"""
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = "E:/projects/EverythingDone"
CORPUS = os.path.join(ROOT, "tmp/spatial-desktop-tuning/corpus")
ASSETS = os.path.join(ROOT, "tmp/spatial-desktop-tuning/assets")
OUT = os.path.join(ROOT, "tmp/spatial-desktop-tuning/qa/matte-soft-probe")
MODEL = os.path.join(ROOT, "tmp/birefnet-lite/onnx/model.onnx")

os.makedirs(OUT, exist_ok=True)

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)

sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0].name


def soft_stats(alpha, lo=0.05, hi=0.95, ring_px=8):
    """返回 (全图中间值占比%, 剪影环内中间值占比%, 环内像素数)。

    环 = 二值核 (α>0.5) 的 dilate−erode，宽约 2*ring_px。硬边只有 1px 台阶，
    软边应该把环填满，所以环内占比才是有判别力的量。
    """
    core = (alpha > 0.5).astype(np.uint8)
    k = np.ones((2 * ring_px + 1, 2 * ring_px + 1), np.uint8)
    ring = (cv2.dilate(core, k) - cv2.erode(core, k)) > 0
    mid = (alpha > lo) & (alpha < hi)
    n = int(ring.sum())
    return (100.0 * mid.mean(),
            100.0 * (mid & ring).sum() / n if n else 0.0,
            n)


print("%-22s | %-28s | %-28s" % ("scene", "raw BiRefNet_lite (未硬化)", "matte.png (落盘)"))
print("%-22s | %8s %8s %7s | %8s %8s %7s"
      % ("", "全图%", "环内%", "环px", "全图%", "环内%", "环px"))
print("-" * 90)

for name in sorted(os.listdir(CORPUS)):
    if not name.endswith(".png"):
        continue
    scene = name[:-4]
    img = np.asarray(Image.open(os.path.join(CORPUS, name)).convert("RGB"))
    ref_p = os.path.join(ASSETS, scene, "matte.png")
    if not os.path.isfile(ref_p):
        continue
    ref = np.asarray(Image.open(ref_p)).astype(np.float32) / 255.0
    h, w = ref.shape[:2]

    resized = cv2.resize(img, (1024, 1024), interpolation=cv2.INTER_AREA)
    tensor = ((resized.astype(np.float32) / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None]
    logits = sess.run(None, {inp: tensor})[-1].reshape(1024, 1024)
    raw = 1.0 / (1.0 + np.exp(-logits.astype(np.float32)))

    # 未裁剪原始输出：1024² 原生分辨率，16-bit，不阈值
    Image.fromarray(np.rint(np.clip(raw, 0, 1) * 65535).astype(np.uint16), mode="I;16") \
        .save(os.path.join(OUT, scene + "_raw1024.png"))
    at_asset = np.clip(cv2.resize(raw, (w, h), interpolation=cv2.INTER_LINEAR), 0, 1)
    Image.fromarray(np.rint(at_asset * 65535).astype(np.uint16), mode="I;16") \
        .save(os.path.join(OUT, scene + "_raw_asset.png"))

    a = soft_stats(at_asset)
    b = soft_stats(ref)
    print("%-22s | %8.3f %8.2f %7d | %8.3f %8.2f %7d"
          % (scene, a[0], a[1], a[2], b[0], b[1], b[2]))
    sys.stdout.flush()

print("\n原始输出已落盘：%s" % OUT)
