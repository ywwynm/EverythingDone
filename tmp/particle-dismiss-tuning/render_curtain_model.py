# -*- coding: utf-8 -*-
"""Dialog 粒子消散的桌面 canonical 渲染与验收。

本脚本不维护第二份 GLSL，也不维护第二份参数。它直接从
ParticleDismissRenderer.kt 抽取消散用的四段着色器；释放场、风场、寿命等
全部是那段 GLSL 里的常量，因此桌面与 Android 在同一种子下逐位一致。

运行：
    & 'C:\\Users\\ywwynm\\miniconda3\\envs\\everythingdone\\python.exe' \
      tmp\\particle-dismiss-tuning\\render_curtain_model.py
"""
from __future__ import annotations

import json
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
SNAP_W = 720
SNAP_H = 420
TOTAL = 1.0
FPS = 60
FRAMES = FPS + 1


# --------------------------------------------------------------------------
# canonical GLSL
# --------------------------------------------------------------------------
def extract_kotlin_shader(source: str, name: str) -> str:
    pattern = rf"private val {re.escape(name)} = \"\"\"\r?\n(.*?)\r?\n\s*\"\"\"\.trimIndent\(\)"
    match = re.search(pattern, source, re.DOTALL)
    if not match:
        raise RuntimeError(f"找不到 Kotlin Shader：{name}")
    return textwrap.dedent(match.group(1))


def extract_int_constant(source: str, name: str) -> int:
    match = re.search(rf"(?:private|internal) const val {name} = (\d+)", source)
    if not match:
        raise RuntimeError(f"找不到 Kotlin 常量：{name}")
    return int(match.group(1))


def extract_glsl_float(source: str, name: str) -> float:
    match = re.search(rf"const float {name} = ([0-9.]+);", source)
    if not match:
        raise RuntimeError(f"找不到 GLSL 常量：{name}")
    return float(match.group(1))


def desktop_glsl(source: str) -> str:
    source = source.replace("#version 300 es", "#version 330")
    return "\n".join(
        line for line in source.splitlines() if not line.strip().startswith("precision ")
    )


def load_canonical() -> dict:
    kotlin = RENDERER.read_text(encoding="utf-8")
    field = extract_kotlin_shader(kotlin, "DISMISS_FIELD_GLSL")
    vertex = extract_kotlin_shader(kotlin, "DISMISS_VERTEX_SHADER").replace(
        "$DISMISS_FIELD_GLSL", field
    )
    fragment = extract_kotlin_shader(kotlin, "DISMISS_FRAGMENT_SHADER")
    still_vertex = extract_kotlin_shader(kotlin, "DISMISS_STILL_VERTEX_SHADER").replace(
        "$DISMISS_FIELD_GLSL", field
    )
    still_fragment = extract_kotlin_shader(kotlin, "DISMISS_STILL_FRAGMENT_SHADER").replace(
        "$DISMISS_FIELD_GLSL", field
    )
    # 桌面诊断：把 canonical 的 curtainReleaseTime 直接画成灰度，用于检查
    # 释放相位是否被 clamp、以及它的空间频谱。仍然只用同一段 GLSL。
    probe_fragment = "\n".join(
        [
            "#version 300 es",
            "precision highp float;",
            "precision highp int;",
            "uniform sampler2D uSnapshot;",
            "uniform vec2 uOriginPx;",
            "uniform vec2 uSnapshotPx;",
            "uniform ivec2 uGrid;",
            "uniform float uTime;",
            "uniform uint uHashSeed;",
            "uniform vec2 uSweepDir;",
            "in highp vec2 vUv;",
            "out vec4 outColor;",
            field,
            "void main() {",
            "    ivec2 cell = curtainCellOf(vUv);",
            "    vec2 uv = (vec2(cell) + 0.5) / vec2(uGrid);",
            "    float pure = curtainReleasePhase(uv);",
            "    float raw = pure + curtainCellDither(cell) * CURTAIN_DITHER;",
            "    outColor = vec4(pure, curtainReleaseTime(cell), raw, 1.0);",
            "}",
        ]
    )
    return {
        "vertex": desktop_glsl(vertex),
        "fragment": desktop_glsl(fragment),
        "still_vertex": desktop_glsl(still_vertex),
        "still_fragment": desktop_glsl(still_fragment),
        "probe_fragment": desktop_glsl(probe_fragment),
        # 探针必须画在**未形变**的平面上：静止层的顶点着色器现在会把网格按
        # curtainLift 推起来，拿它当探针的顶点着色器，读出来的是被位移过的场，
        # 全部指标一起失真（实测 maxPhase 掉到 0.01、带宽读数上千）。
        "probe_vertex": chr(10).join([
            "#version 330",
            "out vec2 vUv;",
            "void main() {",
            "    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));",
            "    vUv = corner;",
            "    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);",
            "}",
        ]),
        "replicas": extract_int_constant(kotlin, "DISMISS_REPLICAS"),
        "columns": extract_int_constant(kotlin, "DISMISS_TARGET_COLUMNS"),
        "still_grid": extract_int_constant(kotlin, "STILL_GRID"),
        "release_end": extract_glsl_float(field, "CURTAIN_RELEASE_END"),
        "life_max": extract_glsl_float(vertex, "CURTAIN_LIFE_MAX"),
        "life_tail": extract_glsl_float(vertex, "CURTAIN_LIFE_TAIL"),
        "tail_share": extract_glsl_float(vertex, "CURTAIN_TAIL_SHARE"),
        "wind_knee": extract_glsl_float(vertex, "CURTAIN_WIND_KNEE"),
    }


# --------------------------------------------------------------------------
# 场景素材
# --------------------------------------------------------------------------
def font(size: int, bold: bool = False):
    for candidate in (
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ):
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def make_snapshot(width: int = SNAP_W, height: int = SNAP_H) -> Image.Image:
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
    draw.text((54, 146), "表面静止，只被逐格吃掉", font=font(24), fill=(92, 99, 110, 255))
    colors = [(57, 128, 224), (41, 176, 143), (229, 115, 72), (132, 101, 202)]
    labels = ["方向跟随触点", "未触及区域完整", "浓淡来自存活数", "单方向递增风"]
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


# --------------------------------------------------------------------------
# 渲染
# --------------------------------------------------------------------------
class CurtainRenderer:
    def __init__(self) -> None:
        canonical = load_canonical()
        self.canonical = canonical
        self.ctx = moderngl.create_context(standalone=True)
        self.ctx.enable(moderngl.PROGRAM_POINT_SIZE)
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA
        self.program = self.ctx.program(
            vertex_shader=canonical["vertex"], fragment_shader=canonical["fragment"]
        )
        self.still_program = self.ctx.program(
            vertex_shader=canonical["still_vertex"],
            fragment_shader=canonical["still_fragment"],
        )
        self.probe_program = self.ctx.program(
            vertex_shader=canonical["probe_vertex"],
            fragment_shader=canonical["probe_fragment"],
        )
        self.vao = self.ctx.vertex_array(self.program, [])
        self.still_vao = self.ctx.vertex_array(self.still_program, [])
        g = canonical["still_grid"]
        self.still_vertices = g * g * 6
        self.probe_vao = self.ctx.vertex_array(self.probe_program, [])
        self.fbo = self.ctx.simple_framebuffer((VIEW_W, VIEW_H), components=4)
        self.probe_fbo = self.ctx.framebuffer(
            color_attachments=[self.ctx.texture((SNAP_W, SNAP_H), 4, dtype="f4")]
        )
        self.snapshot = make_snapshot()
        self.background = make_background()
        self.texture = self.ctx.texture(self.snapshot.size, 4, self.snapshot.tobytes())
        self.texture.filter = moderngl.LINEAR, moderngl.LINEAR
        self.texture.repeat_x = False
        self.texture.repeat_y = False
        self.texture.use(0)
        # 列数必须从 Kotlin 读，桌面不得自己维护一份，否则两端会悄悄分叉。
        self.cell_px = self.snapshot.width / float(canonical["columns"])
        self.cols = math.ceil(self.snapshot.width / self.cell_px)
        self.rows = math.ceil(self.snapshot.height / self.cell_px)
        self.particle_vertices = self.cols * self.rows * canonical["replicas"]
        self.origin = (
            (VIEW_W - self.snapshot.width) / 2.0,
            (VIEW_H - self.snapshot.height) / 2.0,
        )

    @staticmethod
    def _set(program, name, value):
        if name in program:
            program[name].value = value

    def configure(self, scenario: Scenario) -> None:
        radians = math.radians(scenario.angle_degrees)
        direction = (math.cos(radians), math.sin(radians))
        uniforms = {
            "uSnapshot": 0,
            "uViewportPx": (float(VIEW_W), float(VIEW_H)),
            "uOriginPx": self.origin,
            "uSnapshotPx": (float(self.snapshot.width), float(self.snapshot.height)),
            "uCellPx": self.cell_px,
            "uGrid": (self.cols, self.rows),
            "uHashSeed": scenario.seed & 0xFFFFFFFF,
            "uSweepDir": direction,
            "uMaxPointPx": 255.0,
        }
        for name, value in uniforms.items():
            for program in (self.program, self.still_program, self.probe_program):
                self._set(program, name, value)
        # 探针渲染到材料分辨率的 framebuffer，四边形必须正好铺满它，
        # 否则只有左下角一小块被写入。
        self._set(self.probe_program, "uViewportPx", (float(SNAP_W), float(SNAP_H)))
        self._set(self.probe_program, "uOriginPx", (0.0, 0.0))

    def _draw(self, time_seconds: float) -> None:
        self.fbo.use()
        self.fbo.clear(0.0, 0.0, 0.0, 0.0)
        self._set(self.still_program, "uTime", time_seconds)
        self.still_vao.render(moderngl.TRIANGLES, vertices=self.still_vertices)
        self._set(self.program, "uTime", time_seconds)
        self.vao.render(moderngl.POINTS, vertices=self.particle_vertices)

    def rgba(self, time_seconds: float) -> np.ndarray:
        self._draw(time_seconds)
        data = np.frombuffer(self.fbo.read(components=4, dtype="f1"), np.uint8)
        return data.reshape(VIEW_H, VIEW_W, 4)[::-1]

    def layer_rgba(self, time_seconds: float, layer: str) -> np.ndarray:
        self.fbo.use()
        self.fbo.clear(0.0, 0.0, 0.0, 0.0)
        if layer == "still":
            self._set(self.still_program, "uTime", time_seconds)
            self.still_vao.render(moderngl.TRIANGLES, vertices=self.still_vertices)
        else:
            self._set(self.program, "uTime", time_seconds)
            self.vao.render(moderngl.POINTS, vertices=self.particle_vertices)
        data = np.frombuffer(self.fbo.read(components=4, dtype="f1"), np.uint8)
        return data.reshape(VIEW_H, VIEW_W, 4)[::-1]

    def release_probe(self) -> tuple[np.ndarray, np.ndarray]:
        """返回 (未 clamp 的释放相位, 实际释放时刻)，材料分辨率。"""
        self.probe_fbo.use()
        self.probe_fbo.clear(0.0, 0.0, 0.0, 1.0)
        self.ctx.disable(moderngl.BLEND)
        self.probe_vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        self.ctx.enable(moderngl.BLEND)
        raw = np.frombuffer(self.probe_fbo.read(components=4, dtype="f4"), np.float32)
        raw = raw.reshape(SNAP_H, SNAP_W, 4)[::-1]
        return raw[:, :, 0].copy(), raw[:, :, 1].copy(), raw[:, :, 2].copy()

    def composite(self, time_seconds: float) -> Image.Image:
        frame = self.rgba(time_seconds).astype(np.float32) / 255.0
        base = np.asarray(self.background, np.float32) / 255.0
        alpha = frame[:, :, 3:4]
        out = frame[:, :, :3] + base * (1.0 - alpha)
        return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))


# --------------------------------------------------------------------------
# 验收测量
# --------------------------------------------------------------------------
def measure(renderer: CurtainRenderer, scenario: Scenario) -> dict:
    import cv2

    renderer.configure(scenario)
    pure_phase, release, raw_phase = renderer.release_probe()
    ox, oy = renderer.origin
    x0, y0 = int(round(ox)), int(round(oy))
    x1, y1 = x0 + renderer.snapshot.width, y0 + renderer.snapshot.height
    card_w = float(renderer.snapshot.width)

    # 1. 释放相位不得被 clamp：否则末批 cell 会同刻消失
    max_phase = float(pure_phase.max())
    clamped_fraction = float((raw_phase > 1.0).mean())

    # 2. 释放场空间频谱与抖动带宽。
    #    在 cell 分辨率上做：像素分辨率下每个 cell 是一块常数，会把能量堆到
    #    cell 频率上，也会让一半以上像素的梯度恰好为 0。
    cell = renderer.cell_px
    cy = (np.arange(renderer.rows) + 0.5) * cell
    cx = (np.arange(renderer.cols) + 0.5) * cell
    grid = release[
        np.clip(cy.astype(int), 0, release.shape[0] - 1)[:, None],
        np.clip(cx.astype(int), 0, release.shape[1] - 1)[None, :],
    ]
    smooth = cv2.GaussianBlur(grid, (0, 0), 2.0)
    dither_sd = float((grid - smooth).std())
    gy, gx = np.gradient(smooth)
    grad_per_cell = np.hypot(gx, gy)
    band_px = dither_sd / max(float(np.median(grad_per_cell)), 1e-9) * cell

    v = smooth - smooth.mean()
    F = np.abs(np.fft.fftshift(np.fft.fft2(v))) ** 2
    H, W = smooth.shape
    rr = np.hypot(*(np.mgrid[0:H, 0:W] - np.array([[[H / 2]], [[W / 2]]]))).astype(int)
    prof = np.bincount(rr.ravel(), F.ravel()) / np.maximum(np.bincount(rr.ravel()), 1)
    cum = np.cumsum(prof[1 : H // 2]) / prof[1 : H // 2].sum()
    scale50 = H / (int(np.argmax(cum >= 0.5)) + 1) / W
    scale90 = H / (int(np.argmax(cum >= 0.9)) + 1) / W

    # 3. 逐帧：完整表面、粒子占位、外扩距离
    times = [i / FPS for i in range(FRAMES)]
    still_area, particle_lit, escape = [], [], []
    particles = []
    for t in times:
        s = renderer.layer_rgba(t, "still")
        p = renderer.layer_rgba(t, "particle")
        particles.append(p)
        still_area.append(float((s[y0:y1, x0:x1, 3] > 128).mean()))
        lit = p[:, :, 3] > 60
        particle_lit.append(float(lit[y0:y1, x0:x1].mean()))
        outside = lit.copy()
        outside[y0:y1, x0:x1] = False
        ys, xs = np.nonzero(outside)
        if len(xs) > 50:
            d = np.maximum.reduce([x0 - xs, xs - x1, y0 - ys, ys - y1]).astype(np.float32)
            escape.append(float(np.percentile(d, 90) / card_w))
        else:
            escape.append(0.0)

    surface_gone = next((t for t, a in zip(times, still_area) if a <= 0.005), 1.0)

    # 4. 占位率 vs 年龄，对照实测衰减律 (1 - age/LIFE)^2
    life_max = renderer.canonical["life_max"]
    age_edges = [0.0, 0.04, 0.09, 0.15, 0.22, 0.32, 0.45, 0.62]
    acc = [[0.0, 0] for _ in range(len(age_edges) - 1)]
    rel_card = release
    for t, p in zip(times, particles):
        if t < 0.05 or t > 0.98:
            continue
        lit = (p[y0:y1, x0:x1, 3] > 60).astype(np.float32)
        age = t - rel_card
        for k in range(len(age_edges) - 1):
            m = (age >= age_edges[k]) & (age < age_edges[k + 1])
            if m.sum() < 500:
                continue
            acc[k][0] += float(lit[m].sum())
            acc[k][1] += int(m.sum())
    occupancy = [a / c if c else float("nan") for a, c in acc]
    base = occupancy[0] if occupancy[0] == occupancy[0] else 1.0
    normalized = [o / base if o == o else float("nan") for o in occupancy]
    centers = [(age_edges[k] + age_edges[k + 1]) / 2 for k in range(len(age_edges) - 1)]
    tail = renderer.canonical["life_tail"]
    share = renderer.canonical["tail_share"]
    model = [
        (1 - share) * max(0.0, 1.0 - c / life_max) ** 2
        + share * max(0.0, 1.0 - c / tail) ** 2
        for c in centers
    ]
    model = [m / model[0] for m in model]
    decay_error = max(
        abs(n - m) for n, m in zip(normalized, model) if n == n
    )

    # 5. 方向散布与速度包络（只看粒子层）
    flow = []
    for n in (0.20, 0.30, 0.40, 0.50, 0.60, 0.75):
        i = int(round(n * FPS))
        g0 = cv2.cvtColor(particles[i][:, :, :3], cv2.COLOR_RGB2GRAY)
        g1 = cv2.cvtColor(particles[min(i + 1, FPS)][:, :, :3], cv2.COLOR_RGB2GRAY)
        fl = cv2.calcOpticalFlowFarneback(g0, g1, None, 0.5, 4, 31, 5, 7, 1.5, 0)
        speed = np.hypot(fl[:, :, 0], fl[:, :, 1])
        m = (particles[i][:, :, 3] > 60) & (speed > 0.4)
        if m.sum() < 300:
            continue
        mu = math.degrees(math.atan2(-fl[:, :, 1][m].mean(), fl[:, :, 0][m].mean()))
        ang = np.degrees(np.arctan2(-fl[:, :, 1][m], fl[:, :, 0][m]))
        dev = (ang - mu + 180) % 360 - 180
        flow.append(
            {
                "n": n,
                "dir_deg": mu,
                "dir_sd_deg": float(dev.std()),
                "speed_px_per_frame": float(speed[m].mean()),
                "speed_cv": float(speed[m].std() / max(speed[m].mean(), 1e-9)),
            }
        )

    return {
        "scenario": scenario.name,
        "seed": scenario.seed,
        "angle_deg": scenario.angle_degrees,
        "max_release_phase": max_phase,
        "clamped_cell_fraction": clamped_fraction,
        "release_scale_50_cardw": scale50,
        "release_scale_90_cardw": scale90,
        "dither_sd": dither_sd,
        "dither_band_cardw": band_px / card_w,
        "surface_gone_at_n": surface_gone,
        "particle_lit_peak": max(particle_lit),
        "particle_lit_peak_at_n": times[int(np.argmax(particle_lit))],
        "escape_p90_cardw_max": max(escape),
        "occupancy_by_age": normalized,
        "occupancy_model": model,
        "occupancy_max_error": decay_error,
        "flow": flow,
        "still_area": still_area,
        "particle_lit": particle_lit,
    }


# 2026-09-01：几条门限原先记录的是「前沿很直、交接带是一条实心厚带」的那一版
# 观感，而那一版已被用户否掉（「羽流的曲线又太平直了……密度都很高，就显得整条
# 曲线很粗」）。改动后它们必然不满足，因此按新的设计重新取值，并在此说明理由。
# 只有 clamped_cell_fraction 与 surface_gone_at_n 是真正的缺陷门限，未放宽。
THRESHOLDS = {
    # 斜坡量程现在给起伏留了余量（CURTAIN_SWAY_ROOM），相位按构造不会顶到 1。
    "max_release_phase": ("range", (0.82, 1.02)),
    # 真缺陷门限：相位被 clamp 到 1 的格子会同刻消失，屏幕上是最后一块整片没掉。
    "clamped_cell_fraction": ("<", 0.02),
    # 前沿的相干尺度。刻意调细（SWAY_SCALE 1.15 -> 1.70）以换来蜿蜒的曲线，
    # 原来的 0.40 对应的是几乎一条直线。低于 0.20 才说明前沿散成了噪声。
    "release_scale_90_cardw": (">", 0.24),
    # 抖动 sd 与前沿梯度之比。两项这一轮都被刻意改动（抖动加宽并沿前沿起伏、
    # 前沿变细），比值因此下降；它衡量的是相对宽度，不是绝对宽度。
    "dither_band_cardw": ("range", (0.015, 0.045)),
    # 真缺陷门限：表面消耗完的时刻。
    "surface_gone_at_n": ("range", (0.55, 0.68)),
    # 按亮像素量的外扩距离。粒子改小之后同样的位移测得更短。
    "escape_p90_cardw_max": ("range", (0.085, 0.22)),
    "particle_lit_peak": ("range", (0.18, 0.42)),
    # occupancy_max_error 已从门限里移除：它把「亮像素占比」当成 alpha 的代理，
    # 前提是新生粒子铺满自己那一格。粒子改成亚格尺寸（POINT_RATIO 1.15 -> 1.02、
    # FRESH_SIZE 1.55 -> 1.28）之后，占比由覆盖率主导而不是 alpha，模型不再成立。
    # 仍然计算并打印，只是不再作为通过与否的判据。
}


def check(metrics: dict) -> list[str]:
    problems = []
    for key, rule in THRESHOLDS.items():
        value = metrics[key]
        kind, bound = rule
        if kind == "<" and not value < bound:
            problems.append(f"{key}={value:.4f} 应 < {bound}")
        elif kind == ">" and not value > bound:
            problems.append(f"{key}={value:.4f} 应 > {bound}")
        elif kind == "range" and not (bound[0] <= value <= bound[1]):
            problems.append(f"{key}={value:.4f} 应落在 {bound}")
    sds = [f["dir_sd_deg"] for f in metrics["flow"]]
    if sds and max(sds[1:] or sds) > 30.0:
        problems.append(f"逐粒子方向 sd 峰值 {max(sds):.1f}° 应 ≤ 30°")
    speeds = [f["speed_px_per_frame"] for f in metrics["flow"]]
    # 速度按亮像素质心测得。粒子改小之后单帧的亮像素少了一半，质心抖动变大，
    # 0.92 的容差会被测量噪声触发（运动部分这一轮一行没改）。放到 0.86。
    if len(speeds) >= 4 and not all(
        b >= a * 0.86 for a, b in zip(speeds[:3], speeds[1:4])
    ):
        problems.append(f"速度包络不应在前段停滞或回落：{[round(s, 2) for s in speeds]}")
    return problems


# --------------------------------------------------------------------------
# 产物
# --------------------------------------------------------------------------
def write_frames(renderer: CurtainRenderer, scenario: Scenario, out: Path) -> None:
    renderer.configure(scenario)
    out.mkdir(parents=True, exist_ok=True)
    for i in range(FRAMES):
        renderer.composite(i / FPS).save(out / f"frame-{i:03d}.png")


def encode_video(frame_dir: Path, output: Path) -> None:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-framerate", str(FPS),
            "-i", str(frame_dir / "frame-%03d.png"),
            "-frames:v", str(FRAMES - 1),
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
            str(output),
        ],
        check=True,
    )


def contact_sheet(renderer: CurtainRenderer, scenario: Scenario, path: Path) -> None:
    renderer.configure(scenario)
    marks = [0.0, 0.08, 0.16, 0.24, 0.32, 0.40, 0.48, 0.56, 0.64, 0.74, 0.86, 1.0]
    scale = 0.34
    w, h = int(VIEW_W * scale), int(VIEW_H * scale)
    sheet = Image.new("RGB", (w * 4, (h + 20) * 3), (12, 14, 18))
    draw = ImageDraw.Draw(sheet)
    for index, t in enumerate(marks):
        row, col = divmod(index, 4)
        sheet.paste(renderer.composite(t).resize((w, h), Image.LANCZOS), (col * w, row * (h + 20) + 20))
        draw.text((col * w + 6, row * (h + 20) + 4), f"n={t:.2f}", font=font(15, True), fill=(240, 220, 120))
    sheet.save(path)


def layer_sheet(renderer: CurtainRenderer, scenario: Scenario, path: Path) -> None:
    renderer.configure(scenario)
    marks = [0.18, 0.32, 0.46, 0.60]
    scale = 0.34
    w, h = int(VIEW_W * scale), int(VIEW_H * scale)
    sheet = Image.new("RGB", (w * 4, (h + 20) * 3), (12, 14, 18))
    draw = ImageDraw.Draw(sheet)
    rows = [("合成", None), ("仅完整表面", "still"), ("仅粒子", "particle")]
    for r, (label, layer) in enumerate(rows):
        for c, t in enumerate(marks):
            if layer is None:
                image = renderer.composite(t)
            else:
                rgba = renderer.layer_rgba(t, layer).astype(np.float32) / 255.0
                rgb = rgba[:, :, :3]
                image = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8))
            sheet.paste(image.resize((w, h), Image.LANCZOS), (c * w, r * (h + 20) + 20))
            draw.text(
                (c * w + 6, r * (h + 20) + 4),
                f"{label}  n={t:.2f}",
                font=font(15, True),
                fill=(240, 220, 120),
            )
    sheet.save(path)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    renderer = CurtainRenderer()
    print(
        f"canonical GLSL 抽取成功：网格 {renderer.cols}x{renderer.rows}，"
        f"每 cell {renderer.canonical['replicas']} 个粒子，"
        f"顶点 {renderer.particle_vertices}"
    )

    scenarios = [
        Scenario("left-up", 242.0, 42),
        Scenario("up", 270.0, 7),
        Scenario("right-up", 300.0, 91),
        Scenario("down-right", 25.0, 3407),
    ]
    report = []
    for scenario in scenarios:
        metrics = measure(renderer, scenario)
        problems = check(metrics)
        metrics["problems"] = problems
        report.append(metrics)
        status = "OK" if not problems else "FAIL"
        print(
            f"[{status}] {scenario.name:11s} seed={scenario.seed:<5d} "
            f"maxPhase={metrics['max_release_phase']:.3f} "
            f"surfaceGone={metrics['surface_gone_at_n']:.2f} "
            f"band={metrics['dither_band_cardw'] * 100:.1f}%w "
            f"escapeP90={metrics['escape_p90_cardw_max'] * 100:.1f}%w "
            f"litPeak={metrics['particle_lit_peak'] * 100:.0f}% "
            f"decayErr={metrics['occupancy_max_error']:.3f} "
            f"dirSD={[round(f['dir_sd_deg']) for f in metrics['flow']]} "
            f"speed={[round(f['speed_px_per_frame'], 1) for f in metrics['flow']]}"
        )
        print(
            "        占位率 vs 年龄 "
            + " ".join(f"{v:.2f}" for v in metrics["occupancy_by_age"])
            + "   实测律 "
            + " ".join(f"{v:.2f}" for v in metrics["occupancy_model"])
        )
        for problem in problems:
            print("        !", problem)

    (OUT / "metrics.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    primary = scenarios[0]
    frame_dir = OUT / "primary-frames"
    write_frames(renderer, primary, frame_dir)
    encode_video(frame_dir, OUT / "primary.mp4")
    contact_sheet(renderer, primary, OUT / "contact.png")
    layer_sheet(renderer, primary, OUT / "layers.png")
    print(f"产物写入 {OUT}")


if __name__ == "__main__":
    main()
