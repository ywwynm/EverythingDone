"""呈现链核对：粒子层在合成器里是 Device 还是 Client、像素格式与 dataspace，以及
弹窗 + 宿主 + 粒子三层叠加时整屏有没有退化成 GPU 合成；另记面板刷新率与本应用的投票。

`dumpsys SurfaceFlinger` 输出很长，全部落到设备上再 `adb pull`（PowerShell 的 `>` 会转
UTF-16）。粒子层只在动画期间存在，所以用一次「按住不放」的左滑把它留在屏幕上几秒。
"""
import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
REMOTE = '/data/local/tmp/per-frame-sf-%d.txt'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--title', required=True)
    parser.add_argument('--hold', type=int, default=3500, help='按住不放的毫秒数')
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--mode', choices=['swipe', 'dialog'], default='swipe',
                        help='dialog：附件弹窗 → 点录音行，两个弹窗窗口 + 两层粒子 + 暗层同时在')
    args = parser.parse_args()
    os.environ['THING_ANIMATION_SERIAL'] = args.serial
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))
    import device_particle_perf as pp
    import device_ui as ui

    out = REPO / 'tmp/particle-perf/per-frame' / args.serial / 'composition'
    out.mkdir(parents=True, exist_ok=True)
    pp.ROOT = out
    ui.ROOT = out

    def adb(*a):
        return ui.adb(*a)

    def soft(*a):
        return subprocess.run([ui.ADB, '-s', ui.SERIAL, *map(str, a)], capture_output=True)

    # 基线：没有任何粒子层时的整屏合成方式。
    pp.launch()
    if args.mode == 'swipe':
        adb('shell', 'sh', '-c', 'dumpsys SurfaceFlinger > ' + REMOTE % 0)
        adb('pull', REMOTE % 0, out / 'idle.txt')

    if args.mode == 'dialog':
        # 附件弹窗 → 点「录音」长条行：弹窗 A 的快照粒子层、弹窗 B 的凝聚粒子层、
        # 暗层与两个弹窗窗口在同一段时间里叠在宿主窗口之上，层数最多。
        pp.open_detail(args.title)
        tree = pp.dump('et_content')
        pp.tap(pp.node(tree, desc=pp.ADD_ATTACHMENT))
        tree = pp.dump('tv_record_audio_as_bt')
        time.sleep(.8)
        pp.tap(pp.node(tree, rid='tv_record_audio_as_bt'))
        for i in range(1, args.samples + 1):
            soft('shell', 'sh', '-c', 'dumpsys SurfaceFlinger > ' + REMOTE % i)
        pp.dump('voice_visualizer')
        for i in range(1, args.samples + 1):
            adb('pull', REMOTE % i, out / ('dialog-%d.txt' % i))
            soft('shell', 'rm', '-f', REMOTE % i)
        adb('shell', 'input', 'keyevent', '4')
        pp.absent('voice_visualizer')
        adb('shell', 'input', 'keyevent', '4')
        pp.dump('fab_create')
        print('写入', out)
        return

    driver = REPO / 'tmp/thing-animation-device/input-driver/input.jar'
    adb('push', driver, '/data/local/tmp/particle-perf-input.jar')
    tree = pp.dump('fab_create')
    target = next(n for n in tree.iter('node') if n.get('text') == args.title)
    parents = {c: n for n in tree.iter() for c in n}
    while not target.get('resource-id', '').endswith('/cv_thing'):
        target = parents[target]
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
    start, y = x2 - 80, (y1 + y2) // 2
    finish = round(max(12, start - (x2 - x1) * .36))
    before = pp.node(tree, rid='tv_header_subtitle').get('text')

    gesture = subprocess.Popen(
        [ui.ADB, '-s', ui.SERIAL, 'shell', 'CLASSPATH=/data/local/tmp/particle-perf-input.jar',
         'app_process', '/system/bin', 'ParticleGestureInput', str(start), str(y), 'UP',
         str(finish), str(y), '1200', str(finish), str(y), str(args.hold)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    for i in range(1, args.samples + 1):
        soft('shell', 'sh', '-c', 'dumpsys SurfaceFlinger > ' + REMOTE % i)
        time.sleep(.35)
    gesture.wait(timeout=30)
    for i in range(1, args.samples + 1):
        adb('pull', REMOTE % i, out / ('swipe-%d.txt' % i))
        soft('shell', 'rm', '-f', REMOTE % i)
    soft('shell', 'rm', '-f', REMOTE % 0)
    tree = pp.dump('fab_create')
    assert any(n.get('text') == args.title for n in tree.iter('node')), '测试记事消失'
    assert pp.node(tree, rid='tv_header_subtitle').get('text') == before, '记事数量变化'
    print('写入', out)


if __name__ == '__main__':
    main()
