"""真实菜单回归工具：只操作本次经 UI 创建的 PROBE-REMOVE 测试数据。"""
import json
import os
import sys
import time
from pathlib import Path

assert os.environ.get('THING_ANIMATION_SERIAL') in ('392QBFED2226B', 'R5CW20BLNKL')
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'particle-animation-performance'))
import device_batch3_setup as helper

d, ui = helper.d, helper.ui
TITLES = ['PROBE-REMOVEA-20260918', 'PROBE-REMOVEB-20260918']


def select(titles):
    assert titles and all(t.startswith('PROBE-REMOVE') for t in titles)
    x, y = d.center(helper.card_of(d.dump(), titles[0]))
    ui.adb('shell', 'input', 'swipe', x, y, x, y, '800')
    d.dump('rl_contextual_toolbar')
    for title in titles[1:]:
        d.tap(helper.card_of(d.dump(), title))


def action(label):
    tree = d.dump()
    target = next((n for n in tree.iter('node') if n.get('content-desc') == label), None)
    if target is None:
        d.tap(d.node(tree, desc=('更多选项',)))
        target = d.node(d.dump('title'), text=label)
    d.tap(target)


def confirm():
    tree = d.dump('tv_confirm_as_bt_alert')
    text = [n.get('text') for n in tree.iter('node') if n.get('text')]
    print('确认框', json.dumps(text, ensure_ascii=False), flush=True)
    d.tap(d.node(tree, rid='tv_confirm_as_bt_alert'))
    end = time.monotonic() + 20
    while time.monotonic() < end:
        tree = d.dump()
        if not d.present(tree, 'act_select_all') and not d.present(tree, 'tv_confirm_as_bt_alert') and (
                d.present(tree, 'act_search') or d.present(tree, 'et_search')):
            return
    raise AssertionError('确认后没有返回普通或搜索列表')


def search_tests():
    tree = d.dump()
    if not d.present(tree, 'et_search'):
        d.tap(d.node(tree, rid='act_search'))
    d.tap(d.node(d.dump('et_search'), rid='et_search'))
    ui.adb('shell', 'input', 'text', 'PROBE-REMOVE')
    d.dump('et_search')
    ui.adb('shell', 'input', 'keyevent', 'BACK')
    d.dump('et_search')


def status(index):
    assert index in (0, 1, 2)
    tree = d.dump()
    if not any(n.get('content-desc') == '关闭导航抽屉' for n in tree.iter('node')):
        d.tap(d.node(tree, desc=('打开导航抽屉',)))
        tree = d.dump()
    parents = {c: n for n in tree.iter() for c in n}
    node = next(n for n in tree.iter('node')
                if n.get('text') in ('正在进行', '已完成', '回收站') and not n.get('resource-id'))
    while node.get('clickable') != 'true':
        node = parents[node]
    row = parents[node]
    assert len(row) == 3  # ThingStatusSegmentedView 固定三个状态，位置来自当前 UI 树。
    d.tap(row[index])
    tree = d.dump()
    if any(n.get('content-desc') == '关闭导航抽屉' for n in tree.iter('node')):
        ui.adb('shell', 'input', 'keyevent', 'BACK')
    tree = d.dump('tv_header_title')
    assert d.node(tree, rid='tv_header_title').get('text') == ('正在进行', '已完成', '已删除')[index]


def absent(titles):
    shown = {n.get('text') for n in d.dump().iter('node')}
    assert not shown.intersection(titles), shown.intersection(titles)


if __name__ == '__main__':
    # 当前前置条件：两张测试记事均在正在进行根列表。每次动作均手工确认过测试数据范围。
    ui.adb('shell', 'monkey', '-p', ui.PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1')
    d.dump('act_search')
    select(TITLES)
    action('删除所选记事')
    confirm()
    absent(TITLES)
    print('PASS 删除', ui.SERIAL, flush=True)
    status(2)
    search_tests()
    select(TITLES)
    action('恢复所选记事')
    confirm()
    absent(TITLES)
    print('PASS 回收站恢复', ui.SERIAL, flush=True)
    ui.adb('shell', 'input', 'keyevent', 'BACK')
    d.dump('act_search')
    status(0)
    select(TITLES)
    action('删除所选记事')
    confirm()
    absent(TITLES)
    status(2)
    search_tests()
    select(TITLES)
    action('永久删除所选记事')
    confirm()
    absent(TITLES)
    print('PASS 永久删除', ui.SERIAL, flush=True)
    ui.adb('shell', 'input', 'keyevent', 'BACK')
    d.dump('act_search')
    status(0)
