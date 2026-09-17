"""逐帧成本专项（2026-09-17）的基线矩阵驱动。

只向 Android 输入系统注入真实点击、按键与连续 MotionEvent；不发业务 Intent、不改任何
系统设置、不代为授予权限。产物写进被忽略的 `tmp/particle-perf/per-frame/<serial>/`。

与阶段 0 的 `device_particle_perf.py` 共用观察器控制、UI 轮询与左滑注入；本文件只补
方案第 5 节里阶段 0 没有的那几格（点外部关闭、复杂内容面板、录音弹窗消散、预算未命中），
外加录屏与离屏分段基准的开关。

**必须**在跑之前另起一条 `adb -s <序列号> logcat -v time > 文件`：魅族主缓冲只有 256 KiB。
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
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))

CHANGE_COLOR = ('Adjust color', '调整颜色', '調整顏色')


def load(serial):
    os.environ['THING_ANIMATION_SERIAL'] = serial
    global pp, ui, perf
    import device_particle_perf as pp  # noqa: E402
    import device_ui as ui  # noqa: E402
    import device_performance as perf  # noqa: E402
    root = REPO / 'tmp/particle-perf/per-frame' / serial
    root.mkdir(parents=True, exist_ok=True)
    pp.ROOT = root
    ui.ROOT = root
    perf.ROOT = root / 'runs'
    perf.ROOT.mkdir(parents=True, exist_ok=True)
    return root


def adb(*args):
    return ui.adb(*args)


def soft(*args):
    return subprocess.run([ui.ADB, '-s', ui.SERIAL, *map(str, args)], capture_output=True)


def mark(text):
    assert re.fullmatch(r'[A-Za-z0-9_.=-]+', text), '标记必须是纯 ASCII'
    adb('shell', 'log', '-p', 'i', '-t', 'PerFrameMark', text)


def bounds(n):
    return tuple(map(int, re.findall(r'-?\d+', n.get('bounds'))))


def window_of(tree, rid):
    for window in tree:
        if pp.present(window, rid):
            return window
    raise RuntimeError('没有找到含 %s 的窗口' % rid)


def screen_size():
    out = adb('shell', 'wm', 'size').decode(errors='replace')
    sizes = re.findall(r'(\d+)x(\d+)', out)
    return int(sizes[-1][0]), int(sizes[-1][1])


def outside_point(tree, rid, screen):
    """弹窗之外、且远离它的一点（与 device_dismiss_paths.py 同一套取点规则）。"""
    x1, y1, x2, y2 = bounds(window_of(tree, rid))
    w, h = screen
    if y1 > 420:
        return w // 2, max(160, y1 - 200)
    if y2 < h - 420:
        return w // 2, min(h - 160, y2 + 200)
    if x1 > 240:
        return max(80, x1 - 120), h // 2
    return min(w - 80, x2 + 120), h // 2


def tuning(config):
    path = 'files/particle-perf-tuning.json'
    if config is None:
        soft('shell', 'run-as', ui.PACKAGE, 'rm', '-f', path)
        return
    command = 'cat > %s.tmp && mv %s.tmp %s' % (path, path, path)
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'sh', '-c', "'" + command + "'"],
                   input=json.dumps(config).encode(), capture_output=True, check=True)


# ===== 录屏 =====

REMOTE_VIDEO = '/sdcard/per-frame-cap.mp4'


class Recording:
    """触发之前开起来；`screenrecord` 是变帧率，逐帧时间戳由分析脚本单独读。"""

    def __init__(self, name, folder, seconds):
        self.name = name
        self.path = folder / (name + '.mp4')
        soft('shell', 'rm', '-f', REMOTE_VIDEO)
        self.proc = subprocess.Popen(
            [ui.ADB, '-s', ui.SERIAL, 'shell', 'screenrecord', '--bit-rate', '24000000',
             '--time-limit', str(seconds), REMOTE_VIDEO],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        time.sleep(1.6)  # 录制真正开始之前有一段启动时间

    def finish(self):
        self.proc.wait(timeout=60)
        time.sleep(1.0)
        remote = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'md5sum', REMOTE_VIDEO],
                                capture_output=True).stdout.decode(errors='replace').strip()
        digest = re.match(r'([0-9a-f]{32})', remote)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        for _ in range(3):
            adb('pull', REMOTE_VIDEO, self.path)
            if not digest:
                break
            import hashlib
            local = hashlib.md5(self.path.read_bytes()).hexdigest()
            if local == digest.group(1):
                break
        soft('shell', 'rm', '-f', REMOTE_VIDEO)
        return self.path


# ===== 场景 =====

def open_attachment(detail=None):
    tree = detail or pp.dump('et_content')
    pp.tap(pp.node(tree, desc=pp.ADD_ATTACHMENT))
    return pp.dump('tv_record_audio_as_bt')


def scenario_attach_back(label):
    """工具栏图标 → 附件弹窗出现 → 返回键消散。涟漪画在 Activity 窗口上。"""
    tree = pp.dump('et_content')
    pp.begin(label, 'attach-back')
    pp.tap(pp.node(tree, desc=pp.ADD_ATTACHMENT))
    pp.dump('tv_record_audio_as_bt')
    adb('shell', 'input', 'keyevent', '4')
    pp.absent('tv_record_audio_as_bt')
    return pp.end(label)


def scenario_attach_outside(label, screen):
    """附件弹窗 → 点弹窗外部消散（没有涟漪，起步不等结算）。"""
    tree = pp.dump('et_content')
    pp.begin(label, 'attach-outside')
    pp.tap(pp.node(tree, desc=pp.ADD_ATTACHMENT))
    opened = pp.dump('tv_record_audio_as_bt')
    x, y = outside_point(opened, 'tv_record_audio_as_bt', screen)
    adb('shell', 'input', 'tap', x, y)
    pp.absent('tv_record_audio_as_bt')
    return pp.end(label)


def scenario_color_confirm(label):
    """复杂内容面板（调整颜色）：出现 + 点「确定」消散（有涟漪）。"""
    tree = pp.dump('et_content')
    pp.begin(label, 'color-confirm')
    pp.tap(pp.node(tree, desc=CHANGE_COLOR))
    opened = pp.dump('tv_confirm_as_bt_tbe')
    time.sleep(.8)
    pp.tap(pp.node(opened, rid='tv_confirm_as_bt_tbe'))
    pp.absent('tv_confirm_as_bt_tbe')
    return pp.end(label)


def scenario_attach_audio(label):
    """弹窗 A 消散接弹窗 B 出现：附件弹窗里点「录音」长条行。不真的录音。"""
    tree = open_attachment()
    time.sleep(.8)
    pp.begin(label, 'attach-audio')
    pp.tap(pp.node(tree, rid='tv_record_audio_as_bt'))
    pp.dump('voice_visualizer')
    data = pp.end(label)
    return data


def scenario_audio_close(label):
    """录音弹窗（FableSol 在播、停在 PREPARED）消散：返回键。跑完不产生任何附件。"""
    time.sleep(1.4)
    pp.begin(label, 'audio-close')
    adb('shell', 'input', 'keyevent', '4')
    pp.absent('voice_visualizer')
    return pp.end(label)


MEASURING = True


def scenario_create(label):
    tree = pp.dump('fab_create')
    pp.begin(label, 'create-page')
    pp.tap(pp.node(tree, rid='fab_create'))
    if MEASURING:
        perf.wait_progress(label, 'editorReady')
    else:
        pp.dump('et_title')
    data = pp.end(label)
    adb('shell', 'input', 'keyevent', '4')
    tree = pp.dump()
    if pp.present(tree, 'ib_back'):
        adb('shell', 'input', 'keyevent', '4')
    pp.dump('fab_create')
    return data


def swipe_anchor(title):
    """测试记事那张卡片的按下点、纵坐标与卡片宽度。

    纵坐标取卡片**中心**：高卡与矮卡的中心差得很远，按固定 y 会滑到别的记事上。
    高度一并返回，逐卡型报告里要按它对齐片数与压力网格。
    """
    tree = pp.dump('fab_create')
    before = pp.node(tree, rid='tv_header_subtitle').get('text')
    target = next(n for n in tree.iter('node') if n.get('text') == title)
    parents = {c: n for n in tree.iter() for c in n}
    while not target.get('resource-id', '').endswith('/cv_thing'):
        target = parents[target]
    x1, y1, x2, y2 = bounds(target)
    return x2 - 80, (y1 + y2) // 2, x2 - x1, before, y2 - y1


def swipe_check(title, before):
    tree = pp.dump('fab_create')
    assert any(n.get('text') == title for n in tree.iter('node')), '测试记事消失'
    assert pp.node(tree, rid='tv_header_subtitle').get('text') == before, '记事数量变化'


def inject(start, y, *segments):
    driver = REPO / 'tmp/thing-animation-device/input-driver/input.jar'
    adb('push', driver, '/data/local/tmp/particle-perf-input.jar')
    flat = []
    for x, duration in segments:
        flat += [round(x), y, duration]
    adb('shell', 'CLASSPATH=/data/local/tmp/particle-perf-input.jar', 'app_process',
        '/system/bin', 'ParticleGestureInput', start, y, 'UP', *flat)


def scenario_swipe(label, title, fast):
    start, y, width, before, height = swipe_anchor(title)
    finish = max(12, start - width * .36)
    pp.begin(label, 'swipe-fast' if fast else 'swipe-slow',
             {'cardTitle': title, 'cardHeight': height, 'cardWidth': width})
    hold = 600 if fast else 300
    duration = 180 if fast else 1200
    inject(start, y, (finish, duration), (finish, hold))
    data = pp.end(label)
    swipe_check(title, before)
    return data


# 按住不放来回拖：累计位移（向左为正，单位是卡片宽度）与每段时长。
# 速度按「真人快速来回」定：每段约 2.2–2.6 px/ms，即每拍跨过一档以上的历史采样
# （卡片 1300 px、36 档，一档约 36 px），慢到跨不过一档就什么也测不出来。
# 三条硬约束（见 .claude/rules/adb.md）：
#  1. 任何时刻都不能拖到按下点的**右**边——`ParticleSwipeAnimator.recordTouch` 把
#     `dx > touchSlop` 当成反向手势，会直接取消这次会话；
#  2. 最深处留在 0.38 以内，离 ItemTouchHelper 的 0.5 阈值有余量；
#  3. 中途快没关系，**松手那一下必须慢**：完成判定看的是抬手时的速度与位移。
#     最后一段 600 ms 回到按下点附近再停住 400 ms，速度追踪器读到约 0。
WIGGLE = [(.28, 150), (.06, 130), (.38, 160), (.10, 140), (.36, 150), (.04, 600), (.04, 400)]


def scenario_swipe_wiggle(label, title):
    """按住不放、来回左右滑动：让粒子反复前进 / 后退，量的是「跟不跟手」。"""
    start, y, width, before, height = swipe_anchor(title)
    pp.begin(label, 'swipe-wiggle',
             {'cardTitle': title, 'cardHeight': height, 'cardWidth': width})
    inject(start, y, *[(max(12, start - width * f), ms) for f, ms in WIGGLE])
    data = pp.end(label)
    swipe_check(title, before)
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--title', required=True, help='专用测试记事标题')
    parser.add_argument('--swipe-titles', default='',
                        help='逗号分隔的 `类型=标题`（类型可省，省了按 PROBE-<类型>- 推）；'
                             '左滑三格在每张卡上各跑一遍，留空则只用 --title 那一张。'
                             '卡片高度直接改片数、压力网格与 ItemTouchHelper 的阈值，'
                             '一行字的矮卡量不出真实的左滑。')
    parser.add_argument('--only', default='', help='逗号分隔的场景键，留空跑全部')
    parser.add_argument('--record', action='store_true', help='本轮只录屏，不采样观察器')
    parser.add_argument('--probe', default='', help='ParticlePerfProbe 配置（JSON）')
    parser.add_argument('--segment-bench', action='store_true')
    parser.add_argument('--option-frames', action='store_true',
                        help='第三批任务二：E12 / C7 的受控对照（离屏，只出图与数字）')
    parser.add_argument('--per-frame-bench', action='store_true',
                        help='逐帧成本专项第一批的逐项 A/B（与 --segment-bench 同用）')
    parser.add_argument('--bench-repeats', type=int, default=2)
    parser.add_argument('--tuning', default='',
                        help='整轮生效的 ParticlePerfTuning 覆盖（JSON）；收尾恢复默认')
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', args.run)
    root = load(args.serial)
    folder = root / args.run
    folder.mkdir(parents=True, exist_ok=True)
    screen = screen_size()

    # 整轮的开关覆盖必须在 launch 之前落到 files/，ParticlePerfTuning.attach 只在进程启动时读一次。
    base_tuning = json.loads(args.tuning) if args.tuning else None
    if base_tuning:
        tuning(base_tuning)
        adb('shell', 'am', 'force-stop', ui.PACKAGE)
        time.sleep(1.0)
    else:
        tuning(None)

    global MEASURING
    MEASURING = not args.record
    pp.observer(MEASURING)
    pp.PROBE_RUN = args.run
    if args.probe:
        config = json.loads(args.probe)
        config['run_id'] = args.run
        pp.probe(config)
    else:
        pp.probe(None)
    if args.segment_bench:
        pp.APPEARANCE_FRAMES = False
    bench = args.segment_bench

    original_begin = pp.begin

    def begin(label, kind, extra=None):
        extra = dict(extra or {})
        if bench:
            extra['segmentBench'] = True
            extra['segmentBenchRepeats'] = args.bench_repeats
            if args.per_frame_bench:
                extra['perFrameBench'] = True
        if args.option_frames:
            extra['optionFrames'] = True
        original_begin(label, kind, extra)

    if MEASURING:
        pp.begin = begin
    else:
        # 录屏轮：同样的输入序列，但不开观察器、不采任何数字（阶段 0 的口径：录屏轮不计帧率）。
        pp.begin = lambda *a, **k: None
        pp.end = lambda label: {'overlays': []}

    detail_scenes = {'attach-back', 'attach-outside', 'color-confirm', 'attach-audio', 'audio-close'}
    order = ['swipe-slow', 'swipe-fast', 'swipe-wiggle', 'attach-back', 'attach-outside',
             'color-confirm', 'attach-audio', 'audio-close', 'create-hit', 'create-miss']
    # 保留 --only 里的重复项：`create-hit,create-hit` 用来测「第一次未命中、第二次命中」。
    wanted = [s for s in (args.only.split(',') if args.only else order) if s in order]
    def card_of(spec):
        if '=' in spec:
            kind, title = spec.split('=', 1)
            return kind, title
        return (spec.split('-')[1] if spec.startswith('PROBE-') else 'SHORT'), spec
    swipe_titles = [card_of(t) for t in args.swipe_titles.split(',') if t] or \
        [('SHORT', args.title)]
    log = []
    mark('%s.begin.screen=%dx%d' % (args.run, *screen))
    pp.launch()
    in_detail = False
    for index, scene in enumerate(wanted):
        mark('%s.%s.start' % (args.run, scene.replace('-', '_')))
        seconds = {'swipe-slow': 8, 'swipe-fast': 8, 'swipe-wiggle': 12, 'attach-audio': 10,
                   'audio-close': 8, 'create-hit': 10, 'create-miss': 16}.get(scene, 9)
        need_detail = scene in detail_scenes
        if need_detail and not in_detail:
            pp.open_detail(args.title)
            in_detail = True
        if not need_detail and in_detail:
            adb('shell', 'input', 'keyevent', '4')
            pp.dump('fab_create')
            in_detail = False
        # 录制在**导航完成之后**才开：uiautomator 轮询本身要一两秒，放在前面会把录制窗口吃掉。
        recording = Recording(scene, folder / 'video', seconds) if args.record else None
        label = '%s-%s' % (args.run, scene)
        if wanted.count(scene) > 1:
            label += '-%d' % index
        try:
            if scene in ('swipe-slow', 'swipe-fast', 'swipe-wiggle'):
                # 每种卡型各跑一遍；标签带卡型后缀，分析脚本按它分行。
                data = {'overlays': []}
                for suffix, card in swipe_titles:
                    each = '%s-%s' % (label, suffix)
                    mark('%s.%s.%s' % (args.run, scene.replace('-', '_'), suffix))
                    if scene == 'swipe-wiggle':
                        one = scenario_swipe_wiggle(each, card)
                    else:
                        one = scenario_swipe(each, card, scene == 'swipe-fast')
                    data['overlays'] += one.get('overlays') or []
                    time.sleep(1.6)
            elif scene == 'attach-back':
                data = scenario_attach_back(label)
            elif scene == 'attach-outside':
                data = scenario_attach_outside(label, screen)
            elif scene == 'color-confirm':
                data = scenario_color_confirm(label)
            elif scene == 'attach-audio':
                data = scenario_attach_audio(label)
            elif scene == 'audio-close':
                data = scenario_audio_close(label)
            elif scene == 'create-hit':
                tuning(base_tuning)
                data = scenario_create(label)
            elif scene == 'create-miss':
                # 整轮的开关覆盖要并进来，否则这一格会把本轮的 A/B 配方擦掉。
                tuning(dict(base_tuning or {}, creationPrewarm=False))
                adb('shell', 'am', 'force-stop', ui.PACKAGE)
                time.sleep(1.2)
                pp.launch()
                data = scenario_create(label)
                tuning(base_tuning)
            row = {'scene': scene, 'overlays': len(data.get('overlays') or []),
                   'gpu': pp.pull_probe(label) if args.probe else 0}
        finally:
            if recording:
                row = {'scene': scene, 'video': str(recording.finish())}
        mark('%s.%s.done' % (args.run, scene.replace('-', '_')))
        log.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
        time.sleep(2.2)
    if in_detail:
        adb('shell', 'input', 'keyevent', '4')
        pp.dump('fab_create')
    mark('%s.end' % args.run)
    if args.probe:
        # 探针产物在渲染器关闭时才落盘，动画结束之后还有一小段；收尾再拉一次。
        time.sleep(2.5)
        print('探针文件', pp.pull_probe(), flush=True)
    if bench:
        pull_bench(folder, '%s-%s' % (args.run, wanted[-1]),
                   per_frame=args.per_frame_bench)
    if args.option_frames:
        pull_options(folder, args.run, wanted)
    pp.probe(None)
    if base_tuning:
        tuning(None)
        adb('shell', 'am', 'force-stop', ui.PACKAGE)
    (folder / 'matrix-log.json').write_text(json.dumps(log, ensure_ascii=False, indent=1),
                                            encoding='utf-8')
    print('写入', folder / 'matrix-log.json', flush=True)


def pull_options(folder, run, scenes, seconds=1800):
    """E12 / C7 的受控对照：离屏跑完才落盘，必须轮询等 `options/report.json` 出现。

    只看本轮那几个 run 目录（与 `pull_bench` 同一条纪律：设备上留着上几轮的同名产物，
    扫全目录会把旧的当成本轮的）。
    """
    target = folder / 'options'
    target.mkdir(parents=True, exist_ok=True)
    until = time.time() + seconds
    while time.time() < until:
        for scene in scenes:
            remote = perf.BASE + '%s-%s/options/' % (run, scene)
            probe = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'cat', remote + 'report.json'],
                                   capture_output=True)
            if probe.returncode or b'"options"' not in probe.stdout:
                continue
            subprocess.run([ui.ADB, '-s', ui.SERIAL, 'pull', remote + '.', str(target)],
                           capture_output=True)
            print('受控对照已拉回', target, flush=True)
            return True
        time.sleep(5)
    print('WARN: 受控对照没等到', flush=True)
    return False


def pull_bench(folder, label, seconds=900, per_frame=False):
    """离屏基准在动画层释放之后另起线程跑几十秒，必须轮询等它写完。

    **只看本轮那一个 run 目录**：设备上还留着上几轮的 `segment-bench.json`，扫全目录第一次
    轮询就会命中旧文件，整份结果是上一轮的却毫无察觉（2026-09-17 实际发生过一次）。
    """
    remote = perf.BASE + label + '/'
    leaves = (('per-frame-bench.json', 'per-frame-bench-error.txt') if per_frame
              else ('segment-bench.json', 'segment-bench-error.txt'))
    until = time.time() + seconds
    while time.time() < until:
        for leaf in leaves:
            got = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'pull', remote + leaf,
                                  str(folder / leaf)], capture_output=True)
            if not got.returncode and (folder / leaf).is_file():
                print('分段基准已拉回', leaf, flush=True)
                return leaf == leaves[0]
        time.sleep(3)
    print('WARN: 分段基准没等到', flush=True)
    return False


if __name__ == '__main__':
    main()
