"""撤回版本的三机复测；只复用采集方法，不重新加入已撤回的应用插桩。"""
import argparse
import concurrent.futures
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

import recheck_20261009 as r
import verify_swipe_response_20261009 as horizontal
import verify_home_cancel_20261009 as home_cancel

r.OUT = r.ROOT / 'tmp/home-list-scroll-20261009-rollback'
APK = r.ROOT / 'app/build/outputs/update-debug-apk/app-debug-202610090451.apk'
EXPECTED_SHA = 'd7f729b61ad9ac33283edc07e91c6367651b6054dcc144fe95a5eae9361bd10b'


def environment(name, label):
    r.save(r.OUT / name / (label + '-environment.txt'), r.sh(r.DEVICES[name],
        'dumpsys thermalservice; dumpsys battery; df /data; dumpsys display'))


def inventory(name):
    if (r.OUT / name / 'original-particle_tuning.xml').exists():
        raise RuntimeError('本轮备份已经存在，拒绝覆盖')
    r.inventory(name)
    raw = (r.OUT / name / ('original-' + r.PREFS)).read_bytes()
    values = {n.get('name'): n.get('value', n.text) for n in ET.fromstring(raw)}
    if values.get('swipe_complete_animation') != '1':
        raise RuntimeError('当前不是粒子风格，需单独安排临时设置与恢复')
    print(json.dumps(dict(device=name, swipe_particle=True, fresh_backup=True)), flush=True)


def install(name):
    assert hashlib.sha256(APK.read_bytes()).hexdigest() == EXPECTED_SHA
    assert (r.OUT / name / 'original-particle_tuning.xml').exists()
    s = r.DEVICES[name]
    result = r.adb(s, 'install', '-r', APK, timeout=180).decode().strip()
    if 'Success' not in result:
        raise RuntimeError(result)
    path = r.sh(s, 'pm path ' + r.PKG).strip().split('package:')[-1]
    actual = r.sh(s, 'sha256sum ' + path).split()[0]
    assert actual == EXPECTED_SHA, '设备 APK 与发布 APK 不一致'
    r.save(r.OUT / name / 'rollback-package.txt', r.sh(s, 'dumpsys package ' + r.PKG))
    r.save(r.OUT / name / 'rollback-apk-sha256.txt', actual)
    print(json.dumps(dict(device=name, installed='202610090451', sha256=actual)), flush=True)


def vertical(name, label):
    for style in ['flow', 'drift']:
        environment(name, label + '-' + style)
        r.collect(name, style, run=label, count=6)


def swipe(name, label):
    # run_case 中旧探针缺失 activeOverlays 时的默认值不构成覆盖层验证证据。
    horizontal.run_device(name, label, extended=False)
    for style in ['flow', 'drift']:
        for case in ['direct', 'direct2', 'held', 'fast', 'reverse']:
            out = r.OUT / name / ('horizontal-' + label + '-' + style) / case
            row = json.loads((out / 'summary.json').read_text())
            data = json.loads((out / 'result.json').read_text())
            log = (out / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
            assert 'FATAL EXCEPTION' not in log and '样式回退' not in log
            row['overlay_count_measured'] = 'activeOverlays' in data['frames'][-1]
            row['drift_nonzero_measured'] = False
            first = re.search(r'飘散首帧上屏 requestToVisibleMs=([\d.]+)', log)
            row['drift_request_to_first_frame_ms'] = float(first[1]) if first else None
            r.save(out / 'summary.json', json.dumps(row))


def verify_settings(name):
    s = r.DEVICES[name]
    r.sh(s, 'am force-stop ' + r.PKG)
    checked = {}
    for file in ['particle_tuning.xml', r.PREFS]:
        original = (r.OUT / name / ('original-' + file)).read_bytes()
        actual = r.adb(s, 'exec-out', 'run-as', r.PKG, 'cat', 'shared_prefs/' + file)
        r.save(r.OUT / name / ('final-' + file), actual)
        old = {n.get('name'): (n.tag, n.get('value', n.text)) for n in ET.fromstring(original)}
        new = {n.get('name'): (n.tag, n.get('value', n.text)) for n in ET.fromstring(actual)}
        changed = [k for k in sorted(old.keys() | new.keys()) if old.get(k) != new.get(k)]
        checked[file] = dict(bytes_equal=actual == original, changed_keys=changed)
        if file == 'particle_tuning.xml':
            assert actual == original, '粒子偏好未恢复'
        else:
            assert not [k for k in changed if 'last_alarm_rebuild' not in k], changed
    r.sh(s, 'am start -W -n ' + r.PKG + '/.activities.ThingsActivity')
    tree = r.dump(s, r.OUT / name / 'final-ui.xml')
    assert r.cards(tree), '结束时不在首页记事列表'
    setting_queries = [
        'settings get secure long_press_timeout',
        'settings get system accelerometer_rotation',
        'settings get system user_rotation',
        'settings get system peak_refresh_rate',
        'settings get system min_refresh_rate',
        'settings get global animator_duration_scale',
    ]
    before = (r.OUT / name / 'initial-environment.txt').read_text().strip().splitlines()[-6:]
    after = r.sh(s, '; '.join(setting_queries)).strip().splitlines()
    assert before == after, '系统设置与本轮初值不一致'
    checked['system_settings_equal'] = True
    r.save(r.OUT / name / 'settings-verification.json', json.dumps(checked, indent=2))
    print(json.dumps(dict(device=name, settings=checked, home_visible=True)), flush=True)


def cleanup(name):
    s = r.DEVICES[name]
    assert (r.OUT / name / 'settings-verification.json').exists(), '先完成偏好核验'
    # 仅删除本轮明确创建的探针目录与三个设备临时文件。
    base = '/sdcard/Android/data/' + r.PKG + '/files/swipe-shadow-probe/'
    directories = [base + 'hls09-rollback-' + style + '-' + case
                   for style in ['flow', 'drift']
                   for case in ['direct', 'direct2', 'held', 'fast', 'reverse']]
    for path in directories:
        assert path.startswith(base + 'hls09-rollback-')
        r.sh(s, 'rm -rf ' + path)
        r.sh(s, 'test ! -e ' + path)
    files = ['/data/local/tmp/hls09_input.jar', '/data/local/tmp/hls09_window.xml',
             '/data/local/tmp/hls09_initial.png']
    for path in files:
        r.sh(s, 'rm -f ' + path)
        r.sh(s, 'test ! -e ' + path)
    r.save(r.OUT / name / 'cleanup.json', json.dumps(dict(probe_directories=10, temporary_files=3)))
    print(json.dumps(dict(device=name, cleanup=True)), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['inventory', 'install', 'vertical', 'horizontal', 'home', 'settings', 'cleanup'])
    p.add_argument('--label', default='rollback')
    p.add_argument('--device', choices=list(r.DEVICES))
    a = p.parse_args()
    (r.OUT / 'driver').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(r.ROOT / 'tmp/home-list-scroll-20261009-optimized/driver/input.jar', r.OUT / 'driver/input.jar')
    fn = {'inventory': inventory, 'install': install,
          'vertical': lambda n: vertical(n, a.label), 'horizontal': lambda n: swipe(n, a.label),
          'home': home_cancel.run, 'settings': verify_settings, 'cleanup': cleanup}[a.action]
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        jobs = {ex.submit(fn, n): n for n in ([a.device] if a.device else r.DEVICES)}
        for job in concurrent.futures.as_completed(jobs):
            try:
                job.result()
            except Exception as error:
                errors.append((jobs[job], repr(error)))
                print('ERROR', jobs[job], repr(error), flush=True)
    if errors:
        raise RuntimeError(errors)


if __name__ == '__main__':
    main()
