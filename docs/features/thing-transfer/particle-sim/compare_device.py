"""模拟器与真机录屏的逐帧对照：同一份素材（同一种子、同一几何、落盘时的参数）。

1. 录屏按原始帧序解码，逐帧读时间戳（screenrecord 是变帧率，见 .claude/rules/adb.md）；
2. 两边各算一条「粒子层覆盖」曲线：快照范围内与底图（接收用边框淡去后的那张）的差异；
3. 按曲线互相关求时间偏移，把录屏的时间换到模拟器的时间轴上；
4. 在若干时刻输出左右并排的对照图，并给出每一时刻的差异量化（快照范围内逐像素绝对差的均值与 p95）。

用法：
  python -X utf8 compare_device.py --scene scenes/<素材> --video <录屏.mp4> --out out/<名字>
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from export import FFMPEG, FONT, FlightPlayer
from sim_model import HERE, Params, Scene

FFPROBE = 'C:/ffmpeg/bin/ffprobe.exe'


def video_frames(path: Path, width: int, height: int):
    stamps = subprocess.run([FFPROBE, '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                             'frame=best_effort_timestamp_time', '-of', 'csv=p=0', str(path)],
                            capture_output=True, text=True, check=True).stdout.split()
    stamps = [float(x) for x in stamps]
    proc = subprocess.Popen([FFMPEG, '-v', 'error', '-i', str(path), '-vsync', '0', '-f', 'rawvideo',
                             '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE)
    size = width * height * 3
    frames = []
    while True:
        data = proc.stdout.read(size)
        if len(data) < size:
            break
        frames.append(np.frombuffer(data, np.uint8).reshape(height, width, 3))
    proc.wait()
    if len(frames) != len(stamps):
        raise RuntimeError(f'解码帧数 {len(frames)} 与时间戳数 {len(stamps)} 不一致')
    return np.array(stamps), frames


def coverage(image: np.ndarray, reference: np.ndarray, box) -> float:
    x0, y0, x1, y1 = box
    a = image[y0:y1, x0:x1].astype(np.int16)
    b = reference[y0:y1, x0:x1].astype(np.int16)
    return float((np.abs(a - b).max(axis=2) > 24).mean())


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--scene', required=True)
    p.add_argument('--video', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--count', type=int, default=8, help='对照图的时刻数')
    p.add_argument('--window', type=float, nargs=2, help='只在这段模拟时间里对齐与取样（发送的流出、流回分开核对）')
    a = p.parse_args()
    scene = Scene.load(HERE / a.scene)
    session = FlightPlayer(scene, Params.from_scene(scene))
    w, h = scene.screen
    stamps, frames = video_frames(Path(a.video), w, h)
    reference = scene.settled if scene.settled is not None else scene.background
    l, t, r, b = (int(v) for v in scene.bounds)
    # 发送方的粒子流向快照之外（天线），覆盖范围取快照与天线的包围盒
    if scene.kind == 'send':
        sx, sy = (int(v) for v in scene.sink)
        l, t, r, b = min(l, sx - 60), min(t, sy - 60), max(r, sx + 60), max(b, sy + 60)
    box = (max(l, 0), max(t, 0), min(r, w), min(b, h))

    # 设备侧：每一帧相对于底图的覆盖
    device_curve = np.array([coverage(f, reference, box) for f in frames])
    # 模拟器侧：以 240 Hz 采样时间轴（与设备的 60 帧对齐时只取需要的）
    lo_t, hi_t = a.window if a.window else (0., session.timeline.duration)
    sim_t = np.arange(lo_t, hi_t, 1 / 120)
    sim_images = {}

    def sim_image(t: float) -> np.ndarray:
        key = round(t * 1200)
        if key not in sim_images:
            sim_images[key] = session.frame(t)[0]
        return sim_images[key]

    sim_curve = np.array([coverage(sim_image(t), reference, box) for t in sim_t])

    # 找偏移：设备时间 = 模拟时间 + offset；在设备曲线上按模拟时间插值后比 L2
    best = None
    for offset in np.arange(stamps[0] - sim_t[-1], stamps[-1], 1 / 240):
        device_at = np.interp(sim_t + offset, stamps, device_curve, left=np.nan, right=np.nan)
        ok = ~np.isnan(device_at)
        if ok.sum() < len(sim_t) * .8:
            continue
        err = float(np.mean((device_at[ok] - sim_curve[ok]) ** 2))
        if best is None or err < best[0]:
            best = (err, float(offset))
    err, offset = best
    print(f'时间偏移 {offset:.3f} s（设备时间 = 模拟时间 + 偏移），覆盖曲线均方差 {err:.5f}')

    # 对照时刻：覆盖曲线上有粒子的区间里均匀取
    active = sim_t[sim_curve > .02]
    lo, hi = (active[0], active[-1]) if len(active) else (0., session.timeline.duration)
    moments = np.linspace(lo, hi, a.count)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(FONT, 34)
    tiles, report = [], []
    for t in moments:
        k = int(np.argmin(np.abs(stamps - (t + offset))))
        device = frames[k]
        sim = sim_image(t)
        x0, y0, x1, y1 = box
        diff = np.abs(device[y0:y1, x0:x1].astype(np.int16) - sim[y0:y1, x0:x1].astype(np.int16)).max(axis=2)
        report.append(dict(sim_t=round(float(t), 4), device_t=round(float(stamps[k]), 4),
                           gap_ms=round(float((stamps[k] - offset - t) * 1000), 1),
                           mean_abs=round(float(diff.mean()), 2), p95=float(np.percentile(diff, 95)),
                           device_cover=round(coverage(device, reference, box), 4),
                           sim_cover=round(coverage(sim, reference, box), 4)))
        pair = np.concatenate([device[y0:y1, x0:x1], sim[y0:y1, x0:x1]], axis=1)
        im = Image.fromarray(pair)
        scale = 900 / im.width
        im = im.resize((900, round(im.height * scale)), Image.LANCZOS)
        draw = ImageDraw.Draw(im)
        draw.rectangle([0, 0, 900, 46], fill=(0, 0, 0))
        draw.text((10, 4), f'真机 {stamps[k]:.3f}s ｜ 模拟 {t:.3f}s ｜ 差均值 {report[-1]["mean_abs"]}', fill=(255, 255, 255), font=font)
        tiles.append(np.array(im))
    height = max(x.shape[0] for x in tiles)
    tiles = [np.pad(x, ((0, height - x.shape[0]), (0, 0), (0, 0))) for x in tiles]
    rows = [np.concatenate(tiles[i:i + 4], axis=1) for i in range(0, len(tiles), 4)]
    width = max(r.shape[1] for r in rows)
    rows = [np.pad(r, ((0, 0), (0, width - r.shape[1]), (0, 0))) for r in rows]
    Image.fromarray(np.concatenate(rows, axis=0)).save(out / 'pairs.png')
    (out / 'report.json').write_text(json.dumps(dict(offset_s=offset, curve_mse=err, box=box, frames=report),
                                                ensure_ascii=False, indent=2), encoding='utf-8')
    # 覆盖曲线
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']
    fig, ax = plt.subplots(figsize=(9, 3.2))
    ax.plot(stamps - offset, device_curve, '.', ms=3, label='真机（按偏移换到模拟时间）')
    ax.plot(sim_t, sim_curve, '-', lw=1.2, label='模拟器')
    ax.set_xlim(max(0, lo - .3), hi + .3)
    ax.set_xlabel('模拟时间（秒）'); ax.set_ylabel('快照范围内的覆盖')
    ax.legend(); fig.tight_layout(); fig.savefig(out / 'coverage.png', dpi=120)
    for row in report:
        print(row)
    session.close()


if __name__ == '__main__':
    main()
