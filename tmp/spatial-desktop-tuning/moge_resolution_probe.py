#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""端上把长边压到 518 再送进 MoGe，桌面跑原生 720。这一步是否解释得了 fx 的 5~7% 偏差？
同一张图、同一份 ONNX、同一个 num_tokens，只改输入分辨率。"""
import sys, numpy as np
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from moge_onnx_probe import ONNX, normalized_view_plane_uv, solve_focal_shift_golden, masked_downsample
from PIL import Image
import cz_inpaint as cz

ort = cz._import_ort()
sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])

def run(img, w, h):
    o = dict(zip([x.name for x in sess.get_outputs()],
                 sess.run(None, {"image": img.transpose(2, 0, 1)[None].astype(np.float32),
                                 "num_tokens": np.array(1800, dtype=np.int64)})))
    pts, mask = np.asarray(o["points"])[0], np.asarray(o["mask"])[0] > 0.5
    scale = float(np.asarray(o["scale"]).reshape(-1)[0])
    uv = normalized_view_plane_uv(w, h)
    p, u = masked_downsample(pts, uv, mask)
    focal, shift = solve_focal_shift_golden(u, p[:, :2], p[:, 2])
    fx = focal * ((w ** 2 + h ** 2) ** 0.5) / 2.0
    z = (pts[..., 2] + shift) * scale
    zv = z[mask & np.isfinite(z)]
    return fx, np.percentile(zv, [0, 50, 100])

for scene, dev_fx, dev_w in [("00_original_single", 484.0, 378), ("08_person_pet", 679.4, 518)]:
    im = Image.open(f"tmp/spatial-desktop-tuning/assets/{scene}/center.jpg").convert("RGB")
    W, H = im.size
    print(f"\n=== {scene}  原生 {W}x{H} ===")
    for tag, size in [("原生", (W, H)), ("端上口径", None)]:
        if size is None:
            # 复刻端上：长边缩到 <=518，两边向下对齐到 14 的倍数
            s = 518.0 / max(W, H)
            tw, th = int(W * s) // 14 * 14, int(H * s) // 14 * 14
        else:
            tw, th = size
        a = np.asarray(im.resize((tw, th), Image.BILINEAR)).astype(np.float32) / 255.0
        fx, z = run(a, tw, th)
        print(f"  {tag:<8} {tw}x{th}  fx={fx:7.1f}  fx/w={fx/tw:.4f}  "
              f"Z(min/中位/max)={z[0]:.3f}/{z[1]:.3f}/{z[2]:.3f} m")
    print(f"  端上实测           fx={dev_fx:7.1f}  fx/w={dev_fx/dev_w:.4f}")
