"""整段会话与真机录屏的对照：同一份会话记录（同一时间线、同一种子），整屏逐帧比。

1. 录屏按原始帧序解码、逐帧读时间戳（变帧率）；
2. 模拟器按 30 帧 / 秒出小图，与录屏按「录屏时间 = 会话时间 + 偏移」对齐：在偏移的候选里取整屏
   （去掉系统状态栏与导航条）平均差最小的那个；
3. 输出左右并排的同步视频（左真机、右模拟器），并打印各时刻的差异。

用法：python -X utf8 compare_session.py --scene scenes/<会话> --video <录屏.mp4> --out out/<名字>
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from compare_device import video_frames
from export import FFMPEG, FONT, load_params
from sim_model import HERE
from sim_session import SessionPlayer
from sim_timeline import Session


def small(img: np.ndarray, width: int) -> np.ndarray:
    h = round(img.shape[0] * width / img.shape[1])
    return np.asarray(Image.fromarray(img).resize((width, h), Image.BILINEAR)).astype(np.int16)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', required=True)
    ap.add_argument('--video', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--width', type=int, default=540, help='并排视频里每一边的宽度')
    ap.add_argument('--offset', type=float, help='跳过对齐，直接用这个偏移（秒）')
    ap.add_argument('--params', default='scene', help='scene（录下时的参数，缺省）/ file（参数文件）/ 路径')
    ap.add_argument('--captions', nargs=2, default=['真机录屏', '模拟器'], help='左右两边的标题')
    a = ap.parse_args()
    session = Session(HERE / a.scene)
    ctx = moderngl.create_standalone_context(require=430)
    params = load_params(session, a.params)
    w, h = session.screen
    stamps, frames = video_frames(Path(a.video), w, h)
    print(f'录屏 {len(frames)} 帧，{stamps[0]:.2f}–{stamps[-1]:.2f} 秒')

    # ---- 对齐：小图、整屏、去掉状态栏与导航条
    probe = SessionPlayer(session, params, ctx, output_scale=.25)
    sw = probe.out_w
    top, bottom = int(.045 * probe.out_h), int(.97 * probe.out_h)
    sim_t = np.arange(0, probe.duration, 1 / 30)
    sim_small = [small(probe.render(t)[0], sw)[top:bottom] for t in sim_t]
    dev_small = [small(f, sw)[top:bottom] for f in frames]
    if a.offset is None:
        best = None
        for offset in np.arange(stamps[0] - 0.5, stamps[-1] - probe.duration + 0.5, 1 / 60):
            errs = []
            for k in range(0, len(sim_t), 3):
                target = sim_t[k] + offset
                if target < stamps[0] or target > stamps[-1]:
                    continue
                i = int(np.argmin(np.abs(stamps - target)))
                errs.append(np.abs(dev_small[i] - sim_small[k]).mean())
            if len(errs) < len(sim_t) / 3 * .8:
                continue
            e = float(np.mean(errs))
            if best is None or e < best[0]:
                best = (e, float(offset))
        err, offset = best
        print(f'偏移 {offset:.3f} 秒（录屏时间 = 会话时间 + 偏移），整屏平均差 {err:.2f}')
    else:
        offset = a.offset
    probe.close()

    # ---- 各时刻的差异（小图口径）
    rows = []
    for k, t in enumerate(sim_t):
        target = t + offset
        if target < stamps[0] or target > stamps[-1]:
            continue
        i = int(np.argmin(np.abs(stamps - target)))
        d = np.abs(dev_small[i] - sim_small[k]).max(axis=2)
        rows.append((t, float(d.mean()), float(np.percentile(d, 99))))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / 'diff.csv', 'w', encoding='utf-8') as f:
        f.write('会话时间,平均差,99分位\n')
        for t, m, p99 in rows:
            f.write(f'{t:.3f},{m:.2f},{p99:.1f}\n')
    worst = sorted(rows, key=lambda r: -r[1])[:8]
    print('差异最大的时刻：', ', '.join(f'{t:.2f}s={m:.1f}' for t, m, _ in worst))

    # ---- 并排视频（全分辨率出图再缩小）
    player = SessionPlayer(session, params, ctx, output_scale=1.)
    tw = a.width
    th = round(h * tw / w / 2) * 2
    font = ImageFont.truetype(FONT, 26)
    fps = 60
    video_path = out / 'side-by-side.mp4'
    proc = subprocess.Popen([FFMPEG, '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s',
                             f'{tw * 2 + 12}x{th + 46}', '-r', str(fps), '-i', '-', '-c:v', 'libx264',
                             '-pix_fmt', 'yuv420p', '-crf', '17', '-movflags', '+faststart', str(video_path)],
                            stdin=subprocess.PIPE)
    for k in range(int(player.duration * fps)):
        t = k / fps
        target = t + offset
        i = int(np.argmin(np.abs(stamps - target)))
        device = Image.fromarray(frames[i]).resize((tw, th), Image.LANCZOS)
        image, label = player.render(t)
        sim = Image.fromarray(image).resize((tw, th), Image.LANCZOS)
        canvas = Image.new('RGB', (tw * 2 + 12, th + 46), (16, 20, 28))
        canvas.paste(device, (0, 46))
        canvas.paste(sim, (tw + 12, 46))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 8), a.captions[0], fill=(230, 236, 244), font=font)
        draw.text((tw + 22, 8), f'{a.captions[1]}  {t:.2f}s  {label}', fill=(230, 236, 244), font=font)
        proc.stdin.write(np.asarray(canvas).tobytes())
    proc.stdin.close()
    proc.wait()
    player.close()
    print('并排视频', video_path)


if __name__ == '__main__':
    main()
