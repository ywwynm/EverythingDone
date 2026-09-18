"""只读取证：把「音频海浪动画设置」的调参对话框打开，截一张图。

用来量它那枚调色盘按钮在浅色模式下的真实像素——第 8 批第 1 条要求「照搬同一份」，
而「同一份」是什么颜色必须量出来，不能只看布局属性（`android:tint` 在
`AppCompatImageView` 上未必真的生效）。不改任何设置，截完按返回退出。
"""
import argparse
import os
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))

import device_particle_perf as d  # noqa: E402
import device_particle_tuning as t  # noqa: E402
import device_ui as ui  # noqa: E402

TITLE_KEYS = ('音频海浪动画设置', 'Ocean wave animation settings')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', default='b8-ui')
    parser.add_argument('--tag', default='light')
    parser.add_argument('--serial', default=os.environ.get('THING_ANIMATION_SERIAL'))
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', args.run)
    if args.serial:
        ui.SERIAL = args.serial
    folder = REPO / 'tmp/particle-perf/per-frame' / ui.SERIAL / args.run
    folder.mkdir(parents=True, exist_ok=True)

    t.open_settings()
    d.tap(t.scroll_to_text(TITLE_KEYS))
    # 调参对话框里有个正在跑的 GL 预览，`uiautomator dump` 等不到 idle；轮询到控件出现为止。
    node = None
    for _ in range(40):
        tree = d.dump()
        for n in tree.iter('node'):
            if (n.get('resource-id') or '').endswith('/iv_fablesol_tuning_palette'):
                node = n
                break
        if node is not None:
            break
        time.sleep(.3)
    assert node is not None, '海浪调参对话框里找不到调色盘按钮'
    time.sleep(1.2)
    path = t.shot(folder, args.tag + '-fablesol-palette')
    print(node.get('bounds'), path)
    t.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.6)
    t.adb('shell', 'input', 'keyevent', '4')


if __name__ == '__main__':
    main()
