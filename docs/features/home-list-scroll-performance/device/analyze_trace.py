"""Attribute overdue frames of one trace set to app-thread slices and CPU time.

Line parsing and B/E pairing follow
docs/features/dialog-particle-dismiss/tuning-0909-gpt6/analysis/analyze_android_window_trace.py.

Clocks: atrace timestamps are CLOCK_BOOTTIME seconds; framestats are CLOCK_MONOTONIC ns.
The offset is measured by matching 'Choreographer#doFrame <vsyncId>' (main thread) with the
framestats row of the same FrameTimelineVsyncId (FrameStartTime is stamped inside the same
doFrame call).

Usage:
  python analyze_trace.py <run_dir> <trace_file> <set_name> [--pid 6064] [--window-ms 300]
Writes <run_dir>/trace_<set_name>.json and trace_<set_name>.txt.
"""
import argparse
import json
import re
import statistics
import sys
from bisect import bisect_left
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_framestats import parse_dump, stages, MAIN_WINDOW  # noqa: E402

LINE = re.compile(r'^\s*(.+)-(\d+)\s+\(\s*([^)]*)\)\s+\[(\d+)\]\s+\S+\s+(\d+\.\d+):\s+(\w+):\s+(.*)$')
SWITCH = re.compile(r'prev_comm=(.*) prev_pid=(\d+) prev_prio=(-?\d+) prev_state=(\S+) ==> '
                    r'next_comm=(.*) next_pid=(\d+) next_prio=(-?\d+)')
SEARCH_NAMES = ['HardwareBitmap readback', 'copyHWBitmapInto', 'particle-material', 'RV OnBindView',
                'RV OnLayout', 'RV Scroll', 'RV Prefetch', 'inflate', 'Choreographer#doFrame', 'DrawFrames',
                'syncFrameState', 'dequeueBuffer', 'GifDecoder', 'decode', 'ParticleSnapshotCopy',
                'ParticleMaterial', 'ParticleGl', 'glide-source', 'WorkManager', 'ImageReader',
                'copyImageInto', 'Texture upload', 'Bitmap#prepareToDraw', 'saveLayer', 'buildDrawingCache']


def parse_trace(path, pid):
    tgid_of = {}
    name_of = {}
    stacks = defaultdict(list)
    spans = []
    marks = []
    clock = {}
    switches = []  # (ts, cpu, prev_pid, prev_state, next_pid)
    with path.open(encoding='utf-8', errors='replace') as fh:
        for line in fh:
            m = LINE.match(line)
            if not m:
                continue
            task, tid, group, cpu, ts, event, msg = m.groups()
            tid = int(tid)
            ts = float(ts)
            g = group.strip()
            if g.isdigit():
                tgid_of[tid] = int(g)
            name_of.setdefault(tid, task.strip())
            if event == 'sched_switch':
                s = SWITCH.search(msg)
                if s:
                    pc, pp, _, pstate, nc, np_, _ = s.groups()
                    pp = int(pp)
                    np_ = int(np_)
                    name_of[pp] = pc
                    name_of[np_] = nc
                    switches.append((ts, int(cpu), pp, pstate, np_))
                continue
            if event != 'tracing_mark_write':
                continue
            if msg.startswith('HlsMark:'):
                marks.append((ts, msg[len('HlsMark:'):].strip()))
                continue
            if 'trace_event_clock_sync:' in msg:
                k, _, v = msg.split('trace_event_clock_sync:')[1].strip().partition('=')
                clock.setdefault(k, (ts, v))
                continue
            if g != str(pid):
                continue
            parts = msg.split('|', 3)
            kind = parts[0]
            if kind == 'B' and len(parts) >= 3:
                stacks[tid].append((ts, '|'.join(parts[2:])))
            elif kind == 'E':
                if stacks[tid]:
                    start, label = stacks[tid].pop()
                    spans.append(dict(tid=tid, name=label, start=start, end=ts,
                                      ms=(ts - start) * 1000, depth=len(stacks[tid])))
    return dict(tgid_of=tgid_of, name_of=name_of, spans=spans, marks=marks, clock=clock, switches=switches)


def running_intervals(switches, keep_tids):
    """Per tid list of (start, end, cpu) built from sched_switch."""
    current = {}
    out = defaultdict(list)
    for ts, cpu, prev, _, nxt in switches:
        cur = current.get(cpu)
        if cur is not None and cur[0] == prev and prev in keep_tids:
            out[prev].append((cur[1], ts, cpu))
        current[cpu] = (nxt, ts)
    return out


def overlap(a0, a1, b0, b1):
    return max(0.0, min(a1, b1) - max(a0, b0))


def cpu_in(intervals, name_of, lo, hi, top=8):
    per_name = defaultdict(float)
    per_tid = {}
    for tid, ivs in intervals.items():
        t = 0.0
        for s, e, _ in ivs:
            if e < lo or s > hi:
                continue
            t += overlap(s, e, lo, hi)
        if t > 0:
            per_name[name_of.get(tid, str(tid))] += t
            per_tid[tid] = t
    rows = sorted(per_name.items(), key=lambda kv: -kv[1])[:top]
    return [(n, round(v * 1000, 2)) for n, v in rows]


def load_set_frames(run, set_name):
    merged = {}
    for f in sorted(run.glob('%s*_framestats.txt' % set_name)):
        d = parse_dump(f).get(MAIN_WINDOW)
        if not d:
            continue
        for fr in d['frames']:
            merged[(fr.get('FrameTimelineVsyncId'), fr.get('IntendedVsync'))] = fr
    return sorted(merged.values(), key=lambda fr: fr['IntendedVsync'])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('run', type=Path)
    ap.add_argument('trace', type=Path)
    ap.add_argument('set_name')
    ap.add_argument('--pid', type=int, default=6064)
    ap.add_argument('--min-ms', type=float, default=2.0)
    ap.add_argument('--window-ms', type=float, default=300.0)
    a = ap.parse_args()
    tr = parse_trace(a.trace, a.pid)
    name_of, tgid_of, spans = tr['name_of'], tr['tgid_of'], tr['spans']
    app_tids = {t for t, g in tgid_of.items() if g == a.pid}
    main_tid = a.pid
    rt_tids = {t for t in app_tids if name_of.get(t) == 'RenderThread'}
    frames = load_set_frames(a.run, a.set_name)

    # clock offset from vsync ids
    doframes = {}
    for s in spans:
        if s['tid'] == main_tid and s['name'].startswith('Choreographer#doFrame '):
            m = re.match(r'Choreographer#doFrame (\d+)', s['name'])
            if m:
                doframes.setdefault(int(m.group(1)), s)
    diffs = []
    for fr in frames:
        s = doframes.get(fr.get('FrameTimelineVsyncId'))
        if s and fr.get('FrameStartTime'):
            diffs.append(s['start'] - fr['FrameStartTime'] / 1e9)
    if not diffs:
        raise SystemExit('no vsync-id matches between framestats and trace')
    off = statistics.median(diffs)
    spread = (min(diffs) - off, max(diffs) - off)

    by_tid = defaultdict(list)
    for s in spans:
        by_tid[s['tid']].append(s)
    intervals = running_intervals(tr['switches'], app_tids)

    def thread_label(tid):
        if tid == main_tid:
            return 'main'
        return '%s(%d)' % (name_of.get(tid, '?'), tid)

    def slices_in(lo, hi, min_ms):
        rows = []
        for s in spans:
            if s['end'] < lo or s['start'] > hi or s['ms'] < min_ms:
                continue
            rows.append(dict(thread=thread_label(s['tid']), name=s['name'][:120], depth=s['depth'],
                             start_ms=round((s['start'] - lo) * 1000, 2), dur_ms=round(s['ms'], 2)))
        rows.sort(key=lambda r: (r['thread'] != 'main', not r['thread'].startswith('RenderThread'), r['thread'], r['start_ms']))
        return rows

    trace_lo = min(s['start'] for s in spans)
    trace_hi = max(s['end'] for s in spans)
    out = dict(trace=a.trace.name, set=a.set_name, pid=a.pid, boot_minus_mono=off,
               offset_matches=len(diffs), offset_spread_ms=[round(v * 1000, 3) for v in spread],
               clock_sync=tr['clock'], marks=[(round(t, 6), l) for t, l in tr['marks']],
               app_threads=sorted({name_of.get(t, '?') for t in app_tids}))
    text = ['trace %s set %s: boot-mono offset %.6f s from %d doFrame matches (spread %s ms)' % (
        a.trace.name, a.set_name, off, len(diffs), out['offset_spread_ms'])]

    # frames in trace time
    fr_rows = []
    for fr in frames:
        st = stages(fr)
        lo = fr['IntendedVsync'] / 1e9 + off
        hi = fr['FrameCompleted'] / 1e9 + off if fr.get('FrameCompleted') else lo
        fr_rows.append((fr, st, lo, hi))
    in_trace = [r for r in fr_rows if trace_lo <= r[2] <= trace_hi]
    overdue = [r for r in in_trace if r[1]['overdue']]
    text.append('frames inside trace window: %d, overdue: %d' % (len(in_trace), len(overdue)))
    gesture_marks = [(t, l) for t, l in tr['marks'] if re.search(r'\.g\d+\.(up|down)\.begin$', l)]

    def gesture_of(t):
        prev = [(gt, gl) for gt, gl in gesture_marks if gt <= t + 0.01]
        if not prev:
            return None, None
        gt, gl = prev[-1]
        return gl.replace('.begin', ''), round((t - gt) * 1000, 1)

    out['overdue'] = []
    for fr, st, lo, hi in overdue:
        g, dt = gesture_of(lo)
        rec = dict(vsync_id=fr.get('FrameTimelineVsyncId'), intended_vsync_ns=fr['IntendedVsync'],
                   trace_lo=round(lo, 6), trace_hi=round(hi, 6), gesture=g, t_from_gesture_mark_ms=dt,
                   stages=st, slices=slices_in(lo, hi, a.min_ms), cpu_top8=cpu_in(intervals, name_of, lo, hi))
        out['overdue'].append(rec)
        text.append('-' * 100)
        text.append('OVERDUE vsync_id=%s intended=%d gesture=%s t_from_mark=%s ms total=%s deadline=%s' % (
            rec['vsync_id'], rec['intended_vsync_ns'], g, dt, st.get('total'), st.get('deadline')))
        text.append('  stages: ' + ' '.join('%s=%s' % (k, st.get(k)) for k in (
            'startDelay', 'input', 'anim', 'layout', 'draw', 'syncWait', 'sync', 'issue', 'swap', 'gpu', 'dequeue', 'queue')))
        text.append('  slices >= %.1f ms overlapping [IntendedVsync, FrameCompleted]:' % a.min_ms)
        for r in rec['slices']:
            text.append('    %-28s d%d %+9.2f %8.2f  %s' % (r['thread'], r['depth'], r['start_ms'], r['dur_ms'], r['name']))
        text.append('  app CPU time in interval (ms, by thread name): %s' % rec['cpu_top8'])

    # DOWN timelines: every gesture
    downs = [s for s in spans if s['tid'] == main_tid and s['name'].startswith('dispatchInputEvent MotionEvent DOWN')]
    moves = [s for s in spans if s['tid'] == main_tid and s['name'].startswith('dispatchInputEvent MotionEvent MOVE')]
    ups = [s for s in spans if s['tid'] == main_tid and s['name'].startswith('dispatchInputEvent MotionEvent UP')]
    out['gestures'] = []
    for gt, gl in gesture_marks:
        d = next((s for s in downs if s['start'] >= gt), None)
        if d is None:
            continue
        lo = d['start']
        hi = lo + a.window_ms / 1000.0
        mv = next((s for s in moves if s['start'] >= lo), None)
        up = next((s for s in ups if s['start'] >= lo), None)
        rec = dict(gesture=gl.replace('.begin', ''), mark=round(gt, 6), down=round(lo, 6),
                   mark_to_down_ms=round((lo - gt) * 1000, 2),
                   first_move_after_down_ms=round((mv['start'] - lo) * 1000, 2) if mv else None,
                   up_after_down_ms=round((up['start'] - lo) * 1000, 2) if up else None,
                   slices=slices_in(lo, hi, 1.0), cpu_top8=cpu_in(intervals, name_of, lo, hi))
        agg = defaultdict(lambda: defaultdict(lambda: [0, 0.0]))
        for sp in spans:
            if sp['depth'] != 0 or sp['end'] < lo or sp['start'] > hi:
                continue
            ov = overlap(sp['start'], sp['end'], lo, hi) * 1000
            key = re.sub(r'\d{5,}', 'N', sp['name'])[:60]
            cell = agg[thread_label(sp['tid'])][key]
            cell[0] += 1
            cell[1] += ov
        rec['top_level_slices_by_thread'] = {
            th: sorted([(k, v[0], round(v[1], 2)) for k, v in names.items()], key=lambda x: -x[2])[:8]
            for th, names in agg.items()}
        out['gestures'].append(rec)
        text.append('=' * 100)
        text.append('GESTURE %s: DOWN dispatched %.2f ms after mark; first MOVE dispatch +%s ms; UP dispatch +%s ms' % (
            rec['gesture'], rec['mark_to_down_ms'], rec['first_move_after_down_ms'], rec['up_after_down_ms']))
        text.append('  slices >= 1 ms within [DOWN, DOWN+%d ms] (start offset from DOWN):' % a.window_ms)
        for r in rec['slices']:
            text.append('    %-28s d%d %+9.2f %8.2f  %s' % (r['thread'], r['depth'], r['start_ms'], r['dur_ms'], r['name']))
        text.append('  app CPU time in window (ms, by thread name): %s' % rec['cpu_top8'])
        text.append('  depth-0 slices clipped to the window, per thread (name, count, ms):')
        for th, rows in sorted(rec['top_level_slices_by_thread'].items()):
            text.append('    %-28s %s' % (th, rows))

    # name search
    found = {}
    for key in SEARCH_NAMES:
        hits = [s for s in spans if key in s['name']]
        def name_match(comm):
            c = (comm or '').lower()
            k = key.lower()
            # thread comm names are truncated to 15 characters
            return k in c or (len(c) >= 15 and k.startswith(c))
        tids = [t for t in app_tids if name_match(name_of.get(t, ''))]
        cpu = 0.0
        for t in tids:
            cpu += sum(e - s for s, e, _ in intervals.get(t, []))
        found[key] = dict(slices=len(hits), slice_total_ms=round(sum(h['ms'] for h in hits), 2),
                          slice_max_ms=round(max((h['ms'] for h in hits), default=0), 2),
                          slice_threads=sorted({thread_label(h['tid']) for h in hits})[:6],
                          threads_named=sorted({'%s(%d)' % (name_of.get(t), t) for t in tids}),
                          thread_cpu_ms=round(cpu * 1000, 2))
    out['search'] = found
    text.append('=' * 100)
    text.append('NAME SEARCH (slices of pid %d; thread names of pid %d):' % (a.pid, a.pid))
    for k, v in found.items():
        text.append('  %-24s slices=%d total=%sms max=%sms on=%s | threads=%s cpu=%sms' % (
            k, v['slices'], v['slice_total_ms'], v['slice_max_ms'], v['slice_threads'], v['threads_named'], v['thread_cpu_ms']))
    text.append('app threads seen: %s' % out['app_threads'])
    (a.run / ('trace_%s.json' % a.set_name)).write_text(json.dumps(out, indent=1), encoding='utf-8')
    (a.run / ('trace_%s.txt' % a.set_name)).write_text('\n'.join(text) + '\n', encoding='utf-8')
    print('\n'.join(text))


if __name__ == '__main__':
    main()
