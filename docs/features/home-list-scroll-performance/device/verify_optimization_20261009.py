"""三台授权真机的前后对照；复用原诊断口径，独立保存本轮偏好备份。"""
import argparse
import concurrent.futures
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recheck_20261009 as r

ORIGINAL_OUT = r.OUT
r.OUT = r.ROOT / 'tmp/home-list-scroll-20261009-optimized'


def environment(name, label):
    s = r.DEVICES[name]
    r.save(r.OUT / name / (label + '-environment.txt'), r.sh(s,
        'dumpsys thermalservice; dumpsys battery; df /data; dumpsys display'))


def baseline(name):
    r.inventory(name)
    s = r.DEVICES[name]
    apk = r.sh(s, 'pm path ' + r.PKG).strip().split('package:')[-1]
    r.adb(s, 'pull', apk, r.OUT / name / 'baseline.apk', timeout=120)
    for style in ['flow', 'drift']:
        environment(name, 'before-' + style)
        r.collect(name, style, run='before', count=6)


def after(name):
    s = r.DEVICES[name]
    apk = r.OUT / 'candidate.apk'
    print(name, r.adb(s, 'install', '-r', apk, timeout=180).decode().strip(), flush=True)
    r.save(r.OUT / name / 'candidate-package.txt', r.sh(s, 'dumpsys package ' + r.PKG))
    for style in ['flow', 'drift']:
        environment(name, 'after-' + style)
        r.collect(name, style, run='after', count=6)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('phase', choices=['baseline', 'after'])
    p.add_argument('--device', choices=list(r.DEVICES))
    a = p.parse_args()
    if a.phase == 'baseline':
        (r.OUT / 'driver').mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ORIGINAL_OUT / 'driver/input.jar', r.OUT / 'driver/input.jar')
        candidate = r.OUT / 'candidate.apk'
        if not candidate.exists():
            shutil.copyfile(r.ROOT / 'app/build/outputs/apk/debug/app-debug.apk', candidate)
            r.save(r.OUT / 'candidate-sha256.txt', hashlib.sha256(candidate.read_bytes()).hexdigest())
    names = [a.device] if a.device else list(r.DEVICES)
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        jobs = {ex.submit(baseline if a.phase == 'baseline' else after, name): name for name in names}
        for job in concurrent.futures.as_completed(jobs):
            try:
                job.result()
                print(jobs[job], a.phase, 'complete', flush=True)
            except Exception as e:
                errors.append((jobs[job], repr(e)))
                print('ERROR', jobs[job], repr(e), flush=True)
    if errors:
        raise RuntimeError(errors)


if __name__ == '__main__':
    main()
