"""真机驱动小工具：截图（md5 校验）、dump、按 id 找控件、motionevent 手势、录屏。"""
import hashlib
import os
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
SERIAL = os.environ.get('SERIAL', 'R5CW20BLNKL')
PKG = 'com.ywwynm.everythingdone'
# 截图与录屏的落点：环境变量 TAP_OUT，缺省为模拟器的 out/device
OUT = os.environ.get('TAP_OUT') or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'out', 'device')
os.makedirs(OUT, exist_ok=True)


def adb(*args, check=True, capture=True, input_bytes=None):
    r = subprocess.run([ADB, '-s', SERIAL] + list(args), capture_output=capture, input=input_bytes)
    if check and r.returncode != 0:
        raise RuntimeError('adb %s failed: %s' % (' '.join(args), r.stderr.decode('utf-8', 'replace')))
    return r.stdout.decode('utf-8', 'replace') if capture else ''


def shell(cmd, check=True):
    return adb('shell', cmd, check=check)


def screencap(name):
    remote = '/sdcard/tapcap_%d.png' % random.randint(0, 10**9)
    shell('screencap -p %s' % remote)
    remote_hash = shell('md5sum %s' % remote).split()[0]
    dst = os.path.join(OUT, name)
    for _ in range(4):
        adb('pull', remote, dst)
        with open(dst, 'rb') as f:
            local_hash = hashlib.md5(f.read()).hexdigest()
        if local_hash == remote_hash:
            break
    else:
        raise RuntimeError('screencap md5 mismatch for ' + name)
    shell('rm -f %s' % remote, check=False)
    return dst


def dump():
    for _ in range(6):
        remote = '/sdcard/tapdump_%d.xml' % random.randint(0, 10**9)
        shell('uiautomator dump %s' % remote, check=False)
        text = shell('cat %s' % remote, check=False)
        shell('rm -f %s' % remote, check=False)
        if text.strip().startswith('<?xml'):
            try:
                return ET.fromstring(text)
            except ET.ParseError:
                pass
        time.sleep(0.4)
    raise RuntimeError('uiautomator dump failed')


def bounds(node):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds'))
    return tuple(int(v) for v in m.groups())


def find(tree, rid=None, text=None, desc=None):
    for n in tree.iter('node'):
        if rid is not None and not (n.get('resource-id') or '').endswith('/' + rid):
            continue
        if text is not None and text not in (n.get('text') or ''):
            continue
        if desc is not None and desc not in (n.get('content-desc') or ''):
            continue
        return n
    return None


def poll(rid=None, text=None, desc=None, tries=20):
    for _ in range(tries):
        tree = dump()
        n = find(tree, rid, text, desc)
        if n is not None:
            return n
        time.sleep(0.3)
    raise RuntimeError('not found: rid=%s text=%s desc=%s' % (rid, text, desc))


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(x, y):
    shell('input tap %d %d' % (x, y))


def drag(x1, y1, x2, y2, steps=20, step_ms=16, hold_ms=0):
    """motionevent 拖动：按下、逐步移动、抬起。"""
    shell('input motionevent DOWN %d %d' % (x1, y1))
    if hold_ms:
        time.sleep(hold_ms / 1000)
    for k in range(1, steps + 1):
        x = x1 + (x2 - x1) * k // steps
        y = y1 + (y2 - y1) * k // steps
        shell('input motionevent MOVE %d %d' % (x, y))
        time.sleep(step_ms / 1000)
    shell('input motionevent UP %d %d' % (x2, y2))


def record_start(name, seconds, bitrate='12000000'):
    remote = '/sdcard/taprec_%s.mp4' % name
    shell('rm -f %s' % remote, check=False)
    p = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'screenrecord', '--time-limit', str(seconds),
                          '--bit-rate', bitrate, remote])
    time.sleep(0.8)
    return p, remote


def record_finish(p, remote, name):
    p.wait()
    time.sleep(1.0)
    remote_hash = shell('md5sum %s' % remote).split()[0]
    dst = os.path.join(OUT, name + '.mp4')
    for _ in range(4):
        adb('pull', remote, dst)
        with open(dst, 'rb') as f:
            if hashlib.md5(f.read()).hexdigest() == remote_hash:
                break
    else:
        raise RuntimeError('record md5 mismatch')
    shell('rm -f %s' % remote, check=False)
    return dst


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'cap':
        print(screencap(sys.argv[2]))
    elif cmd == 'ids':
        tree = dump()
        for n in tree.iter('node'):
            rid = n.get('resource-id') or ''
            if rid:
                print(rid.split('/')[-1], n.get('text'), n.get('bounds'))
