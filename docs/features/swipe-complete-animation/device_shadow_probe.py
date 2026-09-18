"""虚构列表的系统输入与阴影测量；不会打开用户记事、改设置或删除应用数据。"""
import argparse
import json
import subprocess
import struct
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', choices=['9018f404', '392QBFED2226B', 'R5CW20BLNKL'], required=True)
p.add_argument('--run', required=True)
p.add_argument('--mode', choices=['tap', 'short', 'cancel', 'reverse', 'fast', 'return', 'fling'], default='cancel')
p.add_argument('--held', action='store_true')
p.add_argument('--width-dp', type=int, default=0)
p.add_argument('--backend', choices=['surface', 'texture'], default='surface')
p.add_argument('--video', action='store_true')
p.add_argument('--performance-only', action='store_true')
p.add_argument('--repeat', action='store_true', help='首段松手后立即再次滑动同一张虚构卡片')
p.add_argument('--cancel-event', action='store_true', help='以系统 ACTION_CANCEL 结束手势')
p.add_argument('--fab', action='store_true', help='在卡片上方放置真实 FAB 控件，不绑定业务动作')
p.add_argument('--chrome', action='store_true', help='使用真实 contextual toolbar 布局及半透明阴影遮挡卡片')
p.add_argument('--normal-chrome', action='store_true', help='改测正常首页顶栏')
p.add_argument('--fab-motion', action='store_true', help='粒子期间移动、缩放、淡入淡出 FAB')
p.add_argument('--fab-click', action='store_true', help='松手后点按虚构 FAB，验证绘制接管不影响输入')
a = p.parse_args()
assert a.run.replace('-', '').replace('_', '').isalnum()
repo = Path(__file__).resolve().parents[3]
out = repo / 'tmp/swipe-shadow-probe' / a.serial / a.run
out.mkdir(parents=True, exist_ok=True)
adb_path = 'E:/AndroidSDK/platform-tools/adb.exe'
package = 'com.ywwynm.everythingdone'
remote = f'/sdcard/Android/data/{package}/files/swipe-shadow-probe/{a.run}'

def adb(*args):
    return subprocess.check_output([adb_path, '-s', a.serial, *map(str, args)], stderr=subprocess.STDOUT)

def wait_json(name, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            return json.loads(adb('shell', 'cat', f'{remote}/{name}.json'))
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(.25)
    raise TimeoutError(name)

adb('push', repo / 'tmp/thing-animation-device/input-driver/input.jar', '/data/local/tmp/thing-animation-input.jar')
launch = ['shell', 'am', 'start', '-W', '-n', package + '/.views.particledismiss.ParticleSwipeShadowProbeActivity',
          '--es', 'run_id', a.run, '--ei', 'card_width_dp', a.width_dp,
          '--ez', 'force_texture', str(a.backend == 'texture').lower(),
          '--ez', 'with_fab', str(a.fab).lower(),
          '--ez', 'with_chrome', str(a.chrome or a.normal_chrome).lower(),
          '--ez', 'normal_chrome', str(a.normal_chrome).lower(),
          '--ez', 'fab_motion', str(a.fab_motion).lower(),
          '--ez', 'performance_only', str(a.performance_only).lower()]
status = adb(*launch)
if b'intent has been delivered' in status:
    # 仅退出本脚本的虚构页面，确保 OEM 不复用上一次测量对象。
    adb('shell', 'input', 'keyevent', 'BACK')
    adb(*launch)
b = wait_json('ready')
assert b.get('backend') == a.backend, f'实际安装包或播放路径不符: {b.get("backend")}'
(out / 'ready.json').write_text(json.dumps(b))
adb('shell', 'screencap', '-p', f'/sdcard/shadow-{a.run}.png')
adb('pull', f'/sdcard/shadow-{a.run}.png', out / 'before.png')
adb('shell', 'rm', f'/sdcard/shadow-{a.run}.png')
recorder = None
if a.video:
    recorder = subprocess.Popen([adb_path, '-s', a.serial, 'shell', 'screenrecord',
        '--time-limit', '12', '--bit-rate', '32000000', f'{remote}/capture.mp4'],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(.4)
x = round(b['x'] + b['width'] * .88)
y = round(b['y'] + b['height'] * .5)
distance = .78 if a.mode == 'return' else .12 if a.mode == 'short' else .42
left = round(x - b['width'] * distance)
if a.mode == 'tap':
    steps = [x, y, 16]
elif a.mode == 'fling':
    steps = [12, y, 48]
elif a.mode == 'reverse':
    steps = [left, y, 450, left, y, 350, x+24, y, 450, x+24, y, 200]
elif a.mode == 'fast':
    steps = [left, y, 48, left, y, 350]
elif a.mode == 'return':
    steps = [left, y, 360, left, y, 160]
else:
    steps = [left, y, 450, left, y, 450]
if a.held:
    assert a.mode not in ('tap', 'fling'), '点按与快速甩动没有保持段'
    steps[5] = 2400
gesture = ['shell', 'CLASSPATH=/data/local/tmp/thing-animation-input.jar', 'app_process', '/system/bin',
           'ParticleGestureInput', x, y, 'CANCEL' if a.cancel_event else 'UP', *steps]
if a.held:
    driver = subprocess.Popen([adb_path, '-s', a.serial, *map(str, gesture)], stdout=subprocess.DEVNULL)
    time.sleep(1.1)  # 确定脚本正处于固定进度的保持段；不是等待 UI 导航。
    adb('shell', 'screencap', '-p', f'/sdcard/shadow-{a.run}.png')
    adb('pull', f'/sdcard/shadow-{a.run}.png', out / 'held.png')
    adb('shell', 'rm', f'/sdcard/shadow-{a.run}.png')
    assert driver.wait(timeout=10) == 0
else:
    adb(*gesture)
if a.repeat:
    assert a.mode not in ('return', 'fling'), '重复测试仅限不会确认完成的手势'
    adb(*gesture)
if a.fab_click:
    fab = b['fab']
    adb('shell', 'input', 'tap', round(fab['x'] + fab['width'] / 2), round(fab['y'] + fab['height'] / 2))
data = wait_json('result')
(out / 'result.json').write_text(json.dumps(data, indent=2))
if recorder:
    _, error = recorder.communicate(timeout=16)
    assert recorder.returncode == 0, error.decode(errors='replace')
    adb('pull', f'{remote}/capture.mp4', out / 'capture.mp4')
adb('shell', 'screencap', '-p', f'/sdcard/shadow-{a.run}.png')
adb('pull', f'/sdcard/shadow-{a.run}.png', out / 'after.png')
adb('shell', 'rm', f'/sdcard/shadow-{a.run}.png')
frames = data['frames']
assert frames[-1]['alpha'] == 1 and abs(frames[-1]['tx']) < .5
expected_completions = int(a.mode in ('return', 'fling') and not a.cancel_event)
assert data['completions'] == expected_completions
if a.fab_click:
    assert data['fabClicks'] == 1, '虚构 FAB 没有收到实际点击'
if expected_completions:
    # 最终卡片可见也可能来自超时恢复；必须确认消散与回聚都实际提交了末帧。
    assert len(data['particleFrames']) >= 2, '消散或回聚会话缺失'
    for session in data['particleFrames']:
        bits = session['submitted'][-1][2] & 0xffffffff
        assert struct.unpack('f', struct.pack('I', bits))[0] >= .999, '粒子未播放到末帧'
if a.performance_only:
    print(json.dumps({'run': a.run, 'backend': b.get('backend'), 'completions': data['completions'],
        'windowFrames': len(data['windowFrames']), 'particleSessions': len(data['particleFrames']), 'path': str(out)}))
    raise SystemExit(0)
touch_at = next(v[0] for v in data['inputs'] if v[1] == 0)
measured = [f for f in frames if f['at'] >= touch_at - 150]
deltas = [(b['at'], b['darkness']-a['darkness']) for a,b in zip(measured, measured[1:])]
print(json.dumps({'serial':a.serial,'run':a.run,'completions':data['completions'],
    'largestSteps':sorted(deltas,key=lambda v:abs(v[1]),reverse=True)[:8],
    'initialDarkness':measured[0]['darkness'],'finalDarkness':measured[-1]['darkness'],
    'finalAlpha':frames[-1]['alpha'],'finalZ':frames[-1]['z'],'path':str(out)}))
hidden = [f for f in frames if f.get('contentHidden') and abs(f['tx']) < .5]
if a.mode != 'tap':
    assert hidden, '粒子期间原卡片没有隐藏'
assert abs(measured[-1]['darkness'] - measured[0]['darkness']) < 1.0, '恢复后的阴影与静止值不同'
handoffs = [abs(n['darkness'] - prev['darkness']) for prev, n in zip(measured, measured[1:])
            if prev.get('contentHidden') and not n.get('contentHidden')
            and abs(n['tx']) < .5 and n['view'] == prev['view']]
if b.get('backend') != 'surface' and a.mode != 'tap':
    assert handoffs and max(handoffs) < 1.0, f'阴影交接发生跳变: {handoffs}'
print(json.dumps({'handoffDifferences': handoffs}))
