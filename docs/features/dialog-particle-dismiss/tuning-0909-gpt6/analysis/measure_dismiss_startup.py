"""真实语言弹窗四条关闭路径的启动耗时；确认时保持当前选项，不修改语言。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
p.add_argument('--output', required=True)
p.add_argument('--repeat', type=int, default=2)
p.add_argument('--max-first-ms', type=float)
p.add_argument('--quick-back', type=int, default=0, help='额外检查打开后立即返回；不等待 UI dump')
a = p.parse_args()
ROOT = Path(__file__).resolve().parents[1]
out = ROOT / a.output / a.serial
out.mkdir(parents=True, exist_ok=True)
ADB = ['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial]
PREFIX = 'com.ywwynm.everythingdone:id/'

def adb(*args):
    return subprocess.check_output(ADB + list(args), timeout=25).decode('utf-8', 'replace')

def dump(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/particle-startup.xml')
    raw = adb('shell', 'cat', '/sdcard/particle-startup.xml')
    (out / (name + '.xml')).write_text(raw, 'utf-8')
    return ET.fromstring(raw)

def node(tree, predicate):
    found = [n for n in tree.iter('node') if predicate(n)]
    assert len(found) == 1, len(found)
    return found[0]

def by_id(tree, value):
    return node(tree, lambda n: n.get('resource-id') == value)

def bounds(n):
    return list(map(int, re.findall(r'-?\d+', n.get('bounds'))))

def tap(n):
    l, t, r, b = bounds(n)
    adb('shell', 'input', 'tap', str((l+r)//2), str((t+b)//2))

adb('shell', 'am', 'start', '-W', '-n', 'com.ywwynm.everythingdone/.activities.ThingsActivity')
tap(node(dump('main'), lambda n: n.get('content-desc') in ['Open Navigation Drawer', '打开导航抽屉']))
tap(node(dump('drawer'), lambda n: n.get('text') in ['Settings', '设置']))
pid = adb('shell', 'pidof', 'com.ywwynm.everythingdone').strip().split()[0]
log_path = out / 'startup.log'
with log_path.open('wb') as log:
    capture = subprocess.Popen(ADB + ['logcat', '-T', '1', '--pid', pid, '-v', 'threadtime',
                                     '-s', 'ParticleMicroflake:I', '*:S'], stdout=log, stderr=log,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    results = []
    try:
        for round_number in range(a.repeat):
            for kind in ['outside', 'back', 'cancel', 'confirm']:
                tap(by_id(dump('settings'), PREFIX + 'll_app_language_as_bt'))
                tree = dump(f'{round_number}-{kind}-open')
                content = by_id(tree, 'android:id/content')
                previous = log_path.stat().st_size
                if kind == 'back':
                    adb('shell', 'input', 'keyevent', '4')
                elif kind == 'outside':
                    l, t, r, b = bounds(content)
                    assert t > 80
                    # 超过 Dialog Window 的外部触摸容差；仍在应用可触及的背景范围内。
                    distance = min((r-l)*.14, t*.55)
                    adb('shell', 'input', 'tap', str((l+r)//2), str(round(t-distance)))
                else:
                    tap(by_id(tree, PREFIX + f'tv_{kind}_as_bt_fragment_chooser'))
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    raw = log_path.read_bytes()[previous:].decode('utf-8', 'replace')
                    if '完成 count=' in raw:
                        break
                    time.sleep(.05)
                else:
                    raise AssertionError((kind, '动画没有完成', raw))
                start = next(line for line in raw.splitlines() if '启动阶段 ' in line)
                values = {k: float(v) for k, v in re.findall(r'(\w+Ms)=([\d.]+)', start)}
                presented = next((line for line in raw.splitlines() if '首帧合成 ' in line), '')
                values.update({k: float(v) for k,v in re.findall(r'(\w+Ms)=([\d.]+)', presented)})
                assert 'requestToFirstMs' in values
                results.append(dict(round=round_number, trigger=kind, **values, log=raw))
                print(kind, values, flush=True)
                by_id(dump(f'{round_number}-{kind}-closed'), PREFIX + 'll_app_language_as_bt')
                hierarchy = adb('shell', 'dumpsys', 'activity', 'com.ywwynm.everythingdone/.activities.SettingsActivity')
                assert 'ParticleDismissOverlay{' not in hierarchy
        for i in range(a.quick_back):
            # 等输入焦点移交给新 Dialog 即返回；不等待几秒钟的完整 UI dump。
            target = by_id(dump('quick-settings'), PREFIX + 'll_app_language_as_bt')
            def focus():
                return next(line.strip() for line in adb('shell', 'dumpsys', 'window').splitlines()
                            if 'mCurrentFocus=' in line)
            old_focus = focus()
            previous = log_path.stat().st_size
            tap(target)
            focus_deadline = time.monotonic() + 3
            while time.monotonic() < focus_deadline:
                current_focus = focus()
                if current_focus != old_focus and 'com.ywwynm.everythingdone' in current_focus:
                    break
            else:
                raise AssertionError('新 Dialog 未接到输入焦点，不能用返回键测其关闭动画')
            adb('shell', 'input', 'keyevent', '4')
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                raw = log_path.read_bytes()[previous:].decode('utf-8', 'replace')
                if '完成 count=' in raw:
                    break
                time.sleep(.05)
            else:
                raise AssertionError(('quick-back', i, '动画没有完成', raw))
            start = next(line for line in raw.splitlines() if '启动阶段 ' in line)
            values = {k: float(v) for k, v in re.findall(r'(\w+Ms)=([\d.]+)', start)}
            assert 'requestToFirstMs' in values
            results.append(dict(round=i, trigger='quick-back', **values, log=raw))
            print('quick-back', values, flush=True)
            by_id(dump(f'quick-{i}-closed'), PREFIX + 'll_app_language_as_bt')
            hierarchy = adb('shell', 'dumpsys', 'activity', 'com.ywwynm.everythingdone/.activities.SettingsActivity')
            assert 'ParticleDismissOverlay{' not in hierarchy
    finally:
        capture.terminate()
        capture.wait(timeout=10)
        (out / 'startup.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), 'utf-8')
adb('shell', 'input', 'keyevent', '4')
by_id(dump('final'), PREFIX + 'act_search')
if a.max_first_ms is not None:
    assert max(item['requestToFirstMs'] for item in results) <= a.max_first_ms, results
