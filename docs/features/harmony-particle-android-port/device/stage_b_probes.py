"""阶段 B 的真机验收：用调试包里的探针 Activity（全是虚构内容，不读写记事）逐个入口录屏、取帧、出联系表。

入口：
- alert / chooser：真实 AlertDialogFragment / ChooserDialogFragment 的出现，再点确认按钮看消散；
- removal：多选移除（`ThingListRemovalAnimator`，完成 / 删除 / 恢复 / 彻底删除共用）+ 确认弹窗的出现与消散；
- swipe-left / swipe-right：左滑、右滑完成（`ParticleSwipeAnimator`）+ 留在列表里的回位（`ThingSwipeReturn`）；
- panel：调整外观面板（`ParticlePanelAnimator`）的消散与出现。

用法（Bash 工具里跑）：
    python docs/features/harmony-particle-android-port/device/stage_b_probes.py --run b1 [--only alert,removal]
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

PROBE = d.PACKAGE + '/.views.particledismiss.'


def start(activity, **extras):
    args = ['shell', 'am', 'start', '-n', PROBE + activity]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ['--ez', k, 'true' if v else 'false']
        elif isinstance(v, int):
            args += ['--ei', k, str(v)]
        else:
            args += ['--es', k, str(v)]
    d.adb(*args)


def changed_frames(items, count=24):
    """与前一帧相比有变化的那一段，均匀取 count 帧（整屏，缩放后的帧）。

    门槛按这段录像自己的最大帧差取：小面积的动画整屏均值很小，固定门槛会整段漏掉。
    """
    prev = None
    diffs = []
    for t, path in items:
        a = np.asarray(Image.open(path).convert('L'), dtype=np.float32)
        diffs.append(0.0 if prev is None else float(np.abs(a - prev).mean()))
        prev = a
    threshold = max(.05, .1 * max(diffs))
    active = [i for i, v in enumerate(diffs) if v > threshold]
    if not active:
        return [], diffs
    lo, hi = max(0, active[0] - 2), min(len(items) - 1, active[-1] + 2)
    picks = sorted(set(np.linspace(lo, hi, count).round().astype(int).tolist()))
    return picks, diffs


def sheet(folder, name, video, count=24, columns=8):
    items = r.frames(video, folder / (name + '-frames'), scale_width=480)
    picks, diffs = changed_frames(items, count=count)
    out = None
    if picks:
        out = r.contact_sheet([('t=%.3f' % items[i][0], items[i][1]) for i in picks],
                              folder / (name + '-sheet.png'), columns=columns)
    jumps = sorted(((round(diffs[i], 2), round(items[i][0], 3)) for i in range(len(diffs))), reverse=True)[:6]
    return {'frames': len(items), 'sheet': str(out) if out else None, 'top_jumps': jumps}


def wait_text(keys, tries=40):
    for _ in range(tries):
        tree = d.dump()
        node = d.find(tree, text=keys)
        if node is not None:
            return node
        time.sleep(.25)
    raise RuntimeError('找不到 ' + str(keys))


def case_dialog(folder, kind, button_keys):
    rec = r.Recording(6)
    start('ParticleAppearanceProbeActivity', run_id='drift-' + kind, kind=kind, palette='gradient')
    video = rec.finish(folder / (kind + '-appear.mp4'))
    out = {'appear': sheet(folder, kind + '-appear', video)}
    d.shot(folder, kind + '-shown')
    button = wait_text(button_keys)
    rec = r.Recording(5)
    d.tap(button)
    video = rec.finish(folder / (kind + '-dismiss.mp4'))
    out['dismiss'] = sheet(folder, kind + '-dismiss', video)
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    return out


def case_removal(folder, mode):
    start('ThingListRemovalProbeActivity', run_id='drift-' + mode, case=mode)
    time.sleep(2.0)
    d.shot(folder, 'removal-%s-before' % mode)
    toolbar = None
    for _ in range(20):
        toolbar = d.find(d.dump(), rid='contextual_toolbar')
        if toolbar is not None:
            break
        time.sleep(.3)
    rec = r.Recording(5)
    d.tap(toolbar)
    video = rec.finish(folder / ('removal-%s-confirm-appear.mp4' % mode))
    out = {'confirm_appear': sheet(folder, 'removal-%s-confirm-appear' % mode, video)}
    confirm = wait_text(('确定', 'OK'))
    rec = r.Recording(6)
    d.tap(confirm)
    video = rec.finish(folder / ('removal-%s-run.mp4' % mode))
    out['run'] = sheet(folder, 'removal-%s-run' % mode, video, count=32)
    d.shot(folder, 'removal-%s-after' % mode)
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    return out


def case_swipe(folder, direction):
    start('ParticleSwipeShadowProbeActivity', run_id='drift-swipe-' + direction, allow_right_swipe=True)
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
    if direction == 'left':
        sx, ex = min(x2 - 60, width - 140), max(40, x1 - 40)
    else:
        sx, ex = max(x1 + 60, 140), min(width - 40, x2 + 40)
    d.shot(folder, 'swipe-%s-before' % direction)
    rec = r.Recording(6)
    subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'input', 'swipe', str(sx), str(cy), str(ex), str(cy), '700'])
    video = rec.finish(folder / ('swipe-%s.mp4' % direction))
    out = sheet(folder, 'swipe-%s' % direction, video, count=32)
    d.shot(folder, 'swipe-%s-after' % direction)
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    return out


def case_panel(folder):
    rec = r.Recording(7)
    start('ParticlePanelReuseProbeActivity', run_id='drift-panel')
    video = rec.finish(folder / 'panel.mp4')
    out = sheet(folder, 'panel', video, count=32)
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--only', default='')
    a = p.parse_args()
    only = set(filter(None, a.only.split(',')))
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'rotation_before': d.rotation_state()}
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time'], stdout=log, stderr=subprocess.DEVNULL)
    cases = [
        ('alert', lambda: case_dialog(folder, 'alert', ('确认操作',))),
        ('chooser', lambda: case_dialog(folder, 'chooser', ('确认选择',))),
        ('removal', lambda: case_removal(folder, 'mixed')),
        ('swipe-left', lambda: case_swipe(folder, 'left')),
        ('swipe-right', lambda: case_swipe(folder, 'right')),
        ('panel', lambda: case_panel(folder)),
    ]
    try:
        for name, fn in cases:
            if only and name not in only:
                continue
            try:
                result[name] = fn()
            except Exception as e:  # 单个入口失败不影响其余入口
                result[name] = {'error': repr(e)}
                d.soft('shell', 'am', 'force-stop', d.PACKAGE)
            print(name, result[name])
    finally:
        cat.terminate()
        log.close()
        result['rotation_after'] = d.rotation_state()
        text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
        result['drift_first_frames'] = re.findall(r'飘散首帧上屏[^\n]*', text)
        result['drift_failures'] = re.findall(r'飘散样式回退[^\n]*\n[^\n]*', text)
        r.save_json(result, folder / 'result.json')
        print('rotation', result['rotation_before'], result['rotation_after'])
        print('first frames', len(result['drift_first_frames']), 'failures', len(result['drift_failures']))


if __name__ == '__main__':
    main()
