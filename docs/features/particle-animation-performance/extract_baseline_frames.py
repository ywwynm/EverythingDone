"""从修前录屏里取对照基线帧：整帧 + 4 倍放大裁剪，落盘给后续批次并排对比。

`screenrecord` 是变帧率，帧号换算成秒必须逐帧读时间戳（`.claude/rules/adb.md`），
解码保持原始帧序（`-vsync 0`），并断言解码帧数与时间戳数一致。

动画窗口由「相邻帧的灰度平均绝对差」定位：粒子在播的那一段差分明显高于静止段。
放大裁剪的中心取动画期变化最剧烈处的质心，保证框到粒子本体而不是空白。
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

SMALL_W, SMALL_H = 114, 266


def run(args, **kw):
    return subprocess.run(args, capture_output=True, check=True, **kw)


def timestamps(path):
    out = run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
               'frame=best_effort_timestamp_time', '-of', 'csv=p=0', str(path)]).stdout
    return [float(v) for v in out.decode().split() if v.strip()]


def gray_frames(path, width, height):
    out = run(['ffmpeg', '-v', 'error', '-i', str(path), '-vsync', '0',
               '-vf', 'scale=%d:%d' % (width, height), '-pix_fmt', 'gray',
               '-f', 'rawvideo', '-']).stdout
    frame = width * height
    assert len(out) % frame == 0, '原始帧数据长度不是整帧的倍数'
    return np.frombuffer(out, dtype=np.uint8).reshape(-1, height, width).astype(np.float32)


def animation_window(frames, ratio=.05, gap=3):
    """相邻帧差分的包络；取「高于低门槛」的最长连续段，允许中间有几帧的空档。

    门槛取峰值的 [ratio] 与静止段中位数的三倍里的大者：左滑那种「回弹一下」的场景
    峰值极高，只按峰值比例会把整段动画切成三四帧（2026-09-17 实际发生过）。
    """
    diff = np.abs(np.diff(frames, axis=0)).mean(axis=(1, 2))
    if diff.max() <= 0:
        return 0, len(frames) - 1, diff
    threshold = max(diff.max() * ratio, float(np.median(diff)) * 3)
    active = diff > threshold
    runs = []
    start = None
    for i, on in enumerate(active):
        if on and start is None:
            start = i
        if not on and start is not None:
            runs.append((start, i - 1))
            start = None
    if start is not None:
        runs.append((start, len(active) - 1))
    merged = []
    for run in runs:
        if merged and run[0] - merged[-1][1] <= gap:
            merged[-1] = (merged[-1][0], run[1])
        else:
            merged.append(run)
    if not merged:
        return 0, len(frames) - 1, diff
    best = max(merged, key=lambda r: r[1] - r[0])
    return best[0], best[1] + 1, diff


def crop_box(frames, first, last, size, shape):
    """动画期变化质心处的正方形裁剪框（返回全分辨率坐标）。"""
    change = np.abs(frames[last] - frames[first])
    for i in range(first, last):
        change = np.maximum(change, np.abs(frames[i + 1] - frames[i]))
    weight = change.astype(np.float64)
    if weight.sum() <= 0:
        weight = np.ones_like(weight)
    ys, xs = np.mgrid[0:weight.shape[0], 0:weight.shape[1]]
    cy = (ys * weight).sum() / weight.sum() / weight.shape[0]
    cx = (xs * weight).sum() / weight.sum() / weight.shape[1]
    w, h = shape
    x = int(round(cx * w - size / 2))
    y = int(round(cy * h - size / 2))
    return max(0, min(w - size, x)), max(0, min(h - size, y))


def extract(path, index, out, crop=None, zoom=4):
    filters = ['select=eq(n\\,%d)' % index]
    if crop:
        x, y, size = crop
        filters.append('crop=%d:%d:%d:%d' % (size, size, x, y))
        filters.append('scale=%d:%d:flags=neighbor' % (size * zoom, size * zoom))
    run(['ffmpeg', '-v', 'error', '-y', '-i', str(path), '-vsync', '0',
         '-vf', ','.join(filters), '-frames:v', '1', str(out)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--videos', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--size', type=int, default=320, help='放大裁剪的边长（全分辨率像素）')
    parser.add_argument('--zoom', type=int, default=4)
    parser.add_argument('--crops', default='',
                        help='修前那一轮的 frames.json：裁剪框原样沿用，两边才是同一处')
    args = parser.parse_args()
    reuse = {}
    if args.crops:
        reuse = {k: v.get('crop') for k, v in
                 json.loads(Path(args.crops).read_text(encoding='utf-8')).items()}
    videos = sorted(Path(args.videos).glob('*.mp4'))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    report = {}
    for video in videos:
        stamps = timestamps(video)
        frames = gray_frames(video, SMALL_W, SMALL_H)
        assert len(frames) == len(stamps), '解码帧数 %d 与时间戳数 %d 不一致' % (len(frames), len(stamps))
        probe = run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                     'stream=width,height', '-of', 'csv=p=0', str(video)]).stdout.decode()
        w, h = (int(v) for v in probe.strip().split(',')[:2])
        first, last, diff = animation_window(frames)
        if video.stem in reuse and reuse[video.stem]:
            x, y, size = reuse[video.stem]
            assert size == args.size, '修前修后的裁剪边长必须一致'
        else:
            x, y = crop_box(frames, first, last, args.size, (w, h))
        picks = []
        for fraction in (.1, .5, .9):
            index = int(round(first + (last - first) * fraction))
            index = max(0, min(len(stamps) - 1, index))
            name = '%s-%02d' % (video.stem, round(fraction * 100))
            extract(video, index, out / (name + '-full.png'))
            extract(video, index, out / (name + '-zoom.png'), crop=(x, y, args.size), zoom=args.zoom)
            picks.append(dict(fraction=fraction, frame=index,
                              atMs=round((stamps[index] - stamps[first]) * 1000, 1)))
        report[video.stem] = dict(
            frames=len(stamps), durationMs=round(stamps[-1] * 1000, 1),
            window=[first, last],
            windowMs=round((stamps[last] - stamps[first]) * 1000, 1),
            windowFrames=last - first + 1,
            fpsInWindow=round((last - first) / max(1e-6, stamps[last] - stamps[first]), 1),
            crop=[x, y, args.size], picks=picks)
        print(video.stem, json.dumps(report[video.stem], ensure_ascii=False), flush=True)
    (out / 'frames.json').write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding='utf-8')
    print('写入', out / 'frames.json')


if __name__ == '__main__':
    sys.exit(main())
