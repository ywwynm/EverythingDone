"""第三批：准备与还原。只经真实系统输入建专用测试记事、切动画档位，不发业务 Intent。

用法（两台各一次）：
    setup   建 PerfB3-<日期>-1（纯文字）与 -2（可选加图），并把新建/左滑档位切到粒子
    restore 把档位改回用户原来的值（记事的清理交给 device_particle_cleanup.py）
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_particle_perf as d  # noqa: E402
import device_ui as ui  # noqa: E402
import device_performance as perf  # noqa: E402

TEST_TITLE = re.compile(r'PerfB3-[0-9]{8}-[0-9]')


def create_note(title):
    assert TEST_TITLE.fullmatch(title), title
    tree = d.dump('fab_create')
    assert not any(n.get('text') == title for n in tree.iter('node')), '同名记事已存在'
    d.tap(d.node(tree, rid='fab_create'))
    d.dump('et_title')
    d.tap(d.node(d.dump(), rid='et_title'))
    ui.adb('shell', 'input', 'text', title)
    d.dump('et_title')
    ui.adb('shell', 'input', 'keyevent', '4')
    tree = d.dump()
    if d.present(tree, 'ib_back'):
        ui.adb('shell', 'input', 'keyevent', '4')
    # 保存之后首页要走「腾位 → 粒子 → 完整卡片」三段，标题不会立刻出现在列表里。
    for _ in range(40):
        tree = d.dump('fab_create')
        if any(n.get('text') == title for n in tree.iter('node')):
            break
        time.sleep(.3)
    assert any(n.get('text') == title for n in tree.iter('node')), '没有建出 ' + title
    return d.node(tree, rid='tv_header_subtitle').get('text')


def card_of(tree, title):
    target = next(n for n in tree.iter('node') if n.get('text') == title)
    parents = {c: n for n in tree.iter() for c in n}
    while not target.get('resource-id', '').endswith('/cv_thing'):
        target = parents[target]
    return target


def attach_image(title):
    """经真实附件菜单 + 系统选择器给专用记事加一张图；失败返回原因，不抛。"""
    d.tap(card_of(d.dump('fab_create'), title))
    tree = d.dump('et_content')
    try:
        d.tap(d.node(tree, desc=d.ADD_ATTACHMENT))
        tree = d.dump('tv_record_audio_as_bt')
        labels = ('Choose media files', '选择媒体文件', 'Album', '相册', '图库', 'Gallery', 'Photos')
        row = [n for n in tree.iter('node')
               if n.get('text') in labels or n.get('content-desc') in labels]
        if not row:
            row = [n for n in tree.iter('node')
                   if n.get('resource-id', '').endswith('/tv_select_image_as_bt')]
        assert row, '附件弹窗里没有找到相册入口：' + ' '.join(
            sorted({n.get('text', '') for n in tree.iter('node') if n.get('text')}))
        d.tap(row[0])
        time.sleep(3)
        tree = d.dump()
        # 系统选择器（DocumentsUI）：先点顶部第一个筛选片（「图片」），它按类型列出全部图片，
        # 不必逐级进目录。文案是系统本地化的，所以按分组里的**第一个**按钮定位，不按文字。
        chips = [n for n in tree.iter('node')
                 if n.get('resource-id', '').endswith('/search_chip_group')]
        if chips:
            buttons = [n for n in chips[0].iter('node') if n.get('clickable') == 'true']
            assert buttons, '筛选片分组里没有可点的按钮'
            d.tap(buttons[0])
            time.sleep(3)
            tree = d.dump()
        pictures = [n for n in tree.iter('node')
                    if n.get('resource-id', '').endswith('/item_root')
                    and any('thumbnail' in (c.get('resource-id') or '') for c in n.iter('node'))]
        assert pictures, '系统选择器里没找到可选的图片'
        d.tap(pictures[0])
        time.sleep(3)
        d.dump('et_content')
        ui.adb('shell', 'input', 'keyevent', '4')
        tree = d.dump()
        if d.present(tree, 'ib_back'):
            ui.adb('shell', 'input', 'keyevent', '4')
        tree = d.dump('fab_create')
        card = card_of(tree, title)
        has_image = any(n.get('resource-id', '').endswith('/iv_thing_image')
                        for n in card.iter('node'))
        return {'attached': has_image}
    except Exception as error:  # noqa: BLE001 只报告，不让准备阶段中断整轮
        for _ in range(4):
            ui.adb('shell', 'input', 'keyevent', '4')
            time.sleep(.4)
            if d.present(d.dump(), 'fab_create'):
                break
        return {'attached': False, 'error': str(error)}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['setup', 'restore', 'attach-image'])
    p.add_argument('--titles', nargs='*', default=[])
    p.add_argument('--create-mode', type=int, default=-1)
    p.add_argument('--swipe-mode', type=int, default=-1)
    p.add_argument('--with-image', action='store_true')
    a = p.parse_args()
    d.observer(False)
    d.probe(None)
    d.launch()
    result = {'serial': ui.SERIAL, 'action': a.action}
    if a.action == 'setup':
        for title in a.titles:
            result[title] = create_note(title)
        if a.with_image and len(a.titles) > 1:
            result['image'] = attach_image(a.titles[1])
    if a.action == 'attach-image':
        result['image'] = attach_image(a.titles[0])
    if a.create_mode >= 0:
        perf.select('create', a.create_mode)
    if a.swipe_mode >= 0:
        perf.select('swipe', a.swipe_mode)
    prefs = ui.adb('shell', 'run-as', ui.PACKAGE, 'cat',
                   'shared_prefs/EverythingDone_preferences.xml').decode('utf-8', 'replace')
    result['animation'] = [l.strip() for l in prefs.splitlines() if 'animation' in l]
    tree = d.dump('fab_create')
    result['subtitle'] = d.node(tree, rid='tv_header_subtitle').get('text')
    print(json.dumps(result, ensure_ascii=False))
