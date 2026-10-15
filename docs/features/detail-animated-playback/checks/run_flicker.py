"""只在本轮授权设备安装隔离 APK，保存逐帧记录并校验原应用数据。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[4]
ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
PKG = 'com.ywwynm.everythingdone.gifflickervalidation'
SERIAL, RUN = sys.argv[1:3]
assert SERIAL in ('3B1629006YC00000', 'R5CW20BLNKL', '192.168.100.67:40807')
assert re.fullmatch(r'[a-zA-Z0-9_-]+', RUN)
OUT = ROOT / 'tmp/detail-gif-flicker' / RUN
OUT.mkdir(exist_ok=False)
APK = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
if '--apk' in sys.argv:
    APK = Path(sys.argv[sys.argv.index('--apk') + 1]).resolve()
apk_sha256 = hashlib.sha256(APK.read_bytes()).hexdigest()
badging = subprocess.run(['E:/AndroidSDK/build-tools/36.0.0/aapt.exe', 'dump', 'badging', str(APK)], capture_output=True, check=True).stdout.decode(errors='replace')
assert re.search(r"^package: name='" + re.escape(PKG) + "'", badging), '拒绝安装非隔离包'

def adb(*args, timeout=45):
    return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, timeout=timeout)

def hashes():
    for _ in range(3):
        r = adb('shell', 'run-as', 'com.ywwynm.everythingdone', 'sh', '-c', "'sha256sum databases/* shared_prefs/*'")
        if r.returncode == 0:
            break
        time.sleep(.5)
    return dict(code=r.returncode, text=r.stdout.decode(errors='replace'))

before = hashes()
(OUT / 'original-before.json').write_text(json.dumps(before), encoding='utf-8')
with (OUT / 'install.log').open('wb') as log:
    process = subprocess.Popen([ADB, '-s', SERIAL, 'install', '-r', str(APK)], stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 90
    while process.poll() is None and time.monotonic() < deadline:
        if SERIAL == '3B1629006YC00000' and b'InstallGuideActivity' in adb('shell', 'dumpsys', 'activity', 'activities').stdout:
            adb('shell', 'uiautomator', 'dump', '/sdcard/gif-flicker.xml')
            root = ET.fromstring(adb('shell', 'cat', '/sdcard/gif-flicker.xml').stdout)
            for node in root.iter('node'):
                if node.get('resource-id') == 'com.oplus.appdetail:id/btn_left' and node.get('text') == '继续安装':
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.4)
    assert process.wait(timeout=3) == 0
adb('shell', 'am', 'force-stop', PKG)
for permission in (() if SERIAL == '3B1629006YC00000' else ('READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO')):
    grant = adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission)
    assert grant.returncode == 0, grant.stderr.decode(errors='replace')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
with (OUT / 'instrument.log').open('wb') as log, (OUT / 'process.log').open('wb') as process_log:
    process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'run_id', RUN,
        '-e', 'extended', 'true' if '--extended' in sys.argv else 'false',
        '-e', 'large', 'true' if '--large' in sys.argv else 'false',
        '-e', 'sequential', 'true' if '--sequential' in sys.argv else 'false',
        '-e', 'complex', 'true' if '--complex' in sys.argv else 'false',
        '-e', 'queue', 'true' if '--queue' in sys.argv else 'false',
        '-e', 'fullspan', 'true' if '--fullspan' in sys.argv else 'false',
        '-e', 'boundary', 'true' if '--boundary' in sys.argv else 'false',
        PKG + '/com.ywwynm.everythingdone.detail.DetailGifFlickerProbe'], stdout=log, stderr=subprocess.STDOUT)
    collector = None
    recorder = None
    recording_path = f'/sdcard/gif-flicker-{RUN}.mp4'
    try:
        if '--record' in sys.argv:
            width, height = map(int, re.search(r'Physical size: (\d+)x(\d+)',
                adb('shell', 'wm', 'size').stdout.decode()).groups())
            recording_height = int(height * 720 / width) // 2 * 2
            recorder = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'sh', '-c',
                f"'screenrecord --size 720x{recording_height} --bit-rate 5000000 --time-limit 160 {recording_path} & recorder=$!; echo $recorder; wait $recorder'"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            recorder_pid = recorder.stdout.readline().decode().strip()
            assert recorder_pid.isdigit(), '未获得本轮录屏进程号'
        for _ in range(50):
            pid = adb('shell', 'pidof', PKG).stdout.decode().strip().split()
            if pid:
                collector = subprocess.Popen([ADB, '-s', SERIAL, 'logcat', '--pid', pid[0], '-v', 'threadtime'], stdout=process_log, stderr=subprocess.STDOUT)
                break
            time.sleep(.2)
        deadline = time.monotonic() + (420 if '--boundary' in sys.argv else 300 if '--complex' in sys.argv else 180)
        while process.poll() is None and time.monotonic() < deadline:
            if '--sequential' not in sys.argv and '--boundary' not in sys.argv and SERIAL == '3B1629006YC00000' and any(b'permissioncontroller' in line and b'topResumedActivity' in line
                    for line in adb('shell', 'dumpsys', 'activity', 'activities').stdout.splitlines()):
                adb('shell', 'uiautomator', 'dump', '/sdcard/gif-flicker.xml')
                raw = adb('shell', 'cat', '/sdcard/gif-flicker.xml').stdout
                try:
                    root = ET.fromstring(raw)
                except ET.ParseError:
                    continue
                for node in root.iter('node'):
                    if node.get('resource-id', '').endswith((':id/permission_allow_all_button', ':id/permission_allow_button')):
                        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        break
            time.sleep(.5)
        process.wait(timeout=3)
    finally:
        if collector:
            collector.terminate()
            collector.wait(timeout=5)
        if recorder:
            adb('shell', 'kill', '-2', recorder_pid)
            recorder.wait(timeout=15)
            adb('pull', recording_path, str(OUT / 'screen.mp4'), timeout=90)
            adb('shell', 'rm', '-f', recording_path)
for _ in range(3):
    pulled = adb('pull', f'/sdcard/Android/data/{PKG}/files/gif-flicker/{RUN}/.', str(OUT), timeout=90)
    if pulled.returncode == 0 and (OUT / 'result.json').exists():
        break
    time.sleep(.5)
assert pulled.returncode == 0 and (OUT / 'result.json').exists(), pulled.stderr.decode(errors='replace')
after = hashes()
(OUT / 'original-after.json').write_text(json.dumps(after), encoding='utf-8')
unchanged = before['code'] == after['code'] == 0 and before['text'] == after['text']
result = json.loads((OUT / 'result.json').read_text(encoding='utf-8'))
if any(not all(value for key, value in event.items() if key in ('same', 'stopped', 'passed'))
       for event in result.get('events', [])):
    result['ok'] = False
process_log = (OUT / 'process.log').read_text(encoding='utf-8', errors='replace')
runtime_failures = {token: process_log.count(token) for token in (
    'FATAL EXCEPTION', 'OutOfMemoryError', 'Cannot draw recycled', 'Cannot start a recycled')}
if any(runtime_failures.values()):
    result['ok'] = False
result.update(originalUnchanged=unchanged, apkSha256=apk_sha256, runtimeFailures=runtime_failures)
(OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(RUN, result)
sys.exit(0 if result.get('ok') and unchanged else 1)
