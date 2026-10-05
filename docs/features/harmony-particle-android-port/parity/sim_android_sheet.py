"""桌面模拟器「Android 移植口径」的冒烟检查：几个自有弹窗 × 方向 × 种子，各取几个进度出联系表，并打印每次选到的输入。

用法（conda 环境 everythingdone）：python -X utf8 sim_android_sheet.py --out <png>
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_parity as dp  # noqa: E402
import android_drift as ad  # noqa: E402
import desktop_inputs as di  # noqa: E402

CASES = [('attachment', 11, 135.0), ('attachment', 29, 60.0), ('language', 5, 135.0), ('color', 8, 135.0),
         ('attachment-image', 3, 100.0)]
PROGRESS = (0.0, 0.2, 0.4, 0.6, 0.8)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    a = p.parse_args()
    import moderngl
    ctx = moderngl.create_standalone_context(require=430)
    rows = []
    for scene, seed, direction in CASES:
        meta = di.META[scene]
        r = ad.build_android_renderer(scene, meta, seed, direction, ctx=ctx, ss=1)
        print(scene, seed, direction, '→', r.describe()['source'], flush=True)
        frames = [r.render(q) for q in PROGRESS]
        r.close()
        rows.append((f'{scene} 种子 {seed} 方向 {direction:.0f}°', frames))
    cell_w = 260
    h0, w0 = rows[0][1][0].shape[:2]
    cell_h = round(cell_w * h0 / w0)
    out = Image.new('RGB', (cell_w * len(PROGRESS), (cell_h + 30) * len(rows)), (250, 250, 250))
    d = ImageDraw.Draw(out)
    for i, (label, frames) in enumerate(rows):
        y = i * (cell_h + 30)
        d.text((6, y + 6), label, fill=(20, 20, 20), font=dp.font(18))
        for k, f in enumerate(frames):
            im = Image.fromarray(f).resize((cell_w, round(cell_w * f.shape[0] / f.shape[1])), Image.LANCZOS)
            out.paste(im, (k * cell_w, y + 30))
    out.save(a.out)
    print(a.out)


if __name__ == '__main__':
    main()
