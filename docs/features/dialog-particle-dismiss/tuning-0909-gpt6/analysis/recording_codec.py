"""按原片的码率把模型帧过一遍视频编码（只作诊断与对照）。

原片都是转发后的低码率 H.264/HEVC（每像素每帧 0.015～0.05 比特）；运动中的细颗粒被量化成较大的软斑，
静态边上的模糊（`estimate_edge_blur.py`）量不到这一层。这里把模型帧按原片同一片段的时间轴排成视频：
每个原片独立帧对应的模型帧按原片的重复次数重复，帧率与画幅同原片；用 libx264 编码，按二分调 CRF，
使片段总字节数与原片同一片段的包大小之和相当；解码后在每个独立帧第一次出现的位置取回画面。

输出与 `match_refs.RefFrames` 的独立帧一一对应（同一序号即同一进度），缓存在 match-cache/codec/。
手持拍屏的原片码率还花在相机噪声与抖动上，同码率下模型分到的细节偏多，这一口径只对屏幕录制段严格。
"""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import CACHE, meta
from handoff_review import render_all

OUT = CACHE / 'codec'


def ffprobe_json(args):
    r = subprocess.run(['ffprobe', '-v', 'error', *args, '-of', 'json'], capture_output=True, text=True, encoding='utf-8')
    return json.loads(r.stdout)


def timeline(name):
    """原片片段：每个视频帧所属的独立帧序号、帧率、片段内的包字节数。"""
    d = CACHE / name
    first = np.load(d / 'first-times.npy'); repeats = np.load(d / 'repeats.npy')
    info = json.loads((d / 'frames.json').read_text('utf-8')); fps = float(info['fps'])
    owner = np.repeat(np.arange(len(first)), repeats)
    t0 = float(first[0]); t1 = t0 + len(owner) / fps
    path = meta(name)['reference']['path']
    pk = ffprobe_json(['-select_streams', 'v:0', '-show_entries', 'packet=pts_time,size', path])['packets']
    size = sum(int(p['size']) for p in pk if 'pts_time' in p and t0 - .5 / fps <= float(p['pts_time']) < t1 - .5 / fps)
    return owner, fps, size


def encode(frames, owner, fps, crf, path):
    h, w = frames.shape[1:3]
    cmd = ['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}', '-r', f'{fps:g}', '-i', '-',
           '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-preset', 'medium', '-crf', f'{crf:.2f}', str(path)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for k in owner:
        p.stdin.write(np.ascontiguousarray(frames[k]).tobytes())
    p.stdin.close(); p.wait()
    pk = ffprobe_json(['-select_streams', 'v:0', '-show_entries', 'packet=size', str(path)])['packets']
    return sum(int(q['size']) for q in pk)


def decode(path, w, h, pick):
    p = subprocess.Popen(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE)
    want = set(int(i) for i in pick); out = {}; i = 0; n = w * h * 3
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n: break
        if i in want: out[i] = np.frombuffer(buf, np.uint8).reshape(h, w, 3)
        i += 1
    p.wait()
    return np.stack([out[int(i)] for i in pick])


def capture_blur(name):
    """静态边锚定的额外录像模糊 σ（录像像素，sRGB 编码值上的高斯）：`handoff-review/edge-blur.json`（estimate_edge_blur.py）的 extra。
    向上参考可用剖面不足，取顾问第九次取证的卡片边 10%→90% 上升宽度（2.60，钢铁侠 1.47）换算的 0.84，
    按其余段两种测法之比的中位（0.89）折算为 0.75。"""
    table = json.loads((HERE / 'analysis/match-cache/handoff-review/edge-blur.json').read_text('utf-8'))
    row = table.get(name)
    if isinstance(row, dict): return float(row.get('extra', 0.))
    return {'ironman-up-reference': .75}.get(name, 0.)


def coded(name, label, spec='', root=HERE, tol=.04, verbose=True, blur=None):
    """返回 (与原片独立帧对应的编码后模型帧, 记录)。

    blur：编码前先按 capture_blur(name) 给模型帧加高斯模糊（录像的缩放与转码在静态边上留下的模糊，与颗粒无关）；
    缺省读环境变量 RECORDING_BLUR（'1' 为开）。开启后缓存身份不同，与不加模糊的结果分开存放。"""
    import os
    if blur is None: blur = os.environ.get('RECORDING_BLUR', '') == '1'
    frames = render_all(name, root, label, spec=spec)
    sigma = capture_blur(name) if blur else 0.
    tag = b'codec-v1' + (f'-blur{sigma:.3f}'.encode() if sigma > 0 else b'')
    ident = hashlib.sha256(str(getattr(frames, 'filename', '')).encode() + tag).hexdigest()[:16]
    out = OUT / f'{name}-{ident}.npy'; rec_path = out.with_suffix('.json')
    if out.is_file() and rec_path.is_file():
        return np.load(out, mmap_mode='r'), json.loads(rec_path.read_text('utf-8'))
    progress = np.load(CACHE / name / 'progress.npy')
    owner, fps, target = timeline(name)
    model = np.stack([np.asarray(frames[round(float(np.clip(p, 0, 1)) * 120)]) for p in progress])
    if sigma > 0:
        import cv2
        model = np.stack([cv2.GaussianBlur(f, (0, 0), sigma) for f in model])
    OUT.mkdir(parents=True, exist_ok=True); mp4 = out.with_suffix('.mp4')
    lo, hi = 14., 51.; crf = 28.; best = None
    for _ in range(9):
        size = encode(model, owner, fps, crf, mp4)
        if verbose: print(f'  {name} crf {crf:.2f} 字节 {size} 目标 {target} 比 {size / target:.3f}', flush=True)
        if best is None or abs(size / target - 1) < abs(best[1] / target - 1): best = (crf, size)
        if abs(size / target - 1) <= tol: break
        if size > target: lo = crf
        else: hi = crf
        crf = (lo + hi) / 2
    if best[0] != crf:
        crf = best[0]; encode(model, owner, fps, crf, mp4)
    first_of = np.searchsorted(owner, np.arange(len(progress)))
    got = decode(mp4, model.shape[2], model.shape[1], first_of)
    np.save(out, got)
    rec = dict(scene=name, label=label, crf=round(crf, 2), bytes=int(best[1]), target=int(target), fps=fps, blur_sigma=sigma,
               video_frames=int(len(owner)), unique_frames=int(len(progress)),
               bpp=round(8 * best[1] / (len(owner) * model.shape[1] * model.shape[2]), 4))
    rec_path.write_text(json.dumps(rec, ensure_ascii=False, indent=2), 'utf-8')
    return np.load(out, mmap_mode='r'), rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman'])
    ap.add_argument('--variants', nargs='+', required=True, help='标签=变体（空变体为交接默认）')
    a = ap.parse_args()
    for name in a.scenes:
        for v in a.variants:
            label, spec = v.split('=', 1)
            _, rec = coded(name, label, spec)
            print(name, label, json.dumps(rec, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
