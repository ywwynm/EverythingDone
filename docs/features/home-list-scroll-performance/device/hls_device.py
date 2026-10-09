"""Home list scroll performance: device driver for R5CW20BLNKL (Samsung SM-S9180).

Only vertical gestures are sent (x start == x end). No taps, no long presses.
All device-side artifacts are prefixed with hls_ under /data/local/tmp.
Every mark is written to logcat (tag HlsMark) and appended to <run>/marks.jsonl
with /proc/uptime (CLOCK_BOOTTIME, 10 ms) and date +%s.%N (CLOCK_REALTIME, ns).
"""
import argparse
import hashlib
import json
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = r'E:\AndroidSDK\platform-tools\adb.exe'
SERIAL = 'R5CW20BLNKL'
PKG = 'com.ywwynm.everythingdone'
ACTIVITY = PKG + '/.activities.ThingsActivity'
DEV = '/data/local/tmp'
DEFAULT_RUN = Path(r'C:\Users\ywwynm\AppData\Local\Temp\claude\E--projects-EverythingDone'
                   r'\6c82dbd9-2f3e-4f60-a184-f498828099fe\scratchpad\samsung\run1')
SCREEN_W, SCREEN_H = 1440, 3088
EDGE_X, EDGE_Y = 140, 200

# Localized strings as escapes (no CJK literals in this file).
TXT_CRASH_FEEDBACK = '\u73b0\u5728\u53cd\u9988'   # "report now" button of the crash dialog
TXT_CANCEL = '\u53d6\u6d88'                        # "cancel"

RUN = DEFAULT_RUN


def log(msg):
    print(msg, flush=True)


def adb(args, timeout=300, binary=False, check=True):
    cmd = [ADB, '-s', SERIAL] + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError('adb failed rc=%d cmd=%s\nstdout=%s\nstderr=%s' % (
            r.returncode, args, r.stdout[:2000], r.stderr[:2000]))
    if binary:
        return r.stdout
    return r.stdout.decode('utf-8', 'replace')


def sh(cmd, timeout=300, check=True):
    return adb(['shell', cmd], timeout=timeout, check=check)


def md5_local(path):
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def pull_verified(remote, local, retries=4):
    local = Path(local)
    local.parent.mkdir(parents=True, exist_ok=True)
    out = sh('md5sum %s' % remote).strip()
    m = re.match(r'^([0-9a-f]{32})', out)
    if not m:
        raise RuntimeError('md5sum failed for %s: %s' % (remote, out))
    remote_hash = m.group(1)
    for attempt in range(retries):
        adb(['pull', remote, str(local)], timeout=900)
        if md5_local(local) == remote_hash:
            return remote_hash
        log('md5 mismatch on %s attempt %d, retrying' % (remote, attempt))
    raise RuntimeError('md5 never matched for %s' % remote)


def append_mark(rec):
    with (RUN / 'marks.jsonl').open('a', encoding='utf-8') as f:
        f.write(json.dumps(rec) + '\n')


def parse_r_line(out):
    """Parse 'R <up0> <idle0> <w0> <w1> <up1> <idle1>' produced by the gesture commands."""
    for line in out.splitlines():
        if line.startswith('R '):
            parts = line.split()
            return dict(uptime0=float(parts[1]), wall0=float(parts[3]),
                        wall1=float(parts[4]), uptime1=float(parts[5]))
    raise RuntimeError('no R line in output: %s' % out)


def mark(label, trace=False):
    tm = 'echo HlsMark:%s > /sys/kernel/tracing/trace_marker; ' % label if trace else ''
    out = sh('log -p i -t HlsMark %s; %su=$(cat /proc/uptime); w=$(date +%%s.%%N); echo M $u $w' % (label, tm))
    for line in out.splitlines():
        if line.startswith('M '):
            p = line.split()
            rec = dict(kind='mark', label=label, uptime=float(p[1]), wall=float(p[3]), host=time.time())
            append_mark(rec)
            return rec
    raise RuntimeError('mark failed: %s' % out)


def check_point(x, y):
    if not (EDGE_X <= x <= SCREEN_W - EDGE_X):
        raise ValueError('x=%d violates the %d px side margin' % (x, EDGE_X))
    if not (EDGE_Y <= y <= SCREEN_H - EDGE_Y):
        raise ValueError('y=%d violates the %d px top/bottom margin' % (y, EDGE_Y))


def swipe(label, x, y1, y2, duration=280, trace=False):
    """Vertical swipe only: the same x for start and end."""
    check_point(x, y1)
    check_point(x, y2)
    tb = 'echo HlsMark:%s.begin > /sys/kernel/tracing/trace_marker; ' % label if trace else ''
    te = 'echo HlsMark:%s.end > /sys/kernel/tracing/trace_marker; ' % label if trace else ''
    cmd = ('log -p i -t HlsMark %s.begin; %s'
           'u0=$(cat /proc/uptime); w0=$(date +%%s.%%N); '
           'input swipe %d %d %d %d %d; '
           'w1=$(date +%%s.%%N); u1=$(cat /proc/uptime); %slog -p i -t HlsMark %s.end; '
           'echo R $u0 $w0 $w1 $u1') % (label, tb, x, y1, x, y2, duration, te, label)
    out = sh(cmd)
    rec = dict(kind='swipe', label=label, x=x, y1=y1, y2=y2, duration=duration, trace=trace,
               host=time.time(), **parse_r_line(out))
    append_mark(rec)
    return rec


def hold_slide(label, x, y, direction, trace=True, hold='0.18'):
    """DOWN, sleep, four MOVE steps of 160 px along y, UP. direction=-1 moves the finger up."""
    ys = [y + direction * 160 * k for k in range(1, 5)]
    check_point(x, y)
    for yy in ys:
        check_point(x, yy)
    tb = 'echo HlsMark:%s.begin > /sys/kernel/tracing/trace_marker; ' % label if trace else ''
    te = 'echo HlsMark:%s.end > /sys/kernel/tracing/trace_marker; ' % label if trace else ''
    moves = ''.join('input motionevent MOVE %d %d; ' % (x, yy) for yy in ys)
    cmd = ('log -p i -t HlsMark %s.begin; %s'
           'u0=$(cat /proc/uptime); w0=$(date +%%s.%%N); '
           'input motionevent DOWN %d %d; sleep %s; %s'
           'input motionevent UP %d %d; '
           'w1=$(date +%%s.%%N); u1=$(cat /proc/uptime); %slog -p i -t HlsMark %s.end; '
           'echo R $u0 $w0 $w1 $u1') % (label, tb, x, y, hold, moves, x, ys[-1], te, label)
    out = sh(cmd)
    rec = dict(kind='hold_slide', label=label, x=x, y=y, ys=ys, hold=hold, trace=trace,
               host=time.time(), **parse_r_line(out))
    append_mark(rec)
    return rec


# ---------------------------------------------------------------- UI inspection

def dump_xml(tries=12):
    """uiautomator dump into a unique file; returns raw xml text or None."""
    for _ in range(tries):
        name = '%s/hls_win_%d.xml' % (DEV, random.randint(10 ** 8, 10 ** 9 - 1))
        sh('uiautomator dump %s' % name, check=False, timeout=60)
        raw = adb(['exec-out', 'cat', name], binary=True, check=False)
        sh('rm -f %s' % name, check=False)
        text = raw.decode('utf-8', 'replace')
        if text.startswith('<?xml') and '</hierarchy>' in text:
            return text
        time.sleep(0.4)
    return None


def parse_bounds(b):
    m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', b or '')
    return [int(v) for v in m.groups()] if m else None


def nodes(xml_text):
    root = ET.fromstring(xml_text)
    out = []
    for n in root.iter('node'):
        out.append(dict(rid=n.get('resource-id') or '', cls=n.get('class') or '',
                        pkg=n.get('package') or '', text=n.get('text') or '',
                        desc=n.get('content-desc') or '', bounds=parse_bounds(n.get('bounds')),
                        n=n))
    return out


def screencap(local_name):
    remote = '%s/hls_cap_%d.png' % (DEV, random.randint(10 ** 8, 10 ** 9 - 1))
    sh('screencap -p %s' % remote)
    h = pull_verified(remote, RUN / local_name)
    sh('rm -f %s' % remote, check=False)
    head = (RUN / local_name).read_bytes()[:8]
    if head[:4] != b'\x89PNG':
        raise RuntimeError('bad png header %r' % head)
    return h


# ---------------------------------------------------------------- commands

def cmd_settings(args):
    out = sh('echo accelerometer_rotation=$(settings get system accelerometer_rotation); '
             'echo user_rotation=$(settings get system user_rotation); '
             'dumpsys window displays | grep -A12 RotationLockHistory; '
             'dumpsys window displays | grep -E "mCurrentRotation|mUserRotationMode"; '
             'echo wall=$(date +%s.%N) uptime=$(cat /proc/uptime)')
    (RUN / ('rotation_%s.txt' % args.label)).write_text(out, encoding='utf-8')
    log(out)


def cmd_env(args):
    out = sh('getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id; '
             'wm size; wm density; '
             'echo long_press_timeout=$(settings get secure long_press_timeout); '
             'echo accessibility_enabled=$(settings get secure accessibility_enabled); '
             'echo enabled_accessibility_services=$(settings get secure enabled_accessibility_services); '
             'echo animator_duration_scale=$(settings get global animator_duration_scale); '
             'echo window_animation_scale=$(settings get global window_animation_scale); '
             'echo transition_animation_scale=$(settings get global transition_animation_scale); '
             'echo peak_refresh_rate=$(settings get system peak_refresh_rate); '
             'echo min_refresh_rate=$(settings get system min_refresh_rate); '
             'echo refresh_rate_mode=$(settings get secure refresh_rate_mode); '
             'dumpsys package %s | grep -E "versionName|versionCode|lastUpdateTime"; '
             'echo pid=$(pidof %s); ps -A -o PID,USER,STIME,NAME | grep %s; '
             'cat /proc/meminfo | head -3; nproc; cat /sys/devices/system/cpu/online; '
             'echo thermal=$(dumpsys thermalservice | grep -m1 "Thermal Status"); '
             'dumpsys display | grep -E "mActiveMode|mRefreshRate|renderFrameRate|mActiveSfDisplayMode" | head -8; '
             'echo wall=$(date +%%s.%%N) uptime=$(cat /proc/uptime)' % (PKG, PKG, PKG))
    (RUN / ('env_%s.txt' % args.label)).write_text(out, encoding='utf-8')
    log(out)


def cmd_logcat_start(args):
    base = args.device_name
    existing = sh('ls %s/%s.txt* 2>/dev/null; echo END' % (DEV, base))
    if '%s.txt' % base in existing:
        raise RuntimeError('old %s files exist, refusing to start: %s' % (base, existing))
    sh('nohup logcat -v threadtime -b main -f %s/%s.txt -r 30000 -n 10 >/dev/null 2>&1 &' % (DEV, base))
    time.sleep(1.0)
    log(sh('ps -A -o PID,ARGS | grep "logcat -v threadtime" | grep -v grep; ls -l %s/%s.txt*' % (DEV, base)))
    mark('logcat.started.%s' % base)


def cmd_logcat_stop(args):
    base = args.device_name
    mark('logcat.stopping.%s' % base)
    time.sleep(1.0)
    sh('pkill -f "logcat -v threadtime"', check=False)
    time.sleep(0.5)
    listing = sh('ls %s/%s.txt*' % (DEV, base))
    log(listing)
    for remote in listing.split():
        name = remote.rsplit('/', 1)[1]
        pull_verified(remote, RUN / args.local_dir / name)
        log('pulled %s' % name)


def cmd_power(args):
    """MONOTONIC 'now' from dumpsys power (uptimeMillis + 'ms ago'), bracketed by /proc/uptime reads."""
    out = sh('u0=$(cat /proc/uptime); p=$(dumpsys power | grep -E "mLastWakeTime=|mLastSleepTime="); '
             'u1=$(cat /proc/uptime); echo U0 $u0; echo "$p"; echo U1 $u1; echo W $(date +%s.%N)')
    (RUN / ('power_%s.txt' % args.label)).write_text(out, encoding='utf-8')
    log(out)


def check_front(xml_text):
    ns = nodes(xml_text)
    pkgs = {n['pkg'] for n in ns}
    info = dict(pkgs=sorted(pkgs))
    info['systemui'] = 'com.android.systemui' in pkgs and PKG not in pkgs
    info['crash_dialog'] = any(n['text'] == TXT_CRASH_FEEDBACK for n in ns)
    info['app_front'] = PKG in pkgs
    return info, ns


def cmd_launch(args):
    mark('launch.begin')
    log(sh('am start -n %s' % ACTIVITY))
    time.sleep(3.0)
    mark('launch.after3s')
    for attempt in range(6):
        x = dump_xml()
        if x is None:
            log('dump failed')
            continue
        info, ns = check_front(x)
        log(json.dumps(info))
        if info['systemui']:
            raise SystemExit('LOCKED: systemui in front, stop and ask the user to unlock')
        if info['crash_dialog']:
            cancel = next((n for n in ns if n['text'] == TXT_CANCEL and n['bounds']), None)
            if cancel is None:
                raise SystemExit('crash dialog without a cancel button')
            b = cancel['bounds']
            cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            log('crash dialog: tapping cancel at %d,%d' % (cx, cy))
            mark('crashdialog.cancel')
            sh('input tap %d %d' % (cx, cy))
            time.sleep(1.5)
            continue
        if info['app_front']:
            (RUN / 'launch_dump.xml').write_text(x, encoding='utf-8')
            top = sh('dumpsys activity activities | grep -E "ResumedActivity" | head -3')
            log(top)
            return
        time.sleep(1.0)
    raise SystemExit('app never came to front')


IDS = ('cv_thing', 'iv_thing_image', 'tv_thing_content', 'tv_thing_title')


def extract_layout(xml_text):
    ns = nodes(xml_text)
    cards = []
    for n in ns:
        if not n['rid'].endswith('/cv_thing') or not n['bounds']:
            continue
        el = n['n']
        card = dict(bounds=n['bounds'], children={})
        for c in el.iter('node'):
            rid = (c.get('resource-id') or '').split('/')[-1]
            if rid in IDS[1:] and rid not in card['children']:
                card['children'][rid] = parse_bounds(c.get('bounds'))
        cards.append(card)
    others = {}
    for n in ns:
        rid = n['rid'].split('/')[-1]
        if rid in ('rv_things', 'fl_things', 'actionbar', 'fab_create', 'rl_things_actionbar') and n['bounds']:
            others.setdefault(rid, n['bounds'])
    rv = [n for n in ns if n['cls'].endswith('RecyclerView') and n['bounds']]
    return dict(cards=cards, others=others,
                recyclerviews=[dict(rid=n['rid'], bounds=n['bounds']) for n in rv])


def cmd_layout(args):
    best = None
    for attempt in range(args.tries):
        x = dump_xml()
        if x is None:
            continue
        info, _ = check_front(x)
        if not info['app_front'] or info['systemui']:
            raise SystemExit('app not in front: %s' % info)
        lay = extract_layout(x)
        log('attempt %d: %d cards' % (attempt, len(lay['cards'])))
        if best is None or len(lay['cards']) > len(best[1]['cards']):
            best = (x, lay)
        time.sleep(0.5)
    if best is None:
        raise SystemExit('no dump')
    x, lay = best
    (RUN / ('%s.xml' % args.name)).write_text(x, encoding='utf-8')
    h = screencap('%s.png' % args.name)
    lay['screenshot'] = '%s.png' % args.name
    lay['screenshot_md5'] = h
    lay['wall'] = time.time()
    (RUN / ('%s.json' % args.name)).write_text(json.dumps(lay, indent=1), encoding='utf-8')
    for c in lay['cards']:
        b = c['bounds']
        log('card %s w=%d h=%d children=%s' % (b, b[2] - b[0], b[3] - b[1], c['children']))
    log('others=%s rv=%s' % (lay['others'], lay['recyclerviews']))


def cmd_shot(args):
    h = screencap('%s.png' % args.name)
    log('%s.png md5=%s' % (args.name, h))


def cmd_swipe(args):
    rec = swipe(args.label, args.x, args.y1, args.y2, args.duration, trace=args.trace)
    log(json.dumps(rec))


def gfx_reset(name):
    remote = '%s/hls_%s_prereset.txt' % (DEV, name)
    mark('%s.reset' % name)
    out = sh('u0=$(cat /proc/uptime); dumpsys gfxinfo %s reset > %s; u1=$(cat /proc/uptime); echo U $u0 $u1' % (PKG, remote))
    for line in out.splitlines():
        if line.startswith('U '):
            parts = line.split()
            append_mark(dict(kind='reset_bracket', label='%s.reset.bracket' % name,
                             uptime_before=float(parts[1]), uptime_after=float(parts[3]), host=time.time()))
    pull_verified(remote, RUN / ('%s_prereset.txt' % name))
    sh('rm -f %s' % remote, check=False)


def gfx_dump(name, framestats=True, trace=False):
    remote = '%s/hls_%s.txt' % (DEV, name)
    mark('%s.dump.begin' % name, trace=trace)
    sh('dumpsys gfxinfo %s %s > %s' % (PKG, 'framestats' if framestats else '', remote))
    mark('%s.dump.end' % name, trace=trace)
    pull_verified(remote, RUN / ('%s.txt' % name))
    sh('rm -f %s' % remote, check=False)


def cmd_static(args):
    gfx_reset(args.name)
    mark('%s.static.begin' % args.name)
    time.sleep(args.seconds)
    mark('%s.static.end' % args.name)
    gfx_dump('%s_framestats' % args.name)


def parse_starts(spec):
    out = []
    for item in spec.split(','):
        x, y, d = item.split(':')
        out.append((int(x), int(y), int(d)))
    return out


def sleep_until(t):
    dt = t - time.time()
    if dt > 0:
        time.sleep(dt)


def cmd_set(args):
    """A gesture set: reset, N gestures, a framestats dump after each gesture once the fling
    has ended (framestats keeps only the last 120 frames), and the set-level dump 3 s after the last."""
    starts = parse_starts(args.starts)
    name = args.name
    trace = args.trace_name is not None
    if trace:
        remote = '%s/hls_trace_%s.txt' % (DEV, args.trace_name)
        exists = sh('ls %s 2>/dev/null; echo END' % remote)
        if 'hls_trace_' in exists:
            raise SystemExit('trace file already exists on device: %s' % remote)
        cats = args.categories
        mark('%s.atrace.start.begin' % name)
        out = sh('atrace --async_start -b %d -a %s %s' % (args.buffer_kb, PKG, cats), check=False)
        log('atrace start output: %s' % out.strip())
        (RUN / ('%s_atrace_start.txt' % name)).write_text(out, encoding='utf-8')
        mark('%s.atrace.start.end' % name, trace=True)
        time.sleep(2.0)
    gfx_reset(name)
    time.sleep(1.0)
    for k, (x, y, d) in enumerate(starts, 1):
        label = '%s.g%d.%s' % (name, k, 'up' if d < 0 else 'down')
        if args.kind == 'swipe':
            rec = swipe(label, x, y, y + d * args.distance, args.duration, trace=trace)
        else:
            rec = hold_slide(label, x, y, d, trace=trace)
        t_end = time.time()
        log('%s device wall %.3f-%.3f (%.0f ms)' % (label, rec['wall0'], rec['wall1'], (rec['wall1'] - rec['wall0']) * 1000))
        sleep_until(t_end + args.dump_at)
        gfx_dump('%s_g%d_framestats' % (name, k), trace=trace)
        if k < len(starts):
            sleep_until(t_end + args.wait)
    time.sleep(3.0)
    gfx_dump('%s_framestats' % name, trace=trace)
    if trace:
        mark('%s.atrace.stop.begin' % name, trace=True)
        out = sh('atrace --async_stop -o %s' % remote, timeout=900, check=False)
        mark('%s.atrace.stop.end' % name)
        (RUN / ('%s_atrace_stop.txt' % name)).write_text(out, encoding='utf-8')
        log('atrace stop output: %s' % out.strip()[:500])
        log(sh('ls -l %s' % remote))
        pull_verified(remote, RUN / ('hls_trace_%s.txt' % args.trace_name))
        sh('rm -f %s' % remote, check=False)


def cmd_finish_sf(args):
    remote = '%s/hls_sf.txt' % DEV
    mark('sf.dump')
    sh('dumpsys SurfaceFlinger > %s' % remote)
    pull_verified(remote, RUN / 'surfaceflinger.txt')
    sh('rm -f %s' % remote, check=False)


def cmd_pull_cover_log(args):
    remote = '/sdcard/Android/data/%s/files/debug_logs/video-cover-preview.log' % PKG
    local = RUN / 'video-cover-preview.log'
    try:
        h = pull_verified(remote, local)
        log('pulled directly md5=%s size=%d' % (h, local.stat().st_size))
        return
    except Exception as e:
        log('direct pull failed: %s' % e)
    out = adb(['shell', 'run-as', PKG, 'md5sum', remote])
    remote_hash = out.split()[0]
    raw = adb(['exec-out', 'run-as', PKG, 'cat', remote], binary=True)
    local.write_bytes(raw)
    if md5_local(local) != remote_hash:
        raise RuntimeError('run-as copy md5 mismatch')
    log('pulled via run-as md5=%s size=%d' % (remote_hash, len(raw)))


def main():
    global RUN
    p = argparse.ArgumentParser()
    p.add_argument('--run', type=Path, default=DEFAULT_RUN)
    sub = p.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('settings'); s.add_argument('label'); s.set_defaults(f=cmd_settings)
    s = sub.add_parser('env'); s.add_argument('label'); s.set_defaults(f=cmd_env)
    s = sub.add_parser('logcat-start'); s.add_argument('--device-name', default='hls_logcat')
    s.set_defaults(f=cmd_logcat_start)
    s = sub.add_parser('logcat-stop'); s.add_argument('--device-name', default='hls_logcat')
    s.add_argument('--local-dir', default='logcat'); s.set_defaults(f=cmd_logcat_stop)
    s = sub.add_parser('power'); s.add_argument('label'); s.set_defaults(f=cmd_power)
    s = sub.add_parser('launch'); s.set_defaults(f=cmd_launch)
    s = sub.add_parser('layout'); s.add_argument('name'); s.add_argument('--tries', type=int, default=4)
    s.set_defaults(f=cmd_layout)
    s = sub.add_parser('shot'); s.add_argument('name'); s.set_defaults(f=cmd_shot)
    s = sub.add_parser('mark'); s.add_argument('label'); s.set_defaults(f=lambda a: log(json.dumps(mark(a.label))))
    s = sub.add_parser('swipe'); s.add_argument('label'); s.add_argument('x', type=int)
    s.add_argument('y1', type=int); s.add_argument('y2', type=int)
    s.add_argument('--duration', type=int, default=280); s.add_argument('--trace', action='store_true')
    s.set_defaults(f=cmd_swipe)
    s = sub.add_parser('static'); s.add_argument('name'); s.add_argument('--seconds', type=float, default=5.0)
    s.set_defaults(f=cmd_static)
    s = sub.add_parser('set'); s.add_argument('name'); s.add_argument('--starts', required=True)
    s.add_argument('--kind', choices=('swipe', 'hold'), default='swipe')
    s.add_argument('--distance', type=int, default=640); s.add_argument('--duration', type=int, default=280)
    s.add_argument('--dump-at', type=float, default=1.5); s.add_argument('--wait', type=float, default=3.0)
    s.add_argument('--trace-name'); s.add_argument('--buffer-kb', type=int, default=60000)
    s.add_argument('--categories', default='gfx view input sched freq idle binder_driver')
    s.set_defaults(f=cmd_set)
    s = sub.add_parser('sf'); s.set_defaults(f=cmd_finish_sf)
    s = sub.add_parser('cover-log'); s.set_defaults(f=cmd_pull_cover_log)
    a = p.parse_args()
    RUN = a.run
    RUN.mkdir(parents=True, exist_ok=True)
    a.f(a)


if __name__ == '__main__':
    main()
