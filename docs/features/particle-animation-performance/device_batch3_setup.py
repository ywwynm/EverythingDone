"""第三批起共用的准备与还原：只经真实系统输入建专用测试记事、切动画档位，不发业务 Intent。

用法（两台各一次）：
    setup   建 PerfB<批次>-<日期>-1（纯文字）与 -2（可选加图），并把新建/左滑档位切到粒子
    restore 把档位改回用户原来的值（记事的清理交给 device_particle_cleanup.py）

第四批起标题前缀放宽到 `PerfB<数字>-`，同一份脚本可以给后续批次继续用。
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

# 第三批起另有一组「不同复杂度」的专用卡片：矮卡之外还要高文本卡、清单卡、带图卡，
# 因为卡片高度直接改片数、压力网格与 ItemTouchHelper 的阈值，一行字的卡量不出真实情况。
# 华为 MatePad Pro 13.2 上 `input text` 的拉丁字母会被中文输入法改写
# （"PerfB9-20260917-1" 打出来是 "P儿0260917－1"，连 `-` 都变成全角），而**输入法属于设备设置，
# 不得代为切换**。数字实测原样进去，所以那台用纯数字标题。
TEST_TITLE = re.compile(r'PerfB[0-9]-[0-9]{8}-[0-9]|PROBE-[A-Z]+-[0-9]{8}|909170[0-9]{2}')

# 高文本卡：`card_thing.xml` 里 `tv_thing_content` 的 maxLines 是 9，写满即为列表允许的最高文本卡。
# 正文一律用数字：两台设备打出来的东西才一样，行数与行宽也不会被输入法改掉。
TALL_LINES = ['%d0123456789012345678901234567890123456789' % i for i in range(1, 11)]
LIST_ITEMS = ['%d012345678901234567890' % i for i in range(1, 11)]


def type_lines(lines):
    """逐行输入；`input text` 不接受换行，所以每行之间补一次回车。"""
    for index, line in enumerate(lines):
        if index:
            ui.adb('shell', 'input', 'keyevent', '66')
        ui.adb('shell', 'input', 'text', line.replace(' ', '%s'))


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


def create_card(title, lines=None, checklist=False):
    """建一张指定复杂度的专用测试记事：标题 + 若干行正文，可选转成清单并勾掉前三条。"""
    assert TEST_TITLE.fullmatch(title), title
    tree = d.dump('fab_create')
    assert not any(n.get('text') == title for n in tree.iter('node')), '同名记事已存在 ' + title
    d.tap(d.node(tree, rid='fab_create'))
    d.dump('et_title')
    d.tap(d.node(d.dump(), rid='et_title'))
    ui.adb('shell', 'input', 'text', title)
    if lines:
        d.tap(d.node(d.dump('et_content'), rid='et_content'))
        type_lines(lines)
        if checklist:
            # 工具栏的「清单」按内容的行切成条目；先写好行再切，比逐条敲稳得多。
            tree = d.dump()
            item = next((n for n in tree.iter('node')
                         if n.get('content-desc') in CHECK_LIST_LABELS), None)
            assert item is not None, '工具栏里没有找到清单按钮：' + ' '.join(
                sorted({n.get('content-desc', '') for n in tree.iter('node')
                        if n.get('content-desc')}))
            d.tap(item)
            tree = d.dump('rv_check_list')
            boxes = [n for n in tree.iter('node')
                     if n.get('resource-id', '').endswith('/cb_check_list')]
            assert len(boxes) >= len(lines) - 1, '清单条目只有 %d 条' % len(boxes)
            for box in boxes[:3]:
                d.tap(box)
                time.sleep(.2)
    ui.adb('shell', 'input', 'keyevent', '4')
    tree = d.dump()
    if d.present(tree, 'ib_back'):
        ui.adb('shell', 'input', 'keyevent', '4')
    for _ in range(40):
        tree = d.dump('fab_create')
        if any(n.get('text') == title for n in tree.iter('node')):
            break
        time.sleep(.3)
    assert any(n.get('text') == title for n in tree.iter('node')), '没有建出 ' + title
    card = card_of(tree, title)
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', card.get('bounds')))
    return {'height': y2 - y1, 'width': x2 - x1}


CHECK_LIST_LABELS = ('Check list', '清单', '清單', 'Checklist', '启用清单', '啟用清單',
                     'Enable check list', 'Enable checklist')


def convert_to_checklist(title):
    """打开已有的记事，把正文转成清单并勾掉前三条（`cards` 里那一步失败时补跑）。"""
    d.tap(card_of(d.dump('fab_create'), title))
    # 已经是清单的记事详情页里没有 et_content，只有 rv_check_list。
    tree = d.dump('et_title')
    if not d.present(tree, 'rv_check_list'):
        item = next((n for n in tree.iter('node')
                     if n.get('content-desc') in CHECK_LIST_LABELS), None)
        assert item is not None, '工具栏里没有找到清单按钮：' + ' '.join(
            sorted({n.get('content-desc', '') for n in tree.iter('node') if n.get('content-desc')}))
        d.tap(item)
    tree = d.dump('rv_check_list')
    # 勾选框是 `iv_check_list_state`（见 check_list_et.xml），不是 CheckBox。
    boxes = [n for n in tree.iter('node')
             if n.get('resource-id', '').endswith('/iv_check_list_state')]
    for box in boxes[:3]:
        d.tap(box)
        time.sleep(.3)
    checked = len(boxes)
    ui.adb('shell', 'input', 'keyevent', '4')
    tree = d.dump()
    if d.present(tree, 'ib_back'):
        ui.adb('shell', 'input', 'keyevent', '4')
    tree = d.dump('fab_create')
    card = card_of(tree, title)
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', card.get('bounds')))
    has_list = any(n.get('resource-id', '').endswith('/rv_check_list') for n in card.iter('node'))
    return {'height': y2 - y1, 'items': checked, 'checklistOnCard': has_list}

REMOTE_IMAGE = '/sdcard/Pictures/probe-card-image.jpg'


def push_image(local):
    """把测试图推进相册并让媒体库收录；不改任何系统设置。"""
    ui.adb('push', local, REMOTE_IMAGE)
    ui.adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
           '-d', 'file://' + REMOTE_IMAGE)
    time.sleep(2.0)


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
    p.add_argument('action', choices=['setup', 'restore', 'attach-image', 'cards', 'make-list'])
    p.add_argument('--titles', nargs='*', default=[])
    p.add_argument('--create-mode', type=int, default=-1)
    p.add_argument('--swipe-mode', type=int, default=-1)
    p.add_argument('--with-image', action='store_true')
    p.add_argument('--date', default='20260917', help='cards：标题里的日期')
    p.add_argument('--image', default='', help='cards：本地测试图路径，留空则不建带图卡')
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
    if a.action == 'cards':
        # 一组不同复杂度的专用卡：一行字的矮卡量不出真实的左滑（卡片高度直接改片数、
        # 压力网格与 ItemTouchHelper 的阈值）。每建一张就报它在列表里的实际高度。
        names = a.titles if a.titles else ['PROBE-TALL-%s' % a.date, 'PROBE-LIST-%s' % a.date,
                                           'PROBE-IMAGE-%s' % a.date]
        plan = [(names[0], TALL_LINES, False), (names[1], LIST_ITEMS, True)]
        if a.image:
            plan.append((names[2], None, False))
            push_image(a.image)
        for title, lines, checklist in plan:
            try:
                result[title] = create_card(title, lines, checklist)
                if lines is None:
                    result[title]['image'] = attach_image(title)
                    card = card_of(d.dump('fab_create'), title)
                    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', card.get('bounds')))
                    result[title]['height'] = y2 - y1
            except Exception as error:  # noqa: BLE001 一张建不出来不该拖垮整组
                result[title] = {'error': str(error)}
                for _ in range(4):
                    ui.adb('shell', 'input', 'keyevent', '4')
                    time.sleep(.4)
                    if d.present(d.dump(), 'fab_create'):
                        break
    if a.action == 'make-list':
        for title in a.titles:
            result[title] = convert_to_checklist(title)
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
