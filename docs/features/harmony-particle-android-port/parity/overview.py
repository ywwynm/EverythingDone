"""把一次 drift_parity.py 运行的各组对照拼成一张总览：每组取四个设备时刻，Android 与桌面左右并排，裁到颗粒飞过的范围。

用法：python -X utf8 overview.py --run p2
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_parity as dp  # noqa: E402

PICKS = (24, 54, 84, 120)          # 设备帧
LABELS = {'ironman-ref': '旧钢铁侠参考输入（加光）', 'ref5-over': '参考 5 输入（浅色背景，标准 src-over）',
          'dialog-light': '浅色弹窗：随机格点 + 内容强调（src-over）', 'dialog-dark': '深色弹窗：随机格点 + 内容强调（加光）',
          'ironman-style-dialog': '旧钢铁侠样式用在弹窗上：湍流换算 + 风转到 150°'}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    a = p.parse_args()
    root = dp.OUT / a.run
    rows = []
    for name in LABELS:
        folder = root / name
        if not (folder / 'compare.json').exists():
            continue
        data = json.loads((folder / 'compare.json').read_text('utf-8'))
        frames = data['result']['frames']
        idx = [dp.FRAMES.index(k) for k in PICKS]
        pairs = [(np.asarray(Image.open(folder / 'device' / frames[i]).convert('RGB')),
                  np.asarray(Image.open(folder / 'desktop' / ('frame-%03d.png' % i)).convert('RGB'))) for i in idx]
        bg = pairs[0][0][0, 0].astype(int)
        mask = np.zeros(pairs[0][0].shape[:2], bool)
        for an, _ in pairs:
            mask |= np.abs(an.astype(int) - bg).max(axis=2) > 6
        ys, xs = np.nonzero(mask)
        box = (max(xs.min() - 20, 0), max(ys.min() - 20, 0), min(xs.max() + 20, mask.shape[1]), min(ys.max() + 20, mask.shape[0]))
        worst = max(data['rows'], key=lambda r: r['p999'])
        stats = '27 帧中最差一帧：p99.9 = %d/255，最大 %d/255，差 > 8 的像素占 %.5f%%' % (
            worst['p999'], max(r['max'] for r in data['rows']), 100 * max(r['over8'] for r in data['rows']))
        rows.append((LABELS[name], stats, box, pairs))
    cell = 230
    gap = 14
    out_w = 20 + len(PICKS) * (cell * 2 + 6 + gap)
    heights = []
    for _, _, box, _ in rows:
        heights.append(round(cell * (box[3] - box[1]) / (box[2] - box[0])))
    out = Image.new('RGB', (out_w, 70 + sum(h + 74 for h in heights)), (250, 250, 250))
    d = ImageDraw.Draw(out)
    d.text((14, 10), '同一输入、同一设备时刻：每格左 = Android（R5CW20BLNKL 离屏出帧），右 = 桌面 harmony_native', fill=(20, 20, 20), font=dp.font(24))
    for c, k in enumerate(PICKS):
        d.text((20 + c * (cell * 2 + 6 + gap) + cell - 40, 44), 'T = %.3f s' % (k / dp.HZ), fill=(70, 70, 70), font=dp.font(18))
    y = 70
    for (label, stats, box, pairs), h in zip(rows, heights):
        d.text((14, y + 4), label, fill=(20, 20, 20), font=dp.font(20))
        d.text((14, y + 32), stats, fill=(90, 90, 90), font=dp.font(16))
        y += 60
        for c, (an, de) in enumerate(pairs):
            x = 20 + c * (cell * 2 + 6 + gap)
            for j, im in enumerate((an, de)):
                tile = Image.fromarray(im).crop(box).resize((cell, h), Image.LANCZOS)
                out.paste(tile, (x + j * (cell + 6), y))
        y += h + 14
    target = root / 'overview.png'
    out.save(target)
    print(target, out.size)


if __name__ == '__main__':
    main()
