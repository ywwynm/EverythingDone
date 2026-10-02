"""整段会话的出图：页面、罩层、舞台、按钮 / 面板、粒子层，按手机上的层次与时间线合成。

层次（自下而上）：
- 发送：页面（分享界面盖上之前的窗口）→ 黑色罩层 → 面板 → 舞台（整叠卡片共用的一片投影、卡片、条数、扩散圈）
        → 粒子层
- 接收：页面（隐去舞台与按钮的列表，让位时逐张卡片移动）→ 舞台（罩层与空洞、那圈光、边框粒子）
        → 按钮与提示 → 粒子层
"""
from __future__ import annotations

import math
import time as clock

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from sim_dust import ROUNDING, DustBand
from sim_emerge import ProxyFlight, note_backgrounds, proxy_scene, receive_params
from sim_handoff import compute as compute_handoff
from sim_layers import LIGHT_RADIUS_DP, LIGHT_Z_DP, Layers, affine, union_shadow
from sim_model import Params, build_materials, flow_mode, smooth
from sim_recolor import parse_accent
from sim_renderer import TapRenderer
from sim_stage import ReceiveStage, StageConfig
from sim_timeline import ReceivePlan, SendPlan, Session

FONT_BOLD = 'C:/Windows/Fonts/msyhbd.ttc'
# 接收的波纹画在浅色罩层上时的颜色（中性深灰，不与彩色光尘带抢色）；深色时仍是白色
RING_ON_LIGHT = (0x4A / 255, 0x52 / 255, 0x5E / 255)


def most_common_color(img: np.ndarray, box) -> tuple[float, float, float]:
    """区域里出现最多的颜色（隔 3 像素取样，RGB 打包成一个整数再计数）。"""
    x0, y0, x1, y1 = (int(v) for v in box)
    patch = img[max(y0, 0):max(y1, 1):3, max(x0, 0):max(x1, 1):3, :3].reshape(-1, 3).astype(np.int64)
    if len(patch) == 0:
        return (1.0, 1.0, 1.0)
    packed = (patch[:, 0] << 16) | (patch[:, 1] << 8) | patch[:, 2]
    values, counts = np.unique(packed, return_counts=True)
    c = int(values[counts.argmax()])
    return (((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0)


def touched_palette(params: Params, plan) -> list[int]:
    """碰上之后光尘带换成的调色板（与 ReceiveStage.touched 相同：来件颜色按原调色板的长度循环铺开）。"""
    size = len(params.palette('receive_border_palette'))
    usable = [c | 0xFF000000 for c in getattr(plan, 'colors', []) if c != 0]
    return [usable[k % len(usable)] for k in range(size)] if usable else list(params.palette('receive_border_palette'))


def make_flight(session: Session, params: Params, ctx: moderngl.Context, scale: float, seed: int, plan,
                stage_track=None) -> tuple:
    """一次粒子飞行：(材料, 渲染器, 进度期间那一层)。接收时两层（代理卡片、真实卡片的倒放）共用物理参数与
    倒放时间轴，流入开始时交叉淡化；四周的光尘就近接手真实那层流向四边的一部分颗粒。"""
    scene = session.flights[0][1]
    receive = session.kind == 'receive' and flow_mode() == 'field' and plan.antenna is not None \
        and plan.t_touched is not None and plan.t_inflow is not None and bool(plan.holes)
    if receive:
        params = receive_params(params)
        # 真实那层的运动按同样大小、代表色的纯色卡片来算，只从真实卡片取颜色：弹窗消散模型会按内容（文字、
        # 白底、图片）调整剥离先后与颗粒走法，用真实内容算出来的轨迹与代理那层对不上。预计落点准确时两层
        # 逐片相同，交叉淡化时只有颜色从代表色过渡到卡片内容
        backgrounds = note_backgrounds(session)
        colors = [backgrounds[k % len(backgrounds)].representative() for k in range(len(plan.holes))]
        dynamics = proxy_scene(scene, plan, [tuple(float(v) for v in r) for r in plan.holes], colors)
        build = build_materials(dynamics, params, seed)
    else:
        build = build_materials(scene, params, seed)
    emergence = None
    if receive:
        emergence = ProxyFlight(ctx, session, plan, params, scene, scale, int(seed))
        w, h = plan.stage_rect[2], plan.stage_rect[3]
        sx, sy = plan.stage_rect[:2]
        dust = DustBand(w, h, session.density, plan.corner, params)
        cycle = params['receive_border_palette_cycle_s']
        frames = stage_track['frames'] if stage_track else None

        def palette_at(t):
            if frames:
                step = frames[1][0] - frames[0][0] if len(frames) > 1 else 1 / 60
                k = min(max(int((t - frames[0][0]) / step), 0), len(frames) - 1)
                return frames[k][1].palette
            return touched_palette(params, plan)

        class Shifted:
            """光尘在舞台坐标里，交接用屏幕坐标：只平移，不改别的。"""
            def __init__(self, band):
                self.band, self.n = band, band.n
                self.base_alpha = band.base_alpha

            def evaluate(self, idx, *args, **kwargs):
                x, y, q, e = self.band.evaluate(idx, *args, **kwargs)
                return x + sx, y + sy, q, e

            def inner_fade(self, q):
                return self.band.inner_fade(q)

            def colors(self, idx, x, y, palette, turn):
                return self.band.colors(idx, x - sx, y - sy, palette, turn)

        build.handoff = compute_handoff(build, plan, Shifted(dust), lambda t: t - plan.t_stage, palette_at,
                                        lambda t: (t - plan.t_stage) / cycle, session.density, params, seed)
    renderer = TapRenderer(scene, build, ctx, scale, layer_alpha=receive)
    return build, renderer, emergence


def close_flight(flight):
    if flight is None:
        return
    flight[1].close()
    if len(flight) > 2 and flight[2] is not None:
        flight[2].close()


class SessionPlayer:
    """一次会话、一组参数、一个种子 → 按时间取图。"""

    def __init__(self, session: Session, params: Params, ctx: moderngl.Context, output_scale: float = 1.,
                 seed: int | None = None, stage_hz: float = 120.0, stage_track=None, flight=None, plan=None,
                 accent=None):
        """
        :param flight: `make_flight` 的结果（材料、渲染器、冒出的颗粒），由调用方缓存与释放；不给则自己建、
                       关闭时自己释放
        :param stage_track: 接收舞台（`simulate_stage` 的结果），由调用方缓存；不给则自己算
        :param plan: 时间线（`plan_for` 的结果，调用方可以先改再交进来）；不给则按参数建
        :param accent: 分享面板的强调色（`sim_recolor.Background` 或 `parse_accent` 认得的写法），扩散圈落在面板上
                       的那一段按它画；不给则取会话的（`Session.accent`：记了就用，没记按卡片的颜色）
        """
        started = clock.perf_counter()
        self.s, self.ctx = session, ctx
        self.accent = parse_accent(accent) if accent is not None else getattr(session, 'accent', None)
        self.output_scale = output_scale
        w, h = session.screen
        self.out_w, self.out_h = max(1, round(w * output_scale)), max(1, round(h * output_scale))
        self.tex = ctx.texture((self.out_w, self.out_h), 4)
        self.fbo = ctx.framebuffer([self.tex])
        self.layers = Layers(ctx, session.screen, (self.out_w, self.out_h))
        # 粒子飞行：会话里的第一段（一次会话一次碰触）
        self.flight_scene = session.flights[0][1] if session.flights else None
        self.seed = int(self.flight_scene.meta['seed'] if seed is None else seed) if self.flight_scene else None
        self.flight = self.build = self.emerge = None
        self.bundle = None
        self.owns_flight = False
        self.flight_key = None
        self.stage_hz = stage_hz
        self.stage_track = None
        self.shadows = {}
        self.retune(params, plan=plan, stage_track=stage_track, flight=flight)
        self.setup_ms = (clock.perf_counter() - started) * 1000

    @staticmethod
    def plan_for(session: Session, params: Params):
        return SendPlan(session, params) if session.kind == 'send' else ReceivePlan(session, params)

    def retune(self, params: Params, plan=None, stage_track=None, flight=None):
        """换一组参数：时间线总是重建；粒子轨迹与接收舞台优先用调用方给的（缓存），没给且受影响时自己重算。
        接收时舞台先于粒子飞行：交接要用光尘在那一刻的调色板。"""
        self.p = params
        self.plan = plan if plan is not None else self.plan_for(self.s, params)
        self.duration = self.plan.duration
        if self.s.kind == 'receive':
            w, h = self.plan.stage_rect[2], self.plan.stage_rect[3]
            self.dust = DustBand(w, h, self.s.density, self.plan.corner, params)
            if stage_track is not None:
                self.stage_track = stage_track
            elif self.stage_track is None or self.stage_track.get('key') != self.stage_key(self.s, params, self.plan,
                                                                                            self.stage_hz):
                self.stage_track = self.simulate_stage(self.s, params, self.plan, self.stage_hz)
        if self.flight_scene is not None:
            key = self.flight_key_for(self.s, params, self.seed, self.plan)
            if flight is not None:
                self._release_flight()
                self._use(flight, owned=False)
            elif self.flight is None or (self.owns_flight and key != self.flight_key):
                self._release_flight()
                self._use(make_flight(self.s, params, self.ctx, self.output_scale, self.seed, self.plan,
                                      self.stage_track), owned=True)
            self.flight_key = key

    @staticmethod
    def flight_key_for(session: Session, params: Params, seed, plan) -> tuple:
        """粒子飞行取决于：物理类参数、终点与释放起点、种子；接收时还取决于冒出与交接用到的各个时刻。"""
        scene = session.flights[0][1]
        origin = scene.meta.get('release_origin')
        timing = ()
        if session.kind == 'receive':
            timing = tuple(round(v, 5) if v is not None else None for v in
                           (plan.t_touched, plan.t_complete, plan.t_inflow, plan.t_assembled, plan.t_stage))
        return (str(session.path), params.physics_signature(), tuple(scene.meta['sink']),
                None if origin is None else tuple(origin), seed, timing)

    def _use(self, flight, owned: bool):
        self.bundle = flight
        self.build, self.flight = flight[0], flight[1]
        self.emerge = flight[2] if len(flight) > 2 else None
        self.owns_flight = owned

    def _release_flight(self):
        if self.bundle is not None and self.owns_flight:
            close_flight(self.bundle)
        self.flight = self.build = self.emerge = self.bundle = None
        self.owns_flight = False

    # ---------------------------------------------------------------- 接收舞台：整段先算好
    @staticmethod
    def simulate_stage(session: Session, params: Params, plan: ReceivePlan, hz: float) -> dict:
        """按设备的做法逐帧推进舞台（帧间隔 1/hz），每两帧存一份（出图按 60 帧取）。"""
        w, h = plan.stage_rect[2], plan.stage_rect[3]
        # 边框颗粒已换成光尘带（按时间求值，不在这里推进）：旧的拖尾粒子不再模拟，舞台只提供罩层、
        # 空洞、调色板与整条带的不透明度
        stage = ReceiveStage(StageConfig(w, h, session.density, plan.corner, plan.dim_color,
                                         (params['receive_dim_alpha'][1] if plan.dark else params['receive_dim_alpha'][0]),
                                         capacity=0),
                             params)
        dt = 1.0 / hz
        actions = list(plan.stage_actions)
        frames = []
        stride = max(1, int(round(hz / 60)))
        t = actions[0][0] if actions else 0.0
        k = 0
        while t < plan.duration + dt:
            while actions and actions[0][0] <= t:
                _, name, arg = actions.pop(0)
                if name == 'enter':
                    stage.enter()
                elif name == 'touched':
                    stage.touched(arg)
                elif name == 'converge':
                    stage.converge()
                elif name == 'highlight':
                    stage.highlight(*arg)
                elif name == 'resume':
                    stage.resume()
                elif name == 'exit':
                    stage.exit()
            frame = stage.frame(dt)
            if k % stride == 0:
                if frame is not None and frame.particles is not None:
                    head, tail, radius, rgba = frame.particles
                    frame.particles = (head.astype('float32'), tail.astype('float32'), radius.astype('float32'),
                                       rgba.astype('float32'))
                frames.append((t, frame))
            t += dt
            k += 1
        return dict(hz=hz, frames=frames, start=plan.stage_actions[0][0] if plan.stage_actions else 0.0,
                    key=SessionPlayer.stage_key(session, params, plan, hz))

    @staticmethod
    def stage_key(session: Session, params: Params, plan, hz: float):
        """舞台只取决于这几样：会话、舞台类参数、推进帧率、各动作的时刻（拼好的时刻随流入时长移）。"""
        actions = tuple((round(t, 4), name) for t, name, _ in getattr(plan, 'stage_actions', []))
        return (str(session.path), params.stage_signature(), float(hz), actions)

    def stage_frame(self, t: float):
        track = self.stage_track
        if not track or not track['frames']:
            return None
        frames = track['frames']
        if t < frames[0][0]:
            return None
        step = frames[1][0] - frames[0][0] if len(frames) > 1 else 1 / 60
        k = min(int((t - frames[0][0]) / step), len(frames) - 1)
        return frames[k][1]

    # ---------------------------------------------------------------- 出图
    def render(self, t: float) -> tuple[np.ndarray, str]:
        ctx = self.ctx
        self.fbo.use()
        ctx.viewport = (0, 0, self.out_w, self.out_h)
        ctx.clear(0., 0., 0., 1.)
        if self.s.kind == 'send':
            state = self.plan.at(t)
            self._render_send(state)
            position = state.flight_position
            label = state.label
        else:
            state = self.plan.at(t)
            self._render_receive(t, state)
            position = state.flight_position
            label = state.label
        if self.flight is not None and position is not None:
            if self.s.kind == 'receive' and self.plan.t_inflow is not None:
                # 拼卡那一层在流入开始之后的 [0, 淡入时长] 里整层淡入（光尘接手的颗粒不受它影响）
                fade = self.p['receive_crossfade'][0]
                self.flight.set_layer_alpha(smooth(self.plan.t_inflow, self.plan.t_inflow + fade, t))
            self.flight.accumulate(position)
            self.flight.composite_onto(self.fbo)
        data = self.fbo.read(components=3, alignment=1)
        image = np.frombuffer(data, dtype='uint8').reshape(self.out_h, self.out_w, 3)[::-1].copy()
        return image, label

    # ---- 发送
    def _render_send(self, st):
        L, s, plan, p = self.layers, self.s, self.plan, self.p
        W, H = s.screen
        page = s.image('page')
        L.draw_image_region(L.texture('page', page), (page.shape[1], page.shape[0]), (0, 0, W, H), (0, 0))
        L.fill((0, 0, W, H), (0.0, 0.0, 0.0), st.dim_alpha)
        if st.panel_image:
            cap = s.capture(st.panel_image)
            img = s.image(st.panel_image)
            l, t = cap['layout'][:2]
            px, py = cap['parent']
            L.draw_image_region(L.texture(st.panel_image, img), (img.shape[1], img.shape[0]),
                                (0, 0, img.shape[1], img.shape[0]), (px + l + st.panel_tx, py + t + st.panel_ty))
        ox, oy = plan.stage_offset
        if st.cards_shown:
            # 整叠共用一片投影，画在所有卡片底下；卡片从最底下一张开始画，最上面一张最后画
            self._draw_stack_shadow(st, ox, oy)
            for pose in reversed(st.cards):
                self._draw_card(pose, ox, oy)
            if plan.total > 1 and st.cards:
                self._draw_badge(st.cards[0], ox, oy)
        # 天线处只有波纹（2026-09-25 起不再画光点）：从等待被碰一直播到关闭，流出时随进度收缩。
        # 落在面板上的那一段换成强调色，从完全不透明开始随相位淡出（2026-09-27 起），其余仍是白色
        ax, ay = plan.antenna
        center = (ax + ox, ay + oy)
        panel = self._panel_near_rings(st, center) if self.accent is not None else None
        for radius, alpha, phase in st.pulses:
            if panel is None:
                L.ring(center, radius, alpha)
            else:
                L.ring(center, radius, alpha, clip=('out',) + panel)
                L.ring(center, radius, 1 - phase, accent=self.accent, clip=('in',) + panel)

    def _panel_near_rings(self, st, center):
        """`TapShareCardStage.panelPathNearRings`：面板此刻（含滑入、滑出的平移）的圆角轮廓，屏幕坐标
        (矩形, 四角圆角)；碰不到最大半径以内的圈时为 None，整圈画成白色。

        手机上每帧读面板视图的位置，面板随文案变高时轮廓跟着变（三星那段录下的面板上沿从 2036 移到 2022）：
        这里取此刻画出来的那张面板截图记下的布局，与画面上的面板一致；没有截图时用 `start` 事件里的面板矩形。"""
        s, p, plan = self.s, self.p, self.plan
        cap = s.capture(st.panel_image) if st.panel_image else None
        if cap is not None and cap.get('layout') and cap.get('parent'):
            l, t, r, b = cap['layout']
            ox, oy = cap['parent']
        else:
            l, t, w, h = plan.panel_rect
            r, b = l + w, t + h
            ox, oy = plan.stage_offset
        if r <= l or b <= t:
            return None
        r0, r1 = p['send_pulse_radius_dp']
        reach = (r0 + r1) * s.density * st.pulse_scale
        rect = (ox + l + st.panel_tx, oy + t + st.panel_ty, ox + r + st.panel_tx, oy + b + st.panel_ty)
        cx, cy = center
        # RectF.intersects：两边都是严格不等
        if not (rect[0] < cx + reach and cx - reach < rect[2] and rect[1] < cy + reach and cy - reach < rect[3]):
            return None
        return rect, plan.panel_radii

    def _draw_stack_shadow(self, st, ox, oy):
        """整叠卡片共用的一片投影（`TapShareCardStage` 的 shadow 视图，决策 2026-09-26 第 6 条）：轮廓是此刻已经
        淡入的几张卡片（含平移、缩放、旋转）的并集，卡片自己不投影，上面的卡片不会压暗下面的；不透明度跟最先
        淡入的那张（`updateShadow`）。高度的升降沿用原来的近似：不透明度乘高度比例，点光源的下移随高度收回。"""
        L, s, p = self.layers, self.s, self.p
        casters = [pose for pose in st.cards if pose.alpha > 0.0]
        if st.elevation <= 0.001 or not casters:
            return
        e_px = p['send_elevation_dp'] * s.density
        key = (round(e_px, 3),) + tuple((pose.index, round(pose.cx, 2), round(pose.cy, 2), round(pose.scale, 5),
                                         round(pose.rotation, 4)) for pose in casters)

        def build():
            items = []
            for pose in casters:
                img = s.image(f'card-{pose.index}')
                ch, cw = img.shape[:2]
                items.append((img[:, :, 3], affine(ox + pose.cx - cw / 2, oy + pose.cy - ch / 2, pose.scale,
                                                   pose.scale, pose.rotation, (cw / 2, ch / 2))))
            return union_shadow(items, e_px, LIGHT_Z_DP * s.density, LIGHT_RADIUS_DP * s.density)

        tex, (x0, y0), size, spot = L.stack_shadow(key, build)
        lift = (1 - st.elevation) * spot   # 高度降下来时点光源的投影往回收
        L.draw_texture(tex, size, affine(x0, y0 - lift), st.elevation * max(pose.alpha for pose in st.cards))

    def _draw_card(self, pose, ox, oy):
        L, s = self.layers, self.s
        name = f'card-{pose.index}'
        img = s.image(name)
        ch, cw = img.shape[:2]
        xf = affine(ox + pose.cx - cw / 2, oy + pose.cy - ch / 2, pose.scale, pose.scale, pose.rotation, (cw / 2, ch / 2))
        L.draw_texture(L.texture(name, img), (cw, ch), xf, pose.alpha)

    def _draw_badge(self, pose, ox, oy):
        s = self.s
        key = f'badge-{self.plan.total}'
        if key not in self.layers.textures:
            d = s.density
            r = int(round(13 * d))
            img = Image.new('RGBA', (2 * r + 4, 2 * r + 4), (0, 0, 0, 0))
            draw = ImageDraw.Draw(img)
            draw.ellipse((2, 2, 2 + 2 * r, 2 + 2 * r), fill=(0x20, 0x21, 0x24, 0xE6))
            font = ImageFont.truetype(FONT_BOLD, int(13 * d))
            text = str(self.plan.total)
            box = draw.textbbox((0, 0), text, font=font)
            draw.text((r + 2 - (box[0] + box[2]) / 2, r + 2 - (box[1] + box[3]) / 2), text, fill=(255, 255, 255, 255),
                      font=font)
            self.layers.texture(key, np.array(img))
        tex = self.layers.textures[key]
        w, h = tex.size
        cw, ch = s.image(f'card-{pose.index}').shape[1::-1]
        cx = ox + pose.cx + cw / 2 * pose.scale - 2 * s.density
        cy = oy + pose.cy - ch / 2 * pose.scale + 2 * s.density
        self.layers.draw_texture(tex, (w, h), affine(cx - w / 2, cy - h / 2), pose.alpha)

    # ---- 接收
    def _render_receive(self, t, st):
        L, s, plan, p = self.layers, self.s, self.plan, self.p
        cx0, cy0 = plan.container[:2]
        self._draw_page(st.page, cx0, cy0)
        frame = self.stage_frame(t)
        if frame is not None:
            sx, sy = plan.stage_rect[:2]
            holes = [(sx + r[0], sy + r[1], sx + r[2], sy + r[3]) for r in frame.holes]
            L.dim(plan.dim_color, frame.dim_alpha, holes, frame.hole_open, plan.hole_radius)
            spread = plan.spread(t)
            # 光尘的时钟：进入接收状态以来的秒数（交接按同一个时钟取光尘的位置）
            now = t - plan.t_stage
            turn = now / p['receive_border_palette_cycle_s']
            handoff = getattr(self.build, 'handoff', None) if self.build is not None else None
            band_alpha, glow_alpha = frame.border_alpha, frame.border_alpha
            pull, retreat, retreat_alpha, keep, hidden = 0.0, 0.0, 1.0, None, None
            if plan.t_inflow is not None and t > plan.t_inflow:
                if handoff is not None and plan.t_assembled is not None and t < plan.t_assembled:
                    # 交接：扩散出去的一部分光尘被拼卡的颗粒接手（照常运动到交接那一刻），其余向外退去、渐隐
                    distance_dp, duration = p['receive_dust_retreat']
                    u = min((t - plan.t_inflow) / max(duration, 1e-3), 1.0)
                    retreat = distance_dp * s.density * u * u * (3 - 2 * u)
                    retreat_alpha = (1 - u) ** 1.5
                    band_alpha, glow_alpha = 1.0, retreat_alpha
                    keep, hidden = handoff.dust_keep, t >= handoff.dust_until
                elif handoff is None:
                    u = min((t - plan.t_inflow) / max(p['receive_border_converge_s'], 1e-3), 1.0)
                    pull = p['receive_dust_pull_dp'] * s.density * u * u
            if glow_alpha > 0.01:
                # 带底下的柔光贴着屏幕边缘，随扩散稍微向里延伸
                inset, reach = self.dust.reach(spread)
                sigma = reach * .4 + p['receive_dust_glow'][0] * s.density
                L.band_glow(frame.palette, turn, plan.corner, sx + inset + reach * .1, sigma,
                            p['receive_dust_glow'][1] * glow_alpha, ROUNDING)
            dust = self.dust.at(now, spread, plan.diffuse_time(t), band_alpha, frame.palette, turn, pull, retreat,
                                retreat_alpha, keep, hidden)
            L.flakes(dust, (sx, sy))
        # 天线处的波纹（与发送同一套）：开始接收时才出现，流入时边收缩边淡去
        antenna = plan.antenna or (self.flight_scene.sink if self.flight_scene is not None else None)
        if antenna is not None:
            tint = (1.0, 1.0, 1.0) if plan.dark else RING_ON_LIGHT
            for radius, alpha, _ in plan.pulses(t):
                L.ring(antenna, radius, alpha, tint)
        # 进度期间那一层：代理卡片的倒放（弹窗出现的前半段），按传输进度推进，流入开始后逐片交给真实卡片
        if self.emerge is not None:
            position = self.emerge.position(t)
            self.emerge.renderer.set_layer_alpha(self.emerge.alpha(t))
            if position is not None and self.emerge.renderer.accumulate(position, surface=False):
                self.emerge.renderer.composite_onto(self.fbo)
        if st.pull is not None:
            name, layout, parent, alpha, scale, dy = st.pull
            img = s.image(name)
            l, tp, r, b = layout
            w, h = r - l, b - tp
            xf = affine(parent[0] + l, parent[1] + tp + dy, scale, scale, 0.0, (w / 2, h / 2))
            L.draw_texture(L.texture(name, img), (img.shape[1], img.shape[0]), xf, alpha)

    def _page_background(self, name):
        key = ('bg', name)
        if key not in self.shadows:
            img = self.s.image(name)
            ab = self.plan.actionbar_bottom
            self.shadows[key] = most_common_color(img, (0, ab, img.shape[1], img.shape[0]))
        return self.shadows[key]

    def _draw_page(self, page, cx0, cy0):
        L, s, plan = self.layers, self.s, self.plan
        kind = page[0]
        if kind == 'shift':
            _, name, dy = page
            img = s.image(name)
            H, W = img.shape[:2]
            tex = L.texture(name, img)
            ab = plan.actionbar_bottom
            bg = self._page_background(name)
            L.fill((cx0, cy0, cx0 + W, cy0 + H), bg)
            # 顶栏不动，下面的头部与列表整体平移
            L.draw_image_region(tex, (W, H), (0, ab, W, H), (cx0, cy0 + ab + dy))
            if dy > 0:
                L.fill((cx0, cy0 + ab, cx0 + W, cy0 + ab + dy), bg)
            # 顶栏最后画：上移时列表的上沿压在它下面
            L.draw_image_region(tex, (W, H), (0, 0, W, ab), (cx0, cy0))
            return
        # 让位：以插入之前那张为底，每张卡片从原位置擦掉、画到这一帧的位置上
        t = page[1]
        before = s.image('page-before')
        slots = s.image('page-slots')
        H, W = before.shape[:2]
        tb = L.texture('page-before', before)
        ts = L.texture('page-slots', slots)
        L.draw_image_region(tb, (W, H), (0, 0, W, H), (cx0, cy0))
        frames = [f for f in plan.item_frames if f[0] >= plan.t_before - 1e-6]
        if not frames:
            return
        first = {it[0]: it for it in frames[0][2]}
        current = frames[0]
        for f in frames:
            if f[0] <= t:
                current = f
        last = {it[0]: it for it in frames[-1][2]}
        bg = self._page_background('page-before')
        pad = 3 * s.density
        for iid, x, y, w, h, a in first.values():
            # 不可见的项（头部的占位项）不擦：「正在进行 N 件记事」是另一个视图画在它上面的，擦了就没了
            if a > 0.001:
                L.fill((cx0 + x - pad, cy0 + y - pad, cx0 + x + w + pad, cy0 + y + h + pad), bg)
        for iid, x, y, w, h, a in current[2]:
            if a <= 0.001:
                continue
            if iid in first:
                _, x0, y0, _, _, _ = first[iid]
                tex, src = tb, (x0 - pad, y0 - pad, x0 + w + pad, y0 + h + pad)
            elif iid in last:
                _, x0, y0, _, _, _ = last[iid]
                tex, src = ts, (x0 - pad, y0 - pad, x0 + w + pad, y0 + h + pad)
            else:
                continue
            L.draw_image_region(tex, (W, H), src, (cx0 + x - pad, cy0 + y - pad), a)

    def close(self):
        self.layers.release()
        self._release_flight()
        self.fbo.release()
        self.tex.release()

    def describe(self) -> str:
        parts = [self.s.title, f'{self.duration:.1f} 秒']
        if self.flight is not None:
            parts.append(f'种子 {self.seed} · {self.build.stats["count"]} 片')
        if self.stage_track:
            parts.append(f'边框按 {self.stage_hz:g} Hz 推进')
        return ' · '.join(parts)
