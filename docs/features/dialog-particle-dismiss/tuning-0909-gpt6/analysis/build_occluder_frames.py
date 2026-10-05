"""手持屏摄里入镜的手指（会移动、在颗粒之上）：逐帧按肤色从原片抠出，作为逐帧遮挡层（只用于参考复刻）。

判据：肤色（HSV 色相 0～25 或 165～180、饱和度 0.15～0.65、亮度 > 0.35）、与同一时刻不画颗粒的模型画面相差明显、
落在给定的搜索框内；形态学清理后只留与搜索框左缘或下缘相连的连通块（手指从画面边缘伸进来）。
产物：assets/<段>/occluder-frames.json（每帧：参考进度、补丁文件、外接框）与 assets/<段>/occluder/<序号>.png（RGBA）。

用法：python analysis/build_occluder_frames.py <段名> <进度上限> <x0,y0,x1,y1 搜索框>
例：python analysis/build_occluder_frames.py reference-0926-4 0.55 0,420,420,1280
"""
from pathlib import Path
import json
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_native import NativeRenderer, LIVE_RASTER, inputs_from_entry
from match_compare import Comparison


def skin_mask(rgb):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV).astype(np.float32)
    h, s, v = hsv[..., 0] * 2, hsv[..., 1] / 255, hsv[..., 2] / 255   # 色相换成 0～360
    hue = (h <= 50) | (h >= 330)
    return hue & (s >= 0.15) & (s <= 0.65) & (v >= 0.35)


def main():
    name, p_hi = sys.argv[1], float(sys.argv[2])
    bx0, by0, bx1, by1 = (int(v) for v in sys.argv[3].split(','))
    table = json.loads((ROOT / 'native_reference_inputs.json').read_text('utf-8'))
    c = Comparison(name)
    r = NativeRenderer(name, inputs_from_entry(table[name]), raster=LIVE_RASTER, ss=1)
    r.occluders = []                     # 抠像时不叠已有的遮挡层
    d = ROOT / 'assets' / name
    od = d / 'occluder'; od.mkdir(exist_ok=True)
    for f in od.glob('*.png'):
        f.unlink()
    entries = []
    prog = c.ref.progress
    for i in range(len(prog)):
        p = float(prog[i])
        if p > p_hi:
            continue
        a = np.asarray(c.ref.frames[i])
        bare = r.render_time(r.device_time(p), particles=False)
        diff = np.abs(a.astype(np.int16) - bare.astype(np.int16)).max(2) > 35
        m = np.zeros(a.shape[:2], bool)
        m[by0:by1, bx0:bx1] = (skin_mask(a) & diff)[by0:by1, bx0:bx1]
        m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
        m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
        n, lab, st, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
        keep = np.zeros_like(m, bool)
        for k in range(1, n):
            x, y, w, h, area = st[k]
            touches = x <= bx0 + 2 or y + h >= by1 - 2
            if area >= 1500 and touches:
                keep |= lab == k
        if keep.sum() < 1500:
            continue
        # 边缘羽化 2 像素
        alpha = cv2.GaussianBlur(keep.astype(np.float32), (0, 0), 1.2)
        alpha = np.clip(alpha * 1.6, 0, 1)
        ys, xs = np.nonzero(alpha > 0.02)
        y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
        patch = np.dstack([a[y0:y1, x0:x1], (alpha[y0:y1, x0:x1] * 255).astype(np.uint8)])
        fn = f'occluder/{i:04d}.png'
        Image.fromarray(patch, 'RGBA').save(d / fn)
        entries.append(dict(progress=p, file=fn, box=[int(x0), int(y0), int(x1), int(y1)]))
    r.close()
    # 相邻原片帧的进度间隔的一半作为匹配容差
    steps = np.diff(np.sort(prog))
    tol = float(np.median(steps[steps > 0]) * 0.5) if len(steps) else 0.005
    (d / 'occluder-frames.json').write_text(json.dumps(dict(tolerance=tol, frames=entries), ensure_ascii=False, indent=1), 'utf-8')
    print(name, '遮挡帧', len(entries), '容差', round(tol, 4))


if __name__ == '__main__':
    main()
