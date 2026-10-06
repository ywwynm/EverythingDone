"""把 perf_compare.py 一次运行的「出帧对比」行按执行顺序分到各场景，出两种样式的对照表。

perf_compare 的顺序（每种样式）：弹窗 N 轮（出现、消散）→ 左滑 M 轮（跟手、回位出现）→ 多选移除 R 轮（确认弹窗出现、卡片消散、
确认弹窗消散；后两行按收尾先后出现，按颗粒数区分：确认弹窗消散与它的出现是同一个弹窗，颗粒数只差几颗）→ 新建页 C 轮（出现）。
每个场景报：首帧（第一轮是该进程里的冷启动，单列）、热态中位与范围、上屏 / 应有、间隔 p90 中位、最大间隔。

用法：python perf_table.py --run q10 --dialog 6 --swipe 4 --removal 3 --creation 4
"""
import argparse
import re
import statistics
from pathlib import Path

import drift_driver as d

LINE = re.compile(r'出帧对比 样式=(\S+) 类型=(\S+) 首帧Ms=([\d.]+) 上屏=(\d+) 应有=(\d+) 用时Ms=(\d+) '
                  r'p90间隔Ms=([\d.]+) 最大间隔Ms=([\d.]+) 最大间隔在=(\d+)/(\d+) 颗粒=(\d+)')


def take(rows, kinds):
    out = []
    for kind in kinds:
        row = rows.pop(0)
        if row['kind'] != kind:
            raise RuntimeError('顺序对不上：要 %s，拿到 %s（首帧 %.1f）' % (kind, row['kind'], row['first']))
        out.append(row)
    return out


def describe(items, cold_first=True):
    first = [r['first'] for r in items]
    warm = first[1:] if cold_first and len(first) > 1 else first
    shown = sum(r['shown'] for r in items)
    expected = sum(r['expected'] for r in items)
    return dict(
        cold=first[0] if cold_first else None,
        median=statistics.median(warm), low=min(warm), high=max(warm),
        frames='%d/%d' % (shown, expected), ratio=shown / max(expected, 1),
        p90=statistics.median(r['p90'] for r in items), worst=max(r['max'] for r in items),
        particles=int(statistics.median(r['particles'] for r in items)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--dialog', type=int, default=6)
    p.add_argument('--swipe', type=int, default=4)
    p.add_argument('--removal', type=int, default=3)
    p.add_argument('--creation', type=int, default=4)
    a = p.parse_args()
    text = (d.OUT / a.run / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    by_style = {}
    for m in LINE.finditer(text):
        by_style.setdefault(m.group(1), []).append(dict(
            kind=m.group(2), first=float(m.group(3)), shown=int(m.group(4)), expected=int(m.group(5)),
            p90=float(m.group(7)), max=float(m.group(8)), particles=int(m.group(11))))
    table = {}
    for style, rows in by_style.items():
        rows = list(rows)
        scenes = {k: [] for k in ('弹窗出现', '弹窗消散', '左滑跟手', '卡片回位', '移除确认弹窗出现', '多选移除卡片',
                                  '移除确认弹窗消散', '新建页')}
        for _ in range(a.dialog):
            appear, dismiss = take(rows, ['出现', '消散'])
            scenes['弹窗出现'].append(appear)
            scenes['弹窗消散'].append(dismiss)
        for _ in range(a.swipe):
            swipe, back = take(rows, ['跟手', '出现'])
            scenes['左滑跟手'].append(swipe)
            scenes['卡片回位'].append(back)
        for _ in range(a.removal):
            appear, x, y = take(rows, ['出现', '消散', '消散'])
            # 消散前会重新截图，颗粒数与出现时只差几颗；取更接近的那一行
            near = abs(x['particles'] - appear['particles']) <= abs(y['particles'] - appear['particles'])
            dialog, cards = (x, y) if near else (y, x)
            scenes['移除确认弹窗出现'].append(appear)
            scenes['多选移除卡片'].append(cards)
            scenes['移除确认弹窗消散'].append(dialog)
        for _ in range(a.creation):
            scenes['新建页'].extend(take(rows, ['出现']))
        if rows:
            raise RuntimeError('%s 还剩 %d 行没分' % (style, len(rows)))
        # 每轮左滑、多选移除前都停了进程，这两类每一轮都是冷启动；其余场景第一轮是冷启动
        table[style] = {k: describe(v, cold_first=k not in ('左滑跟手', '卡片回位', '移除确认弹窗出现', '多选移除卡片',
                                                            '移除确认弹窗消散')) for k, v in scenes.items() if v}
    for scene in table[next(iter(table))]:
        print(scene)
        for style in table:
            s = table[style][scene]
            cold = '' if s['cold'] is None else ' 冷启动 %.0f,' % s['cold']
            print('  %s:%s 首帧 %.0f（%.0f–%.0f） 帧 %s p90 %.1f 最大 %.1f 颗粒 %d' % (
                style, cold, s['median'], s['low'], s['high'], s['frames'], s['p90'], s['worst'], s['particles']))


if __name__ == '__main__':
    main()
