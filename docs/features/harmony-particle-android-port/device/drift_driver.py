"""飘散样式的真机驱动（公共部分）。

只经真实系统输入操作；启动应用一律 `am start`（`monkey` 会打开自动旋转，见 .claude/rules/adb.md）。
截图一律「设备端 screencap + md5 校验 + pull」；dump 每次换随机文件名，避免读到上一轮的残留。

设备：已授权三星 R5CW20BLNKL，以及 2026-10-07 本轮的魅族 392QBFED2226B。
无线地址由 DRIFT_SERIAL 传入，并核对真实序列号，避免误用其他在线设备。
"""
import hashlib
import os
import random
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
SERIAL = os.environ.get('DRIFT_SERIAL', 'R5CW20BLNKL')
AUTHORIZED = ('R5CW20BLNKL', '392QBFED2226B')
if SERIAL not in AUTHORIZED:
    identity = subprocess.run([ADB, '-s', SERIAL, 'shell', 'getprop', 'ro.serialno'],
                              capture_output=True, check=True, timeout=10).stdout.decode().strip()
    if identity not in AUTHORIZED:
        raise ValueError('目标设备不在本功能的授权名单内：' + identity)
PACKAGE = 'com.ywwynm.everythingdone'
HOME_ACTIVITY = PACKAGE + '/.activities.ThingsActivity'
REPO = Path(__file__).resolve().parents[4]
OUT = REPO / 'tmp/harmony-particle-android-port'


def adb(*args, check=True):
    result = subprocess.run([ADB, '-s', SERIAL, *map(str, args)], capture_output=True)
    if check and result.returncode:
        raise RuntimeError('ADB 命令失败：' + ' '.join(map(str, args)) + '\n' +
                           result.stderr.decode('utf-8', 'replace') + result.stdout.decode('utf-8', 'replace'))
    return result.stdout


def soft(*args):
    return subprocess.run([ADB, '-s', SERIAL, *map(str, args)], capture_output=True)


def rotation_state():
    auto = adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').decode().strip()
    user = adb('shell', 'settings', 'get', 'system', 'user_rotation').decode().strip()
    return auto, user


def dump(require=None, tries=30, text=None):
    """轮询到目标出现为止；带动画的页面会给出缺一支的陈旧树。"""
    last = None
    for _ in range(tries):
        name = '/sdcard/drift-ui-%08x.xml' % random.getrandbits(32)
        soft('shell', 'uiautomator', 'dump', name)
        raw = soft('shell', 'cat', name).stdout
        soft('shell', 'rm', '-f', name)
        try:
            last = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(.2)
            continue
        if require is None and text is None:
            return last
        if require is not None and find(last, rid=require) is not None:
            return last
        if text is not None and find(last, text=text) is not None:
            return last
        time.sleep(.2)
    raise RuntimeError('UI 没有出现 ' + str(require or text))


def find(tree, rid=None, text=None, desc=None):
    for n in tree.iter('node'):
        if rid is not None and not (n.get('resource-id') or '').endswith('/' + rid):
            continue
        if text is not None:
            value = n.get('text') or ''
            if (value not in text) if isinstance(text, (tuple, list)) else value != text:
                continue
        if desc is not None and (n.get('content-desc') or '') not in desc:
            continue
        return n
    return None


def bounds(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(node):
    x, y = center(node)
    adb('shell', 'input', 'tap', x, y)


def screen_size(tree=None):
    tree = tree if tree is not None else dump()
    width = height = 0
    for n in tree.iter('node'):
        b = n.get('bounds')
        if b:
            x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', b))
            width, height = max(width, x2), max(height, y2)
    return width, height


def shot(folder, name):
    remote = '/sdcard/drift-shot-%08x.png' % random.getrandbits(32)
    target = Path(folder) / (name + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    for _ in range(4):
        soft('shell', 'screencap', '-p', remote)
        digest = soft('shell', 'md5sum', remote).stdout.decode('utf-8', 'replace').strip()
        remote_hash = re.match(r'([0-9a-f]{32})', digest)
        soft('pull', remote, str(target))
        soft('shell', 'rm', '-f', remote)
        if target.is_file() and remote_hash and hashlib.md5(target.read_bytes()).hexdigest() == remote_hash.group(1):
            return target
        time.sleep(.3)
    raise RuntimeError('截图没能校验通过 ' + name)


def dismiss_crash_dialog(tree):
    """崩溃之后再启动，首页会先弹应用自己的反馈对话框；点「取消」，不点「现在反馈」。"""
    if find(tree, text=('现在反馈', 'Feedback now')) is not None:
        cancel = find(tree, text=('取消', 'Cancel'))
        if cancel is not None:
            tap(cancel)
            time.sleep(.8)
            return True
    return False


def launch():
    adb('shell', 'am', 'force-stop', PACKAGE)
    time.sleep(1.0)
    adb('shell', 'am', 'start', '-n', HOME_ACTIVITY)
    for _ in range(3):
        tree = dump()
        if dismiss_crash_dialog(tree):
            continue
        if find(tree, rid='fab_create') is not None:
            return tree
        time.sleep(.6)
    return dump('fab_create')


def scroll_to_text(keys, tries=16):
    width, height = screen_size()
    for _ in range(tries):
        tree = dump()
        node = find(tree, text=keys)
        if node is not None:
            y2 = bounds(node)[3]
            if y2 < height - 120:
                return node
        adb('shell', 'input', 'swipe', width // 2, int(height * .70), width // 2, int(height * .45), 400)
        time.sleep(.9)
    raise RuntimeError('找不到 ' + str(keys))


def open_drawer(tree=None):
    tree = tree if tree is not None else dump('actionbar')
    drawer = find(tree, desc=('Open navigation drawer', '打开导航抽屉', 'Navigate up', '向上导航'))
    if drawer is None:
        toolbar = find(tree, rid='actionbar')
        x1, y1, x2, y2 = bounds(toolbar)
        adb('shell', 'input', 'tap', x1 + (y2 - y1) // 2, (y1 + y2) // 2)
    else:
        tap(drawer)


def drawer_item(keys, tree=None):
    """打开抽屉并点其中一项。文件夹多的时候下面的项要在抽屉范围内往下滚（不能滑到右侧，那是关抽屉）。"""
    open_drawer(tree)
    item = None
    width, height = screen_size()
    for k in range(24):
        tree = dump()
        item = find(tree, text=keys)
        if item is not None and bounds(item)[3] < height - 160:
            break
        item = None
        if k >= 2:
            adb('shell', 'input', 'swipe', width // 3, int(height * .80), width // 3, int(height * .30), 300)
            time.sleep(.7)
        else:
            time.sleep(.3)
    if item is None:
        raise RuntimeError('抽屉里找不到 ' + str(keys))
    tap(item)


def open_tuning_page():
    """抽屉 → 设置 → 粒子动画设置（两个页面都不导出）。"""
    tree = launch()
    drawer_item(('设置', 'Settings'), tree)
    time.sleep(1.2)
    row = scroll_to_text(('粒子动画设置', 'Particle animation settings'))
    tap(row)
    return dump('tv_particle_tuning_style_drift')


def drawer_segment(index):
    """抽屉底部的分段控件：0 正在进行、1 已完成（旗帜）、2 回收站。后两格只有图标、没有文字与描述，
    按「与『正在进行』那一格同一行的可点格，从左到右」定位。"""
    open_drawer()
    width, height = screen_size()
    for k in range(24):
        tree = dump()
        label = None
        for n in tree.iter('node'):
            if n.get('text') == '正在进行' or n.get('text') == 'Underway':
                b = bounds(n)
                if b[0] < width * .6 and b[1] > height * .3:
                    label = n
        if label is not None:
            ly1, ly2 = bounds(label)[1], bounds(label)[3]
            cells = [n for n in tree.iter('node') if n.get('clickable') == 'true'
                     and bounds(n)[1] <= ly1 and bounds(n)[3] >= ly2 and bounds(n)[2] - bounds(n)[0] < width * .8]
            cells.sort(key=lambda n: bounds(n)[0])
            if len(cells) == 3 and bounds(cells[2])[3] < height - 120:
                tap(cells[index])
                return
        adb('shell', 'input', 'swipe', width // 3, int(height * .80), width // 3, int(height * .30), 300)
        time.sleep(.7)
    raise RuntimeError('抽屉里找不到分段控件')
