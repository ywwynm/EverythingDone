# -*- coding: utf-8 -*-
"""Dialog 消散的"连续薄片"原型（2026-09-02 grill 定案）。

整张快照是一张三角网格。前沿（释放时刻场）之后的顶点被一个三维平滑流场逐帧平流带走：
风 + 抬起 + 两个尺度的 curl noise，噪声幅度随离开时间一直增长（远处撕开）。逐像素按
材料坐标上连贯的噪声（粗斑块 + 细颗粒）做阈值溶解，硬切边；释放前按同一张噪声成片变暗。
法向从变形后的网格算，一盏方向光，双面异色。

状态存在网格顶点上（numpy），每帧积分；composite(n) 请求更早的时刻时从头重算。
输出：frames-sheet/frame-%03d.png、sheet.mp4、compare-sheet.mp4（参考 | 薄片）、
compare-sheet-slow.mp4、sheet-contact.png（八个时刻对照表）。
"""
from __future__ import annotations

import glob
import math
import subprocess
import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
TUNING = HERE.parent
sys.path.insert(0, str(TUNING))
import release_time_map as RT  # noqa: E402

SP = RT.SP
CARD = RT.CARD                      # 参考画面里卡片的位置 (45, 95, 525, 567)
VIEW_W, VIEW_H = 580, 660
FPS = 60
OUT = TUNING / "frames-sheet"

# ---------------------------------------------------------------- 参数 ----
WIND_ANGLE_DEG = 235.0      # 图像坐标（y 向下），-125°：左上
GRID_CELL_PX = 5.0          # 网格步长
SUBSTEPS = 2                # 每帧积分次数

# 释放场（与 shader-draft 里的常量一致）
FRONT_ONSET = -0.20
FRONT_SWEEP = 0.60
FRONT_LAG = 0.26
FRONT_LAG_CENTRE = 0.10
FRONT_LAG_WIDTH = 0.36
FRONT_SWAY = 0.05
FRONT_SWAY_FREQ = 1.6
# 顺风角不再另起一片（用户 2026-09-02：不要控件内容中间突然破碎；那块孤岛的前沿向内推、
# 材料却向外飞，被拉成放大镜）。只有一条前沿。

# 流场（像素 / 单位时间）
WIND_SPEED = 460.0          # 满速。前沿约 850 px/单位时间，材料比它慢约一半，于是在前沿后面堆成约两倍密的带；
                            # 700 时压缩五倍以上，网格翻折成一条细带（2026-09-02 实测三角形面积 1.6-4.4 px²）
WIND_RAMP = 0.02            # 离开前沿多久升到满速（近乎立刻：撕开，不是拉伸）
# 翻起然后飞走（用户 2026-09-02："翻到一定程度，就直接让它往左上方飞逝而去"）。
# 折痕后面的材料先绕半径 R 的圆弧弯到 CURL_MAX_ANGLE，然后脱离：保持脱离时的位置关系，
# 沿风向以 FLY_SPEED 飞走、同时缓慢升高，在飞的过程中碎成尘。不翻面、不平板。
CURL_RADIUS = 150.0         # 主前沿的弯曲半径（px）
CURL_MAX_ANGLE = 0.8        # 弯到这个角度（弧度，约 46°）就脱离
# 左上角不再另起折痕（第二块翻板与主前沿交界处会把最后一片拉得稀烂、角尖也会激凸），
# 改为柔和的隆起：角附近的材料只在 z 上抬起，面内不动，等主前沿扫到再照常脱离。
CORNER_LIFT_H = 60.0        # 角尖抬起的高度（px）
CORNER_LIFT_R = 230.0       # 隆起的半径（px），到这里落回平面
CORNER_LIFT_T = 0.30        # 隆起在这段时间里升到满幅
# 左上角的粒子化（用户 2026-09-02："左上角现在只有拉伸、没有粒子化"）：不另起折痕，只有一条
# 从角尖向内推进的溶解前沿，材料在原地（隆起的位置）按细颗粒化成尘，尘沿风向飞走。
CORNER_DUST_START = 0.02    # 角尖开始化尘的时刻（用户：华为一开始就准备粒子化）
CORNER_DUST_SPEED = 700.0   # 溶解前沿从角尖向内推进的速度（px/单位时间）
FLY_SPEED = 900.0           # 脱离后沿风向的速度（px/单位时间），略慢于折痕（约 1100）
FLY_RISE = 200.0            # 脱离后 z 向上升的速度（px/单位时间）
CURL_BIG_LAMBDA = 520.0     # 大褶皱波长（px）
CURL_BIG_GAIN = 2000.0       # 幅度随离开时间的增长率（px/单位时间 每单位年龄）
CURL_SMALL_LAMBDA = 150.0
CURL_SMALL_GAIN = 700.0
CURL_DRIFT = 0.6            # 势场随时间的漂移（噪声单位 / 单位时间）
Z_CAMERA = 1500.0           # 透视：scale = 1 + z / Z_CAMERA

# 溶解
LIFE = 0.18                 # 脱离之后多久碎完
LIFE_START = 0.0            # 溶解按“脱离后的时间”计，脱离即开始
PRE_DARK_LEAD = 0.08        # 溶解前多久开始变暗（按溶解时钟）
PRE_DARK_FLOOR = 0.70       # 化掉前一刻的亮度比（用户：华为有变暗的过程）
COARSE_CELLS = 40.0         # 粗斑块的尺度（px）
FINE_CELLS = 3.0            # 细颗粒的尺度（px）
COARSE_WEIGHT = 0.0         # 用户 2026-09-02：不要洞，只要粒子化；只留细颗粒

# 尘（刚溶解的格子交给点粒子）
DUST_PER_CELL = 4           # 每格溶解时发出的粒子数
DUST_LIFE = 0.20            # 粒子寿命（±30%）
DUST_SPREAD = 25.0          # 出生时的随机速度 sd（px/单位时间），很小
DUST_SIZE = 2.0             # 粒径（px）
DUST_WHITEN = 0.35          # 粒子颜色朝白提的比例

# 光照
LIGHT_TILT = 0.55           # 光从平面上方、向迎风侧倾斜的比例
BACK_BRIGHTNESS = 0.75
BACK_DESATURATE = 0.5
LIT_WHITEN = 0.0           # 受光面朝白提的比例
SHADE_DARK = 0.85           # 背光面的余弦下限


# ------------------------------------------------------------- 噪声 ----
def _hash3(ix, iy, iz, seed):
    h = (ix.astype(np.uint32) * np.uint32(73856093)) ^ (iy.astype(np.uint32) * np.uint32(19349663)) \
        ^ (iz.astype(np.uint32) * np.uint32(83492791)) ^ np.uint32(seed)
    h = h * np.uint32(2654435761)
    h ^= h >> np.uint32(13)
    h = h * np.uint32(0x5bd1e995)
    h ^= h >> np.uint32(15)
    return (h & np.uint32(0xFFFF)).astype(np.float32) / 65535.0


def value_noise3(p, seed):
    """p: (N,3) float. 返回 (N,) in [0,1]，五次缓和的三线性值噪声。"""
    i = np.floor(p).astype(np.int64)
    f = (p - i).astype(np.float32)
    u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0)
    ix, iy, iz = i[:, 0], i[:, 1], i[:, 2]
    def c(dx, dy, dz):
        return _hash3(ix + dx, iy + dy, iz + dz, seed)
    x00 = c(0, 0, 0) + (c(1, 0, 0) - c(0, 0, 0)) * u[:, 0]
    x10 = c(0, 1, 0) + (c(1, 1, 0) - c(0, 1, 0)) * u[:, 0]
    x01 = c(0, 0, 1) + (c(1, 0, 1) - c(0, 0, 1)) * u[:, 0]
    x11 = c(0, 1, 1) + (c(1, 1, 1) - c(0, 1, 1)) * u[:, 0]
    y0 = x00 + (x10 - x00) * u[:, 1]
    y1 = x01 + (x11 - x01) * u[:, 1]
    return y0 + (y1 - y0) * u[:, 2]


def fbm3(p, seed, octaves=2):
    out = np.zeros(p.shape[0], np.float32)
    amp, freq, norm = 1.0, 1.0, 0.0
    for k in range(octaves):
        out += amp * value_noise3(p * freq + k * 17.3, seed + 101 * k)
        norm += amp
        amp *= 0.5
        freq *= 2.0
    return out / norm


def curl3(p, seed, eps=0.02):
    """三维 curl noise：v = ∇×Ψ，Ψ 的三个分量各是一张 fbm。无散。"""
    def psi(k, q):
        return fbm3(q + np.array([[31.7 * k, 11.3 * k, 47.1 * k]], np.float32), seed + 977 * k)
    ex = np.array([[eps, 0, 0]], np.float32)
    ey = np.array([[0, eps, 0]], np.float32)
    ez = np.array([[0, 0, eps]], np.float32)
    d = 2.0 * eps
    d1dy = (psi(0, p + ey) - psi(0, p - ey)) / d
    d1dz = (psi(0, p + ez) - psi(0, p - ez)) / d
    d2dx = (psi(1, p + ex) - psi(1, p - ex)) / d
    d2dz = (psi(1, p + ez) - psi(1, p - ez)) / d
    d3dx = (psi(2, p + ex) - psi(2, p - ex)) / d
    d3dy = (psi(2, p + ey) - psi(2, p - ey)) / d
    return np.stack([d3dy - d2dz, d1dz - d3dx, d2dx - d1dy], 1)


def value_noise2(p, seed):
    z = np.zeros((p.shape[0], 1), np.float32)
    return value_noise3(np.concatenate([p, z], 1), seed)


def fbm2(p, seed, octaves=3):
    out = np.zeros(p.shape[0], np.float32)
    amp, freq, norm = 1.0, 1.0, 0.0
    for k in range(octaves):
        out += amp * value_noise2(p * freq + k * 7.9, seed + 53 * k)
        norm += amp
        amp *= 0.5
        freq *= 2.0
    return out / norm


# --------------------------------------------------------- 释放时刻场 ----
def release_field(material, corner, direction, seed):
    """material: (N,2) 卡片坐标（半对角归一，y 向下）。返回 (N,) 相位。"""
    d = np.array(direction, np.float32)
    perp = np.array([-d[1], d[0]], np.float32)
    upwind = np.array([-corner[0] if d[0] >= 0 else corner[0], -corner[1] if d[1] >= 0 else corner[1]], np.float32)
    downwind = -upwind
    span = max(float(np.dot(downwind - upwind, d)), 1e-3)
    progress = (material - upwind) @ d / span
    across_extent = abs(corner[0] * perp[0]) + abs(corner[1] * perp[1])
    across = (material @ perp) / max(across_extent, 1e-4)
    hump = across - FRONT_LAG_CENTRE
    lag = FRONT_LAG * np.exp(-hump * hump / (2.0 * FRONT_LAG_WIDTH ** 2))
    sway = (fbm2(np.stack([across * FRONT_SWAY_FREQ, np.full_like(across, 0.37)], 1) + 5.1, seed) - 0.5) * FRONT_SWAY
    arrival = FRONT_ONSET + progress * FRONT_SWEEP + lag + sway
    return np.clip(arrival, 0.0, 1.0).astype(np.float32)


# ------------------------------------------------------------ 着色器 ----
VERT = """
#version 330
uniform vec2 uViewportPx;
uniform vec2 uOriginPx;      // 卡片左上角在视口里的位置
uniform vec2 uSnapshotPx;
uniform float uZCamera;
in vec3 aPos;                // 卡片像素坐标 + z（朝观者为正）
in vec3 aNormal;
in vec2 aUv;
in float aAge;
in float aFly;
out vec3 vNormal;
out vec2 vUv;
out float vAge;
out float vFly;
void main() {
    vec2 centre = uSnapshotPx * 0.5;
    float scale = 1.0 + aPos.z / uZCamera;
    vec2 flatPos = centre + (aPos.xy - centre) * scale;
    vec2 px = uOriginPx + flatPos;
    vec2 ndc = px / uViewportPx * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, -aPos.z / 4000.0, 1.0);
    vNormal = aNormal;
    vUv = aUv;
    vAge = aAge;
    vFly = aFly;
}
"""

FRAG = """
#version 330
uniform sampler2D uSnapshot;
uniform vec2 uSnapshotPx;
uniform vec3 uLight;
uniform float uLife;
uniform float uLifeStart;
uniform float uPreLead;
uniform float uPreFloor;
uniform float uCoarsePx;
uniform float uFinePx;
uniform float uCoarseWeight;
uniform float uSeed;
uniform sampler2D uRelease;
uniform sampler2D uPattern;
uniform float uTime;
uniform int uStill;
uniform float uStillHold;
in vec3 vNormal;
in vec2 vUv;
in float vAge;
in float vFly;
out vec4 fragColor;

void main() {
    vec4 src = texture(uSnapshot, vUv);
    if (src.a <= 0.001) discard;
    // fbm 的取值集中在 0.35-0.65（实测 sd 0.13），所有像素几乎同时过阈值，
    // 等于一条干净的擦除边。把粗斑块拉开到 0-1，洞和残条才会分先后出现。
    float pattern = texture(uPattern, vUv).r;   // CPU 生成的溶解噪声，尘的发射用同一张

    vec3 color = src.rgb;
    // 静止层：年龄按释放场纹理逐像素取；已释放的像素归飞行层画，这里丢弃。
    float age = uStill == 1 ? uTime - texture(uRelease, vUv).r : vAge;
    // 静止层多画 uStillHold 那么久：飞行层只画整片释放的格子，两者按同一时刻切换会
    // 在前沿留下一格宽的黑锯齿。
    if (uStill == 1 && age >= uStillHold) discard;
    // 溶解前变暗：按溶解时钟（vFly）在化掉之前的一小段里平滑压暗，不带斑。
    float dark = smoothstep(-uPreLead, 0.0, vFly);
    color *= mix(1.0, uPreFloor, dark);
    if (age < 0.0) {
        // 角的溶解：前沿还没到，但角溶解时钟已经走了，就按细颗粒原地化掉
        if (vFly > 0.0) {
            float thr0 = smoothstep(uLifeStart, uLife, vFly);
            float alive0 = smoothstep(thr0 - 0.01, thr0 + 0.01, pattern);
            if (alive0 <= 0.002) discard;
            fragColor = vec4(color, src.a * alive0);
            return;
        }
        fragColor = vec4(color, src.a);
        return;
    }
    float threshold = smoothstep(uLifeStart, uLife, vFly);  // 脱离圆弧之后才开始碎，从自由端起
    // 硬切：一到两个像素的过渡由 fwidth 给
    // 切边固定很窄：按噪声梯度算会被细颗粒撑到 0.3，整张薄片变成半透明。
    float w = 0.01;
    float alive = smoothstep(threshold - w, threshold + w, pattern);
    if (alive <= 0.002) discard;

    // 正反面按法向的 z 分量判（z 朝观者为正）；gl_FrontFacing 会被 y 翻转的投影搞反。
    vec3 n = normalize(vNormal);
    bool front = n.z >= 0.0;
    if (!front) n = -n;
    // 光照以"平放的薄片"为基准：平的面亮度 1、不提白，只有朝光倾斜的面才提白、
    // 背光倾斜的面才压暗。否则静止层与刚释放的平的格子亮度不同，前沿上出一圈格子边，
    // 整片抬起的平台也会被均匀提白（左上角那团发白就是这样来的）。
    float litFlat = clamp(normalize(uLight).z, 1.0e-3, 1.0);
    float lit = clamp(dot(n, normalize(uLight)), 0.0, 1.0);
    float rel = lit / litFlat;                      // 1 = 与平面同亮
    float shade = rel < 1.0 ? mix(SHADE_DARK, 1.0, rel) : 1.0;
    color *= shade;
    color = mix(color, vec3(1.0), LIT_WHITEN * clamp((rel - 1.0) / max(1.0 / litFlat - 1.0, 1.0e-3), 0.0, 1.0));
    if (!front) {
        float g = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(color, vec3(g), BACK_DESATURATE) * BACK_BRIGHTNESS;
    }
    fragColor = vec4(min(color, vec3(1.0)), src.a * alive);
}
"""

BG_VERT = """
#version 330
in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; vUv.y = 1.0 - vUv.y; gl_Position = vec4(aPos, 0.999, 1.0); }
"""
BG_FRAG = """
#version 330
uniform sampler2D uBg;
in vec2 vUv;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uBg, vUv).rgb, 1.0); }
"""


DUST_VERT = """
#version 330
uniform vec2 uViewportPx;
uniform vec2 uOriginPx;
uniform vec2 uSnapshotPx;
uniform float uZCamera;
uniform float uSize;
in vec3 aPos;
in vec3 aColor;
in float aAlpha;
out vec3 vColor;
out float vAlpha;
void main() {
    vec2 centre = uSnapshotPx * 0.5;
    float scale = 1.0 + aPos.z / uZCamera;
    vec2 px = uOriginPx + centre + (aPos.xy - centre) * scale;
    vec2 ndc = px / uViewportPx * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
    gl_PointSize = uSize * scale;
    vColor = aColor;
    vAlpha = aAlpha;
}
"""
DUST_FRAG = """
#version 330
in vec3 vColor;
in float vAlpha;
out vec4 fragColor;
void main() {
    vec2 d = gl_PointCoord - 0.5;
    float r = length(d) * 2.0;
    float disc = 1.0 - smoothstep(0.7, 1.0, r);
    fragColor = vec4(vColor, vAlpha * disc);
}
"""


def _inject_constants(src):
    return (src.replace("SHADE_DARK", "%.3f" % SHADE_DARK).replace("LIT_WHITEN", "%.3f" % LIT_WHITEN)
            .replace("BACK_DESATURATE", "%.3f" % BACK_DESATURATE).replace("BACK_BRIGHTNESS", "%.3f" % BACK_BRIGHTNESS))


# ------------------------------------------------------------- 渲染器 ----
class SheetRenderer:
    def __init__(self, seed=77):
        self.seed = seed
        files = sorted(glob.glob(str(SP / "anim2" / "a_*.png")))
        first = Image.open(files[0]).convert("RGB")
        self.snapshot = first.crop(CARD).convert("RGBA")
        self.background = Image.open(SP / "bg.png").convert("RGB")
        self.sw, self.sh = self.snapshot.size
        self.origin = (float(CARD[0]), float(CARD[1]))

        self.ctx = moderngl.create_standalone_context()
        self.fbo = self.ctx.framebuffer(
            color_attachments=[self.ctx.texture((VIEW_W, VIEW_H), 4)],
            depth_attachment=self.ctx.depth_renderbuffer((VIEW_W, VIEW_H)))
        self.tex = self.ctx.texture(self.snapshot.size, 4, self.snapshot.tobytes())
        self.tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        # 纹理不得环绕：网格最外一圈顶点的 uv 正好是 0 和 1，环绕采样会把对面那一列的
        # 颜色混进来，卡片右边和下边各出一条细线（用户 2026-09-02 指出）。
        self.tex.repeat_x = False
        self.tex.repeat_y = False
        self.bg_tex = self.ctx.texture(self.background.size, 3, self.background.tobytes())
        self.prog = self.ctx.program(vertex_shader=VERT, fragment_shader=_inject_constants(FRAG))
        self.dust_prog = self.ctx.program(vertex_shader=DUST_VERT, fragment_shader=DUST_FRAG)
        self.dust_cap = 40000
        self.dust_vbo = self.ctx.buffer(reserve=self.dust_cap * 7 * 4)
        self.dust_vao = self.ctx.vertex_array(self.dust_prog, [(self.dust_vbo, "3f 3f 1f", "aPos", "aColor", "aAlpha")])
        # 溶解噪声：粗斑块拉开到 0-1 + 细颗粒，按快照像素生成一张纹理
        yy, xx = np.mgrid[0:self.sh, 0:self.sw]
        pix = np.stack([xx.ravel(), yy.ravel()], 1).astype(np.float32)
        # 逐像素白噪声（用户 2026-09-02：不要先变成方方正正的碎屑，直接成更细小的粒子）。
        # 3 px 的值噪声在 5 px 网格上会剩下一块块小方片；逐像素随机丢点，剩下的就是孤立的
        # 单像素，和尘分不出来。纹理按 NEAREST 采样，免得插值又把它糊成块。
        self.pattern = np.random.default_rng(seed + 17).random((self.sh, self.sw)).astype(np.float32)
        self.pattern_tex = self.ctx.texture((self.sw, self.sh), 1, self.pattern.tobytes(), dtype="f4")
        self.pattern_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.pattern_tex.repeat_x = False
        self.pattern_tex.repeat_y = False
        self.snap_rgb = np.asarray(self.snapshot.convert("RGB"), np.float32) / 255.0
        self.bg_prog = self.ctx.program(vertex_shader=BG_VERT, fragment_shader=BG_FRAG)
        quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], np.float32)
        self.bg_vao = self.ctx.vertex_array(self.bg_prog, [(self.ctx.buffer(quad.tobytes()), "2f", "aPos")])

        # 网格
        self.cols = int(math.ceil(self.sw / GRID_CELL_PX))
        self.rows = int(math.ceil(self.sh / GRID_CELL_PX))
        gx = np.linspace(0.0, self.sw, self.cols + 1, dtype=np.float32)
        gy = np.linspace(0.0, self.sh, self.rows + 1, dtype=np.float32)
        X, Y = np.meshgrid(gx, gy)                       # (rows+1, cols+1)
        self.shape = X.shape
        self.material = np.stack([X.ravel(), Y.ravel()], 1)          # 卡片像素坐标
        self.uv = self.material / np.array([[self.sw, self.sh]], np.float32)
        n = self.material.shape[0]
        idx = np.arange(n).reshape(self.shape)
        a = idx[:-1, :-1].ravel(); b = idx[:-1, 1:].ravel(); c = idx[1:, :-1].ravel(); d = idx[1:, 1:].ravel()
        self.tris = np.stack([a, b, c, b, d, c], 1).astype(np.int32)   # (cells, 6)
        self.ibo = self.ctx.buffer(reserve=self.tris.size * 4)
        self.ibo_all = self.ctx.buffer(self.tris.ravel().astype(np.int32).tobytes())
        # 静止层：同一张网格，位置每帧写入（角能隆起），年龄从释放场纹理取
        self.vbo_still_pos = self.ctx.buffer(reserve=n * 12)
        self.vbo_still_nrm = self.ctx.buffer(reserve=n * 12)
        self.vbo_still_fly = self.ctx.buffer(reserve=n * 4)
        self.still_vao = self.ctx.vertex_array(self.prog, [
            (self.vbo_still_pos, "3f", "aPos"), (self.vbo_still_nrm, "3f", "aNormal"),
            (self.ctx.buffer(self.uv.astype(np.float32).tobytes()), "2f", "aUv"),
            (self.ctx.buffer(np.zeros(n, np.float32).tobytes()), "1f", "aAge"),
            (self.vbo_still_fly, "1f", "aFly")], self.ibo_all)
        self.vbo_pos = self.ctx.buffer(reserve=n * 12)
        self.vbo_nrm = self.ctx.buffer(reserve=n * 12)
        self.vbo_uv = self.ctx.buffer(self.uv.astype(np.float32).tobytes())
        self.vbo_age = self.ctx.buffer(reserve=n * 4)
        self.vbo_fly = self.ctx.buffer(reserve=n * 4)
        attribs = [(self.vbo_pos, "3f", "aPos"), (self.vbo_nrm, "3f", "aNormal"),
                   (self.vbo_uv, "2f", "aUv"), (self.vbo_age, "1f", "aAge"), (self.vbo_fly, "1f", "aFly")]
        # 调试用的着色器变体可能把某个属性优化掉，只绑定程序里还在的。
        attribs = [a for a in attribs if a[2] in self.prog]
        self.vao = self.ctx.vertex_array(self.prog, attribs, self.ibo)

        # 释放时刻
        rad = math.radians(WIND_ANGLE_DEG)
        self.direction = np.array([math.cos(rad), math.sin(rad)], np.float32)
        half_diag = math.hypot(self.sw / 2.0, self.sh / 2.0)
        mat_norm = (self.material - np.array([[self.sw / 2.0, self.sh / 2.0]], np.float32)) / half_diag
        corner = (self.sw / 2.0 / half_diag, self.sh / 2.0 / half_diag)
        r_main = release_field(mat_norm, corner, self.direction, seed)
        # 主前沿折痕的推进速度（px/单位时间）
        span_px = abs(self.direction[0]) * self.sw + abs(self.direction[1]) * self.sh
        self.front_speed = float(span_px / FRONT_SWEEP)
        # 顺风角（风吹向的那个角）只做柔和隆起：按到角尖的距离给一个平滑的权重。
        tip = np.array([0.0 if self.direction[0] < 0 else self.sw, 0.0 if self.direction[1] < 0 else self.sh], np.float32)
        self.corner_tip = tip
        d_tip = np.linalg.norm(self.material - tip[None, :], axis=1)
        w = np.clip(1.0 - d_tip / CORNER_LIFT_R, 0.0, 1.0)
        self.corner_weight = (w * w * (3.0 - 2.0 * w)).astype(np.float32)
        self.region = np.zeros(self.material.shape[0], np.int8)
        self.release = r_main.astype(np.float32)
        self.corner_release = (CORNER_DUST_START + d_tip / CORNER_DUST_SPEED).astype(np.float32)

        self.release_tex = self.ctx.texture(self.shape[::-1], 1, self.release.reshape(self.shape).astype(np.float32).tobytes(), dtype="f4")
        rel_grid = self.release.reshape(self.shape)
        self.cell_release = 0.25 * (rel_grid[:-1, :-1] + rel_grid[:-1, 1:] + rel_grid[1:, :-1] + rel_grid[1:, 1:]).ravel()
        cr_grid = self.corner_release.reshape(self.shape)
        cell_corner = 0.25 * (cr_grid[:-1, :-1] + cr_grid[:-1, 1:] + cr_grid[1:, :-1] + cr_grid[1:, 1:]).ravel()
        self.cell_detach = np.minimum(self.cell_release + CURL_MAX_ANGLE * CURL_RADIUS / self.front_speed, cell_corner).astype(np.float32)
        cx = np.clip(((np.arange(self.cols) + 0.5) * GRID_CELL_PX).astype(int), 0, self.sw - 1)
        cy = np.clip(((np.arange(self.rows) + 0.5) * GRID_CELL_PX).astype(int), 0, self.sh - 1)
        self.cell_pattern = self.pattern[cy][:, cx].ravel()
        self.rng = np.random.default_rng(seed)
        self.release_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.release_tex.repeat_x = False
        self.release_tex.repeat_y = False
        self.reset()

        light = np.array([self.direction[0] * LIGHT_TILT, self.direction[1] * LIGHT_TILT, 1.0], np.float32)   # 从顺风侧上方照来，翻起的板面朝光
        self._set("uLight", tuple(float(v) for v in light / np.linalg.norm(light)))
        self._set("uViewportPx", (float(VIEW_W), float(VIEW_H)))
        self._set("uOriginPx", self.origin)
        self._set("uSnapshotPx", (float(self.sw), float(self.sh)))
        self._set("uZCamera", Z_CAMERA)
        self._set("uLife", LIFE)
        self._set("uLifeStart", LIFE_START)
        self._set("uPreLead", PRE_DARK_LEAD)
        self._set("uPreFloor", PRE_DARK_FLOOR)
        self._set("uCoarsePx", COARSE_CELLS)
        self._set("uFinePx", FINE_CELLS)
        self._set("uCoarseWeight", COARSE_WEIGHT)
        self._set("uSeed", float(seed))
        self._set("uSnapshot", 0)
        self._set("uRelease", 2)
        self._set("uPattern", 3)
        self._set("uStillHold", 0.015)
        self.bg_prog["uBg"].value = 1

    def _set(self, name, value):
        if name in self.prog:
            self.prog[name].value = value

    # ---------------------------------------------------------- 模拟 ----
    def reset(self):
        n = self.material.shape[0]
        self.pos = np.concatenate([self.material, np.zeros((n, 1), np.float32)], 1).astype(np.float32)
        self.time = 0.0
        self.emitted = np.zeros(self.cols * self.rows, bool)
        self.d_pos = np.zeros((0, 3), np.float32)
        self.d_vel = np.zeros((0, 3), np.float32)      # 出生时的随机速度
        self.d_age = np.zeros(0, np.float32)
        self.d_emit_age = np.zeros(0, np.float32)      # 发射时所在格子的年龄
        self.d_life = np.zeros(0, np.float32)
        self.d_color = np.zeros((0, 3), np.float32)

    def region_params(self):
        return ((0, self.direction, CURL_RADIUS, self.front_speed),)

    def corner_lift(self, t):
        rise = min(max(t / CORNER_LIFT_T, 0.0), 1.0)
        rise = rise * rise * (3.0 - 2.0 * rise)
        return (CORNER_LIFT_H * rise * self.corner_weight).astype(np.float32)

    def still_positions(self, t):
        n = self.material.shape[0]
        pos = np.concatenate([self.material, np.zeros((n, 1), np.float32)], 1).astype(np.float32)
        pos[:, 2] += self.corner_lift(t)
        return pos

    def fly_age(self, t):
        """溶解时钟：脱离圆弧之后过了多久，或角溶解前沿过去之后过了多久，取先到者。"""
        age = t - self.release
        out = np.full(age.shape, -1.0, np.float32)
        for region, e, R, speed in self.region_params():
            m = self.region == region
            out[m] = age[m] - CURL_MAX_ANGLE * R / speed
        return np.maximum(out, t - self.corner_release).astype(np.float32)

    def corner_fly_age(self, t):
        return (t - self.corner_release).astype(np.float32)

    def sheet_positions(self, t):
        """折痕后面距离 u 的材料先绕半径 R 的圆弧弯到 CURL_MAX_ANGLE（弧长守恒），
        之后脱离：保持脱离时相对折痕的位置，再沿风向以 FLY_SPEED 飞走并缓慢升高。"""
        n = self.material.shape[0]
        pos = np.concatenate([self.material, np.zeros((n, 1), np.float32)], 1).astype(np.float32)
        age = t - self.release
        wind = self.direction
        for region, e, R, speed in self.region_params():
            m = (self.region == region) & (age > 0.0)
            if not m.any():
                continue
            u = (age[m] * speed).astype(np.float32)
            u_c = CURL_MAX_ANGLE * R
            ub = np.minimum(u, u_c)
            theta = ub / R
            along = ub - R * np.sin(theta)
            height = R * (1.0 - np.cos(theta))
            fly = np.maximum(age[m] - u_c / speed, 0.0)
            pos[m, 0] = self.material[m, 0] + along * e[0] + fly * FLY_SPEED * wind[0]
            pos[m, 1] = self.material[m, 1] + along * e[1] + fly * FLY_SPEED * wind[1]
            pos[m, 2] = height + fly * FLY_RISE
        pos[:, 2] += self.corner_lift(t)
        return pos

    def emit(self):
        # 格子中心的噪声一过阈值，这格就交给尘：在格内随机位置发出粒子，带着格子的颜色
        # 与当地的流场速度。
        age = self.time - self.cell_release
        fly = self.time - self.cell_detach
        thr = np.clip((fly - LIFE_START) / max(LIFE - LIFE_START, 1e-4), 0.0, 1.0)
        thr = thr * thr * (3.0 - 2.0 * thr)
        due = (~self.emitted) & (fly > 0.0) & (thr >= self.cell_pattern)
        idx = np.nonzero(due)[0]
        if idx.size == 0:
            return
        self.emitted[idx] = True
        k = DUST_PER_CELL
        cells = np.repeat(idx, k)
        rows_ = cells // self.cols
        cols_ = cells % self.cols
        fx = self.rng.random(cells.size).astype(np.float32)
        fy = self.rng.random(cells.size).astype(np.float32)
        P = self.pos.reshape(self.shape + (3,))
        p00 = P[rows_, cols_]
        p10 = P[rows_, cols_ + 1]
        p01 = P[rows_ + 1, cols_]
        p11 = P[rows_ + 1, cols_ + 1]
        pos = (p00 * ((1 - fx) * (1 - fy))[:, None] + p10 * (fx * (1 - fy))[:, None]
               + p01 * ((1 - fx) * fy)[:, None] + p11 * (fx * fy)[:, None]).astype(np.float32)
        cell_age = np.repeat(age[idx], k).astype(np.float32)
        mx = np.clip(((cols_ + fx) * GRID_CELL_PX).astype(int), 0, self.sw - 1)
        my = np.clip(((rows_ + fy) * GRID_CELL_PX).astype(int), 0, self.sh - 1)
        color = self.snap_rgb[my, mx]
        color = color + (1.0 - color) * DUST_WHITEN
        vel = self.rng.normal(0.0, DUST_SPREAD, (cells.size, 3)).astype(np.float32)
        life = (DUST_LIFE * self.rng.uniform(0.7, 1.3, cells.size)).astype(np.float32)
        self.d_pos = np.concatenate([self.d_pos, pos])
        self.d_vel = np.concatenate([self.d_vel, vel])
        self.d_age = np.concatenate([self.d_age, np.zeros(cells.size, np.float32)])
        self.d_emit_age = np.concatenate([self.d_emit_age, cell_age])
        self.d_life = np.concatenate([self.d_life, life])
        self.d_color = np.concatenate([self.d_color, color.astype(np.float32)])

    def step_dust(self, step):
        if self.d_pos.shape[0] == 0:
            return
        v = self.velocity(self.d_pos, self.d_emit_age + self.d_age, self.time) + self.d_vel
        self.d_pos += v * step
        self.d_age += step
        keep = self.d_age < self.d_life
        if not keep.all():
            self.d_pos = self.d_pos[keep]
            self.d_vel = self.d_vel[keep]
            self.d_age = self.d_age[keep]
            self.d_emit_age = self.d_emit_age[keep]
            self.d_life = self.d_life[keep]
            self.d_color = self.d_color[keep]

    def velocity(self, pos, age, t):
        d3 = np.array([self.direction[0], self.direction[1], 0.0], np.float32)
        ramp = np.clip(age / WIND_RAMP, 0.0, 1.0); ramp = ramp * ramp * (3.0 - 2.0 * ramp)
        v = d3[None, :] * (WIND_SPEED * ramp)[:, None]
        drift = np.array([[0.0, 0.0, t * CURL_DRIFT]], np.float32)
        big = curl3(pos / CURL_BIG_LAMBDA + drift, self.seed) * (CURL_BIG_GAIN * age)[:, None]
        small = curl3(pos / CURL_SMALL_LAMBDA + drift * 1.7 + 3.1, self.seed + 5) * (CURL_SMALL_GAIN * age)[:, None]
        return v + big + small

    def advance_to(self, n):
        if n < self.time - 1e-9:
            self.reset()
        dt = 1.0 / (FPS * SUBSTEPS)
        while self.time < n - 1e-9:
            step = min(dt, n - self.time)
            self.pos = self.sheet_positions(self.time)
            self.emit()
            self.step_dust(step)
            self.time += step

    def normals(self, pos):
        P = pos.reshape(self.shape + (3,))
        tx = np.zeros_like(P); ty = np.zeros_like(P)
        tx[:, 1:-1] = P[:, 2:] - P[:, :-2]; tx[:, 0] = P[:, 1] - P[:, 0]; tx[:, -1] = P[:, -1] - P[:, -2]
        ty[1:-1] = P[2:] - P[:-2]; ty[0] = P[1] - P[0]; ty[-1] = P[-1] - P[-2]
        nrm = np.cross(tx.reshape(-1, 3), ty.reshape(-1, 3))
        length = np.linalg.norm(nrm, axis=1, keepdims=True)
        return (nrm / np.maximum(length, 1e-6)).astype(np.float32)

    # ---------------------------------------------------------- 绘制 ----
    def composite(self, n):
        self.advance_to(n)
        age = (self.time - self.release).astype(np.float32)
        # 前沿的唇：z 按年龄的鼓包直接给（峰值在 LIFT_TAU），刚脱离的边抬得最高、
        # 朝光；越老越回落。积分式的抬起会让老材料更高，法向反而背光。
        render_pos = self.pos
        self.vbo_pos.write(render_pos.astype(np.float32).tobytes())
        self.vbo_nrm.write(self.normals(render_pos).tobytes())
        self.vbo_age.write(age.tobytes())
        self.vbo_fly.write(self.fly_age(self.time).astype(np.float32).tobytes())
        self.fbo.use()
        self.ctx.viewport = (0, 0, VIEW_W, VIEW_H)
        self.ctx.clear(0.0, 0.0, 0.0, 1.0, depth=1.0)
        self.ctx.disable(moderngl.DEPTH_TEST)
        self.bg_tex.use(1)
        self.bg_vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        self.ctx.enable(moderngl.DEPTH_TEST | moderngl.BLEND)
        self.ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        self.tex.use(0)
        self.release_tex.use(2)
        self.pattern_tex.use(3)
        self._set("uTime", float(self.time))
        self._set("uStill", 1)
        still_pos = self.still_positions(self.time)
        self.vbo_still_pos.write(still_pos.astype(np.float32).tobytes())
        self.vbo_still_nrm.write(self.normals(still_pos).tobytes())
        self.vbo_still_fly.write(self.corner_fly_age(self.time).tobytes())
        self.still_vao.render(moderngl.TRIANGLES)
        self._set("uStill", 0)
        # 飞行层只画三个顶点都已释放的三角形。曾试过"有任一顶点已释放就画"，那会把
        # 每块释放区边缘的三角形拉在飞走的顶点与未动的顶点之间，内容被拉大一两倍
        # （用户 2026-09-02 指出左上角那团被放大）。前沿的平滑靠静止层多保留一小段。
        # 两个翻板交界处的三角形也画（两侧映射略有差异，只是一格宽的小拉伸）；
        # 不画会留下一条黑色的锯齿缝。
        released = age > 0.0
        cell_ok = released[self.tris].all(1)
        idx = self.tris[cell_ok].ravel()
        if idx.size:
            self.ibo.write(idx.astype(np.int32).tobytes())
            self.vao.render(moderngl.TRIANGLES, vertices=int(idx.size))
        # 尘：在薄片之后画，不参与深度
        self.ctx.disable(moderngl.DEPTH_TEST)
        m = self.d_pos.shape[0]
        if m:
            m = min(m, self.dust_cap)
            alpha = (1.0 - self.d_age[:m] / self.d_life[:m]) ** 1.5 * 0.9
            data = np.concatenate([self.d_pos[:m], self.d_color[:m], alpha[:, None].astype(np.float32)], 1).astype(np.float32)
            self.dust_vbo.write(data.tobytes())
            self.ctx.enable(moderngl.PROGRAM_POINT_SIZE)
            self.dust_prog["uViewportPx"].value = (float(VIEW_W), float(VIEW_H))
            self.dust_prog["uOriginPx"].value = self.origin
            self.dust_prog["uSnapshotPx"].value = (float(self.sw), float(self.sh))
            self.dust_prog["uZCamera"].value = Z_CAMERA
            self.dust_prog["uSize"].value = DUST_SIZE
            self.dust_vao.render(moderngl.POINTS, vertices=m)
        self.ctx.disable(moderngl.BLEND)
        data = self.fbo.read(components=3)
        return Image.frombytes("RGB", (VIEW_W, VIEW_H), data).transpose(Image.FLIP_TOP_BOTTOM)


# --------------------------------------------------------------- 输出 ----
def font(size=22):
    for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def label(im, text):
    d = ImageDraw.Draw(im)
    d.rectangle((0, im.height - 34, 300, im.height), fill=(0, 0, 0))
    d.text((8, im.height - 30), text, font=font(22), fill=(240, 240, 240))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    r = SheetRenderer()
    frames = []
    for i in range(FPS + 1):
        n = i / FPS
        im = r.composite(n)
        im.save(OUT / ("frame-%03d.png" % i))
        frames.append(im)
        if i % 10 == 0:
            print("frame", i, flush=True)
    def encode(name, imgs, w, h, slow=1):
        proc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (w, h),
             "-r", str(FPS), "-i", "-", "-vf", "setpts=PTS*%d" % slow, "-c:v", "libx264", "-pix_fmt", "yuv420p",
             "-crf", "20", "-movflags", "+faststart", str(OUT / name)], stdin=subprocess.PIPE)
        for im in imgs:
            proc.stdin.write(np.asarray(im.convert("RGB")).tobytes())
        proc.stdin.close(); proc.wait()
    encode("sheet.mp4", frames, VIEW_W, VIEW_H)
    # 参考 | 薄片
    ref = RT.reference_frames(); rtimes = np.array([n for n, _ in ref])
    pairs = []
    for i, im in enumerate(frames):
        n = i / FPS
        j = int(np.argmin(np.abs(rtimes - n)))
        a = Image.open(ref[j][1]).convert("RGB"); b = im.copy()
        label(a, "参考  n=%.2f" % n); label(b, "薄片原型  n=%.2f" % n)
        sheet = Image.new("RGB", (VIEW_W * 2 + 12, VIEW_H), (18, 18, 20))
        sheet.paste(a, (0, 0)); sheet.paste(b, (VIEW_W + 12, 0))
        pairs.append(sheet)
    encode("compare-sheet.mp4", pairs, VIEW_W * 2 + 12, VIEW_H)
    encode("compare-sheet-slow.mp4", pairs, VIEW_W * 2 + 12, VIEW_H, slow=3)
    # 八个时刻对照表
    wants = [0.10, 0.17, 0.24, 0.31, 0.38, 0.45, 0.52, 0.60]
    x0, y0, x1, y1 = CARD
    tiles = []
    for w in wants:
        i = int(round(w * FPS)); j = int(np.argmin(np.abs(rtimes - w)))
        a = Image.open(ref[j][1]).convert("RGB").crop((x0 - 20, y0 - 20, x1 + 20, y1 + 20))
        b = frames[i].crop((x0 - 20, y0 - 20, x1 + 20, y1 + 20))
        for im, t in ((a, "ref n=%.2f" % w), (b, "sheet n=%.2f" % w)):
            d = ImageDraw.Draw(im); d.rectangle((0, 0, 120, 22), fill=(0, 0, 0)); d.text((4, 4), t, fill=(255, 255, 255))
        tiles.append((a, b))
    tw, th = tiles[0][0].size
    contact = Image.new("RGB", (tw * 4 + 30, th * 4 + 40), (40, 40, 40))
    for k, (a, b) in enumerate(tiles):
        col, row = k % 4, k // 4
        contact.paste(a, (col * (tw + 10), row * 2 * (th + 10)))
        contact.paste(b, (col * (tw + 10), (row * 2 + 1) * (th + 10)))
    contact.save(OUT / "sheet-contact.png")
    print("视频 ->", OUT / "compare-sheet-slow.mp4")
    print("对照表 ->", OUT / "sheet-contact.png")


if __name__ == "__main__":
    main()
