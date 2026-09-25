"""从真机录下的接收会话合成不同列表状态的场景：收到一条普通宽度 / 一条通栏 / 三条记事，列表里有一两条置顶记事。

写出与真机素材同样格式的目录（`scenes/<设备>-receive-<场景>/`：session.json、页面截图、按钮截图、粒子飞行），
预览窗口、导出、对照工具都能直接用。时间线（碰上、传输、流入、退出的各个时刻）与原会话相同，只换列表与来件：

- 列表布局按应用的规则重排（`sim_list.py`）：收到的记事插在置顶记事之后、后收到的在前，逐张放进较短的一列，
  通栏卡片放在两列之后；
- 页面由录下的页面拼出：顶栏与头部取原图（头部的条数重画），卡片取原图里各张卡片的主体，阴影按原图量出的
  轮廓重建；
- 收到的卡片取录下的粒子快照；没有的（通栏、第三条）按样例记事（`tap_simulated_thing_title` /
  `tap_simulated_thing_content`）的字号、边距、文字颜色绘制，字体用微软雅黑代替手机上的系统字体；
- 让位的逐帧位置按录下的那段让位动画的进度曲线插值；
- 置顶记事在卡片右上角画置顶标记（应用的 ic_sticky，着强调色）；
- 碰上时的握手信息记进 `stage-touched`：每条来件的颜色、是否通栏（spans）、卡片高宽比（aspects）；置顶的
  记事记进第一帧列表的 `pinned`。进度期间预计落点（`sim_emerge.predict_slots`）用的就是这些。

用法：python -X utf8 make_layout_scenes.py [--only 场景名 ...]
"""
from __future__ import annotations

import argparse
import copy
import json
import shutil
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from sim_list import Grid, header_of, insertion_order, layout
from sim_model import HERE, ROOT
from sim_recolor import dominant, is_light, rgb_of

SCENES = HERE / 'scenes'
FONT = 'C:/Windows/Fonts/msyh.ttc'
FONT_BOLD = 'C:/Windows/Fonts/msyhbd.ttc'
STICKY = ROOT / 'app/src/main/res/drawable-nodpi/ic_sticky.png'
# res/values-zh-rCN/strings.xml：调试包里「碰一碰模拟」发来的样例记事
SAMPLE_TITLE = '碰一碰模拟 · 样例记事 {}'
SAMPLE_CONTENT = '这是碰一碰的模拟数据，用来预览接收动画，可以直接删除。'
# 行首不能出现的标点：换行时留在上一行末尾（与 Android 的断行一致）
NO_LINE_START = '，。、；：！？）》」』”’'

DEVICES = {'samsung': '三星', 'meizu': '魅族'}
# 来件：录下的两张（封面卡片、纯文字卡片），以及按样例记事画的两张
CARD_COLORS = {'wide': 0x21A675, 'purple': 0x665696}
SCENARIOS = {
    'one-normal': dict(title='收到一条普通宽度的记事', incoming=['orange'], pinned=0),
    'one-full': dict(title='收到一条通栏记事', incoming=['wide'], pinned=0),
    'three': dict(title='收到三条记事', incoming=['orange', 'purple', 'cyan'], pinned=0),
    'pinned-1': dict(title='列表里有一条置顶记事，收到两条', incoming=['cyan', 'orange'], pinned=1),
    'pinned-2': dict(title='列表里有两条置顶记事，收到两条', incoming=['cyan', 'orange'], pinned=2),
}


# ---------------------------------------------------------------- 图像工具

def rounded_mask(w: int, h: int, radius: float, ss: int = 4) -> np.ndarray:
    """圆角矩形 [0, w] × [0, h] 的覆盖率（0–1），ss × ss 超采样。"""
    def axis(n):
        c = (np.arange(n * ss) + .5) / ss
        return np.maximum(np.maximum(radius - c, c - (n - radius)), 0.)
    dx, dy = axis(w), axis(h)
    inside = (dx[None, :] ** 2 + dy[:, None] ** 2) <= radius * radius
    return inside.reshape(h, ss, w, ss).mean(axis=(1, 3))


def runs(flags: np.ndarray) -> list[tuple[int, int]]:
    """布尔序列里连续为真的各段 [起, 止)。"""
    out, start = [], None
    for i, v in enumerate(flags):
        if v and start is None:
            start = i
        elif not v and start is not None:
            out.append((start, i))
            start = None
    if start is not None:
        out.append((start, len(flags)))
    return out


def fit_font(path: str, text: str, target: float, measure: str) -> ImageFont.FreeTypeFont:
    """按墨迹的宽或高（measure = 'width' / 'height'）找字号，使 [text] 的墨迹与 [target] 像素一致。"""
    probe = ImageFont.truetype(path, 200)
    x0, y0, x1, y1 = probe.getbbox(text)
    size = 200 * target / max((x1 - x0) if measure == 'width' else (y1 - y0), 1)
    return ImageFont.truetype(path, max(int(round(size)), 4))


def draw_ink(rgb: np.ndarray, text: str, font, left: float, top: float, ink, alpha: float):
    """在 RGB 浮点图上画一行字：墨迹左上角对准 (left, top)，颜色 [ink]、不透明度 [alpha]（按覆盖率叠加）。"""
    x0, y0, x1, y1 = font.getbbox(text)
    h, w = rgb.shape[:2]
    cover = Image.new('L', (w, h), 0)
    ImageDraw.Draw(cover).text((left - x0, top - y0), text, font=font, fill=255)
    a = np.asarray(cover).astype(np.float64)[..., None] / 255. * alpha
    rgb[:] = rgb * (1 - a) + np.asarray(ink, dtype=np.float64) * a


# ---------------------------------------------------------------- 阴影：按录下的卡片量出轮廓，九宫格拼到任意大小

class Shadow:
    def __init__(self, page: np.ndarray, bg: np.ndarray, rect, radius: float, pad: int):
        l, t, r, b = (int(round(v)) for v in rect)
        region = page[t - pad:b + pad, l - pad:r + pad].astype(np.float64)
        alpha = np.clip(1 - region.mean(axis=2) / bg.mean(), 0, 1)
        inside = np.zeros_like(alpha)
        inside[pad:pad + b - t, pad:pad + r - l] = rounded_mask(r - l, b - t, radius)
        alpha[inside > 0] = 0
        self.pad = pad
        self.corner = pad + int(np.ceil(radius)) + 4
        rows = slice(pad + (b - t) // 3, pad + 2 * (b - t) // 3)
        cols = slice(pad + (r - l) // 3, pad + 2 * (r - l) // 3)
        self.left = alpha[rows, :pad].mean(axis=0)
        self.top = alpha[:pad, cols].mean(axis=1)
        self.bottom = alpha[-pad:, cols].mean(axis=1)
        c = self.corner
        self.tl = alpha[:c, :c].copy()
        self.bl = alpha[-c:, :c].copy()

    def build(self, w: int, h: int) -> np.ndarray:
        """(h + 2 pad, w + 2 pad) 的阴影不透明度，卡片左上角在 (pad, pad)。右侧取左侧的镜像。"""
        p, c = self.pad, self.corner
        a = np.zeros((h + 2 * p, w + 2 * p))
        a[:p, :] = self.top[:, None]
        a[-p:, :] = self.bottom[:, None]
        a[:, :p] = np.maximum(a[:, :p], self.left[None, :])
        a[:, -p:] = np.maximum(a[:, -p:], self.left[None, ::-1])
        a[p:-p, p:-p] = 0
        a[:c, :c] = self.tl
        a[:c, -c:] = self.tl[:, ::-1]
        a[-c:, :c] = self.bl
        a[-c:, -c:] = self.bl[:, ::-1]
        return a


# ---------------------------------------------------------------- 一台设备的素材

@dataclass
class Card:
    key: str                 # 'old-<id>' / 'orange' / 'cyan' / 'wide' / 'purple'
    body: np.ndarray         # (h, w, 4) uint8，直通 alpha
    full: bool = False
    color: int = 0           # 来件的代表色（光尘带换色、代理卡片用）


class Kit:
    """从一段录下的接收会话里取出合成要用的一切：列表几何、页面底色、各张卡片、阴影、文字样式。"""

    def __init__(self, device: str):
        self.device = device
        self.base = SCENES / f'{device}-receive'
        self.meta = json.loads((self.base / 'session.json').read_text(encoding='utf-8'))
        ev = self.meta['events']
        self.events = ev
        self.density = float(self.meta['density'])
        self.screen = tuple(int(v) for v in self.meta['screen'])
        first = lambda kind: next(e for e in ev if e['type'] == kind)  # noqa: E731
        self.actionbar_bottom = int(first('enter')['actionbar_bottom'])
        highlight = first('stage-highlight')
        self.radius = float(highlight['radius'])
        frames = [e for e in ev if e['type'] == 'items']
        self.frames = frames
        self.before = frames[0]
        self.list_rect = self.before['list']
        self.grid = Grid.from_items(self.before['items'], self.list_rect, self.density)
        self.header = header_of(self.before['items'], self.list_rect[2] - self.list_rect[0])
        self.page = np.asarray(Image.open(self.base / 'page-before.png').convert('RGB'))
        self.done = np.asarray(Image.open(self.base / 'page-done.png').convert('RGB'))
        self.bg = rgb_of(dominant(self.page, (0, self.actionbar_bottom, self.screen[0], self.screen[1])))
        bar = self.page[:self.actionbar_bottom].reshape(-1, 3).astype(np.float64)
        hi, lo = bar.max(axis=1), bar.min(axis=1)
        self.accent = np.median(bar[((hi - lo) / np.maximum(hi, 1) > .45) & (hi > 120)], axis=0)
        # 已有的卡片：插入之前那一帧里可见的各项，主体取 page-before
        self.existing = []
        for iid, x, y, w, h, a in self.before['items']:
            if a > .5:
                self.existing.append((iid, Card(f'old-{iid}', self._body(self.page, (x, y, x + w, y + h)),
                                                self.grid.is_full(w))))
        # 录下的来件：粒子快照里的几块，按底色认出封面卡片（青）与纯文字卡片（橙）
        flight = self.base / 'flight-1'
        self.flight_meta = json.loads((flight / 'scene.json').read_text(encoding='utf-8'))
        snapshot = np.asarray(Image.open(flight / 'snapshot.png').convert('RGBA'))
        ox, oy = self.flight_meta['bounds'][:2]
        self.incoming = {}
        for rect in highlight['rects']:
            l, t, r, b = (int(round(v)) for v in rect)
            body = snapshot[t - oy:b - oy, l - ox:r - ox].copy()
            color = dominant(body)
            key = 'orange' if color == 0xD28656 else 'cyan' if color == 0x00B1C6 else f'rec-{color:06X}'
            self.incoming[key] = Card(key, body, False, color)
            if key == 'orange':
                orange_rect = (l, t, r, b)
        self.shadow = Shadow(self.done, self.bg, orange_rect, self.radius, int(round(12 * self.density)))
        self._measure_text()
        self._measure_header()

    def _body(self, page: np.ndarray, rect) -> np.ndarray:
        l, t, r, b = (int(round(v)) for v in rect)
        body = np.zeros((b - t, r - l, 4), np.uint8)
        body[..., :3] = page[t:b, l:r]
        body[..., 3] = np.clip(rounded_mask(r - l, b - t, self.radius) * 255 + .5, 0, 255).astype(np.uint8)
        return body

    # ---- 样例记事的文字样式：从录下的纯文字卡片量（字号、行距、边距、文字不透明度）
    def _measure_text(self):
        body = self.incoming['orange'].body
        rgb = body[..., :3].astype(np.float64)
        base = rgb_of(0xD28656)
        solid = body[..., 3] == 255
        ink = (np.abs(rgb - base).sum(axis=2) > 60) & solid
        # 圆角那几行里有透明像素，按行判断时只看中间的列
        margin = int(self.radius) + 2
        rows = runs(ink[:, margin:-margin].any(axis=1))
        rows = [(a, b) for a, b in rows if b - a > 10]
        title, lines = rows[0], rows[1:]
        cols = np.flatnonzero(ink[lines[0][0]:lines[0][1]].any(axis=0))
        self.left_pad = int(cols.min())
        self.first_line_width = int(cols.max() - cols.min() + 1)
        self.title_top, self.title_height = title[0], title[1] - title[0]
        self.body_top = lines[0][0]
        self.line_height = int(np.median([b - a for a, b in lines]))
        self.pitch = float(np.mean(np.diff([a for a, _ in lines])))
        self.bottom_pad = body.shape[0] - lines[-1][1]
        # 文字不透明度：浅底上是黑字，取最深的那些像素反推
        core = np.percentile(rgb[ink].mean(axis=1), 1)
        self.ink_on_light = 1 - core / base.mean()
        cyan = self.incoming['cyan'].body
        red = cyan[..., 0].astype(np.float64)
        lower = red[cyan.shape[0] // 2:]
        self.ink_on_dark = float(np.percentile(lower, 99.5)) / 255.
        first_line = SAMPLE_CONTENT[:6]
        self.body_font = fit_font(FONT, first_line, self.first_line_width, 'width')
        self.title_font = fit_font(FONT_BOLD, '碰一碰模拟', self.title_height, 'height')

    def text_card(self, width: int, color: int, title: str, content: str) -> np.ndarray:
        """按样例记事的样式画一张纯文字卡片（标题一行、放不下以省略号结尾；正文按字折行）。"""
        inner = width - 2 * self.left_pad
        lines, line = [], ''
        for ch in content:
            if line and self.body_font.getlength(line + ch) > inner and ch not in NO_LINE_START:
                lines.append(line)
                line = ''
            line += ch
        if line:
            lines.append(line)
        height = int(round(self.body_top + (len(lines) - 1) * self.pitch + self.line_height + self.bottom_pad))
        rgb = np.broadcast_to(rgb_of(color), (height, width, 3)).astype(np.float64).copy()
        light = is_light(color)
        ink = (0, 0, 0) if light else (255, 255, 255)
        alpha = self.ink_on_light if light else self.ink_on_dark
        while self.title_font.getlength(title) > inner and len(title) > 1:
            title = title[:-2] + '…'
        draw_ink(rgb, title, self.title_font, self.left_pad, self.title_top, ink, alpha)
        for k, text in enumerate(lines):
            draw_ink(rgb, text, self.body_font, self.left_pad, self.body_top + k * self.pitch, ink, alpha)
        body = np.zeros((height, width, 4), np.uint8)
        body[..., :3] = np.clip(rgb + .5, 0, 255).astype(np.uint8)
        body[..., 3] = np.clip(rounded_mask(width, height, self.radius) * 255 + .5, 0, 255).astype(np.uint8)
        return body

    def card(self, key: str, index: int) -> Card:
        if key in self.incoming:
            return self.incoming[key]
        full = key == 'wide'
        width = int(round(self.grid.span_width(full)))
        color = CARD_COLORS[key]
        return Card(key, self.text_card(width, color, SAMPLE_TITLE.format(index), SAMPLE_CONTENT), full, color)

    # ---- 头部「N件记事」：量出那一行的位置、颜色与字号，按新的条数重画
    def _measure_header(self):
        x, y, w, h = self.header[1:5]
        region = self.page[int(y):int(y + h), int(x):int(x + w)].astype(np.float64)
        ink = np.abs(region - self.bg).sum(axis=2) > 24
        rows = [(a, b) for a, b in runs(ink.any(axis=1)) if b - a > 6]
        a, b = rows[-1]
        cols = np.flatnonzero(ink[a:b].any(axis=0))
        self.count_box = (int(x) + int(cols.min()), int(y) + a, int(x) + int(cols.max()) + 1, int(y) + b)
        line = region[a:b]
        darkest = line.reshape(-1, 3)[np.argmax(np.abs(line - self.bg).sum(axis=2).ravel())]
        self.count_color = darkest
        self.count_font = fit_font(FONT, '件记事', (b - a), 'height')

    def draw_count(self, rgb: np.ndarray, count: int):
        l, t, r, b = self.count_box
        pad = int(4 * self.density)
        rgb[t - pad:b + pad, l - pad:r + 3 * pad] = self.bg
        draw_ink(rgb, f'{count}件记事', self.count_font, l, t, self.count_color, 1.)


# ---------------------------------------------------------------- 合成

def compose(kit: Kit, placed: list, count: int, pinned: set) -> np.ndarray:
    """一张页面：顶栏与头部取 page-before（条数重画），列表区域按 [placed]（(Card, 矩形)）重新铺卡片与阴影。"""
    W, H = kit.screen
    rgb = kit.page.astype(np.float64).copy()
    clear_from = int(kit.grid.top - kit.shadow.pad)
    rgb[clear_from:, :] = kit.bg
    kit.draw_count(rgb, count)
    shade = np.ones((H, W))
    p = kit.shadow.pad
    for card, (l, t, r, b) in placed:
        l, t = int(round(l)), int(round(t))
        h, w = card.body.shape[:2]
        a = kit.shadow.build(w, h)
        y0, x0 = t - p, l - p
        ys, xs = slice(max(y0, 0), min(y0 + a.shape[0], H)), slice(max(x0, 0), min(x0 + a.shape[1], W))
        if ys.start >= ys.stop or xs.start >= xs.stop:
            continue    # 整张在屏幕外
        shade[ys, xs] *= 1 - a[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0]
    rgb *= shade[..., None]
    for card, (l, t, r, b) in placed:
        body = with_sticky(kit, card.body) if card.key in pinned else card.body
        paste(rgb, body, int(round(l)), int(round(t)))
    return np.clip(rgb + .5, 0, 255).astype(np.uint8)


def paste(rgb: np.ndarray, body: np.ndarray, left: int, top: int):
    H, W = rgb.shape[:2]
    h, w = body.shape[:2]
    ys, xs = slice(max(top, 0), min(top + h, H)), slice(max(left, 0), min(left + w, W))
    if ys.start >= ys.stop or xs.start >= xs.stop:
        return
    part = body[ys.start - top:ys.stop - top, xs.start - left:xs.stop - left].astype(np.float64)
    a = part[..., 3:] / 255.
    rgb[ys, xs] = rgb[ys, xs] * (1 - a) + part[..., :3] * a


def with_sticky(kit: Kit, body: np.ndarray) -> np.ndarray:
    """置顶记事：卡片右上角 24 dp 的 ic_sticky，着强调色，被卡片的圆角裁掉。"""
    size = int(round(24 * kit.density))
    icon = Image.open(STICKY).convert('RGBA').resize((size, size), Image.LANCZOS)
    a = np.asarray(icon)[..., 3].astype(np.float64)[..., None] / 255.
    out = body.copy()
    region = out[:size, -size:, :3].astype(np.float64)
    out[:size, -size:, :3] = np.clip(region * (1 - a) + kit.accent * a + .5, 0, 255).astype(np.uint8)
    return out


def motion_curve(kit: Kit) -> list[tuple[float, float]]:
    """录下的让位动画：(相对 land 的毫秒数, 进度 0–1)，取位移最大的那一项。"""
    land = next(e['t'] for e in kit.events if e['type'] == 'land')
    frames = [f for f in kit.frames if f['t'] > land]
    # 起点：land 之前最后一帧（几条都已插入、还没开始让位）
    ref = {it[0]: it for it in [f for f in kit.frames if f['t'] <= land][-1]['items']}
    last = {it[0]: it for it in kit.frames[-1]['items']}

    def distance(a, b):
        return abs(a[1] - b[1]) + abs(a[2] - b[2])
    best = max((i for i in last if i in ref), key=lambda i: distance(last[i], ref[i]))
    total = max(distance(last[best], ref[best]), 1e-6)
    out = []
    for f in frames:
        it = {i[0]: i for i in f['items']}[best]
        out.append((f['t'] - land, min(max(distance(it, ref[best]) / total, 0.), 1.)))
    return out


def build(kit: Kit, name: str, spec: dict, out: Path):
    W, H = kit.screen
    grid = kit.grid
    # 置顶：挑最矮的几张（保持原来的先后）
    heights = sorted(kit.existing, key=lambda e: e[1].body.shape[0])
    pinned_ids = {iid for iid, _ in heights[:spec['pinned']]}
    existing = [iid for iid, _ in kit.existing]
    cards = {iid: card for iid, card in kit.existing}
    pinned_keys = {cards[i].key for i in pinned_ids}
    before_order = [i for i in existing if i in pinned_ids] + [i for i in existing if i not in pinned_ids]
    next_id = max(existing) + 1
    incoming_ids = []
    for k, key in enumerate(spec['incoming']):
        cards[next_id] = kit.card(key, k + 1)
        incoming_ids.append(next_id)
        next_id += 1
    after_order = insertion_order(existing, pinned_ids, incoming_ids)

    def rects(order):
        return dict(zip(order, layout(grid, [(cards[i].body.shape[0], cards[i].full) for i in order])))
    pre, post = rects(before_order), rects(after_order)
    for i in incoming_ids:
        l, t, r, b = post[i]
        if b > H or t < 0:
            raise ValueError(f'{name}: 来件 {cards[i].key} 落在屏幕外（{t:.0f}–{b:.0f}），这个场景要先滚动列表，暂不合成')

    header = kit.header
    head = [list(header)] if header is not None else []

    def frame(t, placed_ids, positions, hidden=()):
        items = head + [[i, *rect_xywh(positions[i]), 0 if i in hidden else 1] for i in placed_ids]
        e = {'t': int(round(t)), 'type': 'items', 'list': kit.list_rect, 'items': items}
        return e

    # ---- 事件：时刻与原会话相同，只换列表、插入与来件
    base_inserts = [e['t'] for e in kit.events if e['type'] == 'insert']
    base_land = next(e['t'] for e in kit.events if e['type'] == 'land')
    t_before = kit.before['t']
    step = (base_inserts[1] - base_inserts[0]) if len(base_inserts) > 1 else 32
    after_insert = next(f['t'] for f in kit.frames if f['t'] > base_inserts[0]) - base_inserts[0]
    events = []
    for e in kit.events:
        if e['type'] in ('items', 'insert', 'land'):
            continue
        e = copy.deepcopy(e)
        if e['type'] == 'stage-touched':
            e['colors'] = [f'FF{cards[i].color:06X}' for i in incoming_ids]
            e['spans'] = ['full' if cards[i].full else 'normal' for i in incoming_ids]
            e['aspects'] = [round(cards[i].body.shape[0] / cards[i].body.shape[1], 6) for i in incoming_ids]
        elif e['type'] == 'stage-highlight':
            e['rects'] = [list(post[i]) for i in incoming_ids]
        events.append(e)
    first = frame(t_before, before_order, pre)
    first['pinned'] = sorted(pinned_ids)
    events.append(first)
    current = list(before_order)
    positions = dict(pre)
    for k, i in enumerate(incoming_ids):
        t = t_before + k * step
        events.append({'t': int(t), 'type': 'insert'})
        current = [j for j in after_order if j in current or j == i]
        positions[i] = post[i]
        events.append(frame(t + after_insert, current, positions, hidden=set(incoming_ids)))
    land = t_before + (len(incoming_ids) - 1) * step + (base_land - base_inserts[-1])
    events.append({'t': int(land), 'type': 'land'})
    for dt, u in motion_curve(kit):
        moving = {j: tuple(a + (b - a) * u for a, b in zip(pre[j], post[j])) for j in existing}
        moving.update({i: post[i] for i in incoming_ids})
        events.append(frame(land + dt, after_order, moving, hidden=set(incoming_ids)))
    slots_t = next(e['t'] for e in kit.events if e['type'] == 'capture' and e['name'] == 'page-slots')
    if events[-1]['t'] >= slots_t:
        raise ValueError(f'{name}: 让位结束（{events[-1]["t"]}）晚于留好空位的截图（{slots_t}）')
    events.sort(key=lambda e: e['t'])

    # ---- 页面
    out.mkdir(parents=True, exist_ok=True)
    count0 = len(existing)
    page_before = compose(kit, [(cards[i], pre[i]) for i in before_order], count0, pinned_keys)
    page_slots = compose(kit, [(cards[i], post[i]) for i in after_order if i not in incoming_ids],
                         count0 + len(incoming_ids), pinned_keys)
    page_done = compose(kit, [(cards[i], post[i]) for i in after_order], count0 + len(incoming_ids), pinned_keys)
    for fname, img in (('page-enter', page_before), ('page-before', page_before), ('page-slots', page_slots),
                       ('page-done', page_done)):
        Image.fromarray(img).save(out / f'{fname}.png')
    assembled = next(e['t'] for e in kit.events if e['type'] == 'assembled')
    exit_t = next((e['t'] for e in kit.events if e['type'] == 'exit'), 1e9)
    for e in kit.events:
        if e['type'] == 'capture' and e.get('channel') == 'pull':
            src = kit.base / f"{e['name']}.png"
            if assembled <= e['captured'] < exit_t:
                redraw_received(src, out / f"{e['name']}.png", len(incoming_ids), kit.density)
            else:
                shutil.copyfile(src, out / f"{e['name']}.png")

    # ---- 粒子飞行：屏幕上的几张新卡片合成一张快照
    boxes = np.array([post[i] for i in incoming_ids])
    l, t = np.floor(boxes[:, :2].min(axis=0)).astype(int)
    r, b = np.ceil(boxes[:, 2:].max(axis=0)).astype(int)
    snapshot = np.zeros((b - t, r - l, 4), np.uint8)
    for i in incoming_ids:
        body = cards[i].body
        x, y = int(round(post[i][0])) - l, int(round(post[i][1])) - t
        region = snapshot[y:y + body.shape[0], x:x + body.shape[1]]
        region[body[..., 3] > 0] = body[body[..., 3] > 0]
    flight = out / 'flight-1'
    flight.mkdir(exist_ok=True)
    Image.fromarray(snapshot).save(flight / 'snapshot.png')
    Image.fromarray(page_slots).save(flight / 'background.png')
    Image.fromarray(page_slots).save(flight / 'background-settled.png')
    fm = copy.deepcopy(kit.flight_meta)
    ax, ay = fm['sink']
    fm['bounds'] = [int(l), int(t), int(r), int(b)]
    # 释放起点：截图上离天线最近的那一点（TapParticleFlight.releaseOriginToward）
    fm['release_origin'] = [(min(max(ax, l), r) - l) / max(r - l, 1), (min(max(ay, t), b) - t) / max(b - t, 1)]
    fm.pop('cards', None)
    (flight / 'scene.json').write_text(json.dumps(fm, ensure_ascii=False, indent=2), encoding='utf-8')

    meta = copy.deepcopy(kit.meta)
    meta['title'] = f"{DEVICES[kit.device]} · 合成：{spec['title']}"
    meta['source'] = f"{kit.meta.get('source', '')} → make_layout_scenes.py {name}"
    meta['synthetic'] = {'base': kit.base.name, 'scenario': name,
                         'incoming': [cards[i].key for i in incoming_ids],
                         'pinned': [cards[i].key for i in sorted(pinned_ids)]}
    meta['events'] = events
    (out / 'session.json').write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding='utf-8')
    return {i: (cards[i].key, [round(v) for v in post[i]]) for i in incoming_ids}


def rect_xywh(rect):
    l, t, r, b = rect
    return [float(l), float(t), float(r - l), float(b - t)]


def redraw_received(src: Path, dst: Path, count: int, density: float):
    """按钮下方的提示在拼好之后是「已收到 N 条记事」：按新的条数重画那一行（位置、颜色、墨迹宽度对齐原图）。"""
    img = np.asarray(Image.open(src).convert('RGBA')).astype(np.float64)
    alpha = img[..., 3]
    solid = runs((alpha > 250).mean(axis=1) > .3)
    below = solid[0][1] if solid else 0
    rows = [(a, b) for a, b in runs((alpha[below:] > 30).any(axis=1)) if b - a > 6]
    if not rows:
        shutil.copyfile(src, dst)
        return
    a, b = rows[0][0] + below, rows[0][1] + below
    cols = np.flatnonzero((alpha[a:b] > 30).any(axis=0))
    left, right = int(cols.min()), int(cols.max()) + 1
    core = img[a:b][alpha[a:b] >= np.percentile(alpha[a:b][alpha[a:b] > 30], 95)]
    color = core[:, :3].mean(axis=0)
    opacity = core[:, 3].mean() / 255.
    old = '已收到 2 条记事'
    font = fit_font(FONT, old, right - left, 'width')
    text = f'已收到 {count} 条记事'
    x0, y0, x1, y1 = font.getbbox(text)
    cover = Image.new('L', (img.shape[1], img.shape[0]), 0)
    center = (left + right) / 2
    ImageDraw.Draw(cover).text((center - (x1 - x0) / 2 - x0, a - y0), text, font=font, fill=255)
    cov = np.asarray(cover).astype(np.float64) / 255.
    out = img.copy()
    pad = int(3 * density)
    out[a - pad:b + pad, :, 3] = 0
    mask = cov > 0
    out[mask, :3] = color
    out[..., 3] = np.maximum(out[..., 3], cov * opacity * 255)
    Image.fromarray(np.clip(out + .5, 0, 255).astype(np.uint8)).save(dst)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', nargs='*', help='只合成这几个（<设备>-receive-<场景>）')
    a = ap.parse_args()
    for device in DEVICES:
        kit = Kit(device)
        for name, spec in SCENARIOS.items():
            full = f'{device}-receive-{name}'
            if a.only and full not in a.only:
                continue
            out = SCENES / full
            if out.exists():
                shutil.rmtree(out)
            placed = build(kit, full, spec, out)
            print(full, placed)


if __name__ == '__main__':
    main()
