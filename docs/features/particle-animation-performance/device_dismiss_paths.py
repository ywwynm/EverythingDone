"""弱设备阶段 A：把「添加附件弹窗 / 调整颜色面板 × 各种关闭方式」逐条跑一遍。

只向 Android 输入系统注入真实点击与按键，不发业务 Intent、不改任何系统设置。
判据全部来自应用自己的 debug 日志（「消散受理 / 消散未受理 / 消散未开播 /
消散降级淡出 / 首帧上屏 / 完成 count=」），因此**必须**在跑之前另起一条
`adb -s <序列号> logcat -v time` 流到本地文件——魅族的主缓冲只有 256 KiB，
事后 `logcat -d` 捞不回来。

每个动作前后向 logcat 打一条 `PhaseAMark` 标记（纯 ASCII，避免 shell 转义与
编码问题），`analyze_dismiss_paths.py` 按标记切段。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]

ADD_ATTACHMENT = ('Add attachment', '添加附件', '新增附件', '增加附件')
CHANGE_COLOR = ('Adjust color', '调整颜色', '調整顏色')
UI_DUMP = '/sdcard/particle-perf-ui.xml'
MARK_TAG = 'PhaseAMark'

ui = None  # 解析完 --serial 再导入，device_ui 在导入时就读环境变量里的序列号。


def adb(*args):
    return subprocess.run([ui.ADB, '-s', ui.SERIAL, *map(str, args)],
                          capture_output=True, check=True).stdout


def soft(*args):
    """允许失败的调用：uiautomator 在动画期间可能什么也不写。"""
    return subprocess.run([ui.ADB, '-s', ui.SERIAL, *map(str, args)], capture_output=True)


def mark(text):
    assert re.fullmatch(r'[A-Za-z0-9_.=-]+', text), '标记必须是纯 ASCII，避免 shell 转义'
    adb('shell', 'log', '-p', 'i', '-t', MARK_TAG, text)


def dump(require=None, tries=30, forbid=None):
    """带动画的弹窗会返回陈旧缓存树；dump 前先删旧文件，并轮询到目标出现为止。"""
    for _ in range(tries):
        soft('shell', 'rm', '-f', UI_DUMP)
        soft('shell', 'uiautomator', 'dump', UI_DUMP)
        got = soft('shell', 'cat', UI_DUMP)
        if got.returncode:
            time.sleep(.2)
            continue
        try:
            tree = ET.fromstring(got.stdout)
        except ET.ParseError:
            time.sleep(.2)
            continue
        if require is not None and not present(tree, require):
            time.sleep(.2)
            continue
        if forbid is not None and present(tree, forbid):
            time.sleep(.2)
            continue
        return tree
    raise RuntimeError('UI 没有到达预期状态 require=%s forbid=%s' % (require, forbid))


def present(tree, rid):
    return any(n.get('resource-id', '').endswith('/' + rid) for n in tree.iter('node'))


def node(tree, rid=None, desc=None):
    found = [n for n in tree.iter('node')
             if (rid is None or n.get('resource-id', '').endswith('/' + rid))
             and (desc is None or n.get('content-desc') in desc)]
    if len(found) != 1:
        raise RuntimeError('期望唯一节点，实际 %d 个：%s' % (len(found), rid or desc))
    return found[0]


def bounds(n):
    return tuple(map(int, re.findall(r'-?\d+', n.get('bounds'))))


def center(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    adb('shell', 'input', 'tap', x, y)


def screen_size():
    out = adb('shell', 'wm', 'size').decode(errors='replace')
    # 有 Override size 时以它为准：输入坐标落在 override 的坐标系里。
    sizes = re.findall(r'(\d+)x(\d+)', out)
    w, h = sizes[-1]
    return int(w), int(h)


def window_of(tree, rid):
    """含 [rid] 的那个顶层窗口节点（`hierarchy` 的直接子节点）。"""
    for window in tree:
        if present(window, rid):
            return window
    raise RuntimeError('没有找到含 %s 的窗口' % rid)


def outside_point(tree, rid, screen):
    """弹窗之外、且远离它的一点。优先取弹窗上方，上方不够就取下方。"""
    x1, y1, x2, y2 = bounds(window_of(tree, rid))
    w, h = screen
    if y1 > 420:
        return w // 2, max(160, y1 - 200)
    if y2 < h - 420:
        return w // 2, min(h - 160, y2 + 200)
    # 窗口几乎占满高度：退到左右侧边。
    if x1 > 240:
        return max(80, x1 - 120), h // 2
    return min(w - 80, x2 + 120), h // 2


def has_permission(name):
    """只读查询：没有授予录音权限的设备上，点「录音」行只会弹系统授权框。
    授权属于设备设置，测试不得代为改动，这一格直接跳过。"""
    out = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'dumpsys', 'package', ui.PACKAGE],
                         capture_output=True).stdout.decode(errors='replace')
    return re.search(r'android\.permission\.' + name + r':\s*granted=true', out) is not None


def launch():
    adb('shell', 'am', 'force-stop', ui.PACKAGE)
    time.sleep(1.2)
    adb('shell', 'monkey', '-p', ui.PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    return dump('fab_create')


def open_detail(tree, title=None):
    """从首页真实点击一张记事卡片进入详情页；不指定标题时取第一张。"""
    cards = [n for n in tree.iter('node') if n.get('resource-id', '').endswith('/cv_thing')]
    if not cards:
        raise RuntimeError('首页没有记事卡片，无法取得宿主详情页')
    target = cards[0]
    if title:
        parents = {c: n for n in tree.iter() for c in n}
        hit = next(n for n in tree.iter('node') if n.get('text') == title)
        while not hit.get('resource-id', '').endswith('/cv_thing'):
            hit = parents[hit]
        target = hit
    texts = [n.get('text') for n in target.iter('node') if n.get('text')]
    tap(target)
    dump('et_content')
    return texts


# ===== 关闭方式 =====

def close_back(**_):
    adb('shell', 'input', 'keyevent', '4')


def close_outside(tree=None, rid=None, screen=None, **_):
    x, y = outside_point(tree, rid, screen)
    adb('shell', 'input', 'tap', x, y)
    return {'点外部坐标': [x, y]}


def close_button(tree=None, button=None, **_):
    tap(node(tree, rid=button))


CASES = [
    # (ASCII 键, 中文名, 打开用的 content-desc, 弹窗判据 id, 关闭方式, 关闭参数)
    ('attach-back', '附件弹窗-返回键', ADD_ATTACHMENT, 'tv_record_audio_as_bt', 'back', {}),
    ('attach-outside', '附件弹窗-点外部', ADD_ATTACHMENT, 'tv_record_audio_as_bt', 'outside', {}),
    ('attach-audio', '附件弹窗-点录音行', ADD_ATTACHMENT, 'tv_record_audio_as_bt', 'audio', {}),
    ('color-back', '颜色面板-返回键', CHANGE_COLOR, 'tv_confirm_as_bt_tbe', 'back', {}),
    ('color-outside', '颜色面板-点外部', CHANGE_COLOR, 'tv_confirm_as_bt_tbe', 'outside', {}),
    ('color-cancel', '颜色面板-点取消', CHANGE_COLOR, 'tv_confirm_as_bt_tbe', 'button',
     {'button': 'tv_cancel_as_bt_tbe'}),
    ('color-confirm', '颜色面板-点确认', CHANGE_COLOR, 'tv_confirm_as_bt_tbe', 'button',
     {'button': 'tv_confirm_as_bt_tbe'}),
]

CLOSERS = {'back': close_back, 'outside': close_outside, 'button': close_button}


def run_case(case, index, screen, settle, gap):
    key, name, opener_desc, marker, how, extra = case
    detail = dump('et_content', forbid=marker)
    mark('%s.%d.open' % (key, index))
    tap(node(detail, desc=opener_desc))
    opened = dump(marker)
    time.sleep(settle)
    record = {'键': key, '场景': name, '轮次': index,
              '弹窗窗口': bounds(window_of(opened, marker))}
    if how == 'audio':
        # 弹窗 → 弹窗：点「录音」行，录音弹窗停在 PREPARED，不产生任何附件。
        mark('%s.%d.close' % (key, index))
        tap(node(opened, rid='tv_record_audio_as_bt'))
        recording = dump('voice_visualizer')
        time.sleep(settle)
        record['录音弹窗窗口'] = bounds(window_of(recording, 'voice_visualizer'))
        mark('%s.%d.close-recording' % (key, index))
        adb('shell', 'input', 'keyevent', '4')
        dump('et_content', forbid='voice_visualizer')
    else:
        mark('%s.%d.close' % (key, index))
        info = CLOSERS[how](tree=opened, rid=marker, screen=screen, **extra)
        if info:
            record.update(info)
        try:
            dump('et_content', forbid=marker, tries=20)
        except RuntimeError:
            # 点外部没命中时不盲目换坐标重试，先记下来再用返回键收场。
            record['关闭未命中'] = True
            adb('shell', 'input', 'keyevent', '4')
            dump('et_content', forbid=marker)
    mark('%s.%d.done' % (key, index))
    time.sleep(gap)
    return record


def back_to_home(tries=4):
    for _ in range(tries):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1.2)
        try:
            return dump('fab_create', tries=6)
        except RuntimeError:
            continue
    raise RuntimeError('没能回到首页')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--title', default='')
    parser.add_argument('--settle', type=float, default=1.5, help='弹窗出现之后静置多久再关')
    parser.add_argument('--gap', type=float, default=2.6, help='两次之间的间隔')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.run)
    os.environ['THING_ANIMATION_SERIAL'] = args.serial
    sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))
    global ui
    import device_ui as ui  # noqa: E402  仅复用 adb 路径与授权序列号校验

    out = REPO / 'tmp/particle-perf/weak-devices' / args.serial / args.run
    out.mkdir(parents=True, exist_ok=True)
    screen = screen_size()
    mark('%s.begin.screen=%dx%d' % (args.run, *screen))
    home = launch()
    titles = open_detail(home, args.title or None)
    print(json.dumps({'screen': screen, 'detail': titles}, ensure_ascii=False), flush=True)
    cases = list(CASES)
    if not has_permission('RECORD_AUDIO'):
        cases = [c for c in cases if c[0] != 'attach-audio']
        print('跳过 attach-audio：设备未授予录音权限，点该行只会弹系统授权框', flush=True)
    records = []
    try:
        for index in range(args.rounds):
            for case in cases:
                record = run_case(case, index, screen, args.settle, args.gap)
                records.append(record)
                print(json.dumps(record, ensure_ascii=False), flush=True)
    finally:
        mark('%s.end' % args.run)
        (out / 'cases.json').write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                        encoding='utf-8')
        back_to_home()
        soft('shell', 'rm', '-f', UI_DUMP)
    print('写入', out / 'cases.json', flush=True)


if __name__ == '__main__':
    main()
