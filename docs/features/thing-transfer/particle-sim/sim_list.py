"""首页列表的瀑布流布局：两列，通栏卡片横跨两列（`ThingsStaggeredLayoutManager`，即 StaggeredGridLayoutManager）。

应用收到记事时把它们插在置顶记事之后，后收到的排在前面（`ThingManager` 按 location 排序：置顶记事的
location < 0 排在最前，新记事取非置顶部分的第一个 location）。插入会让插入位置之后各项的列分配失效，这些项
重新按 `StaggeredGridLayoutManager.getNextSpan` 排：逐项放进当前结束得较早的那一列，一样早放左列；通栏
卡片放在两列都结束之后，之后两列从它的底边接着排。

几何取自录下的列表（两台设备都一样）：卡片四周外边距 8 dp；两列各宽（列表宽 − 6 × 外边距）/ 2；通栏宽
列表宽 − 4 × 外边距；列表最前面是头部的占位项（全宽、不可见），第一张卡片的顶边是它的底边 + 外边距。

模拟器用它合成不同列表状态的场景（`make_layout_scenes.py`），也用它在进度期间预计来件的落点
（`sim_emerge.predict_slots`）。
"""
from __future__ import annotations

from dataclasses import dataclass

MARGIN_DP = 8.0


@dataclass
class Grid:
    """一台设备上列表的几何（屏幕像素）。"""
    margin: float
    columns: tuple[float, float]   # 两列卡片的左缘
    width: float                   # 普通卡片的宽度
    full_x: float                  # 通栏卡片的左缘
    full_width: float
    top: float                     # 头部占位项的底边：第一张卡片的顶边 = top + margin

    @classmethod
    def from_items(cls, items: list, list_rect, density: float) -> 'Grid':
        """[items] 是录下的一帧列表（[id, x, y, w, h, alpha]，按列表顺序），[list_rect] 是列表的 l, t, r, b。"""
        left, _, right, _ = (float(v) for v in list_rect)
        span = right - left
        margin = MARGIN_DP * density
        width = (span - 6 * margin) / 2
        header = header_of(items, span)
        if header is not None:
            top = float(header[2] + header[4])
        else:
            cards = [it for it in items if it[5] > .5]
            top = (min(float(it[2]) for it in cards) - margin) if cards else margin
        x0 = left + 2 * margin
        return cls(margin, (x0, x0 + width + 2 * margin), width, x0, span - 4 * margin, top)

    def span_width(self, full: bool) -> float:
        return self.full_width if full else self.width

    def is_full(self, rect_width: float) -> bool:
        return rect_width > (self.width + self.full_width) / 2


def header_of(items: list, list_width: float):
    """列表最前面的头部占位项：全宽、不可见（「正在进行 N 件记事」由另一个视图画在它上面）。"""
    if items and items[0][5] <= .5 and items[0][3] > .8 * list_width:
        return items[0]
    return None


def layout(grid: Grid, entries: list[tuple[float, bool]]) -> list[tuple[float, float, float, float]]:
    """[entries]：按列表顺序的 (高度, 是否通栏)。返回每一项的矩形 (l, t, r, b)。"""
    ends = [grid.top, grid.top]
    out = []
    for height, full in entries:
        if full:
            top = max(ends) + grid.margin
            out.append((grid.full_x, top, grid.full_x + grid.full_width, top + height))
            ends = [top + height + grid.margin] * 2
        else:
            column = 0 if ends[0] <= ends[1] else 1
            top = ends[column] + grid.margin
            x = grid.columns[column]
            out.append((x, top, x + grid.width, top + height))
            ends[column] = top + height + grid.margin
    return out


def insertion_order(existing: list, pinned: set, incoming: list) -> list:
    """收到之后的列表顺序：置顶的在前（保持原顺序），然后是收到的（后收到的在前），然后是其余的。
    [existing] 是插入之前的各项（不含头部），[incoming] 按收到的先后。"""
    top = [e for e in existing if e in pinned]
    rest = [e for e in existing if e not in pinned]
    return top + list(reversed(incoming)) + rest
