"""虚构卡片的系统手势回归；仅切换粒子风格，收尾恢复本轮原始偏好。"""
import argparse
import concurrent.futures
import json
import re
import struct
import subprocess
import time
from pathlib import Path

import verify_optimization_20261009 as v
r = v.r


def wait_json(s, remote, name, seconds=16):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        raw = r.adb(s, 'exec-out', 'cat', remote + '/' + name + '.json', check=False)
        try:
            return json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            time.sleep(.2)
    raise TimeoutError(remote + '/' + name)


def run_case(name, phase, style, case):
    s = r.DEVICES[name]
    run = 'hls09-' + phase + '-' + style + '-' + case
    remote = '/sdcard/Android/data/' + r.PKG + '/files/swipe-shadow-probe/' + run
    out = r.OUT / name / ('horizontal-' + phase + '-' + style) / case
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'summary.json').exists():
        return json.loads((out / 'summary.json').read_text())
    right = case == 'right'
    r.sh(s, 'am start -W -n ' + r.PKG + '/.views.particledismiss.ParticleSwipeShadowProbeActivity '
         '--es run_id ' + run + ' --ez performance_only true --ez allow_right_swipe ' + str(right).lower())
    b = wait_json(s, remote, 'ready')
    wait_json(s, remote, 'snapshot')
    tree = r.dump(s, out / 'before.xml')
    nodes = [n for n in tree.iter('node') if n.get('content-desc') == '虚构阴影测试卡片']
    assert len(nodes) == 1, '未确认虚构卡片，拒绝注入手势'
    x1, y1, x2, y2 = r.bounds(nodes[0])
    assert abs(x1 - b['x']) <= 1 and abs(y1 - b['y']) <= 1
    r.save(out / 'ready.json', json.dumps(b))
    x, y = round(x1 + (x2 - x1) * (.12 if right else .88)), round((y1 + y2) / 2)
    direction = 1 if right else -1
    distance = .78 if case in ['complete', 'right'] else .42
    target = round(x + direction * (x2 - x1) * distance)
    hold = 160 if case == 'held' else 0
    duration = 48 if case == 'fast' else 350
    segments = ([x, y, hold] if hold else []) + [target, y, duration, target, y, 350]
    if case == 'reverse':
        segments += [x + 16, y, 350, x + 16, y, 160]
    if case == 'vertical':
        segments = [x, y + 160, 350, x, y + 160, 100]
    if case == 'diagonal':
        segments = [x - 40, y + 30, 120, target, y + 30, 350, target, y + 30, 350]
    end = 'UP' if case in ['complete', 'right'] else 'CANCEL'
    pid = int(r.sh(s, 'pidof ' + r.PKG).strip())
    with (out / 'logcat.txt').open('wb') as log:
        lp = subprocess.Popen([r.ADB, '-s', s, 'logcat', '--pid=' + str(pid), '-v', 'monotonic', '-T', '1'], stdout=log, stderr=subprocess.DEVNULL)
        try:
            raw = r.sh(s, 'PARTICLE_INPUT_ASYNC=1 CLASSPATH=/data/local/tmp/hls09_input.jar '
                       'app_process /system/bin HlsGestureInput ' + ' '.join(map(str, [x, y, end, *segments])), timeout=15)
            r.save(out / 'driver.txt', raw)
            data = wait_json(s, remote, 'result')
            r.save(out / 'result.json', json.dumps(data))
        finally:
            lp.terminate()
            lp.wait(timeout=5)
    frames = data['frames']
    expected = int(case in ['complete', 'right'])
    assert data['completions'] == expected, (name, style, case, 'completion', data['completions'])
    assert frames[-1]['alpha'] == 1 and abs(frames[-1]['tx']) < .5, (name, style, case, 'card not restored')
    assert frames[-1].get('activeOverlays', 0) == 0, (name, style, case, 'overlay not released')
    downs = [i for i in data['inputs'] if i[1] == 0]
    moves = [i for i in data['inputs'] if i[1] == 2 and abs(i[2] - x) >= 2]
    first_move = moves[0][0] if moves else None
    hidden = [f for f in frames if f['alpha'] == 0]
    first_nonzero = None
    for session in data['particleFrames']:
        submitted = {f[0]: struct.unpack('f', struct.pack('I', f[2] & 0xffffffff))[0] for f in session['submitted']}
        for f in session['presented']:
            if submitted.get(f[0], 0) > 0:
                stamp = f[3] if len(f) > 3 and f[3] else f[1]
                first_nonzero = (stamp - data['startedAtNanos']) / 1e6
                break
        if first_nonzero is not None:
            break
    row = dict(device=name, phase=phase, style=style, case=case, completions=expected,
               down_ms=downs[0][0], first_move_ms=first_move,
               first_hidden_ms=hidden[0]['at'] if hidden else None,
               flow_first_nonzero_ms=first_nonzero,
               flow_move_to_nonzero_ms=round(first_nonzero - first_move, 2) if first_move is not None and first_nonzero is not None else None,
               move_to_hidden_ms=hidden[0]['at'] - first_move if hidden and first_move is not None else None)
    r.save(out / 'summary.json', json.dumps(row))
    print(json.dumps(row), flush=True)
    r.sh(s, 'input keyevent BACK')
    return row


def run_device(name, phase, extended):
    s = r.DEVICES[name]
    original = (r.OUT / name / 'original-particle_tuning.xml').read_bytes()
    results = []
    r.adb(s, 'push', r.OUT / 'driver/input.jar', '/data/local/tmp/hls09_input.jar')
    try:
        for style in ['flow', 'drift']:
            r.sh(s, 'am force-stop ' + r.PKG)
            r.write_pref(s, 'particle_tuning.xml', r.preference(original, 'particle_style', style))
            cases = ['direct', 'direct2', 'held', 'fast', 'reverse']
            if extended:
                cases += ['complete', 'right', 'vertical', 'diagonal']
            for case in cases:
                results.append(run_case(name, phase, style, case))
    finally:
        r.sh(s, 'am force-stop ' + r.PKG)
        r.write_pref(s, 'particle_tuning.xml', original)
        r.sh(s, 'am start -W -n ' + r.PKG + '/.activities.ThingsActivity')
    r.save(r.OUT / name / ('horizontal-' + phase + '.json'), json.dumps(results, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('phase', choices=['before', 'after'])
    p.add_argument('--extended', action='store_true')
    p.add_argument('--device', choices=list(r.DEVICES))
    a = p.parse_args()
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        jobs = {ex.submit(run_device, name, a.phase, a.extended): name for name in ([a.device] if a.device else r.DEVICES)}
        for job in concurrent.futures.as_completed(jobs):
            try:
                job.result()
            except Exception as e:
                errors.append((jobs[job], repr(e)))
                print('ERROR', jobs[job], repr(e), flush=True)
    if errors:
        raise RuntimeError(errors)
