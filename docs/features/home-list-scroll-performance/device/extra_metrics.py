"""Derived numbers for the run1 collection report that the two analyzers do not print.

- display mode (SurfaceFlinger VRRInfo history) around every gesture; deadline class vs display mode;
  FrameInterval classes
- per trace: snapshot renderer sizes and readback per gesture, CPU per thread in 50 ms bins after DOWN,
  texture uploads by size, upload rate in idle windows, glide-animation CPU around gestures,
  particle thread tids, whole-trace CPU per thread
- logcat: ParticleMicroflake / CFMS / animated-scroll-control lines per gesture

Usage: python extra_metrics.py <run_dir> [--pid 6064]   -> <run_dir>/extra_metrics.txt
       python extra_metrics.py <run_dir> --logcat-only --logcat-file <file> --label-prefix set7_gap,set8_small_again
              --out <name>   (only the per-gesture logcat counts, for the given gesture label prefixes)
Needs framestats_summary.json (analyze_framestats.py with --boot-minus-mono) and trace_<set>.json
(analyze_trace.py) in the run directory.
"""
import argparse
import datetime
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_trace import parse_trace, running_intervals  # noqa: E402

TZ = datetime.timezone(datetime.timedelta(hours=8))
RATE = {0: 120, 1: 96, 2: 60, 3: 48, 4: 30, 5: 24, 6: 10}
TRACES = (('small', 'set4_tsmall'), ('video', 'set5_tvideo'), ('hold', 'set6_thold'))
BIN_THREADS = ['.everythingdone', 'RenderThread', 'ParticleSnapsho', 'ParticleCardHan', 'ParticleGl',
               'ParticleModelWa', 'ParticleMateria', 'ParticleForegro', 'GrallocUploadTh', 'glide-animation',
               'HeapTaskDaemon', 'ReferenceQueueD', 'Jit thread pool']


def local_ts(h, mi, s, ms, month=9, day=26):
    return datetime.datetime(2026, month, day, int(h), int(mi), int(s), int(ms) * 1000, tzinfo=TZ).timestamp()


CAPTURE_TEXT = '抓图路径'   # "capture path" in the ParticleMicroflake log line


def logcat_section(p, a, run, gestures):
    if a.label_prefix:
        prefixes = tuple(x.strip() + '.' for x in a.label_prefix.split(','))
        gestures = [g for g in gestures if g['label'].startswith(prefixes)]
    p('')
    p('=' * 100)
    logcat_path = Path(a.logcat_file) if a.logcat_file else run / 'logcat' / 'hls_logcat.txt'
    p('logcat lines of pid %d per gesture (offset from wall0, ms); file %s' % (a.pid, logcat_path))
    if not gestures:
        p('no gestures selected')
        return
    lo = gestures[0]['wall0'] - 1.0
    hi = gestures[-1]['wall0'] + 5.0
    lines = logcat_path.read_text(encoding='utf-8', errors='replace').splitlines()
    rows = defaultdict(lambda: defaultdict(list))
    for line in lines:
        if ' %d ' % a.pid not in line[:40]:
            continue
        m = re.match(r'(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})', line)
        if not m:
            continue
        mo, dd, h, mi, s, ms = m.groups()
        ts = local_ts(h, mi, s, ms, int(mo), int(dd))
        if a.label_prefix and not (lo <= ts <= hi):
            continue
        if 'ParticleMicroflake' in line:
            kind = 'capture' if CAPTURE_TEXT in line else 'ParticleMicroflake-other'
        elif 'CFMS:: SetUp' in line:
            kind = 'CFMS'
        elif 'animated-scroll-control' in line:
            sc = re.search(r'scroll=(\w+)', line)
            kind = 'gif-%s' % (sc.group(1) if sc else '?')
        else:
            continue
        prev = [g for g in gestures if g['wall0'] <= ts + 0.05]
        label = prev[-1]['label'] if prev else '(before first gesture)'
        rows[label][kind].append(round((ts - prev[-1]['wall0']) * 1000) if prev else None)
    last_ts = None
    for line in reversed(lines):
        m = re.match(r'(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})', line)
        if m:
            mo, dd, h, mi, s, ms = m.groups()
            last_ts = local_ts(h, mi, s, ms, int(mo), int(dd))
            break
    for label in [g['label'] for g in gestures] + ['(before first gesture)']:
        if label in rows:
            p('%-24s %s' % (label, '; '.join('%s n=%d %s' % (k, len(v), v[:4]) for k, v in sorted(rows[label].items()))))
        elif label != '(before first gesture)':
            g = next(x for x in gestures if x['label'] == label)
            if last_ts is not None and g['wall0'] > last_ts:
                continue   # the gesture happened after this logcat file ends
            p('%-24s (no matching lines)' % label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('run', type=Path)
    ap.add_argument('--pid', type=int, default=6064)
    ap.add_argument('--logcat-file')
    ap.add_argument('--logcat-only', action='store_true')
    ap.add_argument('--label-prefix')
    ap.add_argument('--out', default='extra_metrics.txt')
    a = ap.parse_args()
    run = a.run
    out = []
    p = out.append
    marks = [json.loads(l) for l in (run / 'marks.jsonl').read_text(encoding='utf-8').splitlines() if l.strip()]
    gestures = [m for m in marks if m.get('kind') in ('swipe', 'hold_slide')]
    plain = sorted(m['wall'] - m['uptime'] for m in marks if m.get('kind') == 'mark')
    wall_minus_boot = plain[len(plain) // 2]
    p('wall - boot (median over %d marks) = %.3f s, spread %.3f..%.3f s' % (
        len(plain), wall_minus_boot, plain[0] - wall_minus_boot, plain[-1] - wall_minus_boot))
    if a.logcat_only:
        logcat_section(p, a, run, gestures)
        (run / a.out).write_text('\n'.join(out) + '\n', encoding='utf-8')
        print('\n'.join(out))
        return

    # ---- SurfaceFlinger VRR history
    vrr = []
    on = False
    for line in (run / 'surfaceflinger.txt').read_text(encoding='utf-8', errors='replace').splitlines():
        if 'VRRInfo history' in line:
            on = True
        if on:
            m = re.match(r'\[\d+\] (\d\d):(\d\d):(\d\d)\.(\d{3}) ID=(\d+) .*Touch=(\d) Idle=(\d)', line)
            if m:
                h, mi, s, ms, idv, touch, _ = m.groups()
                vrr.append((local_ts(h, mi, s, ms), int(idv), int(touch)))

    def mode_at(t):
        prev = [v for v in vrr if v[0] <= t]
        return RATE[prev[-1][1]] if prev else None

    # the SurfaceFlinger dump only covers what happened before it was taken
    sf_mark = next((m for m in marks if m.get('label') == 'sf.dump'), None)
    sf_wall = sf_mark['wall'] if sf_mark else float('inf')
    p('SurfaceFlinger dump at wall %.3f; later gestures and frames are left out of the two display sections' % sf_wall)

    p('')
    p('== display mode around each measured gesture (relative to wall0 of the gesture)')
    for g in gestures:
        if not g['label'].startswith('set') or g['wall0'] > sf_wall:
            continue
        w0 = g['wall0']
        ev = ['%+.0fms->%dHz(Touch=%d)' % ((t - w0) * 1000, RATE[i], tc) for t, i, tc in vrr if w0 <= t <= w0 + 3.0]
        p('%-22s at begin %sHz; within 3 s: %s' % (g['label'], mode_at(w0), ', '.join(ev) or 'none'))

    fs = json.loads((run / 'framestats_summary.json').read_text(encoding='utf-8'))
    tab = Counter()
    intervals = Counter()
    for name, d in fs.items():
        for r in d['rows']:
            if r['t_boot'] + wall_minus_boot <= sf_wall:
                dl = r['deadline']
                cls = '8.33' if abs(dl - 8.333) < 0.02 else ('16.67' if abs(dl - 16.667) < 0.05 else 'other')
                tab[(cls, mode_at(r['t_boot'] + wall_minus_boot))] += 1
            iv = r['interval']
            intervals[(name, '8.33' if iv < 12 else ('16.67' if iv < 20 else ('33.3' if iv < 40 else 'other')))] += 1
    p('')
    p('== FrameDeadline - IntendedVsync class vs SurfaceFlinger mode at the frame (all sets)')
    for k, v in sorted(tab.items(), key=lambda kv: (kv[0][0], str(kv[0][1]))):
        p('deadline %-6s display %sHz: %d frames' % (k[0], k[1], v))
    p('')
    p('== FrameInterval classes per set')
    for k, v in sorted(intervals.items()):
        p('%-20s %-6s %d' % (k[0], k[1], v))

    # ---- traces
    for tr, set_name in TRACES:
        path = run / ('hls_trace_%s.txt' % tr)
        t = parse_trace(path, a.pid)
        app = {x for x, g in t['tgid_of'].items() if g == a.pid}
        iv = running_intervals(t['switches'], app)
        by_name = defaultdict(list)
        for tid in app:
            by_name[t['name_of'].get(tid, '?')].append(tid)
        spans = t['spans']
        lo = min(s['start'] for s in spans)
        hi = max(s['end'] for s in spans)
        d = json.loads((run / ('trace_%s.json' % set_name)).read_text(encoding='utf-8'))
        downs = [(g['gesture'], g['down']) for g in d['gestures']]

        def cpu(tids, a0, a1):
            return sum(max(0.0, min(e, a1) - max(s, a0)) for tid in tids for s, e, _ in iv.get(tid, []))

        p('')
        p('=' * 100)
        p('trace %s (%s): span %.1f s' % (path.name, set_name, hi - lo))
        total = {n: cpu(tids, lo, hi) for n, tids in by_name.items()}
        p('whole-trace CPU per thread name (ms): ' + ', '.join(
            '%s %.0f' % (n, v * 1000) for n, v in sorted(total.items(), key=lambda kv: -kv[1])[:16]))
        p('app CPU total %.0f ms' % (sum(total.values()) * 1000))
        for n in sorted(by_name):
            if n.startswith('Particle') or n.startswith('glide') or n.startswith('Gralloc'):
                p('  tids %-16s %s' % (n, [(tid, round(cpu([tid], lo, hi) * 1000, 1)) for tid in sorted(by_name[n])]))

        rt = [s for s in spans if t['name_of'].get(s['tid']) == 'RenderThread']
        for label, dn in downs:
            p('-- %s' % label)
            w = [s for s in rt if dn <= s['start'] <= dn + 0.3]
            for s in w:
                if s['depth'] == 0 and s['name'].startswith('DrawFrames -1'):
                    child = next((c for c in rt if c['depth'] == 1 and c['start'] >= s['start']
                                  and c['end'] <= s['end'] and c['name'].startswith('Drawing')), None)
                    size = child['name'].split(' hdr')[0].replace('Drawing', '').strip() if child else '?'
                    p('   DrawFrames -1 @%+.1f ms dur %.2f ms  [%s]' % ((s['start'] - dn) * 1000, s['ms'], size))
                if s['depth'] == 0 and s['name'].startswith('copyImageInto'):
                    p('   copyImageInto @%+.1f ms dur %.2f ms' % ((s['start'] - dn) * 1000, s['ms']))
            p('   CPU per 50 ms bin after DOWN (ms):')
            for n in BIN_THREADS:
                tids = by_name.get(n, [])
                row = [cpu(tids, dn + b * 0.05, dn + (b + 1) * 0.05) * 1000 for b in range(6)]
                if sum(row) >= 1.0:
                    p('     %-16s %s = %.1f' % (n, ' '.join('%5.1f' % v for v in row), sum(row)))

        agg = defaultdict(lambda: [0, 0.0, 0.0])
        ups = defaultdict(list)
        for s in spans:
            m = re.match(r'(Texture upload|Bitmap#prepareToDraw)\S*\s+.*?(\d+x\d+)', s['name'])
            if m:
                k = (m.group(1), m.group(2))
                agg[k][0] += 1
                agg[k][1] += s['ms']
                agg[k][2] = max(agg[k][2], s['ms'])
                if m.group(1) == 'Texture upload':
                    ups[m.group(2)].append(s['start'])
        p('texture uploads by size (count, total ms, max ms):')
        for k, v in sorted(agg.items(), key=lambda kv: -kv[1][1])[:8]:
            p('   %-22s %-10s n=%4d total=%7.1f max=%5.2f' % (k[0], k[1], v[0], v[1], v[2]))
        gl = by_name.get('glide-animation', [])
        for i, (label, dn) in enumerate(downs):
            a0 = dn + 1.6
            a1 = downs[i + 1][1] - 0.1 if i + 1 < len(downs) else dn + 3.3
            rates = ', '.join('%s %.1f/s' % (size, sum(1 for x in ups[size] if a0 <= x <= a1) / (a1 - a0))
                              for size in ('1320x742', '630x472'))
            p('idle after %s (%.2f s): uploads %s; glide-animation CPU [-1s,0] %.0f ms, [0,+1s] %.0f ms' % (
                label, a1 - a0, rates, cpu(gl, dn - 1.0, dn) * 1000, cpu(gl, dn, dn + 1.0) * 1000))

    logcat_section(p, a, run, gestures)
    (run / a.out).write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('\n'.join(out))


if __name__ == '__main__':
    main()
