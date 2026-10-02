"""给会话里的记事换颜色：随机选一种纯色或渐变（与应用同一套调色板与方向），把截图里记事的底色换掉。

换色只动三类像素：底色本身、底色上的文字与图标（按底色明暗选的黑 / 白半透明，覆盖率按像素到
「底色 → 文字色」这条线的投影求出），以及分享面板上用记事颜色画的图标与进度条。离这条线远的像素
（卡片里的图片等其他内容）保持原样。

涉及的图：
- 发送：详情页 `page`（整页都是记事颜色）、抬起的卡片 `card-<n>`、面板 `panel-<n>`、粒子飞行的快照；
  扩散圈落在面板上的那一段按新颜色画（`accent`，渐变照原样铺在圈上）；
- 接收：拼好之后的列表 `page-done` 里收到的那几张卡片、粒子飞行的快照（几张卡片合成一张）、
  碰上时光尘带换成的来件颜色。

颜色、明暗判断、渐变方向逐项对应 `ThingBackground`、`BackgroundUtil.isLight`、`DisplayUtil.createGradientShader`。
"""
from __future__ import annotations

import copy
import json
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.ndimage import label

from sim_model import Scene

# R.array.thing（res/values/colors.xml）
PALETTE = [0x607D8B, 0x00B1C6, 0x366686, 0x364656, 0x96766B, 0x166096, 0x21A675, 0x665696, 0xD28656, 0xAE6060]
ORIENTATIONS = ['L_R', 'T_B', 'LT_RB', 'RT_LB', 'LB_RT', 'RB_LT', 'R_L', 'B_T']


def rgb_of(color: int) -> np.ndarray:
    return np.array([(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF], dtype=np.float64)


def is_light(color: int) -> bool:
    """`BackgroundUtil.isLight`：Rec. 601 亮度大于 150。"""
    r, g, b = rgb_of(color)
    return r * 0.299 + g * 0.587 + b * 0.114 > 150


@dataclass
class Background:
    """`ThingBackground`：纯色，或两色线性渐变（8 个方向之一）。"""
    color: int
    end: int | None = None
    orientation: str = 'L_R'

    @property
    def gradient(self) -> bool:
        return self.end is not None

    def representative(self) -> int:
        """`representativeColor`：渐变取两端 RGB 的算术平均（整数除法）。"""
        if not self.gradient:
            return self.color
        a, b = rgb_of(self.color).astype(int), rgb_of(self.end).astype(int)
        m = (a + b) // 2
        return (int(m[0]) << 16) | (int(m[1]) << 8) | int(m[2])

    def light(self) -> bool:
        return is_light(self.representative())

    def field(self, width: int, height: int) -> np.ndarray:
        """(height, width, 3) 的底色，0–255 浮点。渐变的起止点与 `createGradientShader` 相同，端点之外夹住。"""
        if not self.gradient:
            return np.broadcast_to(rgb_of(self.color), (height, width, 3)).astype(np.float64)
        w, h = float(width), float(height)
        points = {
            'L_R': (0, h / 2, w, h / 2), 'T_B': (w / 2, 0, w / 2, h), 'LT_RB': (0, 0, w, h), 'RT_LB': (w, 0, 0, h),
            'LB_RT': (0, h, w, 0), 'RB_LT': (w, h, 0, 0), 'R_L': (w, h / 2, 0, h / 2), 'B_T': (w / 2, h, w / 2, 0),
        }[self.orientation]
        x0, y0, x1, y1 = points
        dx, dy = x1 - x0, y1 - y0
        yy, xx = np.mgrid[0:height, 0:width].astype(np.float64)
        t = np.clip(((xx + .5 - x0) * dx + (yy + .5 - y0) * dy) / max(dx * dx + dy * dy, 1e-9), 0, 1)[..., None]
        return rgb_of(self.color) * (1 - t) + rgb_of(self.end) * t

    def describe(self) -> str:
        if not self.gradient:
            return f'纯色 #{self.color:06X}'
        return f'渐变 #{self.color:06X} → #{self.end:06X}（{self.orientation}）'


def parse_accent(value) -> Background | None:
    """强调色的几种写法 → `Background`；None 原样返回。

    - `21A675`（纯色；可带 `#`，8 位时去掉透明度）；
    - `21A675,665696,LT_RB`（渐变：起止色与方向，方向缺省为 L_R，取值同 `ThingBackground.Orientation`）；
    - `ThingBackground.toJson()` 的 JSON（字典或字符串），如 `{"mode":"GRADIENT","color":-14571915,
      "endColor":-10070378,"orientation":"LT_RB"}`：手机端若在会话记录里记下强调色，多半是这个样子。"""
    if value is None or isinstance(value, Background):
        return value
    if isinstance(value, int):
        return Background(value & 0xFFFFFF)
    if isinstance(value, str) and value.strip().startswith('{'):
        value = json.loads(value)
    if isinstance(value, dict):
        color = int(value.get('color', 0)) & 0xFFFFFF
        if value.get('mode', 'PURE') != 'GRADIENT':
            return Background(color)
        end = int(value.get('endColor', color)) & 0xFFFFFF
        orientation = value.get('orientation', 'L_R')
        return Background(color, end, orientation if orientation in ORIENTATIONS else 'L_R')
    parts = [x.strip().lstrip('#') for x in str(value).split(',') if x.strip()]
    try:
        if not 1 <= len(parts) <= 3:
            raise ValueError
        colors = [int(x, 16) & 0xFFFFFF for x in parts[:2]]
    except ValueError:
        raise ValueError(f'认不出的强调色写法：{value!r}（纯色 21A675，渐变 21A675,665696,LT_RB）') from None
    if len(colors) == 1:
        return Background(colors[0])
    orientation = parts[2].upper() if len(parts) == 3 else 'L_R'
    if orientation not in ORIENTATIONS:
        raise ValueError(f'渐变方向须为 {"、".join(ORIENTATIONS)} 之一：{value!r}')
    return Background(colors[0], colors[1], orientation)


def random_background(rng: random.Random) -> Background:
    """一半纯色、一半渐变；渐变取调色板里两种不同的颜色、随机方向。"""
    if rng.random() < .5:
        return Background(rng.choice(PALETTE))
    a, b = rng.sample(PALETTE, 2)
    return Background(a, b, rng.choice(ORIENTATIONS))


def dominant(img: np.ndarray, rect=None) -> int:
    """区域里出现最多的不透明颜色（隔 3 像素取样）。"""
    x0, y0, x1, y1 = rect if rect is not None else (0, 0, img.shape[1], img.shape[0])
    px = img[max(int(y0), 0):int(y1):3, max(int(x0), 0):int(x1):3].reshape(-1, img.shape[2])
    if px.shape[1] == 4:
        px = px[px[:, 3] > 250]
    px = px[:, :3].astype(np.int64)
    packed = (px[:, 0] << 16) | (px[:, 1] << 8) | px[:, 2]
    values, counts = np.unique(packed, return_counts=True)
    return int(values[counts.argmax()])


def recolor_region(img: np.ndarray, rect, old: int, new: Background, tolerance: float = 26.0):
    """把 [rect] 里底色为 [old] 的记事换成 [new]（原地修改 RGBA uint8 图）。文字色按新底色的明暗换成黑 / 白，
    覆盖率不变；离「底色 → 文字色」这条线远的像素不动。"""
    x0, y0, x1, y1 = (int(round(v)) for v in rect)
    x0, y0 = max(x0, 0), max(y0, 0)
    x1, y1 = min(x1, img.shape[1]), min(y1, img.shape[0])
    if x1 <= x0 or y1 <= y0:
        return
    region = img[y0:y1, x0:x1, :3].astype(np.float64)
    base = rgb_of(old)
    ink_old = np.zeros(3) if is_light(old) else np.full(3, 255.0)
    axis = ink_old - base
    cover = np.clip(((region - base) @ axis) / max(float(axis @ axis), 1e-9), 0, 1)
    residual = np.linalg.norm(region - (base + cover[..., None] * axis), axis=-1)
    mask = residual < tolerance
    # 卡片里的图片：图片主体的颜色远离这条线、连成大块；取这些大块的外接矩形（外扩 6 像素）整块保留，
    # 图片里恰好落在线上的颜色（例如浅色天空）因此也不会被当成文字换掉
    parts, count = label(~mask)
    if count:
        sizes = np.bincount(parts.ravel())
        height, width = mask.shape
        for k in np.flatnonzero(sizes > 2000):
            if k == 0:
                continue
            ys, xs = np.nonzero(parts == k)
            top, bottom, left, right = max(ys.min() - 6, 0), ys.max() + 7, max(xs.min() - 6, 0), xs.max() + 7
            # 封面图铺满宽度（或高度）时贴着卡片的一边：保护区域延伸到最近的那条边
            if right - left >= .9 * width:
                top, bottom = (0, bottom) if top < height - bottom else (top, height)
            if bottom - top >= .9 * height:
                left, right = (0, right) if left < width - right else (left, width)
            mask[top:bottom, left:right] = False
    ink_new = np.zeros(3) if new.light() else np.full(3, 255.0)
    field = new.field(x1 - x0, y1 - y0)
    out = field * (1 - cover[..., None]) + ink_new * cover[..., None]
    region[mask] = out[mask]
    img[y0:y1, x0:x1, :3] = np.clip(region + .5, 0, 255).astype(np.uint8)


def recolor_accent(img: np.ndarray, old: int, new: int, paper: int = 0xFFFFFF, tolerance: float = 20.0):
    """面板上用记事颜色画在白纸上的图标与进度条（按覆盖率叠在白底上）：换成新的记事颜色。"""
    region = img[..., :3].astype(np.float64)
    base = rgb_of(paper)
    axis = rgb_of(old) - base
    cover = np.clip(((region - base) @ axis) / max(float(axis @ axis), 1e-9), 0, 1)
    residual = np.linalg.norm(region - (base + cover[..., None] * axis), axis=-1)
    mask = (residual < tolerance) & (cover > .02)
    out = base * (1 - cover[..., None]) + rgb_of(new) * cover[..., None]
    region[mask] = out[mask]
    img[..., :3] = np.clip(region + .5, 0, 255).astype(np.uint8)


class RecoloredSession:
    """一个会话换了记事颜色之后的样子：截图、粒子快照、碰上时的来件颜色都换掉，其余照原会话。

    `path` 是原路径下一个虚的子目录（带颜色的编号），所有按路径缓存的东西（粒子轨迹、舞台）因此各自一份。
    """

    def __init__(self, base, backgrounds: list[Background], token: str):
        self.base = base
        self.backgrounds = backgrounds
        self.token = token
        self.path = Path(base.path) / f'recolor-{token}'
        self.meta = base.meta
        self.kind = base.kind
        self.screen = base.screen
        self.density = base.density
        self._images: dict = {}
        self.events = copy.deepcopy(base.events)
        self.cards = self._cards()
        # 碰上时光尘带换成的来件颜色：纯色取它本身，渐变取两端（平均色偏灰，不用）
        for e in self.events:
            if e['type'] == 'stage-touched' and self.kind == 'receive':
                colors = []
                for bg in self._per_card():
                    colors += [bg.color, bg.end] if bg.gradient else [bg.color]
                e['colors'] = [f'FF{c:06X}' for c in colors]
        self.flights = []
        for event, scene in base.flights:
            self.flights.append((event, self._scene(scene)))

    # ---- 与 sim_timeline.Session 同名的接口
    @property
    def title(self) -> str:
        names = '、'.join(bg.describe() for bg in self._per_card())
        return f'{self.base.title} · {names}'

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

    @property
    def params(self):
        return self.base.params

    def note_backgrounds(self) -> list[Background]:
        """每条来件记事换成的底色（接收时从天线冒出的颗粒按它取色）。"""
        return self._per_card()

    @property
    def accent(self) -> Background | None:
        """分享面板的强调色跟着记事换（面板上的图标同样换成了新颜色）：扩散圈落在面板上的那一段按它画。"""
        return self._per_card()[0] if self.kind == 'send' else None

    def image(self, name: str) -> np.ndarray:
        if name not in self._images:
            img = self.base.image(name).copy()
            self._recolor(name, img)
            self._images[name] = img
        return self._images[name]

    # ---- 换色
    def _cards(self) -> list:
        """接收：几张卡片的矩形（屏幕坐标）；发送只有一张卡片，不需要矩形。"""
        if self.kind != 'receive':
            return []
        highlight = self.base.first('stage-highlight')
        return [tuple(r) for r in highlight['rects']] if highlight else []

    def _per_card(self) -> list[Background]:
        count = max(len(self.cards), 1)
        return [self.backgrounds[k % len(self.backgrounds)] for k in range(count)]

    def _recolor(self, name: str, img: np.ndarray):
        bgs = self._per_card()
        if self.kind == 'send':
            old = self._send_color()
            if name == 'page' or name.startswith('card-'):
                recolor_region(img, (0, 0, img.shape[1], img.shape[0]), old, bgs[0])
            elif name.startswith('panel-'):
                recolor_accent(img, old, bgs[0].representative())
        elif name == 'page-done':
            for rect, bg in zip(self.cards, bgs):
                recolor_region(img, rect, dominant(self.base.image(name), rect), bg)

    def _send_color(self) -> int:
        if not hasattr(self, '_old_send'):
            self._old_send = dominant(self.base.image('card-0'))
        return self._old_send

    def _scene(self, scene: Scene) -> Scene:
        snapshot = scene.snapshot.copy()
        bgs = self._per_card()
        if self.kind == 'send':
            recolor_region(snapshot, (0, 0, snapshot.shape[1], snapshot.shape[0]), self._send_color(), bgs[0])
        else:
            ox, oy = scene.bounds[:2]
            for (l, t, r, b), bg in zip(self.cards, bgs):
                rect = (l - ox, t - oy, r - ox, b - oy)
                recolor_region(snapshot, rect, dominant(scene.snapshot, rect), bg)
        meta = dict(scene.meta)
        return Scene(Path(scene.path), meta, snapshot, scene.background, scene.settled)


def recolor(session, seed: int | None = None) -> RecoloredSession:
    """随机换色：每张卡片各自随机一种纯色或渐变。"""
    rng = random.Random(seed)
    count = 1
    if session.kind == 'receive':
        highlight = session.first('stage-highlight')
        count = len(highlight['rects']) if highlight else 1
    token = f'{seed if seed is not None else rng.randrange(1 << 30)}'
    return RecoloredSession(session, [random_background(rng) for _ in range(count)], token)
