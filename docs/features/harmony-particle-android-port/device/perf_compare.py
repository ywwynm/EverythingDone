"""「流体」与「飘散」同口径的性能对比：起播延迟与出帧（R5CW20BLNKL，只用调试探针里的虚构内容）。

两种样式各跑同样的场景，每一轮由两种会话共用的 `ParticleFramePacing` 打一行「出帧对比」（真实上屏时刻）：
- 弹窗：多选移除探针的确认弹窗，出现（倒放）后点弹窗外让它消散。请求时刻在触摸分发里同步打下，
  所以「首帧Ms」就是从点弹窗外到第一帧粒子上屏；
- 左滑：阴影探针的虚构卡片左滑完成（「跟手」会话，请求时刻是截图完成后创建动画规格，
  不包含按下、识别和截图；不能直接称为完整交互延迟）。
样式经 run-as 改 `particle_tuning` 偏好切换（先停进程），跑完把偏好恢复成跑之前的原文并读回核对。

用法（Bash 工具里跑）：python perf_compare.py --run q1 [--dialog-rounds 6 --swipe-rounds 5]
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import stage_b_probes as b  # noqa: E402

PREFS = 'shared_prefs/particle_tuning.xml'
LINE = re.compile(r'出帧对比 样式=(\S+) 类型=(\S+) 首帧Ms=([\d.]+) 上屏=(\d+) 应有=(\d+) 用时Ms=(\d+) '
                  r'p90间隔Ms=([\d.]+) 最大间隔Ms=([\d.]+) 最大间隔在=(\d+)/(\d+) 颗粒=(\d+)')
DRIVER = Path(__file__).resolve().parents[4] / 'tmp/thing-animation-device/input-driver/input.jar'
REMOTE_DRIVER = '/data/local/tmp/thing-animation-input.jar'


def run_as(command):
    return d.soft('shell', 'run-as %s sh -c "%s"' % (d.PACKAGE, command)).stdout.decode('utf-8', 'replace')


def write_prefs(text):
    """整份写回：原始字节经标准输入喂进去（PowerShell 管道会带 BOM，这里不经过它），写完核对文件头。"""
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    time.sleep(.8)
    subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'run-as', d.PACKAGE, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (PREFS, PREFS, PREFS)], input=text.encode('utf-8'), check=True)
    head = d.soft('shell', 'run-as %s od -An -tx1 -N 1 %s' % (d.PACKAGE, PREFS)).stdout.decode().split()
    assert head and head[0] == '3c', head
    # 新建的文件沿用 shell 的 umask（666）；改回应用自己写偏好时的 660
    d.soft('shell', 'run-as %s chmod 660 %s' % (d.PACKAGE, PREFS))


def set_style(original, style):
    body = re.sub(r'\s*<string name="particle_style">[^<]*</string>', '', original)
    body = body.replace('</map>', '    <string name="particle_style">%s</string>\n</map>' % style)
    write_prefs(body)
    assert ('>%s<' % style) in run_as('cat %s' % PREFS)


def dialog_round(outside):
    toolbar = None
    for _ in range(20):
        toolbar = d.find(d.dump(), rid='contextual_toolbar')
        if toolbar is not None:
            break
        time.sleep(.3)
    d.tap(toolbar)
    b.wait_text(('确定', 'OK'))
    time.sleep(1.4)
    if d.find(d.dump(), text=('确定', 'OK')) is None:
        raise RuntimeError('确认弹窗不在了')
    d.adb('shell', 'input', 'tap', outside[0], outside[1])
    for _ in range(20):
        time.sleep(.3)
        if d.find(d.dump(), text=('确定', 'OK')) is None:
            return True
    return False


ASYNC_INPUT = False


def swipe_round(run, k):
    b.start('ParticleSwipeShadowProbeActivity', run_id='perf-%s-%d' % (run, k), allow_right_swipe=True)
    time.sleep(2.0)
    card = None
    for _ in range(20):
        card = d.find(d.dump(), desc=('虚构阴影测试卡片',))
        if card is not None:
            break
        time.sleep(.3)
    width, _ = d.screen_size()
    x1, y1, x2, y2 = d.bounds(card)
    cy = (y1 + y2) // 2
    sx, ex = min(x2 - 60, width - 140), max(40, x1 - 40)
    mid = sx + (ex - sx) * 2 // 5
    # 连续注入（约 8 ms 一个 MOVE，经 InputDispatcher）：先慢拖 900 ms 到卡片 40% 处，再 200 ms 拖到底、松手
    # --async-input：不等应用处理完、每 4 ms 一个 MOVE（240 Hz 上报的触摸屏）；缺省的同步注入会被拴在应用帧率上
    env = ['PARTICLE_INPUT_ASYNC=1'] if ASYNC_INPUT else []
    d.adb('shell', *env, 'CLASSPATH=' + REMOTE_DRIVER, 'app_process', '/system/bin', 'ParticleGestureInput',
          sx, cy, 'UP', mid, cy, 900, ex, cy, 200)
    time.sleep(2.5)


def removal_round(run, k):
    """多选移除探针（虚构列表）：点顶栏的移除，确认弹窗里点「确定」，卡片按粒子移除。"""
    b.start('ThingListRemovalProbeActivity', run_id='perf-removal-%s-%d' % (run, k), case='mixed')
    time.sleep(2.0)
    toolbar = None
    for _ in range(20):
        toolbar = d.find(d.dump(), rid='contextual_toolbar')
        if toolbar is not None:
            break
        time.sleep(.3)
    d.tap(toolbar)
    confirm = b.wait_text(('确定', 'OK'))
    time.sleep(1.2)
    d.tap(confirm)
    time.sleep(3.0)


def resumed_activity():
    """前台 Activity 的类名（`ResumedActivity` 同时覆盖 topResumedActivity= 与 ResumedActivity: 两种写法）。"""
    out = d.soft('shell', 'dumpsys activity activities').stdout.decode('utf-8', 'replace')
    for line in out.splitlines():
        if 'ResumedActivity' in line:
            m = re.search(r'\S+/(\S+?)[\s}]', line)
            if m:
                return m.group(1)
    return ''


def wait_resumed(name, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if name in resumed_activity():
            return True
        time.sleep(.25)
    return False


def creation_round():
    """真实首页点新建按钮，新建页按粒子出现；按返回放弃（空记事不保存），首页记事数前后不变。

    点新建之后先确认新建页真的到了前台：首页在截图完成那一刻没有窗口焦点时会放弃打开（`ThingCreationTransition`），
    这时再按返回就会把应用退到桌面。返回也按前台 Activity 判断，不靠 dump（返回动画期间常拿到不完整的树）。
    """
    tree = d.dump('fab_create')
    node = d.find(tree, rid='tv_header_subtitle')
    before = node.get('text') if node is not None else None
    for attempt in range(3):
        d.tap(d.find(tree, rid='fab_create'))
        if wait_resumed('DetailActivity', 4.0):
            break
        tree = d.dump('fab_create')
    else:
        raise RuntimeError('点新建三次，新建页都没有打开')
    time.sleep(2.0)
    # 第一次返回只收起输入法；回到首页为止（最多三次）
    for _ in range(3):
        d.adb('shell', 'input', 'keyevent', '4')
        if wait_resumed('ThingsActivity', 2.0):
            break
    time.sleep(1.0)
    tree = d.dump('fab_create')
    node = d.find(tree, rid='tv_header_subtitle')
    after = node.get('text') if node is not None else None
    if before != after:
        raise RuntimeError('首页记事数变了：%s → %s' % (before, after))


def summarize(rows):
    out = {}
    for (style, kind), items in sorted(rows.items()):
        first = [r['first'] for r in items]
        ratio = [r['shown'] / max(r['expected'], 1) for r in items]
        out['%s/%s' % (style, kind)] = dict(
            rounds=len(items), first_cold=first[0], first_median=statistics.median(first[1:] or first),
            first_max=max(first[1:] or first), frames_median=statistics.median(ratio),
            p90_median=statistics.median(r['p90'] for r in items), max_gap=max(r['max'] for r in items),
            particles=statistics.median(r['particles'] for r in items))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--dialog-rounds', type=int, default=6)
    p.add_argument('--swipe-rounds', type=int, default=5)
    p.add_argument('--removal-rounds', type=int, default=0)
    p.add_argument('--creation-rounds', type=int, default=0)
    p.add_argument('--styles', default='flow,drift')
    p.add_argument('--async-input', action='store_true')
    a = p.parse_args()
    global ASYNC_INPUT
    ASYNC_INPUT = a.async_input
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    d.adb('push', str(DRIVER), REMOTE_DRIVER)
    original = run_as('cat %s' % PREFS)
    assert original.strip().startswith('<?xml'), original
    result = {'rotation_before': d.rotation_state(), 'prefs_before': original}
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticlePacing:I', 'ParticleDrift:I',
                            'ParticleMicroflake:I'], stdout=log, stderr=subprocess.DEVNULL)
    marks = []
    try:
        for style in a.styles.split(','):
            set_style(original, style)
            width, height = d.screen_size()
            b.start('ThingListRemovalProbeActivity', run_id='perf-%s-%s' % (a.run, style), case='single')
            time.sleep(2.5)
            for k in range(a.dialog_rounds):
                marks.append((style, 'dialog', k, time.time()))
                if not dialog_round((width // 2, int(height * .12))):
                    raise RuntimeError('点弹窗外没有关掉弹窗')
                time.sleep(2.0)
            d.adb('shell', 'am', 'force-stop', d.PACKAGE)
            for k in range(a.swipe_rounds):
                marks.append((style, 'swipe', k, time.time()))
                swipe_round(a.run, k)
                d.adb('shell', 'am', 'force-stop', d.PACKAGE)
                time.sleep(.6)
            for k in range(a.removal_rounds):
                removal_round(a.run, k)
                d.adb('shell', 'am', 'force-stop', d.PACKAGE)
                time.sleep(.6)
            if a.creation_rounds:
                d.launch()
                time.sleep(1.5)
                for k in range(a.creation_rounds):
                    creation_round()
                d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    finally:
        cat.terminate()
        log.close()
        write_prefs(original)
        result['prefs_after'] = run_as('cat %s' % PREFS)
        result['rotation_after'] = d.rotation_state()
    rows = {}
    names = {'流体': 'flow', '飘散': 'drift'}
    for m in LINE.finditer((folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')):
        rows.setdefault((names.get(m.group(1), m.group(1)), m.group(2)), []).append(dict(
            first=float(m.group(3)), shown=int(m.group(4)), expected=int(m.group(5)), span=int(m.group(6)),
            p90=float(m.group(7)), max=float(m.group(8)), worst_at=int(m.group(9)), gaps=int(m.group(10)),
            particles=int(m.group(11))))
    result['rows'] = {'%s/%s' % k: v for k, v in rows.items()}
    result['summary'] = summarize(rows)
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), 'utf-8')
    print(json.dumps(result['summary'], ensure_ascii=False, indent=1))
    print('prefs restored:', result['prefs_after'] == original, 'rotation', result['rotation_before'], result['rotation_after'])


if __name__ == '__main__':
    main()
