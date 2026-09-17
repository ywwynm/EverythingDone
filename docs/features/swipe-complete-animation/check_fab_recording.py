"""检查虚构页面的最终合成录屏；不能以 Window PixelCopy 代替此遮挡回归。"""
import argparse
import json
import subprocess
from pathlib import Path

import numpy as np

p = argparse.ArgumentParser()
p.add_argument('run', type=Path, help='包含 ready.json、result.json、capture.mp4 的目录')
p.add_argument('--motion', action='store_true')
p.add_argument('--ffmpeg', default='C:/ffmpeg/bin/ffmpeg.exe')
a = p.parse_args()
ready = json.loads((a.run / 'ready.json').read_text())
result = json.loads((a.run / 'result.json').read_text())
fab = ready['fab']
x, y, w, h = (round(fab[k]) for k in ('x', 'y', 'width', 'height'))
if a.motion:
    top = max(0, y - round(ready['density'] * 72))
    crop = (x, top, w, y + h - top)
else:
    crop = (x, y, w, h)
cx, cy, cw, ch = crop
decoded = subprocess.run([
    a.ffmpeg, '-v', 'error', '-i', str(a.run / 'capture.mp4'),
    '-vf', f'crop={cw}:{ch}:{cx}:{cy}', '-fps_mode', 'passthrough',
    '-enc_time_base', '1:90000', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'
], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
frames = np.frombuffer(decoded, dtype=np.uint8).reshape(-1, ch, cw, 3).astype(np.int16)
assert len(frames) > 20, '缺少覆盖动画的录屏帧'
if result.get('fabFrames'):
    start, end = result['fabFrames'][0], result['fabFrames'][-1]
    assert np.allclose(start[1:], end[1:], atol=.01), 'FAB 的位置、缩放或透明度未恢复'
report = {'run': a.run.name, 'videoFrames': len(frames), 'fabRenders': result.get('fabRenderFrames')}
if a.motion:
    centers = []
    for frame in frames:
        # 虚构卡片为橙色，FAB 为蓝色；透明度最低 .65 时仍可区分，图标不计入中心。
        blue = (frame[:, :, 2] > frame[:, :, 0] + 50) & (frame[:, :, 2] > frame[:, :, 1] + 35)
        ys, xs = np.nonzero(blue)
        assert len(ys) > w * h * .15, 'FAB 在运动期间消失或被遮住'
        centers.append(float(np.mean(ys)))
    travel = max(centers) - min(centers)
    assert travel > ready['density'] * 50, '真实属性改变，但合成画面中的 FAB 没有移动'
    assert result['fabRenderFrames'] > 10, '动态 FAB 没有持续更新硬件缓冲'
    report['verticalTravelPx'] = round(travel, 2)
else:
    yy, xx = np.ogrid[:h, :w]
    interior = (xx - w / 2) ** 2 + (yy - h / 2) ** 2 < (w * .4) ** 2
    error = np.abs(frames - frames[0])[:, interior].mean(axis=(1, 2))
    report['maxInteriorRgbError'] = round(float(error.max()), 3)
    report['framesOver5'] = int((error > 5).sum())
    # 32 Mbps 有损录屏允许少量压缩误差；旧版覆盖时误差为几十个色阶。
    assert error.max() < 5, report
print(json.dumps(report, ensure_ascii=False))
