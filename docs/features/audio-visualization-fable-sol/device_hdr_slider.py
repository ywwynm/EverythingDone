"""HDR 强度滑杆跨过「关闭」那一档时，预览有没有空帧。

路径：首页 → 抽屉 → 设置 → 「音频海浪动画设置」→ 调参 Dialog（HDR 滑杆在最上面一组）。
`SettingsActivity` 是 exported=false，`am start -n` 起不来，必须走抽屉（见 .claude/rules/adb.md）。

判据是录屏逐帧：预览区（调参 Dialog 顶部那块水面）有没有整块变黑 / 变空的帧。
screenrecord 是变帧率，帧号换算成秒必须逐帧读时间戳。

用法（Bash，先 `export MSYS_NO_PATHCONV=1` 与 `PYTHONUTF8=1`）：

    python device_hdr_slider.py --serial 9018f404 --run oppo1
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'docs/features/particle-animation-performance'))

# 用户的设备可能是英文系统（OPPO 平板就是），文案匹配一律同时接受中英文。
SETTINGS_ROW = ('音频海浪动画设置', 'Ocean wave animation settings')
SETTINGS_LABEL = ('设置', 'Settings')
PREFS = 'shared_prefs/fablesol_tuning.xml'
paths = None


def adb(*args):
    return subprocess.run([paths.ui.ADB, '-s', paths.ui.SERIAL, *map(str, args)],
                          capture_output=True, check=True).stdout


def read_strength():
    out = subprocess.run([paths.ui.ADB, '-s', paths.ui.SERIAL, 'shell',
                          'run-as', paths.ui.PACKAGE, 'cat', PREFS],
                         capture_output=True).stdout.decode(errors='replace')
    m = re.search(r'name="hdr_strength" value="([0-9.]+)"', out)
    return m.group(1) if m else None


def restore_strength(value):
    """验证结束把用户原来的档位改回去；改之前必须 force-stop（见 .claude/rules/adb.md）。"""
    if value is None:
        return
    adb('shell', 'am', 'force-stop', paths.ui.PACKAGE)
    time.sleep(0.9)
    # run-as 要把整条命令交给设备端的 sh 解析；拆成 argv 传过去它不认（实测退出码 1）。
    expr = (r's|<float name=\"hdr_strength\" value=\"[0-9.]*\" />'
            r'|<float name=\"hdr_strength\" value=\"{v}\" />|').format(v=value)
    adb('shell', "run-as %s sed -i '%s' %s" % (paths.ui.PACKAGE, expr, PREFS))
    back = read_strength()
    print('已把 hdr_strength 改回 %s，读回 %s' % (value, back), flush=True)
    assert back == value, '恢复失败'


def dump_text(texts, scroll=False, tries=24):
    """轮询到候选文案之一出现；[scroll] 允许小步向下滚（UI 自动化滚动要小步）。"""
    import xml.etree.ElementTree as ET
    for i in range(tries):
        paths.soft('shell', 'rm', '-f', paths.UI_DUMP)
        paths.soft('shell', 'uiautomator', 'dump', paths.UI_DUMP)
        got = paths.soft('shell', 'cat', paths.UI_DUMP)
        if not got.returncode:
            try:
                tree = ET.fromstring(got.stdout)
            except ET.ParseError:
                tree = None
            if tree is not None and any(n.get('text') in texts for n in tree.iter('node')):
                return tree
        if scroll and i % 2 == 1:
            w, h = paths.screen_size()
            adb('shell', 'input', 'swipe', w // 2, int(h * .62), w // 2, int(h * .45), 300)
        time.sleep(.25)
    raise RuntimeError('没等到文案 %s' % (texts,))


def open_tuning_dialog():
    """抽屉 → 设置 → 滚到那一行 → 点开。每一步都重新 dump，不沿用坐标。"""
    snap = paths.launch()
    # 抽屉：actionbar 左上角那个按钮没有 resource-id，只能按 content-desc 找。
    # 抽屉已经开着时它的 content-desc 是 Close…，这时不要再点一次（会关掉）。
    if not any(n.get('text') in SETTINGS_LABEL for n in snap.iter('node')):
        opener = next((n for n in snap.iter('node')
                       if 'Open Navigation' in (n.get('content-desc') or '')
                       or '打开导航' in (n.get('content-desc') or '')), None)
        if opener is None:
            raise RuntimeError('首页没有找到打开抽屉的按钮')
        paths.tap(opener)
    tree = dump_text(SETTINGS_LABEL)
    hit = next(n for n in tree.iter('node') if n.get('text') in SETTINGS_LABEL)
    paths.tap(hit)
    time.sleep(1.5)
    # 设置页：小步滚到「音频海浪动画设置」
    tree = dump_text(SETTINGS_ROW, scroll=True)
    row = next(n for n in tree.iter('node') if n.get('text') in SETTINGS_ROW)
    paths.tap(row)
    return paths.dump('sb_fablesol_tuning_hdr')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--record', type=int, default=11,
                        help='0 = 不录屏（ColorOS 不允许 shell 写文件，只能看日志）')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.run)
    os.environ['THING_ANIMATION_SERIAL'] = args.serial
    global paths
    import device_dismiss_paths as paths  # noqa: E402
    sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))
    import device_ui  # noqa: E402
    paths.ui = device_ui

    out = REPO / 'tmp/fablesol-hdr-slider' / args.serial / args.run
    out.mkdir(parents=True, exist_ok=True)
    before = read_strength()
    print('测试前 hdr_strength =', before, flush=True)
    tree = open_tuning_dialog()
    bar = paths.node(tree, rid='sb_fablesol_tuning_hdr')
    x1, y1, x2, y2 = paths.bounds(bar)
    preview = None
    for n in tree.iter('node'):
        rid = n.get('resource-id', '')
        if rid.endswith('/fl_fablesol_tuning_preview') or rid.endswith('/v_fablesol_tuning_preview'):
            preview = paths.bounds(n)
    y = (y1 + y2) // 2
    print(json.dumps({'滑杆': [x1, y1, x2, y2], '预览': preview}, ensure_ascii=False), flush=True)

    remote = '/sdcard/hdr-slider.mp4'
    proc = None
    if args.record:
        adb('shell', 'rm', '-f', remote)
        proc = subprocess.Popen(
            [paths.ui.ADB, '-s', paths.ui.SERIAL, 'shell', 'screenrecord',
             '--bit-rate', '20000000', '--time-limit', str(args.record), remote],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    # 三下覆盖两个方向：不管起始档位在哪，「开→关」与「关→开」各至少发生一次。
    for label, tx in (('to-zero', x1 + 6), ('to-max', x2 - 6), ('to-zero-2', x1 + 6)):
        paths.mark('%s.%s' % (args.run, label))
        adb('shell', 'input', 'tap', tx, y)
        time.sleep(2.2)
    paths.mark('%s.done' % args.run)
    dest = out / 'hdr-slider.mp4'
    if proc is None:
        (out / 'geometry.json').write_text(
            json.dumps({'滑杆': [x1, y1, x2, y2], '预览': preview}, ensure_ascii=False),
            encoding='utf-8')
        for _ in range(4):
            adb('shell', 'input', 'keyevent', '4')
            time.sleep(0.8)
        restore_strength(before)
        print('未录屏，判据看日志里的「换格式遮盖」两行', flush=True)
        return
    proc.wait(timeout=60)

    import hashlib
    for _ in range(3):
        digest = adb('shell', 'md5sum', remote).decode(errors='replace').strip().split()[0]
        adb('pull', remote, str(dest))
        if hashlib.md5(dest.read_bytes()).hexdigest() == digest:
            break
    else:
        raise RuntimeError('录屏三次都没校验通过')
    adb('shell', 'rm', '-f', remote)
    (out / 'geometry.json').write_text(
        json.dumps({'滑杆': [x1, y1, x2, y2], '预览': preview}, ensure_ascii=False), encoding='utf-8')
    # 收尾：关掉调参 Dialog 与设置页，别把设备停在设置里。
    for _ in range(4):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(0.8)
    restore_strength(before)
    print('写入', dest, flush=True)


if __name__ == '__main__':
    main()
