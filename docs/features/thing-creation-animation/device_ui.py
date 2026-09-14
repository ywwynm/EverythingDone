"""指定设备实机回归工具；坐标取自当前 UI 层级，产物放入忽略目录 tmp。"""
import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
SERIAL = os.environ.get('THING_ANIMATION_SERIAL', '9018f404')
if SERIAL not in ('9018f404', 'R5CW20BLNKL'):
    raise ValueError('Device is outside the explicitly authorized test scope')
PACKAGE = 'com.ywwynm.everythingdone'
ROOT = Path(__file__).resolve().parents[3] / 'tmp/thing-animation-device'
if os.environ.get('THING_ANIMATION_RUN'):
    run = os.environ['THING_ANIMATION_RUN']
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', run):
        raise ValueError('Invalid test output directory')
    ROOT = ROOT / run / SERIAL
ROOT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.run([ADB, '-s', SERIAL, *map(str, args)], capture_output=True, check=True).stdout


def dump(name='current'):
    adb('shell', 'uiautomator', 'dump', '/sdcard/thing-animation-ui.xml')
    raw = adb('shell', 'cat', '/sdcard/thing-animation-ui.xml')
    (ROOT / f'{name}.xml').write_bytes(raw)
    return ET.fromstring(raw)


def find(tree, attr, value):
    if attr == 'resource-id' and ':' not in value:
        value = PACKAGE + ':id/' + value
    found = [n for n in tree.iter('node') if n.get(attr) == value]
    if len(found) != 1:
        raise RuntimeError(f'Expected one {attr}={value}, found {len(found)}')
    return found[0]


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2


def wait_for(value):
    for _ in range(8):
        tree = dump()
        if any(value in n.get(k, '') for n in tree.iter('node') for k in ('text', 'resource-id', 'content-desc')):
            return tree
        time.sleep(.15)
    raise RuntimeError('UI did not reach ' + value)


def capture(name):
    adb('shell', 'screencap', '-p', '/sdcard/thing-animation-screen.png')
    adb('pull', '/sdcard/thing-animation-screen.png', ROOT / (name + '.png'))


def summary(tree):
    return [{k: n.get(k) for k in ('text', 'resource-id', 'content-desc', 'bounds', 'checked')}
            for n in tree.iter('node') if n.get('clickable') == 'true' or n.get('checkable') == 'true']


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['dump', 'tap', 'back', 'capture', 'prefs', 'scroll', 'record-tap'])
    p.add_argument('--id'); p.add_argument('--text'); p.add_argument('--desc')
    p.add_argument('--expect'); p.add_argument('--name', default='current')
    a = p.parse_args()
    if a.action == 'prefs':
        raw = adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/EverythingDone_preferences.xml')
        selected = [dict(n.attrib) for n in ET.fromstring(raw) if 'animation' in n.get('name', '')]
        (ROOT / (a.name + '-animation-settings.json')).write_text(json.dumps(selected, indent=2), encoding='utf-8')
        print(json.dumps(selected))
    else:
        recording = None
        frames = None
        if a.action in ('tap', 'record-tap'):
            attr, value = next((k, v) for k, v in [('resource-id', a.id), ('text', a.text), ('content-desc', a.desc)] if v)
            x, y = center(find(dump(), attr, value))
            if a.action == 'record-tap':
                folder = ROOT / a.name
                folder.mkdir(exist_ok=True)
                def capture_frames():
                    timings = []
                    start = time.perf_counter()
                    while time.perf_counter() - start < 4:
                        before = time.perf_counter() - start
                        raw = adb('exec-out', 'screencap', '-p')
                        if not raw.startswith(b'\x89PNG'):
                            raise RuntimeError('Invalid screenshot')
                        (folder / f'{len(timings):03d}.png').write_bytes(raw)
                        timings.append([before, time.perf_counter()-start])
                    (folder / 'timings.json').write_text(json.dumps(timings))
                    return len(timings)
                recording = ThreadPoolExecutor(1)
                frames = recording.submit(capture_frames)
                time.sleep(.25)
            adb('shell', 'input', 'tap', x, y)
        elif a.action == 'back':
            adb('shell', 'input', 'keyevent', 4)
        elif a.action == 'scroll':
            nodes = [n for n in dump().iter('node') if n.get('scrollable') == 'true']
            if len(nodes) != 1:
                raise RuntimeError(f'Expected one scroll container, found {len(nodes)}')
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
            adb('shell', 'input', 'swipe', (x1+x2)//2, y2-80, (x1+x2)//2, y1+80, 500)
        if recording:
            print('Captured frames:', frames.result(timeout=15))
            recording.shutdown()
        tree = wait_for(a.expect) if a.expect else dump(a.name)
        if a.action == 'capture':
            capture(a.name)
        print(json.dumps(summary(tree), ensure_ascii=True))
