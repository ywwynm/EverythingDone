"""指定设备上的隔离包验证；正式应用只读取数据库摘要，不启动或改写。"""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

SERIALS = ('3B1629006YC00000', 'R5CW20BLNKL', '192.168.100.67:40807')
PACKAGE = 'com.ywwynm.everythingdone.directionvalidation'
REAL_PACKAGE = 'com.ywwynm.everythingdone'
ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
ROOT = Path(__file__).resolve().parents[4]
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True, choices=SERIALS)
p.add_argument('--run', required=True)
p.add_argument('--phase', choices=('directions', 'batch', 'all'), default='all')
p.add_argument('--style', choices=('flow', 'drift', 'both'), default='both')
p.add_argument('--install', action='store_true')
a = p.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]{1,60}', a.run)
out = ROOT / 'tmp/default-directions-20261019' / a.run / a.serial.replace(':', '_')
out.mkdir(parents=True, exist_ok=True)


def adb(*arguments, timeout=45):
    try:
        return subprocess.check_output([ADB, '-s', a.serial, *map(str, arguments)], timeout=timeout,
                                       stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        print(error.output.decode(errors='replace'), flush=True)
        raise


def real_digest():
    return adb('shell', 'run-as', REAL_PACKAGE, 'sha256sum',
               'databases/EverythingDoneData.db').decode().split()[0]


def instrument(name, remote_root, tag, extras):
    target = f'{PACKAGE}/com.ywwynm.everythingdone.views.particledismiss.{name}'
    args = [ADB, '-s', a.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'run_id', tag]
    for key, value in extras.items():
        args += ['-e', key, str(value)]
    args.append(target)
    print(f'{a.serial} START {tag}', flush=True)
    with (out / f'{tag}.log').open('wb') as log:
        process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)
        started = time.monotonic()
        while process.poll() is None:
            if time.monotonic() - started > 600:
                process.terminate()
                raise TimeoutError(tag)
            time.sleep(1)
    remote = f'/sdcard/Android/data/{PACKAGE}/files/{remote_root}/{tag}'
    adb('pull', remote, out / tag, timeout=90)
    result = json.loads((out / tag / 'result.json').read_text(encoding='utf-8'))
    assert result.get('ok'), result.get('error', result)
    if remote_root == 'default-directions':
        by_case = {}
        for row in result['animations']:
            assert row.get('released'), row
            assert row['style'] == extras['style'], row
            by_case.setdefault(row['case'], set()).add(round(row['angle']) % 360)
        print(f'{a.serial} PASS {tag}: {len(result["animations"])} animations {by_case}', flush=True)
    else:
        assert result['cardDirections'] and set(result['cardDirections']) <= {90, 135}, result.get('cardDirections')
        print(f'{a.serial} PASS {tag}: direction={result["cardDirections"]}, frames={result["cardParticleFrames"]}', flush=True)
    return result


summary = {'serial': a.serial, 'package': PACKAGE, 'runs': []}
summary['realDatabaseBefore'] = real_digest()
(out / 'before.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
try:
    if a.install:
        apk = ROOT / 'tmp/default-directions-20261019/direction-validation.apk'
        summary['apkSha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(a.serial, adb('install', '-r', apk, timeout=180).decode().strip(), flush=True)
    styles = ('drift', 'flow') if a.style == 'both' else (a.style,)
    for style in styles:
        if a.phase in ('directions', 'all'):
            tag = f'{a.run}-{style}-directions'
            result = instrument('ParticleDefaultDirectionProbe', 'default-directions', tag, {'style': style})
            summary['runs'].append({'tag': tag, 'animations': result['animations']})
        if a.phase in ('batch', 'all'):
            cases = [(action, selection)
                     for action in ('finish', 'delete', 'restore', 'untrash', 'forever')
                     for selection in ('single', 'mixed')]
            for action, selection in cases:
                tag = f'{a.run}-{style}-{action}-{selection}'
                result = instrument('SelectionBatchTransitionProbe', 'batch-transition', tag,
                                    {'style': style, 'action': action, 'selection': selection,
                                     'check_directions': 'true',
                                     'screenshots': 'true' if action == 'finish' and selection == 'mixed' else 'false'})
                summary['runs'].append({'tag': tag, 'direction': result['cardDirections'],
                                        'frames': result['cardParticleFrames']})
    summary['ok'] = True
finally:
    summary['realDatabaseAfter'] = real_digest()
    summary['realDatabaseUnchanged'] = summary['realDatabaseBefore'] == summary['realDatabaseAfter']
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    assert summary['realDatabaseUnchanged'], '正式应用数据库摘要发生变化，需核对原因'
    print(a.serial, 'real database unchanged', flush=True)
