"""汇总持续拖动的原始证据；可疑像素样本仍须逐图检查，不能自动解释为应用缺陷。"""
from pathlib import Path
import json
import re
import sys

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
sys.stdout.reconfigure(encoding="utf-8")


def summarize(name):
    directory = ROOT / "tmp/selection-transition-20261013" / name
    result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
    samples = [json.loads(line) for line in (directory / "drag-samples.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = [row for sample in samples for row in sample["rows"]]
    gif_ids = {row["id"] for row in rows if row.get("drawable") == "GifDrawable"}
    frames = {id_: {row.get("frame") for row in rows if row["id"] == id_ and row.get("frame", -1) >= 0} for id_ in gif_ids}
    before = json.loads((directory / "original-before.json").read_text(encoding="utf-8"))
    after = json.loads((directory / "original-after.json").read_text(encoding="utf-8"))
    summary_file = directory / "complex-summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.exists() else None
    log = (directory / "process.log").read_text(encoding="utf-8", errors="replace")
    failures = [line for line in log.splitlines() if re.search(
        r"OutOfMemoryError|FATAL EXCEPTION|failed to load|load.failed|onLoadFailed", line, re.I)]
    memory = []
    keys = ["pssKb", "nativeAllocatedBytes", "javaUsedBytes", "activeResources", "glideCacheBytes", "bitmapPoolBytes"]
    for pass_ in sorted({s["pass"] for s in samples if s["pass"] >= 0}):
        data = [s for s in samples if s["pass"] == pass_]
        entry = {"pass": pass_, "samples": len(data), "durationMs": data[-1]["time"] - data[0]["time"]}
        for key in keys:
            values = [s[key] for s in data if key in s]
            if values:
                entry[key] = {"first": values[0], "max": max(values), "last": values[-1]}
        peak = max(data, key=lambda s: s["pssKb"])
        entry["peakPssBreakdownKb"] = peak.get("memoryStats")
        memory.append(entry)
    return {"run": name, "setup": json.loads((directory / "run-info.json").read_text(encoding="utf-8")),
        "ok": result.get("ok"), "error": result.get("error"), "summary": summary,
        "originalUnchanged": before["code"] == after["code"] == 0 and before["stdout"] == after["stdout"],
        "observedGifIds": len(gif_ids), "gifIdsWithChangingFrames": sum(len(v) > 1 for v in frames.values()),
        "transparentRows": sum(r["alpha"] < .01 for r in rows),
        "transparentOriginalDragViewRows": sum(r["alpha"] < .01 and r.get("isOriginalDragView", False) for r in rows),
        "observedFailureLogLines": len(failures), "memoryByPass": memory}


if __name__ == "__main__":
    print(json.dumps([summarize(name) for name in sys.argv[1:]], ensure_ascii=False, indent=2))
