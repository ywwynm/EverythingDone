"""按真实输入区间分析窗口帧及 FrameTimeline；不把静止 GIF 帧计作滑动帧。"""
from pathlib import Path
from collections import defaultdict
import bisect
import csv
import io
import json
import statistics
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
TP = ROOT / "tmp/home-list-scroll-20261009/trace_processor_shell.exe"

def percentile(values, p):
    a = sorted(values)
    if not a: return None
    ix = (len(a)-1)*p/100
    lo = int(ix)
    return a[lo] + (a[min(lo+1, len(a)-1)]-a[lo])*(ix-lo)

def distribution(values):
    return {k: round(v, 3) if v is not None else None for k, v in {
        "median": percentile(values, 50), "p95": percentile(values, 95),
        "p99": percentile(values, 99), "max": max(values, default=None)}.items()}

def analyze(run):
    d = ROOT / "tmp/selection-transition-20261013" / run
    t = ROOT / "tmp/media-memory-20261015" / run
    result = json.loads((d / "perf-result.json").read_text(encoding="utf-8"))
    verdict = json.loads((d / "result.json").read_text(encoding="utf-8"))
    spans = sorted(result["spans"], key=lambda r: r["start"])
    starts = [s["start"] for s in spans]
    def containing(ns):
        i = bisect.bisect_right(starts, ns)-1
        return spans[i] if i >= 0 and ns <= spans[i]["end"] else None
    def key(s):
        # fling 的手指加速段单独列出，避免掺进松手后的惯性。
        if "fling" in s["phase"] and s["part"] == "finger": return s["phase"] + "-input"
        return s["phase"]
    grouped = defaultdict(list)
    for r in csv.DictReader((d / "perf-frames.csv").open(encoding="utf-8")):
        r = {k: int(v) for k, v in r.items()}
        s = containing(r["vsync"])
        if s: grouped[key(s)].append(r)
    memory = json.loads((d / "perf-memory.json").read_text(encoding="utf-8"))
    samples = json.loads((d / "perf-samples.json").read_text(encoding="utf-8"))
    def query(sql):
        r = subprocess.run([str(TP), "query", str(t / "trace.perfetto-trace"), sql], capture_output=True, check=True)
        return list(csv.DictReader(io.StringIO(r.stdout.decode())))
    cache = t / "timeline.json"
    if cache.exists(): timeline = json.loads(cache.read_text(encoding="utf-8"))
    elif (t / "trace.perfetto-trace").exists():
        clocks = query("SELECT ts-clock_value offset FROM clock_snapshot WHERE clock_name='MONOTONIC' LIMIT 1")
        layer = query("SELECT layer_name,COUNT(*) n FROM actual_frame_timeline_slice WHERE layer_name LIKE '%batchvalidation%ThingsActivity%' AND layer_name NOT LIKE '%SurfaceView%' AND layer_name NOT LIKE '%Bounds for%' AND layer_name NOT LIKE '%Background for%' GROUP BY layer_name ORDER BY n DESC LIMIT 1")
        assert layer, "没有主窗口 FrameTimeline"
        name = layer[0]["layer_name"].replace("'", "''")
        timeline = {"offset": int(clocks[0]["offset"]), "layer": layer[0]["layer_name"], "frames": query(
            "SELECT ts,dur,present_type,on_time_finish,jank_type,surface_frame_token FROM actual_frame_timeline_slice WHERE layer_name='" + name + "' ORDER BY ts")}
        cache.write_text(json.dumps(timeline), encoding="utf-8")
    else:
        timeline = {"offset": 0, "layer": None, "frames": []}
    presented = defaultdict(list)
    for row in timeline["frames"]:
        row = dict(row); row["ns"] = int(row["ts"]) - timeline["offset"]
        s = containing(row["ns"])
        if s: presented[key(s)].append(row)
    group_results = {}
    for name, rows in grouped.items():
        duration = sum((s["end"]-s["start"])/1e9 for s in spans if key(s) == name)
        eligible = [r for r in rows if r["deadline"] > 0]
        phase = next((p for p in result["phases"] if p["name"] == name), None)
        mem = [m for m in memory if phase and phase["start"] <= m["ns"] <= phase["end"]]
        smp = [s for s in samples if s["phase"] == name]
        gifs = defaultdict(set)
        shown = set(); ready = set(); playing = 0; spinner = 0
        for s in smp:
            for c in s["cards"]:
                shown.add(c["id"])
                if "drawable" in c: ready.add(c["id"])
                active_span = containing(s["ns"])
                if active_span is None or key(active_span) != name: continue
                if "gif" in c:
                    gifs[(c["id"], c["gif"])].add(c["frame"])
                    playing += bool(c["playing"])
                spinner += bool(c["spinner"])
        ft = presented[name]
        gaps = []
        presented_count = 0
        for s in spans:
            if key(s) != name: continue
            times = sorted(set(r["ns"] + int(r["dur"]) for r in ft
                if s["start"] <= r["ns"] <= s["end"] and r["present_type"] != "Dropped Frame" and int(r["dur"]) > 0))
            presented_count += len(times)
            gaps.extend((b-a)/1e6 for a,b in zip(times,times[1:]))
        group_results[name] = {
            "frames": len(rows), "seconds": round(duration, 2),
            "distancePx": phase["distance"] if phase else None,
            "totalMs": distribution([r["total"]/1e6 for r in rows]),
            "overDeadlinePct": round(100*sum(r["total"] >= r["deadline"] for r in eligible)/max(1,len(eligible)),3),
            "over50ms": sum(r["total"] > 50e6 for r in rows), "over100ms": sum(r["total"] > 100e6 for r in rows),
            "droppedMetrics": sum(r["dropped"] for r in rows),
            "stageMs": {k: distribution([r[k]/1e6 for r in rows]) for k in ("unknown","layout","draw","sync","gpu")},
            "timelineFrames": len(ft),
            "frameMetricsCoveragePct": round(len(rows)/len(ft)*100,2) if ft else None,
            "presentationGapMs": distribution(gaps),
            "presentationGapsOver25ms": sum(g > 25 for g in gaps),
            "presentationGapsOver50ms": sum(g > 50 for g in gaps),
            "presentedFramesPerSecond": round(presented_count/duration,2) if ft else None,
            "timelineAppJankPct": round(100*sum("App Deadline Missed" in r["jank_type"] for r in ft)/len(ft),3) if ft else None,
            "timelineAnyJankPct": round(100*sum(r["jank_type"] != "None" for r in ft)/len(ft),3) if ft else None,
            "timelineLatePct": round(100*sum(r["present_type"] == "Late Present" for r in ft)/len(ft),3) if ft else None,
            "pssPeakMiB": round(max((m["pssKb"] for m in mem), default=0)/1024,2),
            "pssMedianMiB": round(statistics.median([m["pssKb"] for m in mem] or [0])/1024,2),
            "seenCards": len(shown), "readyCards": len(ready), "playingSamples": playing,
            "changedGifObjects": sum(len(v)>1 for v in gifs.values()), "spinnerSamples": spinner,
            "pausedSamples": sum(s["paused"] for s in smp if containing(s["ns"])), "samples": len(smp)}
    snapshots = [json.loads(l) for l in (d / "media-memory.jsonl").read_text(encoding="utf-8").splitlines()]
    active_memory = [m for m in memory if any(p["start"] <= m["ns"] <= p["end"] for p in result["phases"])]
    summary = {"run": run, "policy": result["policy"], "model": result["model"], "refreshRate": result["refreshRate"],
        "ok": verdict["ok"], "seen": result.get("seen"), "mediaReady": result.get("mediaReady"),
        "pssPeakMiB": round(max(m["pssKb"] for m in active_memory)/1024,2),
        "allIncludingScreenshotsPssPeakMiB": round(max(m["pssKb"] for m in memory)/1024,2),
        "nativePeakMiB": round(max(m["nativeBytes"] for m in active_memory)/1024**2,2),
        "javaPeakMiB": round(max(m["javaBytes"] for m in active_memory)/1024**2,2),
        "memorySampleMs": distribution([m["costNs"]/1e6 for m in memory]), "groups": group_results,
        "runtimeBefore": result["runtimeBefore"], "runtimeAfter": result.get("runtimeAfter"),
        "snapshots": [{k:v for k,v in s.items() if k not in ("images","loaders")} for s in snapshots],
        "originalUnchanged": json.loads((d/"original-before.json").read_text(encoding="utf-8"))["stdout"] == json.loads((d/"original-after.json").read_text(encoding="utf-8"))["stdout"]}
    (t / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(run, summary["ok"], "PSS峰值", summary["pssPeakMiB"])
    for k, v in group_results.items():
        if k.endswith("-input"): continue
        print(k, "p95",v["totalMs"]["p95"], "p99",v["totalMs"]["p99"], "jank",v["timelineAppJankPct"], "距离",v["distancePx"], "GIF播放样本",v["playingSamples"])
    return summary

if __name__ == "__main__":
    for run in sys.argv[1:]: analyze(run)
