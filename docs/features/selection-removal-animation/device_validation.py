"""两台已授权设备的虚构列表回归。沿用现有偏好，不访问业务数据库。"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', choices=['392QBFED2226B', 'R5CW20BLNKL'], required=True)
p.add_argument('--run', required=True)
p.add_argument('--cases', default='mixed,single,partial,offscreen,all')
p.add_argument('--texture', action='store_true', help='虚构页使用旧系统的窗口内纹理交接，不修改偏好')
p.add_argument('--exit-chrome', action='store_true', help='主体消失后按生产路径切换普通顶栏并退出选择顶栏')
a = p.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]+', a.run)
repo = Path(__file__).resolve().parents[3]
out = repo / 'tmp/selection-removal-validation' / a.run / a.serial
out.mkdir(parents=True, exist_ok=True)
exe = 'E:/AndroidSDK/platform-tools/adb.exe'
package = 'com.ywwynm.everythingdone'

def adb(*args):
    return subprocess.check_output([exe, '-s', a.serial, *map(str, args)], stderr=subprocess.STDOUT)

def pref_state():
    raw = adb('shell', 'run-as', package, 'cat', 'shared_prefs/EverythingDone_preferences.xml')
    tree = ET.fromstring(raw)
    selected = {e.get('name'): e.get('value', e.text) for e in tree
                if 'particle' in e.get('name', '') or e.get('name') == 'swipe_complete_animation'}
    try:
        tuning = ET.fromstring(adb('shell', 'run-as', package, 'cat', 'shared_prefs/particle_tuning.xml'))
        selected.update({e.get('name'): e.get('value', e.text) for e in tuning
                         if e.get('name') in ('particle_size_dp_index', 'flow_detail',
                                             'color_precision_standard', 'auto_performance_tuning')})
    except subprocess.CalledProcessError:
        pass  # 从未打开设置页时使用应用默认值。
    return selected

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/list-removal-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/list-removal-ui.xml'))

def tap(predicate, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        t = tree()
        found = next((e for e in t.iter('node') if predicate(e)), None)
        if found is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', found.get('bounds')))
            adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
            return
    raise AssertionError('未找到目标控件')

before = pref_state()
reports = []
for case in a.cases.split(','):
    run = a.run + '-' + case
    remote = f'/sdcard/Android/data/{package}/files/list-removal-probe/{run}'
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', package + '/.views.particledismiss.ThingListRemovalProbeActivity',
        '--es', 'run_id', run, '--es', 'case', case, '--ez', 'texture', str(a.texture).lower(),
        '--ez', 'exit_chrome', str(a.exit_chrome).lower())
    tap(lambda e: e.get('content-desc') == '执行虚构批量操作')
    recorder = None
    if case in ('mixed', 'partial'):
        recorder = subprocess.Popen([exe, '-s', a.serial, 'shell', 'screenrecord', '--time-limit', '8',
                                     '--bit-rate', '12000000', remote + '/capture.mp4'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    tap(lambda e: e.get('resource-id', '').endswith('/tv_confirm_as_bt_alert') and e.get('text') == '确定')
    result = None
    end = time.monotonic() + 18
    while time.monotonic() < end:
        try:
            result = json.loads(adb('shell', 'cat', remote + '/result.json'))
            break
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(.25)
    assert result is not None, f'{case} 未结束'
    (out / f'{case}.json').write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
    original = [-i if i % 4 == 0 else i for i in range(1, 19)]
    removed = {'single': {1}, 'offscreen': {18}, 'all': set(original)}.get(case, {1,3,-8,18})
    assert result['remaining'] == [i for i in original if i not in removed]
    assert result['suppressed'] is False
    last = result['samples'][-1]
    assert all(c['visible'] == 0 and c['alpha'] == 1 for c in last['cards']), last
    assert all(c['transitionAlpha'] == 1 for c in last.get('chrome', [])), '前景接管后未恢复'
    report = {'case': case, 'reflowMs': result['reflowMs'], 'remaining': len(result['remaining']),
              'maxLayers': max(s['layers'] for s in result['samples'])}
    reports.append(report)
    print(json.dumps(report), flush=True)
    if recorder:
        recorder.wait(timeout=15)
        adb('pull', remote + '/capture.mp4', out / f'{case}.mp4')
    adb('shell', 'input', 'keyevent', 'BACK')
after = pref_state()
assert before == after, '粒子相关偏好发生变化'
(out / 'summary.json').write_text(json.dumps({'cases': reports, 'settingsUnchanged': True,
                                             'settings': after}, ensure_ascii=False), encoding='utf-8')
print('PASS：全部场景完成，粒子设置保持原值', flush=True)
