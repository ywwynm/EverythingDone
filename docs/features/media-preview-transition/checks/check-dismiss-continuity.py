"""检查真实返回时间线；输入探针 result.json，不读取原记事内容。"""
import json
import pathlib
import sys

failures = []
count = 0
for argument in sys.argv[1:]:
    path = pathlib.Path(argument)
    report = json.loads(path.read_text(encoding="utf-8-sig"))
    for run in report["runs"]:
        if not run["name"].endswith("-return"):
            continue
        count += 1
        timeline = run["timeline"]
        start = next((time for event, time in timeline if event in ("return-interactive-start", "return-capture")), None)
        moved = next((time for event, time in timeline if event.startswith("return-progress:") and float(event.split(":")[1]) > 0), None)
        if start is None or moved is None:
            failures.append({"file": path.name, "scene": run["name"], "missingTimeline": True})
            continue
        delay = (moved - start) / 1e6
        if delay > 48:
            failures.append({"file": path.name, "scene": run["name"], "delayMs": round(delay, 2)})
print(json.dumps({"checked": count, "failures": failures}, ensure_ascii=False))
sys.exit(1 if failures or count == 0 else 0)
