"""整段会话的时间线：由真机记下的事件（`TapSessionRecorder`）与参数文件推出每一时刻各层的状态。

外部决定的时刻（界面打开、碰上、传输各阶段、列表插入与让位、首帧上屏、用户关闭或退出）取录下的值；
应用自己控制的时长与缓动（抬起、罩层、面板、投影、流出、停留、流回、流入、舞台的各种淡入淡出）取
参数文件。改了参数，受它影响的后续时刻跟着移，例如流出变长，「已发送」与流回随之推后。

时间单位：会话时间（秒），0 是分享界面打开 / 进入接收状态的那一刻。
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image

from sim_model import SAMPLES, Params, Scene, cubic_bezier, smooth
from sim_recolor import Background, dominant, parse_accent


def accel_decel(t: float) -> float:
    """`ValueAnimator` 不设插值器时的默认曲线（AccelerateDecelerateInterpolator）。"""
    t = min(max(t, 0.0), 1.0)
    return math.cos((t + 1) * math.pi) / 2 + 0.5


def ripple_rings(now: float, params: Params, density: float,
                 scale: float = 1.0) -> list[tuple[float, float, float]]:
    """`TapShareCardStage` 的扩散圈：rings 圈错开相位，半径 = 起始 + 扩展 × 缓出进度（再乘 scale），
    不透明度随相位线性减到 0。返回 [(半径像素, 不透明度, 相位)]；落在分享面板上的那一段按 1 − 相位画。"""
    rings = int(params['send_pulse_rings'])
    r0, r1 = params['send_pulse_radius_dp']
    out = []
    for k in range(rings):
        phase = ((now / params['send_pulse_period_s']) + k / max(rings, 1)) % 1.0
        eased = 1 - (1 - phase) ** 2
        out.append(((r0 + r1 * eased) * density * scale, (1 - phase) * params['send_pulse_alpha'], phase))
    return out


EASE_PANEL_OPEN = cubic_bezier(0.2, 0, 0, 1)
EASE_PANEL_CLOSE = cubic_bezier(0.4, 0, 1, 1)

# 分享面板四角的圆角（dp，`TapShareOverlay.buildViews` 交给 `TapShareCardStage.setPanel` 的那一组）：上下排时面板
# 悬浮在底部，四角同「调整颜色」面板（`app_chrome_dialog_popup_corner_radius`）；手机横屏贴右半边时只有左侧两角
# （`SIDE_PANEL_CORNER_DP`，与 `bg_tap_share_panel_side` 相同）
PANEL_CORNER_DP = 16.0
SIDE_PANEL_CORNER_DP = 24.0

# 倒放时间轴在传输期间从上限的这个倍数起软着陆（`TapReceiveTimeline.KNEE`）
SOFT_CAP_KNEE = 0.8


class Session:
    """一次完整会话：`session.json`、各层截图、其中的粒子飞行（`flight-<n>/`）。"""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.meta = json.loads((self.path / 'session.json').read_text(encoding='utf-8'))
        self.kind = self.meta['kind']
        self.events = self.meta['events']
        w, h = self.meta['screen']
        self.screen = (int(w), int(h))
        self.density = float(self.meta['density'])
        self._images = {}
        self.flights = []
        for e in self.of('flight'):
            scene = Scene.load(self.path / e['name'])
            # 接收：几张卡片各自的矩形（屏幕坐标，舞台留洞时记下的那几块），用来按卡片分组
            highlight = self.first('stage-highlight')
            if self.kind == 'receive' and highlight and 'cards' not in scene.meta:
                scene.meta['cards'] = [list(r) for r in highlight['rects']]
            self.flights.append((e, scene))

    @property
    def title(self) -> str:
        return self.meta.get('title') or self.path.name

    def of(self, kind: str) -> list[dict]:
        return [e for e in self.events if e['type'] == kind]

    def first(self, kind: str, **match) -> dict | None:
        for e in self.events:
            if e['type'] == kind and all(e.get(k) == v for k, v in match.items()):
                return e
        return None

    def capture(self, name: str) -> dict | None:
        return self.first('capture', name=name)

    def channel(self, channel: str) -> list[dict]:
        return sorted((e for e in self.of('capture') if e.get('channel') == channel), key=lambda e: e['captured'])

    def image(self, name: str) -> np.ndarray:
        """截图，RGBA uint8。"""
        if name not in self._images:
            self._images[name] = np.array(Image.open(self.path / f'{name}.png').convert('RGBA'))
        return self._images[name]

    @property
    def params(self) -> Params:
        return Params.from_scene(self)

    @property
    def accent(self) -> Background | None:
        """分享面板的强调色（`TapShareOverlay.Request.accent`）：扩散圈落在面板上的那一段按它画。

        会话里记了就用（`start` 事件的 `accent`，或手动加在 `session.json` 顶层的 `accent`；写法见
        `sim_recolor.parse_accent`）；没记就取最上面一张卡片里出现最多的颜色——详情页分享时强调色就是记事的颜色，
        选择模式里分享时是应用的强调色，要另外给（`--accent`）。接收的会话没有面板，为 None。"""
        if self.kind != 'send':
            return None
        if not hasattr(self, '_accent'):
            start = self.first('start') or {}
            accent = parse_accent(start.get('accent', self.meta.get('accent')))
            if accent is None and (self.path / 'card-0.png').is_file():
                accent = Background(dominant(self.image('card-0')))
            self._accent = accent
        return self._accent


class Ramp:
    """一个属性的一串动画段：(开始时刻, 目标值, 时长, 缓动)。每段从当时的值出发，与 ValueAnimator 相同。"""

    def __init__(self, initial: float):
        self.initial = initial
        self.segments: list[tuple[float, float, float, object]] = []

    def to(self, start: float, target: float, duration: float, ease=accel_decel):
        self.segments.append((start, target, max(duration, 1e-6), ease))
        self.segments.sort(key=lambda s: s[0])
        return self

    def at(self, t: float) -> float:
        value = self.initial
        for k, (start, target, duration, ease) in enumerate(self.segments):
            if t < start:
                break
            # 下一段开始就把这一段截断（动画被取消，从当时的值接着走）
            end = start + duration
            if k + 1 < len(self.segments):
                end = min(end, self.segments[k + 1][0])
            origin = value
            u = min((min(t, end) - start) / duration, 1.0)
            value = origin + (target - origin) * ease(u)
        return value


# ---------------------------------------------------------------- 发送

@dataclass
class CardPose:
    index: int
    cx: float
    cy: float
    scale: float
    rotation: float
    alpha: float


@dataclass
class SendState:
    dim_alpha: float
    panel_ty: float
    panel_image: str | None
    cards: list
    elevation: float
    cards_shown: bool
    pulses: list
    glow: float
    flight_position: float | None
    label: str
    # 面板的横向平移（手机横屏时从右侧滑入）；扩散圈此刻的缩放（流出时收缩）
    panel_tx: float = 0.0
    pulse_scale: float = 1.0


class SendPlan:
    """发送会话：`TapShareOverlay` 与 `TapShareCardStage` 的编排。"""

    def __init__(self, session: Session, params: Params):
        self.s, self.p = session, params
        d = session.density
        start = session.first('start')
        self.t_start = start['t'] / 1000
        self.stage_offset = tuple(start['stage'][:2])
        self.slots = start['slots']
        self.antenna = tuple(start['antenna'])
        self.antenna_decor = tuple(start.get('antenna_decor') or start['antenna'])
        self.total = int(start.get('total', len(self.slots)))
        self.pulse_start = start['pulse_start'] / 1000
        # 面板在舞台里的位置 [left, top, width, height]（舞台与面板同一个父视图）；上下排时沿 y 滑入滑出，
        # 手机横屏贴右半边时沿 x（panel 事件的 axis，2026-09-26 起才记；start 的 side_by_side 更晚才有）
        self.panel_rect = start['panel']
        panel_events = session.of('panel')
        axis = next((e['axis'] for e in panel_events if e.get('axis')), None)
        side = start.get('side_by_side')
        side = axis == 'x' if side is None else bool(side)
        self.panel_axis = axis or ('x' if side else 'y')
        slide = float(self.panel_rect[2] if side else self.panel_rect[3])
        corner = (SIDE_PANEL_CORNER_DP if side else PANEL_CORNER_DP) * d
        # 左上、右上、右下、左下
        self.panel_radii = (corner, 0.0, 0.0, corner) if side else (corner,) * 4
        self.dark = bool(start.get('dark'))
        phases = [(e['t'] / 1000, e['name']) for e in session.of('phase')]
        self.phases = phases
        self.t_ready = next((t for t, n in phases if n == 'READY'), self.t_start)
        self.t_connect = next((t for t, n in phases if n == 'CONNECTING'), None)
        self.t_sent_phase = next((t for t, n in phases if n == 'SENT'), None)
        close = session.first('close')
        self.t_close = close['t'] / 1000 if close else None
        take = session.first('takeover')
        rec_drop = next((e['duration'] for e in session.of('elevation') if e['to'] == 0 and e['duration'] < 300), 140)
        ms = lambda key: params[key] / 1000  # noqa: E731
        # 碰上 → 压下投影 → 截图、建粒子层 → 首帧上屏：截图与预算的那一段取录下的值
        self.t_takeover = None
        if take is not None:
            self.t_takeover = take['t'] / 1000 + (params['send_shadow_drop_ms'] - rec_drop) / 1000
        self.outflow_s = ms('send_outflow_ms')
        # 发送完成后卡片重新抬起（与打开分享时同一段抬起），不再从天线处流回：流回像是发送失败、被撤回
        self.reform_s = ms('send_lift_ms')
        self.out_ease = cubic_bezier(*params['send_outflow_ease'])
        self.t_drained = self.t_reform = self.t_returned = None
        if self.t_takeover is not None:
            self.t_drained = self.t_takeover + self.outflow_s
            show_sent = max(self.t_drained, self.t_sent_phase or self.t_drained)
            self.t_reform = show_sent + ms('send_sent_hold_ms')
            self.t_returned = self.t_reform + self.reform_s
            if self.t_close is not None and self.t_close <= self.t_reform:
                self.t_reform = self.t_returned = None
        dims = params['send_dim_alpha']
        dim_target = dims[1] if self.dark else dims[0]
        self.dim = Ramp(0.0).to(self.t_start, dim_target, ms('send_open_ms'))
        # 滑入的起点取录下的值（悬浮的面板要走到屏幕底边之外，比面板本身高）
        open_from = next((e['from'] for e in panel_events if e.get('ease') == 'open'), slide)
        self.panel = Ramp(float(open_from)).to(self.t_start, 0.0, ms('send_open_ms'), EASE_PANEL_OPEN)
        lift_ease = cubic_bezier(*params['send_lift_ease'])
        self.lift = Ramp(0.0).to(self.t_start, 1.0, ms('send_lift_ms'), lift_ease)
        self.elevation = Ramp(0.0).to(self.t_start, 1.0, ms('send_lift_ms'))
        if self.t_connect is not None:
            self.elevation.to(self.t_connect, 0.0, ms('send_shadow_drop_ms'))
        if self.t_reform is not None:
            # 重新抬起：卡片此前已化成颗粒流走（不可见），抬起从 0 开始，投影随抬起恢复
            self.lift.to(self.t_reform, 0.0, 1e-6).to(self.t_reform + 1e-6, 1.0, self.reform_s, lift_ease)
            self.elevation.to(self.t_reform, 1.0, self.reform_s)
        if self.t_close is not None:
            close_to = next((e['to'] for e in panel_events if e.get('ease') == 'close'), slide)
            self.dim.to(self.t_close, 0.0, ms('send_close_ms'))
            self.panel.to(self.t_close, float(close_to), ms('send_close_ms'), EASE_PANEL_CLOSE)
            # 落回：ValueAnimator 的时长按剩余进度缩放
            self.lift.to(self.t_close, 0.0, ms('send_drop_ms') * max(self.lift.at(self.t_close), 1e-3),
                         cubic_bezier(*params['send_drop_ease']))
            self.elevation.to(self.t_close, 0.0, ms('send_drop_ms'))
        end = session.first('end')
        tail = max(ms('send_drop_ms'), ms('send_close_ms')) + 0.35
        self.duration = max((end['t'] / 1000) if end else 0.0, (self.t_close or 0.0) + tail,
                            (self.t_returned or 0.0) + 0.6)
        self.panels = [(e['captured'] / 1000, e['name']) for e in session.channel('panel')]

    def milestones(self) -> list[tuple[str, float]]:
        """各阶段的起点（会话时间），界面上按段跳转、循环用。"""
        marks = [('打开、抬起', self.t_start), ('等待被碰', self.t_ready), ('碰上、连接', self.t_connect),
                 ('流出', self.t_takeover), ('已发送', self.t_drained), ('重新抬起', self.t_reform),
                 ('复原、再等', self.t_returned), ('关闭、落回', self.t_close)]
        return sorted(((n, t) for n, t in marks if t is not None and t < self.duration), key=lambda m: m[1])

    # ---- 各层
    def flight_progress(self, t: float) -> float | None:
        """粒子层当前的进度（0–1）；没有粒子层时为 None。"""
        if self.t_takeover is None or t < self.t_takeover:
            return None
        if self.t_close is not None and t >= self.t_close:
            return None
        if t < self.t_drained:
            return self.out_ease((t - self.t_takeover) / self.outflow_s)
        if self.t_reform is None or t < self.t_reform:
            return 1.0
        return None

    def glow(self, progress: float | None) -> float:
        if progress is None:
            return 0.0
        a, b, c, d = self.p['send_glow_curve']
        return smooth(a, b, progress) * (1 - smooth(c, d, progress))

    def poses(self, t: float) -> list[CardPose]:
        p, d = self.p, self.s.density
        lift = self.lift.at(t)
        several = len(self.slots) > 1
        stagger = 0.08
        out = []
        for k, slot in enumerate(self.slots):
            w, h = slot['size']
            tcx, tcy, tscale, trot = slot['target']
            local = min(max((lift - k * stagger) / (1 - k * stagger), 0.0), 1.0) if several else lift
            origin = slot.get('origin')
            if origin:
                l, tp, r, b = origin
                scx, scy, sscale, srot = (l + r) / 2, (tp + b) / 2, (r - l) / w, 0.0
            else:
                scx, scy = tcx, tcy + p['send_detail_rise_dp'] * d
                sscale, srot = tscale * p['send_detail_rise_scale'], trot
            lerp = lambda a, b: a + (b - a) * local  # noqa: E731
            alpha = min(max(local / p['send_lift_fade'], 0.0), 1.0)
            out.append(CardPose(k, lerp(scx, tcx), lerp(scy, tcy), lerp(sscale, tscale), lerp(srot, trot), alpha))
        return out

    def shrink(self, t: float) -> float:
        """波纹收缩的程度（0–1）：流出时随进度增加，「已发送」停留时最小，重新抬起时随抬起展开。"""
        if self.t_takeover is None or t < self.t_takeover:
            return 0.0
        if t < self.t_drained:
            return self.out_ease((t - self.t_takeover) / self.outflow_s)
        if self.t_reform is None or t < self.t_reform:
            return 1.0
        return 1.0 - self.lift.at(t)

    def pulse_scale(self, t: float) -> float:
        return 1 - self.p['send_pulse_shrink'] * self.shrink(t)

    def pulses(self, t: float) -> list[tuple[float, float, float]]:
        """天线处的波纹：从等待被碰一直播到关闭，中途不断；流出时随进度收缩，重新抬起时再展开。"""
        if t < self.t_ready or (self.t_close is not None and t >= self.t_close):
            return []
        return ripple_rings(t - self.pulse_start, self.p, self.s.density, self.pulse_scale(t))

    def label(self, t: float) -> str:
        name = '打开'
        for tt, n in self.phases:
            if t >= tt:
                name = n
        progress = self.flight_progress(t)
        if progress is not None:
            if self.t_drained is not None and t < self.t_drained:
                return f'流出 {progress:.2f}'
            return '已发送（停留）'
        if self.t_close is not None and t >= self.t_close:
            return '关闭、落回'
        if self.t_reform is not None and self.t_reform <= t < self.t_returned:
            return f'重新抬起 {self.lift.at(t):.2f}'
        return {'PREPARING': '正在准备', 'READY': '等待被碰', 'CONNECTING': '正在连接', 'SENDING': '正在发送',
                'SENT': '已发送'}.get(name, name)

    def at(self, t: float) -> SendState:
        progress = self.flight_progress(t)
        shown = progress is None
        panel = None
        for tt, name in self.panels:
            if tt <= t or panel is None:
                panel = name
        offset = self.panel.at(t)
        side = self.panel_axis == 'x'
        return SendState(self.dim.at(t), 0.0 if side else offset, panel, self.poses(t), self.elevation.at(t), shown,
                         self.pulses(t), self.glow(progress), None if progress is None else progress * SAMPLES,
                         self.label(t), panel_tx=offset if side else 0.0, pulse_scale=self.pulse_scale(t))


# ---------------------------------------------------------------- 接收

@dataclass
class ReceiveState:
    page: tuple               # ('shift', 图名, dy) 或 ('items', t)
    pull: tuple | None        # (图名, layout, parent, alpha, scale, dy)
    flight_position: float | None
    label: str


class ReceivePlan:
    """接收会话：`TapReceiveController`、`TapReceivePull` 与舞台的编排。"""

    def __init__(self, session: Session, params: Params):
        self.s, self.p = session, params
        enter = session.first('enter')
        self.container = enter['container']
        # 天线在屏幕上的位置（进入接收状态时记下）：波纹画在这里
        self.antenna = tuple(enter['antenna']) if enter.get('antenna') else None
        self.actionbar_bottom = enter['actionbar_bottom']
        stage = session.first('stage-enter')
        self.t_stage = stage['t'] / 1000
        self.stage_rect = stage['rect']
        self.corner = float(stage['corner'])
        self.dim_color = int(stage['dim_color'], 16)
        lum = ((self.dim_color >> 16) & 0xFF) * .3 + ((self.dim_color >> 8) & 0xFF) * .59 + (self.dim_color & 0xFF) * .11
        self.dark = lum < 128
        touched = session.first('stage-touched')
        self.t_touched = touched['t'] / 1000 if touched else None
        self.colors = [int(c, 16) for c in touched['colors']] if touched else []
        # 握手里发送方告知的每条记事卡片是否通栏、高宽比（收到的先后）；真机素材没有这两项
        self.incoming_spans = touched.get('spans') if touched else None
        self.incoming_aspects = touched.get('aspects') if touched else None
        self.t_inflow = session.first('inflow-start')['t'] / 1000 if session.first('inflow-start') else None
        self.appear_s = params['receive_appear_s']
        self.t_assembled = self.t_inflow + self.appear_s if self.t_inflow is not None else None
        highlight = session.first('stage-highlight')
        self.holes = highlight['rects'] if highlight else []
        self.hole_radius = highlight['radius'] if highlight else 0.0
        # 数据到齐（进度 100%）：列表开始插入的那一刻
        insert = session.first('insert')
        self.t_complete = insert['t'] / 1000 if insert else self.t_inflow
        exit_ = session.first('stage-exit')
        self.t_exit = exit_['t'] / 1000 if exit_ else None
        end = session.first('end')
        self.duration = end['t'] / 1000 if end else (self.t_exit or 0) + 1.2
        # 列表下移量：逐帧记下的 (t, px, slot)
        self.offsets = [(e['t'] / 1000, e['px'], e['slot']) for e in session.of('offset')]
        self.offset0 = float(enter.get('offset', 0.0))
        # 页面截图：拼好之后那一张跟着拼好的时刻移
        cap = {n: session.capture(n) for n in ('page-enter', 'page-before', 'page-slots', 'page-done')}
        self.t_before = cap['page-before']['captured'] / 1000 if cap['page-before'] else None
        self.t_slots = cap['page-slots']['captured'] / 1000 if cap['page-slots'] else None
        self.t_done_rec = cap['page-done']['captured'] / 1000 if cap['page-done'] else None
        # 拼好那一刻真实卡片显出来（与留出空洞同一条消息），页面从这一刻起就是有卡片的那张；
        # 截图本身晚一帧，不能按截图时刻切，否则中间有一帧空位
        self.t_done = self.t_assembled if (cap['page-done'] and self.t_assembled is not None) else None
        # 让位：逐帧的卡片位置
        frames = session.of('items')
        self.item_frames = [(e['t'] / 1000, e['list'], e['items']) for e in frames]
        # 列表里置顶的记事（id）；真机素材没有这一项，当作没有置顶
        self.pinned = set(next((e['pinned'] for e in frames if e.get('pinned')), []))
        self.t_motion_end = None
        if self.t_before is not None and self.item_frames:
            later = [f for f in self.item_frames if f[0] >= self.t_before]
            self.t_motion_end = later[-1][0] if later else self.t_before
        self.pulls = [(e['captured'] / 1000, e['name'], e['layout'], e['parent']) for e in session.channel('pull')]
        # 列表腾出位置、卡片的确切位置已知的时刻：留好空位的那张截图（没有就取让位结束、数据到齐）
        settled = [v for v in (self.t_slots, self.t_motion_end, self.t_complete) if v is not None]
        self.t_settled = self.t_slots if self.t_slots is not None else (max(settled) if settled else None)
        # 舞台的动作：拼好之后的空洞与恢复跟着拼好的时刻移
        actions = [(self.t_stage, 'enter', None)]
        if self.t_touched is not None:
            actions.append((self.t_touched, 'touched', self.colors))
        if self.t_inflow is not None:
            actions.append((self.t_inflow, 'converge', None))
        if self.t_assembled is not None:
            actions.append((self.t_assembled, 'highlight', (self.holes, self.hole_radius)))
            actions.append((self.t_assembled + 1e-4, 'resume', None))
        if self.t_exit is not None:
            actions.append((self.t_exit, 'exit', None))
        self.stage_actions = sorted(actions, key=lambda a: a[0])

    def spread(self, t: float) -> float:
        """光尘带的扩散程度：碰上之前 0；传输中随进度增加到 1；数据到齐之后（列表让位的那段时间）继续
        缓慢增加（每秒 0.25，最多再加 0.5）；拼好之后舞台恢复等待，回到 0。"""
        if self.t_touched is None or t < self.t_touched:
            return 0.0
        if self.t_assembled is not None and t >= self.t_assembled:
            return 0.0
        s = self.progress(t)
        if self.t_complete is not None and t > self.t_complete:
            s += min((t - self.t_complete) * 0.25, 0.5)
        return s

    def diffuse_time(self, t: float) -> float:
        """开始扩散以来的秒数：碰上起算，拼好之后归零。"""
        if self.t_touched is None or t < self.t_touched:
            return 0.0
        if self.t_assembled is not None and t >= self.t_assembled:
            return 0.0
        return t - self.t_touched

    def progress(self, t: float) -> float:
        """光尘带收拢用的传输进度（0–1）：碰上到数据到齐之间先慢后快（握手、建连占去前一段），
        拼好之后舞台恢复等待，回到 0。"""
        if self.t_touched is None or t < self.t_touched:
            return 0.0
        if self.t_assembled is not None and t >= self.t_assembled:
            return 0.0
        end = self.t_complete if self.t_complete is not None else self.t_touched + 1.0
        u = min(max((t - self.t_touched) / max(end - self.t_touched, 1e-3), 0.0), 1.0)
        return u ** 1.6

    def milestones(self) -> list[tuple[str, float]]:
        """各阶段的起点（会话时间），界面上按段跳转、循环用。"""
        marks = [('进入接收', self.t_stage), ('碰上、传输', self.t_touched), ('插入、让位', self.t_before),
                 ('流入', self.t_inflow), ('拼好、留洞', self.t_assembled), ('退出', self.t_exit)]
        return sorted(((n, t) for n, t in marks if t is not None and t < self.duration), key=lambda m: m[1])

    def offset(self, t: float) -> tuple[float, float]:
        """(下移量, 空位高度)。"""
        px, slot = self.offset0, None
        for tt, v, sl in self.offsets:
            if tt > t:
                break
            px, slot = v, sl
        if slot is None:
            slot = self.offsets[0][2] if self.offsets else max(px, 1.0)
        return px, slot

    def page(self, t: float) -> tuple:
        if self.t_before is not None and t < self.t_before:
            return ('shift', 'page-before', self.offset(t)[0] - self.offset(self.t_before)[0])
        if self.t_motion_end is not None and t <= self.t_motion_end:
            return ('items', t)
        if self.t_done is not None and t >= self.t_done:
            # 截图里的列表停在截图那一刻的下移量；退出时空位收回，按下移量的变化平移
            return ('shift', 'page-done', self.offset(t)[0] - self.offset(self.t_done_rec)[0])
        if self.t_slots is not None:
            return ('shift', 'page-slots', 0.0)
        return ('shift', 'page-before', 0.0)

    def pull(self, t: float):
        if not self.pulls:
            return None
        chosen = self.pulls[0]
        for item in self.pulls:
            if item[0] <= t:
                chosen = item
        px, slot = self.offset(t)
        if px <= 0.5:
            return None
        f = min(max(px / max(slot, 1e-3), 0.0), 1.0)
        d = self.s.density
        return (chosen[1], chosen[2], chosen[3], f * f, 0.86 + 0.14 * f, (1 - f) * -10 * d)

    def pulses(self, t: float) -> list[tuple[float, float, float]]:
        """接收时天线处的波纹（与发送同一套扩散圈）。等待时不显示（天线位置可能压着界面元素），开始接收、
        显示进度时才淡入，颗粒从这里冒出；流入时边收缩边淡去，卡片拼好时已经消失。"""
        if self.t_touched is None or t < self.t_touched:
            return []
        fade = self.p['receive_border_fade_s']
        envelope = min((t - self.t_touched) / fade, 1.0)
        leave = 0.0
        if self.t_inflow is not None:
            span = 0.6 * (self.t_assembled - self.t_inflow) if self.t_assembled is not None else fade
            leave = smooth(self.t_inflow, self.t_inflow + max(span, 1e-3), t)
        if self.t_exit is not None:
            leave = max(leave, min(max((t - self.t_exit) / fade, 0.0), 1.0))
        envelope *= 1.0 - leave
        if envelope <= 0.003:
            return []
        scale = 1.0 - self.p['send_pulse_shrink'] * leave
        return [(r, a * envelope, phase)
                for r, a, phase in ripple_rings(t - self.t_touched, self.p, self.s.density, scale)]

    # ---- 倒放的时间轴：进度期间那一层（代理卡片）与拼卡那一层（真实卡片）共用
    def reverse_tau(self, t: float) -> float | None:
        """倒放走到哪儿（0–1；0 是正向时间轴的终点，1 是拼好）。逐项对应 `TapReceiveTimeline`，差别只在传输
        进度：这里按碰上到数据到齐的时间先慢后快地假设，手机上用界面显示的真实进度；到齐时的速度这里取解析的
        斜率，手机上是逐帧速度按 0.1 秒平滑的结果。

        - 碰上之后随传输进度推进：scale × 进度^gamma + floor × 秒数（gamma < 1：起步快，颗粒一碰上就冒出来）；
          越过上限的 SOFT_CAP_KNEE 倍之后按指数逼近上限（`softCap`，拐点处斜率连续）：传输很慢时「最低速度 ×
          秒数」会一直涨，不软着陆就等于数据还没到先拼出了代理卡片。一两秒的传输落不到这一段；
        - 数据到齐之后按指数逼近上限 cap：一直在动、越来越慢，不会先拼出代理卡片；速度在到齐那一刻连续、至少是
          最低速度；到齐时已经贴着上限就停在那里；
        - 流入开始之后从当时的位置与速度出发，平滑加速（三次 Hermite），流入时长结束时正好到 1，
          末速度是正常速度（1 / 流入时长）。"""
        if self.t_touched is None or t < self.t_touched:
            return None
        scale, floor, cap, gamma = self.p['receive_emerge_pace']
        tt = self.t_touched
        tc = self.t_complete if self.t_complete is not None else tt + 1.
        if self.t_inflow is not None:
            # 流入开始时还没到齐：手机上 startInflow 先按此刻到齐（complete）
            tc = min(tc, self.t_inflow)
        span = max(tc - tt, 1e-3)
        knee = cap * SOFT_CAP_KNEE
        knee_room = cap - knee

        def soft_cap(raw):
            if raw <= knee:
                return raw
            return knee + knee_room * (1 - math.exp(-(raw - knee) / knee_room))

        if t <= tc:
            # 传输进度：模拟器里按碰上到数据到齐的时间先慢后快（u^1.6，与 progress() 相同）
            return soft_cap(scale * ((t - tt) / span) ** (1.6 * gamma) + floor * (t - tt))
        raw_c = scale + floor * span
        tau_c = soft_cap(raw_c)
        # 到齐那一刻的速度：softCap 的斜率 × 未软着陆时的斜率，至少是最低速度（complete 里的 max(rate, floor)）
        slope = 1.0 if raw_c <= knee else math.exp(-(raw_c - knee) / knee_room)
        rate_c = max(slope * (scale * 1.6 * gamma / span + floor), floor)
        room = cap - tau_c

        def wait(x):
            if room <= 1e-6:
                return tau_c
            return tau_c + room * (1 - math.exp(-rate_c * max(x - tc, 0.0) / room))
        if self.t_inflow is None or t <= self.t_inflow:
            return wait(t)
        tau_h = wait(self.t_inflow)
        rate_h = 0.0 if room <= 1e-6 else rate_c * math.exp(-rate_c * max(self.t_inflow - tc, 0.0) / room)
        d = self.appear_s
        u = (t - self.t_inflow) / d
        if u >= 1:
            return 1.
        h00, h10 = 2 * u ** 3 - 3 * u ** 2 + 1, u ** 3 - 2 * u ** 2 + u
        h01, h11 = -2 * u ** 3 + 3 * u ** 2, u ** 3 - u ** 2
        return h00 * tau_h + h10 * d * rate_h + h01 + h11

    def time_of_tau(self, tau):
        """reverse_tau 的反函数（逐元素）：倒放走到 [tau] 的会话时刻。"""
        end = self.t_assembled if self.t_assembled is not None else (self.t_inflow or self.t_touched) + 2.
        grid = np.linspace(self.t_touched, end, 4001)
        taus = np.array([self.reverse_tau(x) for x in grid])
        taus = np.maximum.accumulate(taus + np.arange(len(taus)) * 1e-12)
        return np.interp(tau, taus, grid)

    def forward_time(self, t: float) -> float | None:
        """此刻对应的正向时间轴位置（0–1）：1 − reverse_tau。"""
        tau = self.reverse_tau(t)
        return None if tau is None else 1. - tau

    def flight_position(self, t: float) -> float | None:
        """拼卡那一层（真实卡片的倒放）取的采样位置（0–60，可带小数）：流入开始到拼好之间。"""
        if self.t_inflow is None or t < self.t_inflow or (self.t_assembled is not None and t >= self.t_assembled):
            return None
        return (1. - self.reverse_tau(t)) * SAMPLES

    def label(self, t: float) -> str:
        if self.t_exit is not None and t >= self.t_exit:
            return '退出'
        if self.t_assembled is not None and t >= self.t_assembled:
            return '拼好、留出空洞'
        if self.t_inflow is not None and t >= self.t_inflow:
            return f'流入 {(t - self.t_inflow) / self.appear_s:.2f}'
        if self.t_before is not None and t >= self.t_before:
            return '插入、让位'
        if self.t_touched is not None and t >= self.t_touched:
            return '碰上、传输'
        return '等待被碰'

    def at(self, t: float) -> ReceiveState:
        return ReceiveState(self.page(t), self.pull(t), self.flight_position(t), self.label(t))
