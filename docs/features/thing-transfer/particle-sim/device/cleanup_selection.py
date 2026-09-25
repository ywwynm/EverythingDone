"""把「碰一碰模拟 · 样例记事」移入回收站。每一轮：只选屏幕上完整可见的样例卡片，删除前逐张核对
「被选中的卡片」恰好就是这些样例（按卡片的选中状态 + 卡片里的标题），任何一项对不上就退出选择模式并停下。

用法：SERIAL=<序列号> python cleanup_selection.py [--dry]（三星；魅族上长按会被识屏浮层截走，用 cleanup_detail.py）
--dry 只选、核对、截图，然后退出选择模式，不删除。
"""
import colorsys
import re
import sys
import time

import numpy as np
from PIL import Image

import dev

TITLE = '碰一碰模拟 · 样例记事'
dry = '--dry' in sys.argv


def cards(tree):
    """每张卡片：(卡片节点, 标题, 是否选中)。卡片节点取标题的祖先里 resource-id 以 cv_thing 结尾的那个。"""
    parent = {c: p for p in tree.iter() for c in p}
    out = []
    for n in tree.iter('node'):
        if not (n.get('resource-id') or '').endswith('/tv_thing_title'):
            continue
        card = n
        while card is not None and not (card.get('resource-id') or '').endswith('/cv_thing'):
            card = parent.get(card)
        if card is None:
            continue
        selected = card.get('selected') == 'true' or card.get('checked') == 'true'
        out.append((card, n.get('text') or '', selected))
    return out


def selected_count(tree):
    for n in tree.iter('node'):
        m = re.match(r'^(\d+)\s*/\s*(\d+)$', (n.get('text') or '').strip())
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def card_saturation(image, card):
    """卡片区域里出现最多的颜色的饱和度。选择模式下未选中的卡片换成浅色，饱和度明显更低。"""
    x1, y1, x2, y2 = dev.bounds(card)
    patch = np.asarray(image.crop((x1 + 6, y1 + 6, x2 - 6, y2 - 6)).convert('RGB')).reshape(-1, 3)
    colors, counts = np.unique(patch, axis=0, return_counts=True)
    r, g, b = colors[counts.argmax()] / 255.0
    return colorsys.rgb_to_hsv(r, g, b)[1]


def leave_selection():
    dev.shell('input keyevent 4')
    time.sleep(0.8)


for round_ in range(20):
    tree = dev.dump()
    height = max(dev.bounds(n)[3] for n in tree.iter('node'))
    visible = [(c, t) for c, t, _ in cards(tree)
               if TITLE in t and dev.bounds(c)[1] > 250 and dev.bounds(c)[3] < height - 150]
    if not visible:
        if not any(TITLE in t for _, t, _ in cards(tree)):
            print('屏幕上没有样例了')
            break
        dev.shell('input swipe 720 2000 720 1300 600')
        time.sleep(1.0)
        continue
    x, y = dev.center(visible[0][0])
    dev.shell('input motionevent DOWN %d %d' % (x, y))
    time.sleep(0.9)
    dev.shell('input motionevent UP %d %d' % (x, y))
    time.sleep(0.9)
    for c, _ in visible[1:]:
        dev.tap(*dev.center(c))
        time.sleep(0.45)
    time.sleep(0.6)
    tree = dev.dump()
    count = selected_count(tree)
    shot = dev.screencap('cleanup_%s_round%d.png' % (dev.SERIAL, round_))
    image = Image.open(shot)
    # 卡片节点在选择模式下位置不变，沿用这一轮 dump 里的样例卡片
    sats = [round(card_saturation(image, c), 2) for c, _ in visible]
    print('round', round_, 'visible samples', len(visible), 'toolbar', count, 'saturation', sats)
    # 工具栏计数等于所点的样例数、且这些样例在截图上都是选中的深色：选中的恰好就是它们
    ok = count is not None and count[0] == len(visible) and all(v >= 0.45 for v in sats)
    if not ok:
        leave_selection()
        raise SystemExit('选中的卡片与样例对不上，停下（见截图）')
    if dry:
        leave_selection()
        print('dry run：已退出选择模式，未删除')
        break
    more = dev.poll(desc='更多选项')
    dev.tap(*dev.center(more))
    delete = dev.poll(text='删除所选记事')
    dev.tap(*dev.center(delete))
    # 确认框：「将删除所选记事，共 N 件，可在回收站恢复」，件数必须与这一轮选的一致
    message = dev.poll(text='共%d件' % len(visible))
    print('confirm:', message.get('text'))
    dev.tap(*dev.center(dev.poll(text='确定')))
    time.sleep(1.8)
print('done')
