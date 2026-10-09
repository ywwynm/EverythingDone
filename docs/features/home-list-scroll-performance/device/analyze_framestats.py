"""Parse gfxinfo framestats dumps of one run and summarise per set and per gesture.

Every framestats dump holds only the most recent 120 frames, so each set is dumped once
after every gesture (once the fling is over) plus once at the end; the dumps of a set are
merged by FrameTimelineVsyncId and IntendedVsync.

Clocks: framestats timestamps are CLOCK_MONOTONIC ns; marks.jsonl carries /proc/uptime
(CLOCK_BOOTTIME, 10 ms). --boot-minus-mono converts marks onto the frame clock; without it
the offset is estimated from each set's reset mark and the 'Stats since' of its first dump.

Usage: python analyze_framestats.py <run_dir> [--boot-minus-mono SECONDS]
Writes <run_dir>/framestats_summary.json and framestats_summary.txt.
"""
import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path

STAGES = [
    ('startDelay', 'IntendedVsync', 'HandleInputStart'),
    ('input', 'HandleInputStart', 'AnimationStart'),
    ('anim', 'AnimationStart', 'PerformTraversalsStart'),
    ('layout', 'PerformTraversalsStart', 'DrawStart'),
    ('draw', 'DrawStart', 'SyncQueued'),
    ('syncWait', 'SyncQueued', 'SyncStart'),
    ('sync', 'SyncStart', 'IssueDrawCommandsStart'),
    ('issue', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('swap', 'SwapBuffers', 'FrameCompleted'),
    ('gpu', 'SwapBuffers', 'GpuCompleted'),
    ('total', 'IntendedVsync', 'FrameCompleted'),
    ('deadline', 'IntendedVsync', 'FrameDeadline'),
]
MAIN_WINDOW = 'com.ywwynm.everythingdone/com.ywwynm.everythingdone.activities.ThingsActivity'


def parse_dump(path):
    """Return {window_name: {'stats_since': ns, 'total_rendered': n, 'frames': [dict]}}."""
    text = path.read_text(encoding='utf-8', errors='replace')
    lines = text.splitlines()
    out = {}
    window = None
    i = 0
    while i < len(lines):
        line = lines[i]
        m = re.match(r'^Window: (.+)$', line.strip())
        if m:
            window = m.group(1).strip()
            out.setdefault(window, dict(stats_since=None, total_rendered=None, frames=[]))
        m = re.match(r'^Stats since: (\d+)ns', line.strip())
        if m and window is not None:
            out[window]['stats_since'] = int(m.group(1))
        m = re.match(r'^Total frames rendered: (\d+)', line.strip())
        if m and window is not None:
            out[window]['total_rendered'] = int(m.group(1))
        if line.strip() == '---PROFILEDATA---' and window is not None:
            j = i + 1
            header = None
            while j < len(lines) and lines[j].strip() != '---PROFILEDATA---':
                if not lines[j].strip():
                    j += 1
                    continue
                row = [c.strip() for c in lines[j].split(',')]
                if row and row[-1] == '':
                    row = row[:-1]
                if header is None:
                    header = row
                elif len(row) >= len(header):
                    f = {}
                    for k, v in zip(header, row):
                        try:
                            f[k] = int(v)
                        except ValueError:
                            f[k] = v
                    # 魅族 Android 15 的表头把这两列顺序写反；按量纲识别，保留其余字段。
                    if f.get('FrameInterval', 0) > 10**12 and 0 < f.get('FrameStartTime', 0) < 10**9:
                        f['FrameInterval'], f['FrameStartTime'] = f['FrameStartTime'], f['FrameInterval']
                    out[window]['frames'].append(f)
                j += 1
            i = j
        i += 1
    return out


def stages(f):
    s = {}
    for name, a, b in STAGES:
        if f.get(a) and f.get(b):
            s[name] = round((f[b] - f[a]) / 1e6, 3)
    s['overdue'] = bool(f.get('FrameCompleted') and f.get('FrameDeadline') and f['FrameCompleted'] > f['FrameDeadline'])
    s['interval'] = round(f.get('FrameInterval', 0) / 1e6, 3)
    s['dequeue'] = round(f.get('DequeueBufferDuration', 0) / 1e6, 3)
    s['queue'] = round(f.get('QueueBufferDuration', 0) / 1e6, 3)
    return s


def load_marks(run):
    marks = []
    p = run / 'marks.jsonl'
    if p.exists():
        for line in p.read_text(encoding='utf-8').splitlines():
            if line.strip():
                marks.append(json.loads(line))
    return marks


def pct(values, q):
    if not values:
        return None
    v = sorted(values)
    k = (len(v) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return round(v[lo] + (v[hi] - v[lo]) * (k - lo), 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('run', type=Path)
    ap.add_argument('--boot-minus-mono', type=float, default=None)
    a = ap.parse_args()
    run = a.run
    marks = load_marks(run)
    files = sorted(run.glob('*_framestats.txt'))
    sets = defaultdict(list)
    for f in files:
        m = re.match(r'^(set\d+[a-z_]*?)(?:_g(\d+))?_framestats\.txt$', f.name)
        if not m:
            continue
        sets[m.group(1)].append((int(m.group(2)) if m.group(2) else 99, f))
    summary = {}
    text = []
    for set_name in sorted(sets):
        dumps = sorted(sets[set_name])
        merged = {}
        per_dump = []
        stats_since = None
        for order, path in dumps:
            d = parse_dump(path).get(MAIN_WINDOW)
            if d is None:
                continue
            if stats_since is None:
                stats_since = d['stats_since']
            ids = []
            for f in d['frames']:
                key = (f.get('FrameTimelineVsyncId'), f.get('IntendedVsync'))
                merged[key] = f
                ids.append(f.get('IntendedVsync'))
            per_dump.append(dict(file=path.name, frames=len(d['frames']), total_rendered=d['total_rendered'],
                                 first_vsync=min(ids) if ids else None, last_vsync=max(ids) if ids else None))
        frames = sorted(merged.values(), key=lambda f: f['IntendedVsync'])
        # clock offset
        reset = next((m for m in marks if m.get('label') == '%s.reset' % set_name), None)
        if a.boot_minus_mono is not None:
            off = a.boot_minus_mono
            off_src = 'argument'
        elif reset and stats_since:
            off = reset['uptime'] - stats_since / 1e9
            off_src = 'reset mark vs Stats since (reset lags the mark by one adb round trip)'
        else:
            off = None
            off_src = 'none'
        gestures = [m for m in marks if m.get('kind') in ('swipe', 'hold_slide') and m['label'].startswith(set_name + '.')]
        dumps_marks = [m for m in marks if m.get('label', '').startswith(set_name + '_') and '.dump.' in m.get('label', '')]
        rows = []
        for f in frames:
            s = stages(f)
            t = f['IntendedVsync'] / 1e9
            row = dict(vsync_id=f.get('FrameTimelineVsyncId'), intended_vsync_ns=f['IntendedVsync'],
                       input_event_id=f.get('InputEventId'), flags=f.get('Flags'), **s)
            if off is not None:
                tb = t + off
                row['t_boot'] = round(tb, 4)
                prev = [g for g in gestures if g['uptime0'] <= tb + 0.02]
                if prev:
                    g = prev[-1]
                    row['gesture'] = g['label']
                    row['t_from_gesture_ms'] = round((tb - g['uptime0']) * 1000, 1)
                near = [m for m in dumps_marks if abs(m['uptime'] - tb) < 0.35]
                if near:
                    row['near_dump_mark'] = near[0]['label']
            rows.append(row)
        totals = [r['total'] for r in rows if 'total' in r]
        overdue = [r for r in rows if r['overdue']]
        longest = sorted(rows, key=lambda r: -r.get('total', 0))[:8]
        per_gesture = []
        for g in gestures:
            gr = [r for r in rows if r.get('gesture') == g['label'] and r.get('t_from_gesture_ms', 1e9) < 2500]
            gt = [r['total'] for r in gr if 'total' in r]
            # coverage: did we keep frames from before the gesture began?
            before = [r for r in rows if off is not None and r['t_boot'] < g['uptime0']]
            per_gesture.append(dict(
                label=g['label'], x=g.get('x'), y=g.get('y1', g.get('y')), uptime0=g['uptime0'],
                frames_2p5s=len(gr), overdue=sum(1 for r in gr if r['overdue']),
                p50=pct(gt, 0.5), p90=pct(gt, 0.9), max=max(gt) if gt else None,
                intervals=sorted({r['interval'] for r in gr}),
                has_frames_before_begin=bool(before)))
        summary[set_name] = dict(
            dumps=per_dump, stats_since_ns=stats_since, boot_minus_mono=off, offset_source=off_src,
            frames=len(rows), overdue=len(overdue), p50=pct(totals, 0.5), p90=pct(totals, 0.9),
            max=max(totals) if totals else None, intervals=sorted({r['interval'] for r in rows}),
            per_gesture=per_gesture, overdue_frames=overdue, longest=longest, rows=rows)
        text.append('=' * 100)
        text.append('%s: %d unique frames, overdue %d, total p50 %s p90 %s max %s ms, frame intervals %s' % (
            set_name, len(rows), len(overdue), pct(totals, 0.5), pct(totals, 0.9), max(totals) if totals else None,
            sorted({r['interval'] for r in rows})))
        text.append('offset boot-mono=%s (%s)' % (off, off_src))
        for d in per_dump:
            text.append('  dump %s frames=%d rendered_since_reset=%s' % (d['file'], d['frames'], d['total_rendered']))
        for g in per_gesture:
            text.append('  %s start=(%s,%s) frames<=2.5s=%d overdue=%d p50=%s p90=%s max=%s intervals=%s before_begin_kept=%s' % (
                g['label'], g['x'], g['y'], g['frames_2p5s'], g['overdue'], g['p50'], g['p90'], g['max'],
                g['intervals'], g['has_frames_before_begin']))
        cols = ('startDelay', 'input', 'anim', 'layout', 'draw', 'syncWait', 'sync', 'issue', 'swap', 'gpu', 'total', 'deadline', 'interval', 'dequeue', 'queue')
        text.append('  overdue frames:')
        text.append('    %-16s %-22s %8s ' % ('vsync', 'gesture', 't_ms') + ' '.join('%8s' % c for c in cols))
        for r in overdue:
            text.append('    %-16s %-22s %8s ' % (r['intended_vsync_ns'], r.get('gesture', '-'), r.get('t_from_gesture_ms', '-')) +
                        ' '.join('%8s' % r.get(c, '-') for c in cols) + ('  [%s]' % r['near_dump_mark'] if 'near_dump_mark' in r else ''))
        text.append('  longest 8:')
        for r in longest:
            text.append('    %-16s %-22s %8s ' % (r['intended_vsync_ns'], r.get('gesture', '-'), r.get('t_from_gesture_ms', '-')) +
                        ' '.join('%8s' % r.get(c, '-') for c in cols) + (' OVERDUE' if r['overdue'] else '') +
                        ('  [%s]' % r['near_dump_mark'] if 'near_dump_mark' in r else ''))
    (run / 'framestats_summary.json').write_text(json.dumps(summary, indent=1), encoding='utf-8')
    (run / 'framestats_summary.txt').write_text('\n'.join(text) + '\n', encoding='utf-8')
    print('\n'.join(text))


if __name__ == '__main__':
    main()
