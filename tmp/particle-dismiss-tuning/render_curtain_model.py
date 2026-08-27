# -*- coding: utf-8 -*-
"""渲染当前 Android 消失 Shader 的 60fps 桌面蓝本。

本脚本不维护第二份 GLSL。它直接从 ParticleDismissRenderer.kt 读取 canonical
Shader，把 GLSL ES 3.00 的版本头转换为桌面 GLSL 3.30 后交给 ModernGL 编译。
输出单次播放、多随机种子矩阵和多方向矩阵，专门检查：

1. 起点多数但非全部位于运动反向一侧；
2. 多个局部帷幔近同时出现；
3. 未触及区域保持完整；
4. 早期片层相干，后期才逐渐松散；
5. 尾流仍沿本次触点方向运动。
"""

from __future__ import annotations

import math
import re
import subprocess
import textwrap
from dataclasses import dataclass
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RENDERER = ROOT / "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissRenderer.kt"
OUT = HERE / "frames-curtain"

VIEW_W = 1280
VIEW_H = 900
DENSITY = 2.4
CELL_PX = 1.1 * DENSITY
DRIFT_PX = 210.0 * DENSITY
NOISE_SCALE_PX = 120.0 * DENSITY
TOTAL = 1.0
FPS = 60
PANEL_COLOR = (245 / 255.0, 246 / 255.0, 248 / 255.0)


def extract_kotlin_shader(source: str, name: str) -> str:
    pattern = rf"private val {re.escape(name)} = \"\"\"\r?\n(.*?)\r?\n\s*\"\"\"\.trimIndent\(\)"
    match = re.search(pattern, source, re.DOTALL)
    if not match:
        raise RuntimeError(f"找不到 Kotlin Shader：{name}")
    return textwrap.dedent(match.group(1))


def extract_dismiss_replicas(source: str) -> int:
    match = re.search(r"private const val DISMISS_REPLICAS = (\d+)", source)
    if not match:
        raise RuntimeError("找不到 Kotlin 消失动画副本数：DISMISS_REPLICAS")
    return int(match.group(1))


def desktop_glsl(source: str) -> str:
    source = source.replace("#version 300 es", "#version 330")
    return "\n".join(
        line for line in source.splitlines()
        if not line.strip().startswith("precision ")
    )


def load_canonical_shaders() -> tuple[str, str, str, str]:
    kotlin = RENDERER.read_text(encoding="utf-8")
    shared = extract_kotlin_shader(kotlin, "DISMISS_FIELD_GLSL")
    vertex = extract_kotlin_shader(kotlin, "DISMISS_VERTEX_SHADER").replace(
        "$DISMISS_FIELD_GLSL", shared
    )
    fragment = extract_kotlin_shader(kotlin, "DISMISS_FRAGMENT_SHADER")
    still_vertex = extract_kotlin_shader(kotlin, "STILL_VERTEX_SHADER")
    still_fragment = extract_kotlin_shader(kotlin, "DISMISS_STILL_FRAGMENT_SHADER").replace(
        "$DISMISS_FIELD_GLSL", shared
    )
    return tuple(map(desktop_glsl, (vertex, fragment, still_vertex, still_fragment)))


DISMISS_REPLICAS = extract_dismiss_replicas(RENDERER.read_text(encoding="utf-8"))


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def make_snapshot(width: int = 720, height: int = 420) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(
        (1, 1, width - 2, height - 2),
        radius=38,
        fill=(245, 246, 248, 255),
        outline=(225, 228, 234, 255),
        width=2,
    )
    draw.text((54, 42), "删除这条记事？", font=font(38, True), fill=(31, 35, 42, 255))
    draw.text(
        (54, 102),
        "删除后会进入回收站，可在 30 天内恢复。",
        font=font(25),
        fill=(92, 99, 110, 255),
    )
    draw.text((54, 146), "帷幔应从多个局部边缘近同时掀起", font=font(24), fill=(92, 99, 110, 255))

    colors = [(57, 128, 224), (41, 176, 143), (229, 115, 72), (132, 101, 202)]
    labels = ["方向跟随触点", "未触及区域完整", "邻域先相干", "随机受约束"]
    for index, (color, label) in enumerate(zip(colors, labels)):
        y = 210 + (index // 2) * 58
        x = 54 + (index % 2) * 315
        draw.rounded_rectangle((x, y, x + 282, y + 42), radius=17, fill=color + (255,))
        draw.text((x + 18, y + 7), label, font=font(20, True), fill=(255, 255, 255, 255))

    draw.text((470, 354), "取消", font=font(26, True), fill=(105, 112, 124, 255))
    draw.text((584, 354), "删除", font=font(26, True), fill=(221, 73, 65, 255))
    return image


def make_background() -> Image.Image:
    image = Image.new("RGB", (VIEW_W, VIEW_H), (26, 29, 36))
    draw = ImageDraw.Draw(image)
    for y in range(0, VIEW_H, 72):
        shade = 35 + (y // 72) % 2 * 5
        draw.rectangle((0, y, VIEW_W, y + 71), fill=(shade, shade + 3, shade + 9))
    draw.text((48, 44), "完事儿 · Dialog 下层界面", font=font(29, True), fill=(164, 171, 184))
    for index in range(8):
        y = 108 + index * 82
        draw.rounded_rectangle((48, y, 1232, y + 58), radius=18, fill=(49, 54, 65))
        draw.ellipse((72, y + 15, 100, y + 43), fill=(76, 125 + index * 5, 194))
        draw.rectangle((122, y + 17, 520 + index * 34, y + 26), fill=(91, 98, 112))
        draw.rectangle((122, y + 34, 420 + index * 21, y + 42), fill=(72, 78, 91))
    return image


@dataclass
class Scenario:
    name: str
    angle_degrees: float
    seed: int


class CurtainRenderer:
    def __init__(self) -> None:
        vertex, fragment, still_vertex, still_fragment = load_canonical_shaders()
        self.ctx = moderngl.create_context(standalone=True)
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA
        self.program = self.ctx.program(vertex_shader=vertex, fragment_shader=fragment)
        self.still_program = self.ctx.program(
            vertex_shader=still_vertex,
            fragment_shader=still_fragment,
        )
        self.vao = self.ctx.vertex_array(self.program, [])
        self.still_vao = self.ctx.vertex_array(self.still_program, [])
        self.fbo = self.ctx.simple_framebuffer((VIEW_W, VIEW_H), components=4)
        self.snapshot = make_snapshot()
        self.background = make_background()
        self.texture = self.ctx.texture(
            self.snapshot.size,
            4,
            self.snapshot.tobytes(),
        )
        self.texture.filter = moderngl.LINEAR, moderngl.LINEAR
        self.texture.repeat_x = False
        self.texture.repeat_y = False
        self.texture.use(0)
        self.cols = math.ceil(self.snapshot.width / CELL_PX)
        self.rows = math.ceil(self.snapshot.height / CELL_PX)
        self.particle_vertices = self.cols * self.rows * DISMISS_REPLICAS
        self.origin = (
            (VIEW_W - self.snapshot.width) / 2.0,
            (VIEW_H - self.snapshot.height) / 2.0,
        )

    @staticmethod
    def set_uniform(program: moderngl.Program, name: str, value: object) -> None:
        if name in program:
            program[name].value = value

    def configure(self, scenario: Scenario) -> None:
        radians = math.radians(scenario.angle_degrees)
        direction = (math.cos(radians), math.sin(radians))
        noise_seed = (
            float((scenario.seed * 37) % 997) / 1.7,
            float((scenario.seed * 83 + 19) % 991) / 1.9,
        )
        hash_seed = (scenario.seed * 2654435761) & 0x7FFFFFFF
        common = {
            "uSnapshot": 0,
            "uViewportPx": (float(VIEW_W), float(VIEW_H)),
            "uOriginPx": self.origin,
            "uCellPx": CELL_PX,
            "uGrid": (self.cols, self.rows),
            "uDriftPx": DRIFT_PX,
            "uNoiseScalePx": NOISE_SCALE_PX,
            "uMaxPointPx": 256.0,
            "uNoiseSeed": noise_seed,
            "uHashSeed": hash_seed,
            "uPanelColor": PANEL_COLOR,
            "uReplicas": DISMISS_REPLICAS,
            "uSweepDir": direction,
        }
        for name, value in common.items():
            self.set_uniform(self.program, name, value)
            self.set_uniform(self.still_program, name, value)

    def render(self, scenario: Scenario, time_seconds: float) -> Image.Image:
        self.configure(scenario)
        self.program["uTime"].value = time_seconds
        self.still_program["uTime"].value = time_seconds
        self.fbo.use()
        self.fbo.clear(0.0, 0.0, 0.0, 0.0)
        self.still_vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        self.vao.render(moderngl.POINTS, vertices=self.particle_vertices)
        rgba = np.frombuffer(
            self.fbo.read(components=4, alignment=1),
            dtype=np.uint8,
        ).reshape(VIEW_H, VIEW_W, 4)
        rgba = np.flipud(rgba).copy()

        # Shader 输出预乘 alpha；Pillow 合成前恢复为 straight alpha。
        alpha = rgba[:, :, 3:4].astype(np.float32)
        rgb = rgba[:, :, :3].astype(np.float32)
        nonzero = alpha > 0
        rgb = np.where(nonzero, np.minimum(rgb * 255.0 / np.maximum(alpha, 1.0), 255.0), 0.0)
        straight = np.concatenate((rgb.astype(np.uint8), rgba[:, :, 3:4]), axis=2)
        overlay = Image.fromarray(straight, "RGBA")
        return Image.alpha_composite(self.background.convert("RGBA"), overlay).convert("RGB")

    def render_particle_rgba(self, scenario: Scenario, time_seconds: float) -> np.ndarray:
        """只渲染粒子层，返回 OpenGL 预乘 alpha RGBA，供确定性指标检查。"""
        self.configure(scenario)
        self.program["uTime"].value = time_seconds
        self.fbo.use()
        self.fbo.clear(0.0, 0.0, 0.0, 0.0)
        self.vao.render(moderngl.POINTS, vertices=self.particle_vertices)
        rgba = np.frombuffer(
            self.fbo.read(components=4, alignment=1),
            dtype=np.uint8,
        ).reshape(VIEW_H, VIEW_W, 4)
        return np.flipud(rgba).copy()


def save_contact_sheet(renderer: CurtainRenderer, scenario: Scenario) -> None:
    times = [0.00, 0.08, 0.16, 0.24, 0.36, 0.50, 0.68, 0.84, 1.00]
    cell_w, cell_h = 480, 356
    sheet = Image.new("RGB", (cell_w * 3, cell_h * 3), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    for index, time_seconds in enumerate(times):
        frame = renderer.render(scenario, time_seconds)
        frame.thumbnail((cell_w, cell_h - 28), Image.Resampling.LANCZOS)
        x = (index % 3) * cell_w
        y = (index // 3) * cell_h
        sheet.paste(frame, (x, y + 28))
        draw.text((x + 10, y + 4), f"t={time_seconds:.2f}s", font=font(18), fill=(190, 196, 208))
    sheet.save(OUT / f"contact-{scenario.name}.png")


def render_video_frames(
    renderer: CurtainRenderer,
    scenarios: list[Scenario],
    output_dir: Path,
    columns: int,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = math.ceil(len(scenarios) / columns)
    thumb_w = 640
    thumb_h = 450
    for frame_index in range(FPS + 1):
        time_seconds = frame_index / FPS
        canvas = Image.new("RGB", (columns * thumb_w, rows * thumb_h), (17, 19, 24))
        draw = ImageDraw.Draw(canvas)
        for index, scenario in enumerate(scenarios):
            frame = renderer.render(scenario, time_seconds).resize(
                (thumb_w, thumb_h),
                Image.Resampling.LANCZOS,
            )
            x = (index % columns) * thumb_w
            y = (index // columns) * thumb_h
            canvas.paste(frame, (x, y))
            draw.rectangle((x, y, x + 280, y + 32), fill=(17, 19, 24))
            draw.text(
                (x + 8, y + 5),
                f"{scenario.name}  seed={scenario.seed}",
                font=font(17),
                fill=(220, 224, 232),
            )
        canvas.save(output_dir / f"frame-{frame_index:03d}.png")


def encode_video(frame_dir: Path, output: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-framerate",
            str(FPS),
            "-i",
            str(frame_dir / "frame-%03d.png"),
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-crf",
            "18",
            str(output),
        ],
        check=True,
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    renderer = CurtainRenderer()

    primary = Scenario("up", -90.0, 42)
    save_contact_sheet(renderer, primary)

    primary_frames = OUT / "primary-frames"
    render_video_frames(renderer, [primary], primary_frames, columns=1)
    encode_video(primary_frames, OUT / "primary-up.mp4")

    seed_scenarios = [
        Scenario(f"up-{seed}", -90.0, seed)
        for seed in (7, 19, 31, 42, 58, 73, 89, 101, 127, 149, 173, 211)
    ]
    seed_frames = OUT / "seed-matrix-frames"
    render_video_frames(renderer, seed_scenarios, seed_frames, columns=4)
    encode_video(seed_frames, OUT / "seed-matrix.mp4")

    direction_scenarios = [
        Scenario("up", -90.0, 42),
        Scenario("down", 90.0, 42),
        Scenario("left", 180.0, 42),
        Scenario("right-up", -35.0, 42),
    ]
    direction_frames = OUT / "direction-matrix-frames"
    render_video_frames(renderer, direction_scenarios, direction_frames, columns=2)
    encode_video(direction_frames, OUT / "direction-matrix.mp4")

    print(f"canonical GLSL 编译成功；输出：{OUT}")


if __name__ == "__main__":
    main()
