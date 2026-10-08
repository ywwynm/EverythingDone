"""真实点击新建的完整计时：按下/抬手 → 首页快照 → Activity → 首次粒子上屏。

沿用只读观察器的 ParticleLaunchTrace 与出帧统计，不改变计时起点或驱动业务。
只打开空白编辑页后返回，核对记事数；偏好及诊断开关在 finally 中恢复。
"""
import argparse
import json
import re
import statistics
import subprocess
import time

import drift_driver as d
import perf_compare as pc

ENABLED = 'files/thing-animation-performance-enabled'
CONTROL = 'files/thing-animation-performance-control.json'
REMOTE = '/sdcard/Android/data/%s/files/thing-animation-performance/' % d.PACKAGE


def write_private(path, value):
    if value is None:
        d.adb('shell', 'run-as', d.PACKAGE, 'rm', '-f', path)
    else:
        subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'run-as', d.PACKAGE, 'sh', '-c',
                        "'cat > %s'" % path], input=value, capture_output=True, check=True)


def control(value):
    write_private(CONTROL, json.dumps(value).encode())


def await_json(path, accept=lambda value: True, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = d.soft('shell', 'cat', path)
        if result.returncode == 0:
            try:
                value = json.loads(result.stdout)
                if accept(value):
                    return value
            except json.JSONDecodeError:
                pass
        time.sleep(.2)
    raise RuntimeError('观察器未返回有效结果：' + path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--rounds', type=int, default=4)
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,60}', args.run)
    folder = d.OUT / args.run
    folder.mkdir(parents=True, exist_ok=True)
    prefs = pc.run_as('cat %s' % pc.PREFS)
    assert prefs.strip().startswith('<?xml')
    original = {}
    for path in (ENABLED, CONTROL):
        result = d.soft('shell', 'run-as', d.PACKAGE, 'cat', path)
        original[path] = result.stdout if result.returncode == 0 else None
    rotation = d.rotation_state()
    rows = []
    try:
        pc.set_style(prefs, 'drift')
        write_private(ENABLED, b'1')
        control({})
        d.launch()
        for index in range(args.rounds):
            # 新标签避免读到上一次执行残留；不删除任何现有测量目录。
            run = '%s-%d-%d' % (args.run, index, time.time_ns() // 1000000)
            control(dict(run_id=run, kind='create', title='', mode=3,
                         driver='system-input', beatTrace=False, stallSampling=False))
            await_json(REMOTE + run + '/progress.json', lambda value: value.get('ready'))
            time.sleep(.3)
            d.soft('logcat', '-c')
            pc.creation_round()
            control({})
            result = await_json(REMOTE + run + '/result.json')
            (folder / ('observer-%d.json' % index)).write_text(json.dumps(result), 'utf-8')
            log = d.adb('logcat', '-d', '-v', 'time', '-s', 'ParticlePacing:I', 'ParticleDrift:I', 'ParticleMicroflake:I').decode('utf-8', 'replace')
            (folder / ('logcat-%d.txt' % index)).write_text(log, 'utf-8')
            launches = {mark['label']: mark['at'] for mark in result['launchTrace']}
            overlay = next(item for item in result['overlays'] if item.get('creationPage'))
            first = [float(m.group(3)) for m in pc.LINE.finditer(log) if m.group(2) == '出现']
            assert len(first) == 1, first
            fab = launches['fab-up']
            down = max(item[0] for item in result['inputs'] if item[1] == 0 and item[2] == 0 and item[0] <= fab)
            click_first = (overlay['requestAt'] - fab) / 1e6 + first[0]
            row = dict(round=index, clickToFirstMs=click_first,
                       downToFirstMs=click_first + (fab - down) / 1e6,
                       homeCaptureMs=(launches['home-capture'] - launches['home-capture-request']) / 1e6,
                       activityToRequestMs=(overlay['requestAt'] - launches['start-activity']) / 1e6,
                       requestToFirstMs=first[0],
                       trace={label: (at - fab) / 1e6 for label, at in launches.items()})
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
    finally:
        pc.write_prefs(prefs)
        for path, value in original.items():
            write_private(path, value)
        restored = pc.run_as('cat %s' % pc.PREFS) == prefs
        rotation_after = d.rotation_state()
    warm = rows[1:] or rows
    summary = {key: statistics.median(row[key] for row in warm) for key in (
        'clickToFirstMs', 'downToFirstMs', 'homeCaptureMs', 'activityToRequestMs', 'requestToFirstMs')}
    (folder / 'result.json').write_text(json.dumps(dict(rows=rows, warmMedian=summary,
        prefsRestored=restored, rotationBefore=rotation, rotationAfter=rotation_after), ensure_ascii=False, indent=2), 'utf-8')
    print('热运行中位数', json.dumps(summary, ensure_ascii=False), flush=True)
    print('偏好恢复', restored, '旋转设置', rotation, rotation_after, flush=True)


if __name__ == '__main__':
    main()
