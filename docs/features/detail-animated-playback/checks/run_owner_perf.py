"""授权设备上的同 APK A/B；安装前核实独立包名，原应用数据只读校验。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
ROOT = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').exists())
ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
PKG = 'com.ywwynm.everythingdone.gifownervalidation'
SERIAL, LABEL = sys.argv[1:3]
assert SERIAL in ('3B1629006YC00000', 'R5CW20BLNKL', '192.168.100.67:40807')
assert re.fullmatch(r'[a-zA-Z0-9_-]+', LABEL)
BASE = ROOT / 'tmp/gif-owner-perf-20261020'
OUT = BASE / LABEL
OUT.mkdir(exist_ok=False)
APK = BASE / 'fixture.apk'
apk_sha = hashlib.sha256(APK.read_bytes()).hexdigest()
badging = subprocess.run(['E:/AndroidSDK/build-tools/36.0.0/aapt.exe', 'dump', 'badging', str(APK)], capture_output=True, check=True).stdout.decode(errors='replace')
assert re.search(r"^package: name='" + re.escape(PKG) + "'", badging), '拒绝安装非独立包'

def adb(*args, timeout=60, data=None):
    return subprocess.run([ADB, '-s', SERIAL, *args], input=data, capture_output=True, timeout=timeout)

def hashes():
    r = adb('shell', 'run-as', 'com.ywwynm.everythingdone', 'sh', '-c', "'sha256sum databases/* shared_prefs/*'")
    assert r.returncode == 0, r.stderr.decode(errors='replace')
    return sorted(r.stdout.decode(errors='replace').splitlines())

def environment(path):
    result = {}
    for cmd in [('getprop', 'ro.product.model'), ('getprop', 'ro.build.version.sdk'), ('dumpsys', 'battery'), ('dumpsys', 'thermalservice'), ('dumpsys', 'display'), ('df', '-k', '/data')]:
        r = adb('shell', *cmd)
        result[' '.join(cmd)] = r.stdout.decode(errors='replace')
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')

before = hashes()
(OUT / 'original-before.json').write_text(json.dumps(before), encoding='utf-8')
environment(OUT / 'environment-before.json')
with (OUT / 'install.log').open('wb') as log:
    process = subprocess.Popen([ADB, '-s', SERIAL, 'install', '-r', str(APK)], stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 120
    while process.poll() is None and time.monotonic() < deadline:
        if SERIAL == '3B1629006YC00000' and b'InstallGuideActivity' in adb('shell', 'dumpsys', 'activity', 'activities').stdout:
            adb('shell', 'uiautomator', 'dump', '/sdcard/gif-owner-install.xml')
            raw = adb('shell', 'cat', '/sdcard/gif-owner-install.xml').stdout
            try:
                root = ET.fromstring(raw)
            except ET.ParseError:
                continue
            for node in root.iter('node'):
                if node.get('resource-id') == 'com.oplus.appdetail:id/btn_left' and node.get('text') == '继续安装':
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.5)
    assert process.wait(timeout=3) == 0
for permission in (() if SERIAL == '3B1629006YC00000' else ('READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO')):
    r = adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission)
    assert r.returncode == 0, r.stderr.decode(errors='replace')
policies = ['isolated', 'shared'] if SERIAL == 'R5CW20BLNKL' else ['shared', 'isolated']
if '--reverse' in sys.argv:
    policies.reverse()
if '--policy' in sys.argv:
    policies = [sys.argv[sys.argv.index('--policy') + 1]]
results = []
try:
    for policy in policies:
        run = LABEL + '-' + policy
        directory = OUT / policy
        directory.mkdir()
        adb('shell', 'am', 'force-stop', PKG)
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        environment(directory / 'environment-before.json')
        print('START', run, flush=True)
        trace_pid = None
        trace_remote = f'/data/misc/perfetto-traces/gif-owner-{run}.perfetto-trace'
        if '--trace' in sys.argv:
            config = '''buffers { size_kb: 4096 fill_policy: RING_BUFFER }
duration_ms: 720000
write_into_file: true
file_write_period_ms: 2500
flush_period_ms: 2500
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "android.packages_list" } }
'''
            (directory/'trace-config.pbtxt').write_text(config, encoding='utf-8')
            started = adb('shell', 'perfetto', '--background-wait', '--txt', '-c', '-', '-o', trace_remote, data=config.encode())
            (directory/'trace-start.txt').write_bytes(started.stdout + started.stderr)
            trace_pid = int(started.stdout.decode().strip().splitlines()[-1])
        with (directory / 'instrument.log').open('wb') as log, (directory / 'process.log').open('wb') as process_log:
            process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'run_id', run,
                '-e', 'policy', policy, '-e', 'large_home', 'true' if '--large-home' in sys.argv else 'false',
                '-e', 'after_only', 'true' if '--after-only' in sys.argv else 'false',
                '-e', 'require_mapped', 'true' if '--require-mapped' in sys.argv else 'false',
                PKG + '/com.ywwynm.everythingdone.detail.GifOwnerPerformanceProbe'], stdout=log, stderr=subprocess.STDOUT)
            collector = None
            try:
                for _ in range(100):
                    pids = adb('shell', 'pidof', PKG).stdout.decode().split()
                    if pids:
                        collector = subprocess.Popen([ADB, '-s', SERIAL, 'logcat', '--pid', pids[0], '-v', 'threadtime'], stdout=process_log, stderr=subprocess.STDOUT)
                        break
                    time.sleep(.2)
                deadline = time.monotonic() + 720
                previous = ''
                while process.poll() is None and time.monotonic() < deadline:
                    # 仅读取进度文件，计时期间不截屏、不遍历系统视图树。
                    progress = adb('shell', 'cat', f'/sdcard/Android/data/{PKG}/files/gif-owner/{run}/progress.json').stdout.decode().strip()
                    if progress and progress != previous:
                        print(run, progress, flush=True); previous = progress
                    time.sleep(5)
                if process.poll() is None:
                    adb('shell', 'am', 'force-stop', PKG)
                    raise TimeoutError(run)
                process.wait(timeout=3)
            finally:
                if collector:
                    collector.terminate(); collector.wait(timeout=5)
                if trace_pid is not None:
                    adb('shell', 'kill', '-INT', str(trace_pid))
                    time.sleep(3)
                    traced = adb('pull', trace_remote, str(directory/'trace.perfetto-trace'), timeout=90)
                    assert traced.returncode == 0, traced.stderr.decode(errors='replace')
                    adb('shell', 'rm', '-f', trace_remote)
        pulled = adb('pull', f'/sdcard/Android/data/{PKG}/files/gif-owner/{run}/.', str(directory), timeout=90)
        assert pulled.returncode == 0 and (directory / 'result.json').exists(), pulled.stderr.decode(errors='replace')
        environment(directory / 'environment-after.json')
        result = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
        log_text = (directory / 'process.log').read_text(encoding='utf-8', errors='replace')
        failures = {token: log_text.count(token) for token in ('FATAL EXCEPTION', 'OutOfMemoryError', 'Cannot draw recycled', 'Cannot start a recycled')}
        result['scenarioCompleted'] = result.get('ok', False)
        result['ok'] = result['scenarioCompleted'] and not any(failures.values())
        result.update(apkSha256=apk_sha, originalUnchanged=hashes() == before, runtimeFailures=failures)
        (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('DONE', run, 'ok=', result.get('ok'), 'originalUnchanged=', result['originalUnchanged'], 'error=', result.get('error'), flush=True)
        results.append(result)
        if not result.get('ok') or any(failures.values()):
            break
        time.sleep(20)
finally:
    after = hashes()
    (OUT / 'original-after.json').write_text(json.dumps(after), encoding='utf-8')
    environment(OUT / 'environment-after.json')
    adb('shell', 'am', 'force-stop', PKG)
    print('ORIGINAL_UNCHANGED', before == after, flush=True)
sys.exit(0 if len(results) == len(policies) and all(r.get('ok') and r['originalUnchanged'] and not any(r['runtimeFailures'].values()) for r in results) else 1)
