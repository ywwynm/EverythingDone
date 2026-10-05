"""逐帧分析一轮对话框预览（消散 → 出现）：本体覆盖率、窗外颗粒、相邻帧跳变。

用法：python analyze_round.py <录屏.mp4> <对话框 bounds x1,y1,x2,y2（设备像素）> [--sheet 输出.png]
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_record as r  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument('video')
    p.add_argument('box')
    p.add_argument('--margin', type=int, default=360)
    p.add_argument('--sheet')
    p.add_argument('--from-t', type=float, default=0)
    p.add_argument('--to-t', type=float, default=99)
    p.add_argument('--count', type=int, default=24)
    a = p.parse_args()
    x1, y1, x2, y2 = map(int, a.box.split(','))
    video = Path(a.video)
    items = r.frames(video, video.with_suffix('') .parent / (video.stem + '-full'), scale_width=1440)
    imgs = [(t, np.asarray(Image.open(f).convert('RGB'), dtype=np.int16)) for t, f in items]
    h, w = imgs[0][1].shape[:2]
    m = a.margin
    ox1, oy1, ox2, oy2 = max(0, x1 - m), max(0, y1 - m), min(w, x2 + m), min(h, y2 + m)
    # 底色取页面左上角一块里出现最多的颜色。
    patch = imgs[0][1][oy1:oy1 + 40, 0:200].reshape(-1, 3)
    colors, counts = np.unique(patch, axis=0, return_counts=True)
    page = colors[counts.argmax()]
    print('page color', page, 'share %.2f' % (counts.max() / len(patch)))
    rows = []
    prev = None
    for t, img in imgs:
        body = img[y1:y2, x1:x2]
        outer = img[oy1:oy2, ox1:ox2].copy()
        outer[y1 - oy1:y2 - oy1, x1 - ox1:x2 - ox1] = page
        dev = np.abs(outer - page).max(axis=2)
        outside = float((dev > 12).mean())
        white = float((np.abs(body - 255).max(axis=2) <= 6).mean())
        jump = float(np.abs(img[oy1:oy2, ox1:ox2] - prev).mean()) if prev is not None else 0.0
        prev = img[oy1:oy2, ox1:ox2]
        rows.append((t, white, outside, jump))
    for t, white, outside, jump in rows:
        if a.from_t <= t <= a.to_t:
            print('t=%.3f  本体白占比=%.3f  窗外颗粒=%.4f  相邻帧差=%.2f' % (t, white, outside, jump))
    if a.sheet:
        sel = [(t, f) for (t, f) in items if a.from_t <= t <= a.to_t]
        idx = np.linspace(0, len(sel) - 1, min(a.count, len(sel))).round().astype(int)
        r.contact_sheet([('t=%.3f' % sel[i][0], sel[i][1]) for i in idx], a.sheet, columns=6,
                        crop=(ox1, oy1, ox2, oy2))


if __name__ == '__main__':
    main()
