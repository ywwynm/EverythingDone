"""接收：进度期间天线处的颗粒（2026-09-25 第三版第六轮）——弹窗出现动画的前半段。

拼卡用的是弹窗出现那套「消散倒放」。进度期间记事内容还没到（碰上时只知道条数与每条的代表色），真实卡片
的倒放算不出来，所以先按预计落点做几张代理卡片（代表色的圆角矩形，`predict_slots`），播它们的倒放：

- 颗粒按弹窗出现的方式沿途浮现、朝落点飘，一部分从天线处浮现（receive_emerge_sink）；
- 两层共用一条倒放时间轴（`ReceivePlan.reverse_tau`）：随传输进度推进，数据到齐之后越来越慢但不停，
  流入开始之后平滑加速到正常速度；
- 流入开始时交叉淡化：代理那层淡出，真实卡片的倒放从同一个进度淡入。两团颗粒按同一套运动、朝同一个
  方向飞，位置几乎重合，看上去是同一团颗粒接着飞完、拼出卡片；四周的光尘同时就近接手（`sim_handoff.py`）。

代理卡片与真实卡片用同一个模型、同一套着色器（`shared/particle-dismiss`），画面上是同一种颗粒。
"""
from __future__ import annotations

from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

from sim_model import SAMPLES, Params, Scene, build_materials, smooth
from sim_list import Grid, header_of, insertion_order, layout
from sim_recolor import Background, rgb_of
from sim_renderer import TapRenderer


# ---------------------------------------------------------------- 预计位置与来件颜色

def note_backgrounds(session) -> list[Background]:
    """每条来件记事的底色（纯色或渐变）。换过色的会话给出各自的 Background；原会话按碰上时的颜色取纯色。"""
    method = getattr(session, 'note_backgrounds', None)
    if callable(method):
        return method()
    touched = session.first('stage-touched')
    colors = [int(c, 16) & 0xFFFFFF for c in touched['colors']] if touched else [0x607D8B]
    return [Background(c) for c in colors]


def predict_slots(plan, params) -> list[tuple[float, float, float, float]]:
    """进度期间对每条来件记事落点的估计（屏幕像素 l, t, r, b），顺序与来件颜色、舞台留洞的矩形一致（收到的先后）。

    碰上时应用已经知道：列表插入之前的样子（各项的位置、哪几条置顶）、来件的条数，以及握手里发送方告知的每条
    记事卡片是否通栏、高宽比（它画过这些卡片）。按应用插入与排列的规则（`sim_list.py`：插在置顶记事之后、后收到
    的在前，逐张放进较短的一列，通栏放在两列之后）排一遍就是落点。

    模拟器里通栏与高宽比取会话里记下的握手信息（合成的场景有），没有时取最终卡片的；高宽比再乘
    receive_emerge_aspect（1 = 准确，用来试预计不准的情形）。
    """
    finals = [tuple(float(v) for v in r) for r in plan.holes]
    if not finals:
        return []
    frames = [f for f in plan.item_frames if plan.t_before is None or f[0] >= plan.t_before - 1e-6]
    if not frames:
        return finals
    _, list_rect, items = frames[0]
    grid = Grid.from_items(items, list_rect, plan.s.density)
    head = header_of(items, list_rect[2] - list_rect[0])
    existing = [it for it in items if it is not head and it[5] > .5]
    spans = plan.incoming_spans or [None] * len(finals)
    aspects = plan.incoming_aspects or [None] * len(finals)
    error = params['receive_emerge_aspect']
    size = {('old', it[0]): (float(it[4]), grid.is_full(float(it[3]))) for it in existing}
    for k, (l, t, r, b) in enumerate(finals):
        full = spans[k] == 'full' if spans[k] is not None else grid.is_full(r - l)
        aspect = aspects[k] if aspects[k] is not None else (b - t) / max(r - l, 1.)
        size[('new', k)] = (grid.span_width(full) * aspect * error, full)
    order = insertion_order([('old', it[0]) for it in existing], {('old', i) for i in plan.pinned},
                            [('new', k) for k in range(len(finals))])
    placed = dict(zip(order, layout(grid, [size[key] for key in order])))
    return [placed[('new', k)] for k in range(len(finals))]


def proxy_scene(real: Scene, plan, slots: list, colors: list[int]) -> Scene:
    """代理卡片：预计落点上的代表色圆角矩形（其余透明），天线、屏幕、密度、种子与真实那段相同。"""
    lo = np.floor(np.min([s[:2] for s in slots], axis=0)).astype(int)
    hi = np.ceil(np.max([s[2:] for s in slots], axis=0)).astype(int)
    w, h = int(hi[0] - lo[0]), int(hi[1] - lo[1])
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    radius = float(getattr(plan, 'hole_radius', 0.) or 10 * plan.s.density)
    for (l, t, r, b), color in zip(slots, colors):
        c = tuple(int(v) for v in rgb_of(color)) + (255,)
        draw.rounded_rectangle((l - lo[0], t - lo[1], r - lo[0] - 1, b - lo[1] - 1), radius=radius, fill=c)
    meta = dict(real.meta)
    # 流向四边的那一份与真实那层相同（frame 沿用），两层颗粒团才重合
    meta.update(bounds=[float(lo[0]), float(lo[1]), float(hi[0]), float(hi[1])], cards=[list(s) for s in slots],
                release_origin=None, title='代理卡片')
    return Scene(Path(real.path) / 'proxy', meta, np.asarray(img).copy(), real.background, real.settled)


def receive_params(params: Params) -> Params:
    """接收两层（代理卡片、真实卡片）共用的物理参数：天线处浮现的那一份的散开半径、淡去半径、占比与寿命末端的
    淡出（倒放里即冒出时的淡入，时间轴单位）取 receive_emerge_sink。两层一致，交叉淡化时两团颗粒才重合。
    进度期间时间轴走得慢（约每秒 0.1），淡入按时间轴算会被拉得很长，所以这里比发送用的短得多。"""
    out = params.copy()
    spread_dp, fade_dp, share, fade_in = params['receive_emerge_sink']
    out.values['sink_spread_dp'] = [spread_dp]
    out.values['sink_absorb_dp'] = [0., fade_dp]
    out.values['sink_absorb_share'] = [share]
    out.values['sink_fade_s'] = [fade_in]
    return out


class ProxyFlight:
    """进度期间的那一层：代理卡片的倒放。与真实卡片的倒放共用一条时间轴（`ReceivePlan.reverse_tau`），
    流入开始时两层交叉淡化（`LAYER_ALPHA`，整层的不透明度）：这一层淡出，真实那层从同一个进度淡入。不逐片
    配对（逐片配对会把卡片上连成一片的区域拆散，半路成一块块碎片）。"""

    def __init__(self, ctx: moderngl.Context, session, plan, params: Params, real: Scene, scale: float = 1.,
                 seed: int = 20260925):
        """[params] 已经是 `receive_params` 处理过的那一份。"""
        self.plan, self.p = plan, params
        self.slots = predict_slots(plan, params)
        backgrounds = note_backgrounds(session)
        # 碰上时接收方只知道每条记事的代表色（渐变取两端的平均），代理卡片按它上色
        self.colors = [backgrounds[k % len(backgrounds)].representative() for k in range(len(self.slots))]
        self.scene = proxy_scene(real, plan, self.slots, self.colors)
        self.build = build_materials(self.scene, params, seed)
        self.renderer = TapRenderer(self.scene, self.build, ctx, scale, layer_alpha=True)
        self.n = len(self.build.base)
        _, fade_start, fade_end = params['receive_crossfade']
        self.fade_start = self.fade_end = None
        if plan.t_inflow is not None:
            # 流入开始之后在 [fade_start, fade_end] 里整层淡出
            self.fade_start = plan.t_inflow + fade_start
            self.fade_end = plan.t_inflow + fade_end

    def alpha(self, t: float) -> float:
        """这一层此刻整层的不透明度。"""
        if self.fade_end is None:
            return 1.
        return 1. - smooth(self.fade_start, self.fade_end, t)

    def position(self, t: float) -> float | None:
        """画这一层时取的采样位置（0–60，可带小数）；碰上之前、淡出之后返回 None。"""
        if self.fade_end is not None and t > self.fade_end + .02:
            return None
        f = self.plan.forward_time(t)
        return None if f is None else f * SAMPLES

    def close(self):
        self.renderer.close()
