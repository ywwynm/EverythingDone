"""第八批（粒子动画设置页第四轮八条）的真机取证驱动。

只经真实系统输入操作，不发业务 Intent、不改任何系统设置、不碰记事数据。
复用 `device_particle_tuning.py` 的截图、滑动与定位辅助（截图一律「设备端 screencap +
md5 校验 + pull」，见 .claude/rules/adb.md）。

一轮按顺序取这几样：

1. 静止一屏（换色图标取色、参数区整体、自动调节行的间距都从这一张上量）；
2. 换一次主题色，再截一张（示例卡片的文字颜色要跟着底色走）；
3. **右滑**过阈值松手，录屏取「播完消失 + 粒子聚回」那一段（第 8 条）；
4. 切到对话框预览，**录屏**取两个图标按钮的布局过渡（第 7 条）；
5. 打开方向弹层截一张，选一个方向再截按钮本身（第 7 条）；
6. 点「稍后」，**录屏**取「涟漪播完 → 粒子接管 → 阴影随粒子散开」那一段（第 4、6 条）。

用法：
    THING_ANIMATION_SERIAL=<序列号> python device_batch8_ui.py --run b8-ui --tag light
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))

import device_particle_perf as d  # noqa: E402
import device_particle_tuning as t  # noqa: E402
import device_ui as ui  # noqa: E402


def poll_rid(rid, tries=40):
    """轮询到这个控件出现为止。

    页面上一直有粒子动画在播，`uiautomator dump` 等不到 idle，可能返回**缺了一支的**树
    （2026-09-18 在三星上实测：选项卡那一行整条不在树里）。一次找不到不等于控件不存在，
    见 .claude/rules/adb.md。
    """
    for _ in range(tries):
        for n in d.dump().iter('node'):
            if (n.get('resource-id') or '').endswith('/' + rid):
                return n
        time.sleep(.3)
    raise RuntimeError('页面上找不到 ' + rid)


def record(folder, prefix, seconds, action, settle=1.6, tail=3.0):
    """录屏跑一段动作再逐帧落盘。

    `screenrecord` 是**变帧率**，帧号不能按固定帧率折算成秒，必须逐帧读时间戳
    （见 .claude/rules/adb.md）。`action()` 返回动作真正发生的时刻（相对录制开始）。
    """
    remote = '/sdcard/particle-b8.mp4'
    t.soft('shell', 'rm', '-f', remote)
    rec = subprocess.Popen(
        [ui.ADB, '-s', ui.SERIAL, 'shell', 'screenrecord', '--time-limit', str(seconds),
         '--bit-rate', '20000000', remote],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.time()
    time.sleep(settle)
    at = action()
    marked = (at if at is not None else time.time()) - started
    time.sleep(tail)
    rec.wait(timeout=40)
    local = Path(folder) / (prefix + '.mp4')
    t.soft('pull', remote, str(local))
    t.soft('shell', 'rm', '-f', remote)
    frames = []
    if local.is_file():
        frames = t.extract_frames(local, folder, prefix + '-', marked, before=0.5, after=2.8)
    return {'video': str(local), 'mark_s': round(marked, 2), 'frames': frames}


def right_swipe_and_return(folder, prefix, result):
    """右滑过阈值松手：与左滑对称——播完消失，再以粒子从**右侧**聚回来。"""
    x1, y1, x2, y2 = t.card_rect()
    width, _ = t.screen_size()
    cy = (y1 + y2) // 2
    # **两端都要避开系统返回手势区**。右滑是从左边缘往右推，起点贴着卡片左缘（三星上卡片
    # 几乎顶满屏宽，x1 只有几十像素）会被 One UI 判成返回手势，整页被退掉、后面每一步都在
    # 设置页上瞎点（2026-09-18 实测）。左右各留 140 px，与左滑那一条同一个余量。
    start = max(x1 + 24, 140)
    end = min(x2 + 40, width - 140)

    def action():
        job = t.swipe_async(start, cy, end, cy, 3000)
        time.sleep(2.5)
        result[prefix + '-card-gone'] = t.shot(folder, prefix + '-card-gone')
        job.wait(timeout=20)
        return time.time()

    return record(folder, prefix, 13, action, settle=1.6, tail=4.0)


def tap_direction(folder, prefix, cell_rid, result):
    """打开方向弹层，截一张，再点其中一格；按钮上的箭头应当随之改向。"""
    d.tap(poll_rid('iv_particle_tuning_direction'))
    time.sleep(.9)
    result[prefix + '-popup'] = t.shot(folder, prefix + '-popup')
    cell = None
    for _ in range(12):
        tree = d.dump()
        for n in tree.iter('node'):
            if (n.get('resource-id') or '').endswith('/' + cell_rid):
                cell = n
                break
        if cell is not None:
            break
        time.sleep(.25)
    assert cell is not None, '方向弹层里找不到 ' + cell_rid
    d.tap(cell)
    time.sleep(1.2)
    result[prefix + '-button'] = t.shot(folder, prefix + '-button')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', default='b8-ui')
    parser.add_argument('--tag', default='light')
    parser.add_argument('--serial', default=os.environ.get('THING_ANIMATION_SERIAL'))
    parser.add_argument('--skip-swipe', action='store_true',
                        help='跳过右滑那一段（只想补截图时用）')
    parser.add_argument('--swipe-only', action='store_true',
                        help='只跑右滑那一段')
    parser.add_argument('--dir-effect', default='',
                        help='逗号分隔的方向格 id 后缀（如 b,lt）：每个方向各录一段「点稍后 → '
                             '消散」，用来证明选的方向真的改变了粒子的去向')
    parser.add_argument('--bounds', action='store_true',
                        help='只读参数区几个控件的 bounds，换算成 dp。'
                             '第 2、3 条要的是尺寸与间距的实测值，截图上按墨迹量会被字形'
                             '的上下留白带偏，按控件 bounds 量才是界面真正的取值')
    parser.add_argument('--colors', type=int, default=0,
                        help='只做一件事：连点换色按钮若干次，每次截一张。'
                             '示例卡片的文字颜色随底色自适应，要证明它就得碰到一种**浅**底色，'
                             '而颜色是从用户自己的记事里随机取的，只能多取几次')
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', args.run)
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,40}', args.tag)
    if args.serial:
        ui.SERIAL = args.serial
    p = args.tag
    folder = REPO / 'tmp/particle-perf/per-frame' / ui.SERIAL / args.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'serial': ui.SERIAL, 'run': args.run, 'tag': args.tag}

    t.open_page()
    result['labels_open'] = t.read_labels(d.dump())

    if args.dir_effect:
        d.tap(poll_rid('tv_particle_tuning_mode_dialog'))
        time.sleep(8.0)
        for cell in args.dir_effect.split(','):
            tap_direction(folder, '%s-eff-%s' % (p, cell), 'iv_particle_direction_' + cell, result)
            time.sleep(8.0)

            def press():
                d.tap(t.dialog_button('tv_particle_tuning_dialog_cancel'))
                return time.time()

            result['%s-eff-%s-replay' % (p, cell)] = record(
                folder, '%s-eff-%s' % (p, cell), 10, press, settle=1.4, tail=4.0)
            time.sleep(4.0)
        t.adb('shell', 'input', 'keyevent', '4')
        (folder / ('batch8-direction-%s.json' % args.tag)).write_text(
            json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
        return

    if args.bounds:
        density = float(t.soft('shell', 'wm', 'density').stdout
                        .decode('utf-8', 'replace').split(':')[-1].strip()) / 160.0
        result['density'] = density
        boxes = {}
        for rid in ('tv_particle_tuning_color_label', 'tv_particle_tuning_color_standard',
                    'tv_particle_tuning_color_high', 'tv_particle_tuning_color_detail',
                    'tv_particle_tuning_auto_label', 'll_particle_tuning_auto_row',
                    'tv_particle_tuning_auto_detail', 'cb_particle_tuning_auto'):
            tree, _ = t.find_rid(rid)
            x1, y1, x2, y2 = t.bar_bounds(tree, rid)
            boxes[rid] = {'px': [x1, y1, x2, y2],
                          'h_dp': round((y2 - y1) / density, 1),
                          'w_dp': round((x2 - x1) / density, 1)}
        gap = boxes['tv_particle_tuning_auto_detail']['px'][1] - \
            boxes['ll_particle_tuning_auto_row']['px'][3]
        result['boxes'] = boxes
        result['auto_row_to_detail_dp'] = round(gap / density, 2)
        (folder / ('batch8-bounds-%s.json' % args.tag)).write_text(
            json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        t.adb('shell', 'input', 'keyevent', '4')
        print(json.dumps(result, ensure_ascii=False))
        return

    if args.colors > 0:
        shots = []
        for i in range(args.colors):
            d.tap(poll_rid('iv_particle_tuning_palette'))
            time.sleep(1.1)
            shots.append(t.shot(folder, '%s-color-%02d' % (p, i)))
        result['color_shots'] = shots
        t.adb('shell', 'input', 'keyevent', '4')
        (folder / ('batch8-colors-%s.json' % args.tag)).write_text(
            json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
        return

    # ① 静止一屏：换色图标的像素、参数区整体、自动调节行的间距都从它上面量。
    result[p + '-card-idle'] = t.shot(folder, p + '-card-idle')

    # ② 换一种主题色：示例卡片的标题与正文要跟着底色改颜色（第 5 条）。
    d.tap(poll_rid('iv_particle_tuning_palette'))
    time.sleep(1.2)
    result[p + '-card-color'] = t.shot(folder, p + '-card-color')
    d.tap(poll_rid('iv_particle_tuning_palette'))
    time.sleep(1.2)
    result[p + '-card-color-2'] = t.shot(folder, p + '-card-color-2')

    # ③ 右滑过阈值（第 8 条）。
    if not args.skip_swipe:
        result[p + '-right-swipe'] = right_swipe_and_return(folder, p + '-right', result)
        time.sleep(1.5)
        result['labels_after_right'] = t.read_labels(d.dump())
    if args.swipe_only:
        t.adb('shell', 'input', 'keyevent', '4')
        (folder / ('batch8-swipe-%s.json' % args.tag)).write_text(
            json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
        return

    # ④ 切到对话框预览：两个图标按钮的布局过渡要有动画（第 7 条）。
    def switch():
        d.tap(poll_rid('tv_particle_tuning_mode_dialog'))
        return time.time()

    result[p + '-tab-switch'] = record(folder, p + '-tab', 9, switch, settle=1.4, tail=2.6)
    time.sleep(7.0)
    result[p + '-dialog-idle'] = t.shot(folder, p + '-dialog-idle')

    # ⑤ 方向弹层：默认左上，改成正下再看按钮上的箭头（第 7 条）。
    tap_direction(folder, p + '-dir-down', 'iv_particle_direction_b', result)
    time.sleep(7.0)
    result[p + '-dir-down-dialog'] = t.shot(folder, p + '-dir-down-dialog')
    # 改回默认的左上，第 6 条那一段在默认方向下取证。
    tap_direction(folder, p + '-dir-lt', 'iv_particle_direction_lt', result)
    time.sleep(7.0)

    # ⑥ 点「稍后」：涟漪要先播完，随后粒子接管，阴影跟着粒子一起散（第 4、6 条）。
    def press():
        node = t.dialog_button('tv_particle_tuning_dialog_cancel')
        d.tap(node)
        return time.time()

    result[p + '-replay'] = record(folder, p + '-replay', 10, press, settle=1.4, tail=4.0)
    time.sleep(3.0)
    result[p + '-dialog-after'] = t.shot(folder, p + '-dialog-after')
    result['labels_end'] = t.read_labels(d.dump())

    t.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.8)
    (folder / ('batch8-log-%s.json' % args.tag)).write_text(
        json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
