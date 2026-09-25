"""碰一碰粒子模拟器的 GPU 部分：预算轨迹、按时间轴取帧、按手机的合成方式出图。

着色器一律读仓库原文，不另写副本：
- 积分：`shared/particle-dismiss/step.comp`，打开 `SINK`，并按 `ParticleReverseShader.integration`
  插入「超过寿命不再积分」那一行（碰一碰的两种会话都走预算轨迹这条路）；
- 绘制：`shared/particle-dismiss/material.vert` / `material.frag` 的默认路径（实例化顶点属性）；
- 转色：`shared/particle-dismiss/resolve.frag`。
压力修正复用 GPT6 桌面模拟器的 `PressureGrid`，与 Android 的 peel-splat / blur / project 同一公式。

出图与手机一致：颗粒在线性 RGBA16F 上累积，`resolve.frag` 转成 sRGB 预乘，再按 SurfaceFlinger
的方式（ONE, ONE_MINUS_SRC_ALPHA）叠到 sRGB 底图上；发送方天线处的光画在粒子层之下。
"""
from __future__ import annotations

import math
import time as clock

import moderngl
import numpy as np

from sim_model import ROOT, SAMPLES, STEP, Build, Moment, Scene, flow_mode, um

SHARED = ROOT / 'shared/particle-dismiss'
import pressure_grid  # noqa: E402  GPT6 目录已由 sim_model 加进 sys.path

INTEGRATION_MARKER = '    float h=min(dt,age);'
GL_SRGB8_ALPHA8 = 0x8C43
GL_FRAMEBUFFER_SRGB = 0x8DB9


def desktop(source: str, defines: str = '') -> str:
    """GLES 3.1 原文换成桌面 GLSL 4.30；精度声明在桌面 GLSL 里合法且无副作用。"""
    first, rest = source.split('\n', 1)
    if first.strip() != '#version 310 es':
        raise ValueError('着色器首行不是 #version 310 es')
    return '#version 430\n' + defines + rest


def integration_source(targets: bool = False) -> str:
    """[targets]：逐片指定终点（接收时的交接，见 sim_handoff.py），打开 SINK_TARGETS。"""
    source = (SHARED / 'step.comp').read_text(encoding='utf-8')
    if source.count(INTEGRATION_MARKER) != 1:
        raise ValueError('step.comp 里找不到唯一的积分起点，ParticleReverseShader 的约定变了')
    # 与 ParticleReverseShader.integration 相同：材质与压力都不再读取超过寿命的颗粒
    source = source.replace(INTEGRATION_MARKER, '    if(age>=m.physical.z)return;\n' + INTEGRATION_MARKER)
    mode = flow_mode()
    defines = '' if mode.startswith('dialog') else '#define SINK 1\n' if mode == 'legacy' else \
        '#define SINK 1\n#define SINK_FIELD 1\n'
    if targets and mode == 'field':
        defines += '#define SINK_TARGETS 1\n'
    return desktop(source, defines)


RESTORE = r'''#version 430
layout(local_size_x=256) in;
struct State{vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer H{State history[];};
layout(std430,binding=1) writeonly buffer S{State state[];};
uniform int count,frame,next;
uniform float fraction;
// history.comp 的恢复方向：只还原位置 xyz 与速度 xy，手势进度在相邻两帧之间线性插值。
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    State a=history[frame*count+int(i)],b=history[next*count+int(i)];
    state[i].pos=vec4(mix(a.pos.xyz,b.pos.xyz,fraction),0.);
    state[i].vel=vec4(mix(a.vel.xy,b.vel.xy,fraction),0.,0.);
}
'''

FULLVERT = r'''#version 430
out vec2 uv;
const vec2 v[3]=vec2[3](vec2(0,0),vec2(2,0),vec2(0,2));
void main(){uv=v[gl_VertexID];gl_Position=vec4(uv.x*2.-1.,1.-uv.y*2.,0,1);}
'''

BACKGROUND = r'''#version 430
uniform sampler2D settled,border;
uniform float border_alpha;
in vec2 uv;out vec4 frag;
// 两张 sRGB 底图按边框的不透明度过渡：边框层以 α 叠在无边框画面上，正好是两图的线性插值。
void main(){frag=vec4(mix(texture(settled,uv).rgb,texture(border,uv).rgb,border_alpha),1.);}
'''

GLOW = r'''#version 430
uniform vec2 screen,center;
uniform float radius,alpha;
in vec2 uv;out vec4 frag;
// TapShareCardStage 的 GLOW_SPRITE：白色，透明度按高斯衰减，边缘减去常数项归零。
void main(){
    vec2 p=uv*screen;
    float r=min(length(p-center)/radius,1.);
    float floor_=exp(-5.);
    float falloff=clamp((exp(-5.*r*r)-floor_)/(1.-floor_),0.,1.);
    float a=falloff*alpha;
    frag=vec4(vec3(a),a);
}
'''


def set_groups(program, groups: list):
    """按卡片分组的三个 uniform；不分组时 count = 0（着色器用缺省值）。"""
    if 'sink_group_count' not in program:
        return
    rects = np.zeros((4, 4), 'float32')
    params = np.zeros((4, 4), 'float32')
    for k, (rect, values) in enumerate(groups[:4]):
        rects[k] = rect
        params[k] = values
    program['sink_group_count'] = len(groups[:4])
    if 'sink_groups' in program:
        program['sink_groups'].write(rects.tobytes())
    if 'sink_group_params' in program:
        program['sink_group_params'].write(params.tobytes())


def dialog_touch(scene: Scene) -> tuple[float, float]:
    """`ParticleFlowGeometry.touchGap / touchStrength`：天线当作弹窗的触点，屏幕当作可用背景。"""
    l, t, r, b = scene.bounds
    width, height = r - l, b - t
    x, y = scene.sink[0] - l, scene.sink[1] - t
    dx, dy = x - width * .5, y - height * .5
    distance = math.hypot(dx, dy)
    if distance < 1e-6:
        return 0., 0.
    ux, uy = dx / distance, dy / distance
    edge = min(width / (2 * max(abs(ux), 1e-9)), height / (2 * max(abs(uy), 1e-9)))
    gap = max(0., (distance - edge) / min(width, height))
    sw, sh = scene.screen
    left, top, right, bottom = -l, -t, sw - l, sh - t
    limit = float('inf')
    if abs(ux) > 1e-9:
        limit = min(limit, ((right if ux > 0 else left) - width * .5) / ux)
    if abs(uy) > 1e-9:
        limit = min(limit, ((bottom if uy > 0 else top) - height * .5) / uy)
    strength = min(max((distance - edge) / max(limit - edge, 1.), 0.), 1.)
    return gap, strength


class TapRenderer:
    """一次飞行（一个种子、一组物理参数）的完整轨迹与出图。"""

    def __init__(self, scene: Scene, build: Build, ctx: moderngl.Context, output_scale: float = 1.,
                 jacobi: int | None = None, hand_until: bool = False):
        """[hand_until]：这一层的颗粒会被另一层逐片接手（接收时进度期间那一层），打开 HAND_UNTIL，
        用 `set_hand_until` 给出每一片交出去的时间轴位置。"""
        self.scene, self.build, self.ctx = scene, build, ctx
        started = clock.perf_counter()
        w, h = scene.screen
        self.out_w, self.out_h = max(1, round(w * output_scale)), max(1, round(h * output_scale))
        b = build
        self.n = len(b.base)
        self.cw, self.ch = b.card
        self.span = min(self.cw, self.ch)
        self.nx, self.ny = b.nx, b.ny
        self.cell = b.cell
        angle = math.radians(b.direction)
        self.wind = (math.cos(angle), -math.sin(angle))
        self.touch_strength = float(scene.meta.get('touch_strength', .5))
        self.touch_gap = scene.meta.get('touch_gap')
        if flow_mode().startswith('dialog'):
            self.touch_gap, self.touch_strength = dialog_touch(scene)
        iterations = scene.meta.get('jacobi_iterations', 100) if jacobi is None else jacobi
        self.jacobi = int(100 if iterations is None else iterations)

        # 缓冲：材料 12 浮点（三个 vec4）、状态 8 浮点（两个 vec4）
        self.material = ctx.buffer(b.base.astype('float32').tobytes())
        self.state = ctx.buffer(reserve=self.n * 32)
        self.pigment = ctx.buffer(b.pigment.astype('float32').tobytes())
        self.peel_compression = ctx.buffer(b.peel_compression.astype('float32').tobytes())
        self.history = ctx.buffer(reserve=(SAMPLES + 1) * self.n * 32)
        self.draw_state = ctx.buffer(reserve=self.n * 32)
        # 交接：逐片的终点与接手颜色（没有交接时不建，着色器也不打开 SINK_TARGETS）
        handoff = getattr(b, 'handoff', None)
        self.targets = handoff is not None and flow_mode() == 'field'
        self.handoff_target = self.handoff_origin = None
        if self.targets:
            self.handoff_target = ctx.buffer(np.ascontiguousarray(handoff.target, dtype='float32').tobytes())
            self.handoff_origin = ctx.buffer(np.ascontiguousarray(handoff.origin, dtype='float32').tobytes())

        # 颜色精度「标准」（P2_SRGB8_ACCUMULATION）：前景与累积目标都换成 SRGB8_ALPHA8，
        # 采样器直接给线性值，material.frag 按 SRGB_TARGET 省掉 linear()，混合在线性空间里做
        self.srgb = bool(scene.meta.get('srgb_accumulation'))
        fg = scene.snapshot
        self.fg_tex = ctx.texture((fg.shape[1], fg.shape[0]), 4, np.ascontiguousarray(fg).tobytes(),
                                  internal_format=GL_SRGB8_ALPHA8 if self.srgb else None)
        self.fg_tex.repeat_x = self.fg_tex.repeat_y = False

        self.compute = ctx.compute_shader(integration_source(self.targets))
        self._setup_integration()
        self.pressure = pressure_grid.PressureGrid(self) if self.jacobi > 0 else None
        if self.pressure is not None and self.jacobi != 100:
            self.pressure.update = self._pressure_update  # 「流动精细度」不是默认的 100
        if self.pressure is None:
            self._zero_peel_field()
        self.restore = ctx.compute_shader(RESTORE)
        self.restore['count'] = self.n

        material_defines = '#define SRGB_TARGET 1\n' if self.srgb else ''
        if flow_mode() == 'field':
            material_defines += '#define SINK_FIELD 1\n'
        if self.targets:
            material_defines += '#define SINK_TARGETS 1\n'
        self.hand = None
        if hand_until and flow_mode() == 'field':
            material_defines += '#define HAND_UNTIL 1\n'
            self.hand = ctx.buffer(np.tile(np.array([-1., 0.], 'float32'), (self.n, 1)).tobytes())
        vert = desktop((SHARED / 'material.vert').read_text(encoding='utf-8'), material_defines)
        frag = desktop((SHARED / 'material.frag').read_text(encoding='utf-8'), material_defines)
        self.program = ctx.program(vertex_shader=vert, fragment_shader=frag)
        attributes = [
            (self.material, '4f 4f 4f/i', 'material_src', 'material_physical', 'material_random'),
            (self.draw_state, '4f 4f/i', 'state_pos', 'state_vel'),
            (self.pigment, 'f/i', 'pigment'),
        ]
        if self.targets:
            attributes += [(self.handoff_target, '4f/i', 'sink_explicit'), (self.handoff_origin, '4f/i', 'sink_origin')]
        if self.hand is not None:
            attributes += [(self.hand, '2f/i', 'hand_until')]
        self.vao = ctx.vertex_array(self.program, attributes, skip_errors=True)
        self._setup_draw()

        if self.srgb:
            self.accum = ctx.texture((self.out_w, self.out_h), 4, internal_format=GL_SRGB8_ALPHA8)
        else:
            self.accum = ctx.texture((self.out_w, self.out_h), 4, dtype='f2')
        self.accum.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.accum_fbo = ctx.framebuffer([self.accum])
        self.out_tex = ctx.texture((self.out_w, self.out_h), 4)
        self.out_fbo = ctx.framebuffer([self.out_tex])
        self.resolve = ctx.program(vertex_shader=desktop((SHARED / 'resolve.vert').read_text(encoding='utf-8')),
                                   fragment_shader=desktop((SHARED / 'resolve.frag').read_text(encoding='utf-8')))
        self.resolve_vao = ctx.vertex_array(self.resolve, [])
        self.bg_program = ctx.program(vertex_shader=FULLVERT, fragment_shader=BACKGROUND)
        self.bg_vao = ctx.vertex_array(self.bg_program, [])
        self.glow_program = ctx.program(vertex_shader=FULLVERT, fragment_shader=GLOW)
        self.glow_vao = ctx.vertex_array(self.glow_program, [])
        self.bg_border = self._texture(scene.background)
        self.bg_settled = self._texture(scene.settled) if scene.settled is not None else self.bg_border
        self.setup_ms = (clock.perf_counter() - started) * 1000
        started = clock.perf_counter()
        self._precompute()
        self.precompute_ms = (clock.perf_counter() - started) * 1000

    # ------------------------------------------------------------ 构造

    def _texture(self, rgb: np.ndarray):
        tex = self.ctx.texture((rgb.shape[1], rgb.shape[0]), 3, np.ascontiguousarray(rgb).tobytes())
        tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        tex.repeat_x = tex.repeat_y = False
        return tex

    def _setup_integration(self):
        c, b = self.compute, self.build
        rules = um.RULES
        geometry = um.field_geometry(self.cw, self.ch)
        delta = um.field_rotation(b.direction, *geometry[:2])
        flow = um.guidance()
        self.flow_tex = self.ctx.texture3d(tuple(flow.shape[2::-1]), 2, flow.tobytes(), dtype='f4')
        confidence = um.flow_confidence()
        self.confidence_tex = self.ctx.texture3d(tuple(confidence.shape[::-1]), 1, confidence.tobytes(), dtype='f1')
        for tex in (self.flow_tex, self.confidence_tex):
            tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            tex.repeat_x = tex.repeat_y = tex.repeat_z = False
        values = {
            'count': self.n, 'guide_field': 3, 'confidence_field': 4,
            'touch_gap': float(self.touch_gap or rules['touch_gap_default']),
            'touch_strength': self.touch_strength, 'field_geometry': tuple(float(v) for v in geometry),
            'dt': STEP, 'span': self.span, 'wind_gain': rules['wind_gain'], 'curl_gain': rules['curl_gain'],
            'roll_gain': rules['roll_gain'], 'guide_gain': rules['guide_gain'], 'wind': self.wind,
            'card': (self.cw, self.ch), 'guide_rotation': (math.cos(delta), math.sin(delta)),
            'variation_affine': tuple(float(v) for v in b.variation[:4]),
            'variation_bend': tuple(float(v) for v in b.variation[4:8]),
            'variation_wave': tuple(float(v) for v in b.variation[8:12]),
            'variation_clock': tuple(float(v) for v in b.variation[12:14]),
            # 终点：与 configureIntegration 的 sinkMode 分支逐项对应
            'sink_point': (b.sink.x, b.sink.y),
            'sink_frame': (b.sink.left, b.sink.top, b.sink.right, b.sink.bottom),
            'sink_frame_mix': (b.sink.share, b.sink.jitter),
            'sink_swirl': b.sink.swirl, 'sink_homing': tuple(float(v) for v in b.sink.homing),
            'sink_flow': tuple(float(v) for v in b.sink.flow), 'sink_band': tuple(float(v) for v in b.sink.band),
            'sink_vortex': float(b.sink.vortex), 'sink_glide': tuple(float(v) for v in b.sink.glide),
            'sink_handoff': tuple(float(v) for v in b.sink.handoff),
        }
        set_groups(c, b.sink.groups)
        for key, value in values.items():
            if key in c:
                c[key] = value

    def _setup_draw(self):
        p, b, s = self.program, self.build, self.scene
        rules = um.RULES
        values = {
            'frame': b.frame, 'card': b.card, 'offset': b.offset, 'cell': tuple(float(v) for v in b.cell),
            'wind': self.wind, 'span': self.span, 'roll_gain': rules['roll_gain'], 'light_gain': rules['light_gain'],
            'body_weight': b.body_weight, 'release_spread': b.release_spread, 'panel_weight': b.panel_weight,
            'nx': b.nx, 'foreground': 0, 'grid_count': b.nx * b.ny, 'diagnostic': 0, 'extrapolate': 0.,
            'sink_point': (b.sink.x, b.sink.y), 'sink_frame_mix': (b.sink.share, b.sink.jitter),
            'sink_absorb': tuple(float(v) for v in b.sink.absorb),
        }
        set_groups(p, b.sink.groups)
        for key, value in values.items():
            if key in p:
                p[key] = value

    def _zero_peel_field(self):
        """流动精细度为 0：整段压力更新不跑，修正场是构造时清空的零场。"""
        self.zero_peel = self.ctx.texture((4, 4), 4, dtype='f4')
        self.zero_peel.write(np.zeros((4, 4, 4), 'float32').tobytes())
        self.compute['peel_field'] = 5
        self.compute['peel_bounds'] = (-self.span, -self.span, 4., 4.)

    def _pressure_update(self, time):
        """PressureGrid.update 的迭代次数按本机「流动精细度」取（原实现固定 100）。"""
        g = self.pressure
        r = self
        r.material.bind_to_storage_buffer(0); r.state.bind_to_storage_buffer(1)
        r.peel_compression.bind_to_storage_buffer(3); g.accum.bind_to_storage_buffer(4); r.fg_tex.use(0)
        g.splat['pass'] = 0; g.splat['time'] = time
        g.splat.run(group_x=(math.prod(g.shape) + 255) // 256); g.barrier()
        g.splat['pass'] = 1; g.splat.run(group_x=(r.n + 255) // 256); g.barrier()
        g.temp.bind_to_storage_buffer(5); g.velocity.bind_to_storage_buffer(6)
        gx, gy = (g.shape[0] + 15) // 16, (g.shape[1] + 15) // 16
        for p in (0, 1):
            g.blur['pass'] = p; g.blur.run(group_x=gx, group_y=gy); g.barrier()
        g.velocity.bind_to_storage_buffer(0)
        for i in range(self.jacobi):
            g.pressures[g.current].bind_to_storage_buffer(1); g.pressures[1 - g.current].bind_to_storage_buffer(2)
            g.project['iteration'] = i; g.project.run(group_x=gx, group_y=gy); g.barrier()
            g.current = 1 - g.current
        g.resolve()

    def _bind_integration(self):
        self.material.bind_to_storage_buffer(0)
        self.state.bind_to_storage_buffer(1)
        self.pigment.bind_to_storage_buffer(2)
        self.peel_compression.bind_to_storage_buffer(3)
        if self.handoff_target is not None:
            self.handoff_target.bind_to_storage_buffer(2)   # 积分器不读色素，2 号给逐片终点
        self.flow_tex.use(3)
        self.confidence_tex.use(4)
        if self.pressure is not None:
            self.pressure.texture.use(5)
        else:
            self.zero_peel.use(5)

    def _precompute(self):
        """完整积分 240 步（压力每 4 步一次），每 4 步存一份状态：第 k 份即时间 k/60 的画面。"""
        initial = np.zeros((self.n, 8), dtype='float32')
        initial[:, :2] = self.build.base[:, :2]
        self.state.write(initial.tobytes())
        if self.pressure is not None:
            self.pressure.reset()
        frame_bytes = self.n * 32
        self.ctx.copy_buffer(self.history, self.state, size=frame_bytes, write_offset=0)
        groups = (self.n + 255) // 256
        self._bind_integration()
        for step in range(1, SAMPLES * 4 + 1):
            self.compute['time'] = step * STEP
            self.compute.run(group_x=groups)
            self.ctx.memory_barrier()
            if step % 4 == 0:
                if self.pressure is not None:
                    self.pressure.update(step * STEP)
                    self._bind_integration()
                self.ctx.copy_buffer(self.history, self.state, size=frame_bytes, write_offset=(step // 4) * frame_bytes)
        self.ctx.finish()

    # ------------------------------------------------------------ 出图

    def _restore(self, position: float) -> float:
        position = min(max(position, 0.), float(SAMPLES))
        frame = int(math.floor(position))
        fraction = position - frame
        nxt = min(frame + 1, SAMPLES)
        self.history.bind_to_storage_buffer(0)
        self.draw_state.bind_to_storage_buffer(1)
        self.restore['frame'] = frame
        self.restore['next'] = nxt
        self.restore['fraction'] = float(fraction)
        self.restore.run(group_x=(self.n + 255) // 256)
        self.ctx.memory_barrier()
        return position / SAMPLES

    def set_hand_until(self, values: np.ndarray):
        """(n, 2)：每一片交出去的时间轴位置（0–1）与淡去的长度；从不交出的是 (-1, 0)。"""
        if self.hand is not None:
            self.hand.write(np.ascontiguousarray(values, dtype='float32').tobytes())

    def read_positions(self, frame: int) -> np.ndarray:
        """第 [frame] 份采样里各片的位置（材料坐标，(n, 2)）。"""
        frame = min(max(int(frame), 0), SAMPLES)
        data = self.history.read(size=self.n * 32, offset=frame * self.n * 32)
        return np.frombuffer(data, dtype='float32').reshape(self.n, 8)[:, :2].copy()

    def accumulate(self, position: float | None, surface: bool = True) -> bool:
        """把第 [position] 份采样（可带小数，发送按进度插值）的粒子累积到线性缓冲；None 时清空。"""
        ctx = self.ctx
        self.accum_fbo.use()
        ctx.viewport = (0, 0, self.out_w, self.out_h)
        ctx.clear(0., 0., 0., 0.)
        if position is None:
            return False
        t = self._restore(position)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
        if self.srgb:
            ctx.enable_direct(GL_FRAMEBUFFER_SRGB)
        self.program['time'] = float(t)
        self.fg_tex.use(0)
        for material_pass in ((0, 1) if surface else (1,)):
            # 与 Android 相同：表面按原像素覆盖（最近点），运动片线性过滤
            self.fg_tex.filter = (moderngl.NEAREST, moderngl.NEAREST) if material_pass == 0 \
                else (moderngl.LINEAR, moderngl.LINEAR)
            self.program['material_pass'] = material_pass
            self.vao.render(mode=moderngl.TRIANGLE_STRIP, vertices=4, instances=self.n)
        if self.srgb:
            ctx.disable_direct(GL_FRAMEBUFFER_SRGB)
        ctx.disable(moderngl.BLEND)
        return True

    def composite_onto(self, fbo):
        """把累积好的粒子层 resolve 成 sRGB 预乘，按合成器的方式叠到 [fbo] 上（粒子层在最上面）。"""
        ctx = self.ctx
        fbo.use()
        ctx.viewport = (0, 0, self.out_w, self.out_h)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
        self.accum.use(3)
        self.resolve['screen'] = 3
        self.resolve_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        ctx.disable(moderngl.BLEND)

    def render(self, moment: Moment, glow_at: tuple[float, float] | None = None, glow_px: tuple[float, float] = (0., 0.),
               particles_only: bool = False) -> np.ndarray:
        """只有粒子飞行的素材：画一刻，返回 (H, W, 3) uint8，与手机屏幕同一朝向。

        :param glow_at: 天线在屏幕上的像素位置；None 不画光
        :param glow_px: 光的（半径像素，不透明度）
        """
        ctx = self.ctx
        drawn = self.accumulate(moment.position)
        # 屏幕：底图（接收时两张底图按边框不透明度过渡）
        self.out_fbo.use()
        ctx.viewport = (0, 0, self.out_w, self.out_h)
        if particles_only:
            ctx.clear(0., 0., 0., 1.)
        else:
            self.bg_settled.use(1); self.bg_border.use(2)
            self.bg_program['settled'] = 1; self.bg_program['border'] = 2
            self.bg_program['border_alpha'] = float(moment.border)
            self.bg_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
        # 天线处的光：画在舞台上，位于粒子层之下
        if glow_at is not None and moment.glow > .01 and glow_px[0] > 0:
            w, h = self.scene.screen
            sx, sy = self.out_w / w, self.out_h / h
            self.glow_program['screen'] = (float(self.out_w), float(self.out_h))
            self.glow_program['center'] = (glow_at[0] * sx, glow_at[1] * sy)
            self.glow_program['radius'] = float(glow_px[0] * sx)
            self.glow_program['alpha'] = float(glow_px[1])
            self.glow_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        ctx.disable(moderngl.BLEND)
        if drawn:
            self.composite_onto(self.out_fbo)
        data = self.out_fbo.read(components=3, alignment=1)
        return np.frombuffer(data, dtype='uint8').reshape(self.out_h, self.out_w, 3)[::-1].copy()

    def close(self):
        if self.pressure is not None:
            self.pressure.close()
        for name in ('material', 'state', 'pigment', 'peel_compression', 'history', 'draw_state', 'fg_tex',
                     'handoff_target', 'handoff_origin', 'hand',
                     'compute', 'restore', 'program', 'vao', 'accum', 'accum_fbo', 'out_tex', 'out_fbo', 'resolve',
                     'resolve_vao', 'bg_program', 'bg_vao', 'glow_program', 'glow_vao', 'flow_tex', 'confidence_tex'):
            obj = getattr(self, name, None)
            if obj is not None:
                obj.release()
        for tex in {id(self.bg_border): self.bg_border, id(self.bg_settled): self.bg_settled}.values():
            tex.release()
        if hasattr(self, 'zero_peel'):
            self.zero_peel.release()
