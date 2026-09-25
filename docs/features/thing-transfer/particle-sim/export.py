"""命令行出图：联系表（若干时刻并排）或视频。

素材目录里有 `session.json` 的按整段会话出图（`sim_session.SessionPlayer`：发送从打开分享界面开始，接收从
进入接收状态开始）；只有 `scene.json` 的（会话里的 `flight-<n>/`）只出粒子飞行那一段（`FlightPlayer`）。

示例（在本目录执行，Python 用 conda 环境 everythingdone）：
  python -X utf8 export.py --scene scenes/samsung-receive --sheet out/sheet.png --times 2 4 6 8
  python -X utf8 export.py --scene scenes/samsung-send --video out/send.mp4 --width 720
  python -X utf8 export.py --scene scenes/meizu-receive --video out/inflow.mp4 --start 5.5 --end 8 --rate 0.5
  python -X utf8 export.py --scene scenes/samsung-send --video out/ab.mp4 --compare out/params-b.properties
  python -X utf8 export.py --scene scenes/samsung-send/flight-1 --video out/flight.mp4 --params scene
  python -X utf8 export.py --scene scenes/samsung-receive --grid scenes/samsung-receive-one-full       scenes/samsung-receive-three --video out/grid.mp4 --start 3.2 --end 9 --rate 0.5
"""
from __future__ import annotations

import argparse
import subprocess
import time as clock
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from sim_model import HERE, Params, Scene, build_materials, timeline_for
from sim_renderer import TapRenderer

FFMPEG = 'C:/ffmpeg/bin/ffmpeg.exe'
FONT = 'C:/Windows/Fonts/msyh.ttc'


class FlightPlayer:
    """只有粒子飞行的素材（`scene.json`）+ 参数 + 种子 → 按时间取图。"""

    def __init__(self, scene: Scene, params: Params, seed: int | None = None, output_scale: float = 1.,
                 ctx: moderngl.Context | None = None):
        self.scene, self.params = scene, params
        self.seed = int(scene.meta['seed'] if seed is None else seed)
        self.ctx = ctx or moderngl.create_standalone_context(require=430)
        started = clock.perf_counter()
        self.build = build_materials(scene, params, self.seed)
        self.build_ms = (clock.perf_counter() - started) * 1000
        self.renderer = TapRenderer(scene, self.build, self.ctx, output_scale)
        self.timeline = timeline_for(scene, params)
        density = scene.density
        radius_dp = params['send_glow_radius_dp']
        self.glow_radius = (radius_dp[0], radius_dp[1], density)

    @property
    def duration(self) -> float:
        return self.timeline.duration

    def frame(self, t: float, particles_only: bool = False) -> tuple[np.ndarray, str]:
        moment = self.timeline.at(t)
        base, extra, density = self.glow_radius
        radius = (base + extra * moment.glow) * density
        alpha = moment.glow * self.params['send_glow_alpha']
        image = self.renderer.render(moment, self.scene.sink if self.scene.kind == 'send' else None,
                                     (radius, alpha), particles_only)
        return image, moment.label

    render = frame

    def close(self):
        self.renderer.close()

    def describe(self) -> str:
        b, r = self.build, self.renderer
        return (f'{self.scene.title} · 种子 {self.seed} · {b.stats["count"]} 片（复制片 {b.stats["replicas"]}）'
                f' · 建材 {self.build_ms:.0f} ms · 预算轨迹 {r.precompute_ms:.0f} ms')


def load_params(source, which: str) -> Params:
    """file = 参数文件；scene = 素材录下时手机上的参数；其余当作一个参数文件的路径。"""
    if which == 'scene':
        return Params.from_scene(source)
    if which == 'file':
        return Params.load()
    return Params.load(Path(which))


def open_player(path: Path, params_choice: str = 'file', seed: int | None = None, scale: float = 1.,
                ctx: moderngl.Context | None = None, stage_hz: float = 120., recolor_seed: int | None = None):
    ctx = ctx or moderngl.create_standalone_context(require=430)
    if (path / 'session.json').is_file():
        from sim_session import SessionPlayer
        from sim_timeline import Session
        session = Session(path)
        if recolor_seed is not None:
            from sim_recolor import recolor
            session = recolor(session, recolor_seed)
        return SessionPlayer(session, load_params(session, params_choice), ctx, scale, seed, stage_hz)
    scene = Scene.load(path)
    return FlightPlayer(scene, load_params(scene, params_choice), seed, scale, ctx)


def label(image: np.ndarray, text: str) -> np.ndarray:
    im = Image.fromarray(image)
    draw = ImageDraw.Draw(im)
    size = max(18, image.shape[1] // 28)
    font = ImageFont.truetype(FONT, size)
    draw.rectangle([0, 0, image.shape[1], size * 1.6], fill=(0, 0, 0))
    draw.text((size * .4, size * .25), text, fill=(255, 255, 255), font=font)
    return np.array(im)


def sheet(player, times: list[float], out: Path, width: int = 360):
    tiles = []
    for t in times:
        image, text = player.render(t)
        h = round(image.shape[0] * width / image.shape[1])
        small = np.array(Image.fromarray(image).resize((width, h), Image.LANCZOS))
        tiles.append(label(small, f'{t:.2f}s {text}'))
    out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.concatenate(tiles, axis=1)).save(out)


def _encoder(out: Path, size: tuple[int, int], fps: int, scaled: tuple[int, int] | None = None):
    out.parent.mkdir(parents=True, exist_ok=True)
    w, h = size
    command = [FFMPEG, '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}',
               '-r', str(fps), '-i', '-']
    if scaled and scaled != size:
        command += ['-vf', f'scale={scaled[0]}:{scaled[1]}:flags=lanczos']
    command += ['-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '16', '-preset', 'medium', '-movflags',
                '+faststart', str(out)]
    return subprocess.Popen(command, stdin=subprocess.PIPE)


def _finish(process):
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError('ffmpeg 失败')


def video(player, out: Path, rate: float = 1., fps: int = 60, width: int | None = None, start: float = 0.,
          end: float | None = None, progress=None):
    """把 [start, end]（会话时间）按 rate 倍速出成视频。progress(已出帧数, 总帧数) 返回 False 时中止。"""
    end = player.duration if end is None else min(end, player.duration)
    frames = int(round((end - start) / rate * fps))
    first = player.render(start)[0]
    h, w = first.shape[:2]
    scaled = (width, round(h * width / w / 2) * 2) if width else (w, h)
    process = _encoder(out, (w, h), fps, scaled)
    try:
        for k in range(frames):
            image, _ = player.render(start + k / fps * rate)
            process.stdin.write(image.tobytes())
            if progress is not None and progress(k + 1, frames) is False:
                break
    finally:
        _finish(process)


def pair_video(left, right, out: Path, rate: float = 1., fps: int = 60, width: int = 540,
               captions: tuple[str, str] = ('参数文件', '当前参数'), start: float = 0., end: float | None = None,
               progress=None):
    """两组参数左右并排（同一会话、同一种子、同一时刻），每边缩到 width 宽，上方写说明与当时的阶段。"""
    end = max(left.duration, right.duration) if end is None else end
    frames = int(round((end - start) / rate * fps))
    first = left.render(start)[0]
    h0, w0 = first.shape[:2]
    th = round(h0 * width / w0 / 2) * 2
    head, gap = 46, 12
    size = (width * 2 + gap, th + head)
    font = ImageFont.truetype(FONT, 24)
    process = _encoder(out, size, fps)
    try:
        for k in range(frames):
            t = start + k / fps * rate
            canvas = Image.new('RGB', size, (16, 20, 28))
            draw = ImageDraw.Draw(canvas)
            for i, (player, caption) in enumerate(((left, captions[0]), (right, captions[1]))):
                image, text = player.render(min(t, player.duration))
                canvas.paste(Image.fromarray(image).resize((width, th), Image.LANCZOS), (i * (width + gap), head))
                draw.text((i * (width + gap) + 10, 9), f'{caption} · {t:.2f}s · {text}', fill=(230, 236, 244),
                          font=font)
            process.stdin.write(np.asarray(canvas).tobytes())
            if progress is not None and progress(k + 1, frames) is False:
                break
    finally:
        _finish(process)


def short_title(player) -> str:
    """网格里每格的说明：会话标题去掉设备名与「完整会话」之类的尾巴。"""
    title = getattr(getattr(player, 's', None), 'title', None) or player.describe().split(' · ')[0]
    rest = title.split(' · ', 1)[-1]
    for tail in ('· 完整会话', ' · 完整会话'):
        rest = rest.replace(tail, '')
    return rest.replace('合成：', '').strip()


def grid_video(players, out: Path, captions: list[str], cols: int = 3, width: int = 420, rate: float = 1.,
               fps: int = 60, start: float = 0., end: float | None = None, progress=None):
    """几段会话按网格并排（同一时刻），每格缩到 width 宽，上方写说明与当时的阶段。各段的屏幕比例可以不同，
    格高取最高的那一段，矮的在下方留空。"""
    end = max(p.duration for p in players) if end is None else end
    frames = int(round((end - start) / rate * fps))
    sizes = []
    for player in players:
        h0, w0 = player.render(start)[0].shape[:2]
        sizes.append((width, round(h0 * width / w0 / 2) * 2))
    tile_h = max(h for _, h in sizes)
    head, gap = 40, 12
    rows = (len(players) + cols - 1) // cols
    size = (cols * width + (cols - 1) * gap, rows * (tile_h + head) + (rows - 1) * gap)
    size = (size[0] + size[0] % 2, size[1] + size[1] % 2)
    font = ImageFont.truetype(FONT, 20)
    process = _encoder(out, size, fps)
    try:
        for k in range(frames):
            t = start + k / fps * rate
            canvas = Image.new('RGB', size, (16, 20, 28))
            draw = ImageDraw.Draw(canvas)
            for i, (player, caption, (w, h)) in enumerate(zip(players, captions, sizes)):
                x, y = (i % cols) * (width + gap), (i // cols) * (tile_h + head + gap)
                image, text = player.render(min(t, player.duration))
                canvas.paste(Image.fromarray(image).resize((w, h), Image.LANCZOS), (x, y + head))
                draw.text((x + 8, y + 8), f'{caption} · {text}', fill=(230, 236, 244), font=font)
            draw.text((size[0] - 150, size[1] - 30), f'{t:.2f}s', fill=(150, 160, 175), font=font)
            process.stdin.write(np.asarray(canvas).tobytes())
            if progress is not None and progress(k + 1, frames) is False:
                break
    finally:
        _finish(process)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--scene', required=True, help='scenes/<会话> 或会话里的 flight-<n>')
    p.add_argument('--params', default='file', help='file（参数文件）/ scene（素材录下时的参数）/ 路径')
    p.add_argument('--compare', help='另一组参数（同 --params 的写法）：左右并排出视频，左边 --params、右边它')
    p.add_argument('--grid', nargs='*', help='另外几段会话：与 --scene 按网格并排出视频（同一组参数、同一时刻）')
    p.add_argument('--cols', type=int, default=3, help='网格的列数')
    p.add_argument('--seed', type=int)
    p.add_argument('--sheet')
    p.add_argument('--times', type=float, nargs='*')
    p.add_argument('--video')
    p.add_argument('--rate', type=float, default=1.)
    p.add_argument('--start', type=float, default=0., help='从会话的这一秒开始')
    p.add_argument('--end', type=float, help='到会话的这一秒结束')
    p.add_argument('--width', type=int)
    p.add_argument('--scale', type=float, default=1., help='出图分辨率相对屏幕的比例')
    p.add_argument('--hz', type=float, default=120., help='接收舞台（边框粒子）按多少 Hz 推进，对应设备刷新率')
    p.add_argument('--recolor', type=int, help='按这个种子随机换记事颜色（纯色或渐变）')
    a = p.parse_args()
    path = Path(a.scene) if Path(a.scene).is_absolute() else HERE / a.scene
    ctx = moderngl.create_standalone_context(require=430)
    player = open_player(path, a.params, a.seed, a.scale, ctx, a.hz, a.recolor)
    other = open_player(path, a.compare, a.seed, a.scale, ctx, a.hz, a.recolor) if a.compare else None
    print(player.describe())
    try:
        if a.sheet:
            times = a.times or [x * player.duration / 7 for x in range(8)]
            sheet(player, times, Path(a.sheet))
            print('联系表', a.sheet)
        if a.video:
            started = clock.perf_counter()
            if a.grid:
                extra = [open_player(Path(g) if Path(g).is_absolute() else HERE / g, a.params, a.seed, a.scale, ctx,
                                     a.hz, a.recolor) for g in a.grid]
                players = [player] + extra
                try:
                    captions = [short_title(x) for x in players]
                    grid_video(players, Path(a.video), captions, a.cols, a.width or 420, a.rate, start=a.start,
                               end=a.end)
                finally:
                    for x in extra:
                        x.close()
            elif other is not None:
                pair_video(player, other, Path(a.video), a.rate, width=a.width or 540,
                           captions=(a.params, a.compare), start=a.start, end=a.end)
            else:
                video(player, Path(a.video), a.rate, width=a.width, start=a.start, end=a.end)
            print('视频', a.video, f'{clock.perf_counter() - started:.1f} s')
    finally:
        player.close()
        if other is not None:
            other.close()


if __name__ == '__main__':
    main()
