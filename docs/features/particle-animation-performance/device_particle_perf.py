"""阶段 0 真实系统输入驱动：只读观察器采样 + debug GPU 计时钩子。

不发送业务 Intent 或广播；除应用图标启动外，全部操作都是向 Android 输入系统
注入的真实点击、按键和滑动。产物写入被忽略的 tmp/particle-perf/phase0/<serial>/。
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))
import device_ui as ui  # noqa: E402  仅复用 adb 调用与授权序列号校验
import device_performance as perf  # noqa: E402

ROOT = REPO / 'tmp/particle-perf/phase0' / ui.SERIAL
ROOT.mkdir(parents=True, exist_ok=True)
ui.ROOT = ROOT
perf.ROOT = ROOT / 'runs'
perf.ROOT.mkdir(parents=True, exist_ok=True)
PROBE_OUT = '/sdcard/Android/data/' + ui.PACKAGE + '/files/particle-perf/'

ADD_ATTACHMENT = ('Add attachment', '添加附件', '新增附件', '增加附件')


def adb(*args):
    return ui.adb(*args)


def shell_write(remote_relative, text):
    """经 run-as 写应用私有诊断文件；只传采样标签，不含业务指令。"""
    command = ('cat > %s.tmp && mv %s.tmp %s' % (remote_relative, remote_relative, remote_relative))
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'sh', '-c', "'" + command + "'"],
                   input=json.dumps(text).encode(), capture_output=True, check=True)


PROBE_RUN = 'run'


def probe(config):
    path = 'files/particle-perf-probe.json'
    if config is None:
        subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'rm', '-f', path],
                       capture_output=True)
    else:
        subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'rm', '-rf', PROBE_OUT + config['run_id']],
                       capture_output=True)
        shell_write(path, config)


def observer(enabled):
    path = 'files/thing-animation-performance-enabled'
    if enabled:
        subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'sh', '-c',
                        "'echo 1 > " + path + "'"], capture_output=True, check=True)
    else:
        subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'rm', '-f', path],
                       capture_output=True)


def dump(require=None, tries=25):
    """带动画的弹窗会返回陈旧缓存树；必须轮询到目标出现为止。"""
    last = None
    for _ in range(tries):
        adb('shell', 'uiautomator', 'dump', '/sdcard/particle-perf-ui.xml')
        raw = adb('shell', 'cat', '/sdcard/particle-perf-ui.xml')
        try:
            last = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(.15)
            continue
        if require is None or present(last, require):
            return last
        time.sleep(.15)
    raise RuntimeError('UI 没有出现 ' + str(require))


def present(tree, rid):
    return any(n.get('resource-id', '').endswith('/' + rid) for n in tree.iter('node'))


def absent(rid, tries=25):
    for _ in range(tries):
        tree = dump()
        if not present(tree, rid):
            return tree
        time.sleep(.15)
    raise RuntimeError('UI 仍然存在 ' + rid)


def node(tree, rid=None, desc=None, text=None):
    found = [n for n in tree.iter('node')
             if (rid is None or n.get('resource-id', '').endswith('/' + rid))
             and (desc is None or n.get('content-desc') in desc)
             and (text is None or n.get('text') == text)]
    if len(found) != 1:
        raise RuntimeError('期望唯一节点，实际 %d 个：%s' % (len(found), rid or desc or text))
    return found[0]


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    adb('shell', 'input', 'tap', x, y)


def launch():
    adb('shell', 'am', 'force-stop', ui.PACKAGE)
    time.sleep(1.2)
    adb('shell', 'monkey', '-p', ui.PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    return dump('fab_create')


GPU_CHECK = False
APPEARANCE_FRAMES = False
# 第五批：G0 对照的定性轮。固定种子让「与种子有关」与「与并发有关」分得开；
# GPU_LOAD 在另一个 EGL 上下文里制造与提前预算同形态的并发负载。
GPU_SEED = None
GPU_DIAGNOSTICS = False
GPU_LOAD = False
GPU_REPEATS = 3
GPU_PIN_FRAME = -1


def begin(run, kind, extra=None):
    settings = {'run_id': run, 'kind': kind, 'title': '', 'mode': 3, 'driver': 'system-input',
                'memoryOnly': False, 'gpuCheck': GPU_CHECK,
                'diagnosticOnly': GPU_CHECK and GPU_SEED is not None,
                'stallSampling': True, 'appearanceFrames': APPEARANCE_FRAMES}
    if GPU_CHECK and GPU_SEED is not None:
        settings['gpuCheckSeed'] = GPU_SEED
    if GPU_CHECK and GPU_DIAGNOSTICS:
        settings['gpuCheckDiagnostics'] = True
        settings['gpuCheckLoad'] = GPU_LOAD
        settings['gpuCheckRepeats'] = GPU_REPEATS
        if GPU_PIN_FRAME >= 0:
            settings['gpuCheckPinFrame'] = GPU_PIN_FRAME
    settings.update(extra or {})
    (perf.ROOT / run).mkdir(parents=True, exist_ok=True)
    # 同名 run 重跑时，设备上还留着上一轮的 result.json；end() 的轮询会在观察器写出
    # 新文件之前就把旧文件读回来（观察器是 finish() + 300 ms 才写），于是拿到上一轮的
    # 数字却毫无察觉（2026-09-15 实际发生：整批场景的粒子时间线全是空的）。先删干净。
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'rm', '-rf', perf.BASE + run],
                   capture_output=True)
    # 观察器按 `run_id` 变没变来换采集器（`ObserverManager.poll` 里的 `next != run`）。
    # 上一轮如果中途出错、没走到 `end()`，进程里还记着同一个 id，**再写同一个 id 什么也不会
    # 发生**，症状是 `ready did not become true`，而且重跑多少次都一样（2026-09-17 在华为上
    # 卡了两轮）。先写一份空配置把它清掉，等过一个轮询周期再写本轮的。
    perf.control({})
    time.sleep(.35)
    perf.control(settings)
    perf.wait_progress(run, 'ready')
    # 观察器每 250 ms 重新登记一次宿主窗口；等过一个周期再注入输入。
    time.sleep(.4)
    print('Measuring', run, flush=True)


def end(run):
    data = perf.end(run)
    pull_probe(run)
    pull_parity(run, max(1, len(data.get('overlays') or [])))
    return data


def pull_parity(run, expected=1, seconds=2400):
    """G0 对照在动画层释放之后另起线程跑，写完才有 gpu-parity-*.json；必须轮询等它。"""
    if not GPU_CHECK:
        return 0
    remote = perf.BASE + run + '/'
    target = perf.ROOT / run
    until = time.time() + seconds
    while time.time() < until:
        listing = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'ls', remote],
                                 capture_output=True)
        names = [n.strip() for n in listing.stdout.decode(errors='replace').split()
                 if n.strip().startswith('gpu-parity-')]
        if len(names) >= expected:
            target.mkdir(parents=True, exist_ok=True)
            for name in names:
                adb('pull', remote + name, target / name)
            return len(names)
        time.sleep(2)
    print('WARN: gpu-parity 没等到', run, flush=True)
    return 0


def pull_probe(_label=None):
    target = ROOT / 'gpu' / PROBE_RUN
    target.mkdir(parents=True, exist_ok=True)
    listing = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'ls', PROBE_OUT + PROBE_RUN],
                             capture_output=True)
    if listing.returncode:
        return 0
    names = [n.strip() for n in listing.stdout.decode(errors='replace').split() if n.strip().endswith('.json')]
    for name in names:
        if not (target / name).exists():
            adb('pull', PROBE_OUT + PROBE_RUN + '/' + name, target / name)
    return len(names)


# ===== 场景 =====

def open_detail(title):
    """从首页真实点击指定记事卡片进入详情页。"""
    tree = dump('fab_create')
    target = next(n for n in tree.iter('node') if n.get('text') == title)
    parents = {c: n for n in tree.iter() for c in n}
    while not target.get('resource-id', '').endswith('/cv_thing'):
        target = parents[target]
    tap(target)
    return dump('et_content')


def attachment_icon(tree):
    return node(tree, desc=ADD_ATTACHMENT)


def scenario_attach_open(run, rounds):
    """详情页 → 工具栏“添加附件” → 弹窗出现 → 返回键消散。"""
    for i in range(rounds):
        tree = dump('et_content')
        label = '%s-%d' % (run, i)
        begin(label, 'attach-open')
        tap(attachment_icon(tree))
        dump('tv_record_audio_as_bt')
        adb('shell', 'input', 'keyevent', '4')
        absent('tv_record_audio_as_bt')
        data = end(label)
        print(json.dumps({'run': label, 'overlays': len(data['overlays']),
                          'gpu': pull_probe(label)}), flush=True)
        time.sleep(2.2)


def scenario_attach_audio(run, rounds):
    """添加附件弹窗已打开；测量点“录音”行之后的涟漪 + 消散 + 出现。"""
    for i in range(rounds):
        tree = dump('et_content')
        tap(attachment_icon(tree))
        tree = dump('tv_record_audio_as_bt')
        time.sleep(.8)
        label = '%s-%d' % (run, i)
        begin(label, 'attach-audio')
        tap(node(tree, rid='tv_record_audio_as_bt'))
        dump('voice_visualizer')
        data = end(label)
        # 录音弹窗停在 PREPARED，没有开始录音；返回键关闭，不产生任何附件。
        adb('shell', 'input', 'keyevent', '4')
        absent('voice_visualizer')
        print(json.dumps({'run': label, 'overlays': len(data['overlays']),
                          'gpu': pull_probe(label)}), flush=True)
        time.sleep(2.2)


def pull_appearance(run):
    """2d 的三组对比帧；导出线程在动画层释放之后才跑，所以要等它把 report.json 写出来。"""
    remote = perf.BASE + run + '/appearance-frames'
    target = perf.ROOT / run / 'appearance-frames'
    until = time.time() + 120
    while time.time() < until:
        listing = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'ls', remote],
                                 capture_output=True)
        if not listing.returncode and 'report.json' in listing.stdout.decode(errors='replace'):
            target.mkdir(parents=True, exist_ok=True)
            adb('pull', remote + '/.', target)
            return len(list(target.glob('*.png')))
        time.sleep(1)
    return 0


TRIM_BETWEEN = False


def background_and_return():
    """第五批追加项：退后台 + 内存告警放掉内存态预测素材，回到前台后磁盘缓存应当仍然可用。

    **不 force-stop**：进程还是同一个，验证的是「内存态被清掉之后还能不能从盘上读回来」。
    `trimMemory()` 把待办的预算调度也撤了，靠回到前台时 `onHomeVisible` 重新排上。
    """
    pid = adb('shell', 'pidof', ui.PACKAGE).decode(errors='replace').split()
    assert pid, '应用不在运行'
    adb('shell', 'input', 'keyevent', '3')
    time.sleep(1.2)
    adb('shell', 'am', 'send-trim-memory', pid[0], 'RUNNING_CRITICAL')
    time.sleep(0.8)
    adb('shell', 'monkey', '-p', ui.PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    after = adb('shell', 'pidof', ui.PACKAGE).decode(errors='replace').split()
    assert after == pid, '进程被杀了，这一轮不是「内存态清掉后从盘上读回来」'


def scenario_create_page(run, rounds):
    """首页新建按钮 → 创建页（新建档位为粒子）。"""
    for i in range(rounds):
        if TRIM_BETWEEN and i:
            background_and_return()
        tree = dump('fab_create')
        label = '%s-%d' % (run, i)
        begin(label, 'create-page')
        tap(node(tree, rid='fab_create'))
        perf.wait_progress(label, 'editorReady')
        data = end(label)
        frames = pull_appearance(label) if APPEARANCE_FRAMES else 0
        print(json.dumps({'run': label, 'overlays': len(data['overlays']),
                          'gpu': pull_probe(label), 'appearanceFrames': frames}), flush=True)
        adb('shell', 'input', 'keyevent', '4')
        tree = dump()
        if present(tree, 'ib_back'):
            adb('shell', 'input', 'keyevent', '4')
        dump('fab_create')
        time.sleep(2.0)


def scenario_repeat(run, rounds, times=10):
    """同一详情页连续开关同一弹窗 10 次，全部计入一轮。"""
    for i in range(rounds):
        tree = dump('et_content')
        icon = attachment_icon(tree)
        label = '%s-%d' % (run, i)
        begin(label, 'dialog-repeat')
        for _ in range(times):
            tap(icon)
            dump('tv_record_audio_as_bt')
            adb('shell', 'input', 'keyevent', '4')
            absent('tv_record_audio_as_bt')
        data = end(label)
        print(json.dumps({'run': label, 'overlays': len(data['overlays']),
                          'gpu': pull_probe(label)}), flush=True)
        time.sleep(2.2)


def scenario_swipe(run, rounds, title, fast):
    """左滑档位为粒子；慢拖 1.2 秒 36% 宽度，快拖 180 ms。"""
    driver = REPO / 'tmp/thing-animation-device/input-driver/input.jar'
    adb('push', driver, '/data/local/tmp/particle-perf-input.jar')
    for i in range(rounds):
        tree = dump('fab_create')
        before = node(tree, rid='tv_header_subtitle').get('text')
        target = next(n for n in tree.iter('node') if n.get('text') == title)
        parents = {c: n for n in tree.iter() for c in n}
        while not target.get('resource-id', '').endswith('/cv_thing'):
            target = parents[target]
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
        start, y = x2 - 80, (y1 + y2) // 2
        finish = round(max(12, start - (x2 - x1) * .36))
        label = '%s-%d' % (run, i)
        begin(label, 'swipe-fast' if fast else 'swipe-slow')
        if fast:
            adb('shell', 'CLASSPATH=/data/local/tmp/particle-perf-input.jar', 'app_process',
                '/system/bin', 'ParticleGestureInput', start, y, 'UP', finish, y, 180, finish, y, 600)
        else:
            adb('shell', 'CLASSPATH=/data/local/tmp/particle-perf-input.jar', 'app_process',
                '/system/bin', 'ParticleGestureInput', start, y, 'UP', finish, y, 1200, finish, y, 300)
        data = end(label)
        tree = dump('fab_create')
        assert any(n.get('text') == title for n in tree.iter('node')), '测试记事消失'
        assert node(tree, rid='tv_header_subtitle').get('text') == before, '记事数量变化'
        print(json.dumps({'run': label, 'overlays': len(data['overlays']),
                          'gpu': pull_probe(label)}), flush=True)
        time.sleep(2.0)


SCENARIOS = {'attach-open': scenario_attach_open, 'attach-audio': scenario_attach_audio,
             'create-page': scenario_create_page, 'repeat': scenario_repeat}

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--scenario', required=True,
                   choices=list(SCENARIOS) + ['swipe-slow', 'swipe-fast', 'launch'])
    p.add_argument('--run', required=True)
    p.add_argument('--rounds', type=int, default=3)
    p.add_argument('--title', default='')
    p.add_argument('--probe', default='')
    p.add_argument('--gpu-check', action='store_true')
    p.add_argument('--gpu-seed', type=int, default=None,
                   help='固定 G0 对照的随机种子（同时打开 diagnosticOnly）')
    p.add_argument('--gpu-diagnostics', action='store_true',
                   help='第五批定性轮：每条路径自跑多遍、定位差异像素、比对状态缓冲')
    p.add_argument('--gpu-load', action='store_true',
                   help='定性轮里在另一个 EGL 上下文制造与提前预算同形态的并发 GPU 负载')
    p.add_argument('--gpu-repeats', type=int, default=3)
    p.add_argument('--trim-between', action='store_true',
                   help='创建页每一轮之前先退后台 + 发一次 RUNNING_CRITICAL 再回来，'
                        '验证内存态清掉之后磁盘缓存仍然可用')
    p.add_argument('--gpu-pin-frame', type=int, default=-1,
                   help='已知的临界帧：对着它反复跑出现路径，直到复现自分歧')
    p.add_argument('--appearance-frames', action='store_true',
                   help='创建页出现：离屏导出现状 / 只开 2b / 2b+2c 三组对比帧')
    a = p.parse_args()
    GPU_CHECK = a.gpu_check
    GPU_SEED = a.gpu_seed
    GPU_DIAGNOSTICS = a.gpu_diagnostics
    GPU_LOAD = a.gpu_load
    GPU_REPEATS = a.gpu_repeats
    GPU_PIN_FRAME = a.gpu_pin_frame
    TRIM_BETWEEN = a.trim_between
    APPEARANCE_FRAMES = a.appearance_frames
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', a.run)
    PROBE_RUN = a.run
    observer(True)
    if a.probe:
        config = json.loads(a.probe)
        config['run_id'] = a.run
        probe(config)
    else:
        probe(None)
    if a.scenario == 'launch':
        launch()
        sys.exit(0)
    launch()
    if a.scenario in ('attach-open', 'attach-audio', 'repeat'):
        assert a.title, '需要 --title 指定专用测试记事'
        open_detail(a.title)
    if a.scenario.startswith('swipe'):
        scenario_swipe(a.run, a.rounds, a.title, a.scenario == 'swipe-fast')
    else:
        SCENARIOS[a.scenario](a.run, a.rounds)
    probe(None)
