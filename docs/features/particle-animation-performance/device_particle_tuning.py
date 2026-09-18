"""粒子动画设置页的真机验证驱动（第四批建立，第五批改造后更新，第六、七批再次更新）。

只经真实系统输入操作：抽屉 → 设置 → 「粒子动画设置」→ 两种预览各截若干张 → 滑过阈值松手并
录屏取「播完消失 + 倒放回来」那一段 → 换一次主题色 → 切一次颜色精度 → 开关一次性能自动调节 →
拖两根滑杆 → 恢复默认。不发业务 Intent、不改任何系统设置、不碰记事数据。

截图一律「设备端 screencap + md5 校验 + pull」：`adb pull` 会出中段损坏的残图，
只校验文件头发现不了（见 .claude/rules/adb.md）。倒放只有几百毫秒，screencap 本身要一秒上下，
所以那一段改用 `screenrecord` + 逐帧时间戳取帧。

用法：
    THING_ANIMATION_SERIAL=<序列号> python device_particle_tuning.py --run b6-ui
    THING_ANIMATION_SERIAL=<序列号> python device_particle_tuning.py --run b6-ui \
        --tag dark --shots-only          # 只走截图那几步（深色 / 横屏各跑一次）
"""
import argparse
import hashlib
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
import device_ui as ui  # noqa: E402

TITLE_KEYS = ('粒子动画设置', 'Particle animation settings')


def adb(*args):
    return ui.adb(*args)


def soft(*args):
    return subprocess.run([ui.ADB, '-s', ui.SERIAL, *map(str, args)], capture_output=True)


def screen_size():
    """从 uiautomator 的根节点取，不用 `wm size`。

    华为平板报 `Physical 2880x1920` + `Override 1920x2880`，而设备此刻可能是**横屏**：
    按 override 那一组算出来的 y 会落到屏幕外，滑动等于没发生（2026-09-17 实际踩到）。
    dump 出来的 bounds 一定是当前朝向下的真实坐标。
    """
    tree = d.dump()
    width = height = 0
    for n in tree.iter('node'):
        b = n.get('bounds')
        if not b:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', b))
        width = max(width, x2)
        height = max(height, y2)
    assert width > 0 and height > 0, '取不到屏幕尺寸'
    return width, height


def shot(folder, name):
    """设备端截图，按内容哈希校验后再落盘。"""
    remote = '/sdcard/particle-tuning-shot.png'
    target = Path(folder) / (name + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    for _ in range(4):
        soft('shell', 'rm', '-f', remote)
        soft('shell', 'screencap', '-p', remote)
        digest = soft('shell', 'md5sum', remote).stdout.decode('utf-8', 'replace').strip()
        remote_hash = re.match(r'([0-9a-f]{32})', digest)
        soft('pull', remote, str(target))
        if not target.is_file():
            continue
        local_hash = hashlib.md5(target.read_bytes()).hexdigest()
        if remote_hash is None or local_hash == remote_hash.group(1):
            soft('shell', 'rm', '-f', remote)
            return str(target)
        time.sleep(.3)
    raise RuntimeError('截图没能校验通过 ' + name)


def swipe_async(x1, y1, x2, y2, ms):
    """把滑动交给后台进程，好在动画进行中截图（截图本身要 1 秒上下）。"""
    return subprocess.Popen(
        [ui.ADB, '-s', ui.SERIAL, 'shell', 'input', 'swipe',
         str(x1), str(y1), str(x2), str(y2), str(ms)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def text_node(tree, keys):
    for n in tree.iter('node'):
        if n.get('text') in keys:
            return n
    return None


def scroll_to_text(keys, tries=14):
    """设置页很长，按文案滚动到目标行；dump 只输出可见节点。"""
    width, height = screen_size()
    for _ in range(tries):
        tree = d.dump()
        found = text_node(tree, keys)
        if found is not None:
            return found
        adb('shell', 'input', 'swipe', width // 2, int(height * .78),
            width // 2, int(height * .35), 320)
        time.sleep(.45)
    raise RuntimeError('设置页里找不到 ' + str(keys))


def open_settings():
    d.launch()
    tree = d.dump('fab_create')
    # 抽屉按钮在 actionbar 上，按 content-desc 找（中英文都可能）。
    drawer = None
    for n in tree.iter('node'):
        desc = n.get('content-desc') or ''
        if desc in ('Open navigation drawer', '打开导航抽屉', 'Navigate up', '向上导航'):
            drawer = n
            break
    if drawer is None:
        width, height = screen_size()
        adb('shell', 'input', 'tap', int(width * .07), int(height * .075))
    else:
        d.tap(drawer)
    time.sleep(.8)
    item = scroll_to_text(('设置', 'Settings'), tries=4)
    d.tap(item)
    time.sleep(1.2)


def open_page():
    """抽屉 → 设置 → 粒子动画设置。"""
    d.observer(False)
    d.probe(None)
    open_settings()
    row = scroll_to_text(TITLE_KEYS)
    d.tap(row)
    d.dump('sb_particle_tuning_cell')
    time.sleep(.8)


def bar_bounds(tree, rid):
    n = d.node(tree, rid=rid)
    return tuple(map(int, re.findall(r'-?\d+', n.get('bounds'))))


def scroll_params(fraction=.30):
    """参数区是 ScrollView，颜色精度与性能自动调节在下半截，要先滚下去。"""
    width, height = screen_size()
    adb('shell', 'input', 'swipe', width // 2, int(height * .88),
        width // 2, int(height * (.88 - fraction)), 320)
    time.sleep(.5)


def scroll_params_top(times=6):
    """把参数区滚回顶部。

    `find_rid` 只会往下滚，前一次为了找底部的控件滚下去之后，上面那两根滑杆就落到了屏幕外，
    再找它们会一路滚到底也找不到（2026-09-18 在三星上实际发生）。每次定位之前先回顶部。
    """
    width, height = screen_size()
    for _ in range(times):
        adb('shell', 'input', 'swipe', width // 2, int(height * .62),
            width // 2, int(height * .92), 300)
        time.sleep(.3)


def find_rid(rid, scrolls=6):
    """在参数区里找一个控件，找不到、或者它被屏幕底边截断了，就往下滚一点再找。

    **被截断的节点一定要滚出来再点**：uiautomator 把 bounds 裁到屏幕内，控件中心因此落进
    底部手势区，`input tap` 打到系统手势上，应用什么也收不到——症状是「点了没反应」
    （2026-09-18 实际发生：「恢复默认」在最底下，第一轮整段没生效）。
    """
    _, height = screen_size()
    scroll_params_top()
    for _ in range(scrolls):
        tree = d.dump()
        node = None
        for n in tree.iter('node'):
            if (n.get('resource-id') or '').endswith('/' + rid):
                node = n
                break
        if node is not None:
            bottom = int(re.findall(r'-?\d+', node.get('bounds'))[3])
            if bottom < height - 8:
                return tree, node
        scroll_params()
    raise RuntimeError('页面上找不到 ' + rid)


def drag_bar(rid, fraction):
    """把滑杆拖到某一比例的位置；按控件自己的 bounds 算，不估坐标。"""
    tree, _ = find_rid(rid)
    x1, y1, x2, y2 = bar_bounds(tree, rid)
    y = (y1 + y2) // 2
    start = x1 + int((x2 - x1) * .04)
    end = x1 + int((x2 - x1) * fraction)
    adb('shell', 'input', 'swipe', start, y, end, y, 420)
    time.sleep(.6)


def read_labels(tree):
    out = {}
    for n in tree.iter('node'):
        rid = n.get('resource-id', '')
        if rid.endswith('/tv_particle_tuning_cell_value'):
            out['cell'] = n.get('text')
        elif rid.endswith('/tv_particle_tuning_flow_value'):
            out['flow'] = n.get('text')
        elif rid.endswith('/tv_particle_tuning_stats'):
            out['stats'] = n.get('text')
        elif rid.endswith('/tv_particle_tuning_hint'):
            out['hint'] = n.get('text')
        elif rid.endswith('/tv_particle_tuning_card_title'):
            out['card'] = n.get('text')
        elif rid.endswith('/tv_particle_tuning_dialog_title'):
            out['dialog'] = n.get('text')
        elif rid.endswith('/cb_particle_tuning_auto'):
            out['auto'] = n.get('checked')
    return out


def card_rect():
    tree, _ = find_rid('cv_particle_tuning_card')
    return bar_bounds(tree, 'cv_particle_tuning_card')


def shot_during_swipe(folder, name):
    """按**示例卡自己的 bounds**起手，不按预览区的：卡片最宽 640 dp 且居中。"""
    x1, y1, x2, y2 = card_rect()
    cy = (y1 + y2) // 2
    start = x2 - int((x2 - x1) * .12)
    # 3000 ms 走 280 px：太快会被 ItemTouchHelper 当成甩动，太慢会被当成长按
    # （见 .claude/rules/adb.md）。本页已经判不成完成，这里只求停在中间态。
    job = swipe_async(start, cy, max(x1 + 20, start - 280), cy, 3000)
    time.sleep(1.5)
    path = shot(folder, name)
    job.wait(timeout=20)
    return path


def full_swipe_and_return(folder, prefix, result):
    """滑过阈值松手：卡片把剩下那一段播完、彻底消失，随后以粒子沿反向轨迹回来。

    这两段加起来只有一秒多，而一次 `screencap` 要一秒上下——只能录屏再按时间戳取帧
    （`screenrecord` 是变帧率，帧号换算成秒必须逐帧读时间戳，见 .claude/rules/adb.md）。
    """
    x1, y1, x2, y2 = card_rect()
    width, _ = screen_size()
    cy = (y1 + y2) // 2
    # **起点必须离右边缘足够远**：卡片最宽 640 dp，在三星上几乎顶到屏幕右缘，从 `x2 - 24`
    # 起手正好落进系统返回手势区，整页会被直接退掉（2026-09-18 实际发生）。留出 140 px。
    start = min(x2 - 24, width - 140)
    end = max(40, x1 - 40)
    remote = '/sdcard/particle-return.mp4'
    soft('shell', 'rm', '-f', remote)
    rec = subprocess.Popen(
        [ui.ADB, '-s', ui.SERIAL, 'shell', 'screenrecord', '--time-limit', '13',
         '--bit-rate', '20000000', remote],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    recording_started = time.time()
    time.sleep(1.6)
    job = swipe_async(start, cy, end, cy, 3000)
    # 手指到最左端、卡片应当已经彻底消散：这一张在松手之前拍。
    time.sleep(2.5)
    result[prefix + 'shot_card_gone'] = shot(folder, prefix + 'card-gone')
    job.wait(timeout=20)
    released = time.time() - recording_started
    time.sleep(4.0)
    rec.wait(timeout=30)
    local = Path(folder) / (prefix + 'return.mp4')
    soft('pull', remote, str(local))
    soft('shell', 'rm', '-f', remote)
    result[prefix + 'return_video'] = str(local)
    result[prefix + 'release_offset_s'] = round(released, 2)
    if local.is_file():
        result[prefix + 'return_frames'] = extract_frames(local, folder, prefix, released)
    return result


def extract_frames(video, folder, prefix, released, before=0.6, after=2.6):
    """按逐帧时间戳取松手前后那一段的帧；`screenrecord` 变帧率，不能按帧号折算。"""
    probe = subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'frame=best_effort_timestamp_time', '-of', 'csv=p=0', str(video)],
        capture_output=True, text=True)
    stamps = [float(v) for v in probe.stdout.split() if v.replace('.', '', 1).isdigit()]
    if not stamps:
        return []
    out = Path(folder) / (prefix + 'return-frames')
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ['ffmpeg', '-y', '-v', 'error', '-i', str(video), '-vsync', '0',
         str(out / 'frame-%04d.png')], capture_output=True)
    files = sorted(out.glob('frame-*.png'))
    if len(files) != len(stamps):
        stamps = stamps[:len(files)]
    # 录屏比手势晚起来一点，取一个宽一些的窗口，帧上写明各自的时刻。
    # 松手之后先播完消失（约 0.7 s），再倒放回来（约 0.6 s），窗口要盖住两段。
    window = [(s, f) for s, f in zip(stamps, files)
              if released - before <= s <= released + after]
    kept = []
    if window:
        step = max(1, len(window) // 14)
        for s, f in window[::step][:14]:
            name = out / ('t%06d.png' % int(round(s * 1000)))
            f.replace(name)
            kept.append(str(name))
    for f in out.glob('frame-*.png'):
        f.unlink()
    return kept


def dialog_button(rid, tries=40):
    """示例对话框里的「稍后 / 继续」。第七批起重播由这两个按钮触发，预览区本身不再可点。

    **必须轮询**：对话框在出现/消散那一段是不可见的（粒子叠加层在演），`uiautomator dump`
    只输出可见节点，一次没找到不等于按钮不存在（见 .claude/rules/adb.md）。
    """
    for _ in range(tries):
        tree = d.dump()
        for n in tree.iter('node'):
            if (n.get('resource-id') or '').endswith('/' + rid):
                return n
        time.sleep(.3)
    raise RuntimeError('对话框上找不到 ' + rid)


def shot_during_step(folder, name, rid='tv_particle_tuning_dialog_cancel'):
    """点「稍后」重播一轮，随即截图抓消散进行中的那一帧。"""
    d.tap(dialog_button(rid))
    time.sleep(.22)
    return shot(folder, name)


def shot_button_ripple(folder, name, rid='tv_particle_tuning_dialog_cancel'):
    """按住不放期间截一张：两个按钮里应当只有被按的那一个在播涟漪（第七批第 6 条）。

    **三星（One UI）上不能跑这一步**：长按 `TextView` 会触发系统的「拖放」功能，
    2026-09-18 实测直接把 `com.samsung.android.onetouch` 的引导页拉到前台，整轮驱动作废。
    那台用 `--skip-ripple-shot` 跳过，涟漪的证据由魅族那一轮提供。
    """
    x1, y1, x2, y2 = tuple(map(int, re.findall(r'-?\d+', dialog_button(rid).get('bounds'))))
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    job = swipe_async(cx, cy, cx, cy, 1800)
    time.sleep(.35)
    path = shot(folder, name)
    job.wait(timeout=20)
    return path


def prefs():
    raw = soft('shell', 'run-as', ui.PACKAGE, 'cat',
               'shared_prefs/particle_tuning.xml').stdout.decode('utf-8', 'replace')
    return [l.strip() for l in raw.splitlines() if 'name=' in l]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--tag', default='', help='截图文件名前缀，用来区分浅色/深色、竖屏/横屏')
    parser.add_argument('--shots-only', action='store_true',
                        help='只走截图那几步，不拖滑杆、不动新设置、不恢复默认')
    parser.add_argument('--skip-ripple-shot', action='store_true',
                        help='跳过「按住按钮截涟漪」那一步；三星上长按会触发系统的拖放引导页')
    parser.add_argument('--serial', default=os.environ.get('THING_ANIMATION_SERIAL'))
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', args.run)
    assert re.fullmatch(r'[a-zA-Z0-9_-]{0,40}', args.tag)
    if args.serial:
        ui.SERIAL = args.serial
    prefix = (args.tag + '-') if args.tag else ''
    folder = REPO / 'tmp/particle-perf/per-frame' / ui.SERIAL / args.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'serial': ui.SERIAL, 'run': args.run, 'tag': args.tag}

    result['prefs_at_start'] = prefs()
    open_page()
    result['opened'] = True
    result['labels_open'] = read_labels(d.dump())

    # ① 记事卡片预览：静止一张，左滑到一半再一张。
    result['shot_card'] = shot(folder, prefix + 'card-idle')
    result['shot_card_swiped'] = shot_during_swipe(folder, prefix + 'card-swiped')
    time.sleep(2.4)
    result['labels_after_card'] = read_labels(d.dump())

    # ② 滑到最左端让卡片彻底消散再松手，录屏取倒放中的帧（第 5 条）。
    full_swipe_and_return(folder, prefix, result)
    time.sleep(1.2)
    result['labels_after_return'] = read_labels(d.dump())

    # ③ 换一种主题色：整页跟着换，换色前后各一张（第 4、6、7 条）。
    result['shot_color_before'] = shot(folder, prefix + 'color-before')
    _, palette = find_rid('iv_particle_tuning_palette')
    d.tap(palette)
    time.sleep(1.0)
    result['shot_color_after'] = shot(folder, prefix + 'color-after')

    # ④ 对话框预览：进页面自动播一轮（出现 → 消散 → 出现）之后静止，点按钮才重播（第 6 条）。
    d.tap(d.node(d.dump(), rid='tv_particle_tuning_mode_dialog'))
    time.sleep(1.8)
    result['shot_dialog'] = shot(folder, prefix + 'dialog')
    # 自动播放约 350 + 2200 + 2200 ms 三步，末尾那次出现播完约在 6 s；等它静止下来。
    time.sleep(6.0)
    result['shot_dialog_idle'] = shot(folder, prefix + 'dialog-idle')
    # 按住截图那一下松手也算一次点击，会重播一轮；一轮约 2.2 s 的间隔加 1.2 s 的动画，等它走完。
    if not args.skip_ripple_shot:
        result['shot_dialog_ripple'] = shot_button_ripple(folder, prefix + 'dialog-ripple')
        time.sleep(5.0)
    result['shot_dialog_playing'] = shot_during_step(folder, prefix + 'dialog-playing')
    time.sleep(5.0)
    result['shot_dialog_after_replay'] = shot(folder, prefix + 'dialog-after-replay')
    result['labels_after_dialog'] = read_labels(d.dump())

    if not args.shots_only:
        # ⑤ 颜色精度：切到「标准」再切回「高精度」，日志里的 accumulation= 行是判据（第 1 条）。
        _, standard = find_rid('tv_particle_tuning_color_standard')
        d.tap(standard)
        time.sleep(3.2)
        result['shot_color_standard'] = shot(folder, prefix + 'color-standard')
        result['prefs_color_standard'] = prefs()
        _, high = find_rid('tv_particle_tuning_color_high')
        d.tap(high)
        time.sleep(3.2)
        result['prefs_color_high'] = prefs()

        # ⑥ 性能自动调节：默认必须是未勾选，开一次再关回去。
        tree, auto = find_rid('cb_particle_tuning_auto')
        result['auto_default_checked'] = auto.get('checked')
        d.tap(auto)
        time.sleep(.8)
        result['shot_auto_on'] = shot(folder, prefix + 'auto-on')
        result['prefs_auto_on'] = prefs()
        _, auto = find_rid('cb_particle_tuning_auto')
        d.tap(auto)
        time.sleep(.8)
        result['prefs_auto_off'] = prefs()

        # ⑦ 拖两根滑杆，看读数、像素换算与统计行是否跟着变。
        result['prefs_before'] = prefs()
        drag_bar('sb_particle_tuning_cell', .62)
        drag_bar('sb_particle_tuning_flow', .35)
        time.sleep(3.0)
        result['labels_after_drag'] = read_labels(d.dump())
        result['shot_after_drag'] = shot(folder, prefix + 'dialog-after-drag')
        result['prefs_after'] = prefs()

        # ⑧ 回到卡片预览再截一张（新参数下的左滑）。
        d.tap(d.node(d.dump(), rid='tv_particle_tuning_mode_card'))
        time.sleep(.8)
        result['shot_card_after_drag'] = shot_during_swipe(folder, prefix + 'card-swiped-after-drag')
        time.sleep(2.4)
        result['labels_after_card_drag'] = read_labels(d.dump())

        # ⑨ 恢复默认，读回设置核对。
        _, reset = find_rid('tv_particle_tuning_reset')
        d.tap(reset)
        time.sleep(1.2)
        result['labels_after_reset'] = read_labels(d.dump())
        result['shot_after_reset'] = shot(folder, prefix + 'after-reset')
        result['prefs_after_reset'] = prefs()

    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.8)
    name = 'tuning-log%s.json' % (('-' + args.tag) if args.tag else '')
    (folder / name).write_text(
        json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
