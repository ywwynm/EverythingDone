"""归档精简结果；保留无效样本和未完成项，避免把部分通过写成全通过。"""
from pathlib import Path
import json
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
OUT = Path(__file__).resolve().parent
DATA = ROOT / "tmp/media-memory-20261015"
RAW = ROOT / "tmp/selection-transition-20261013"
paired = {}
for device, before, after in (("samsung", "a2", "b1"), ("meizu", "a1", "b2"), ("oppo", "a2", "b1")):
    pair = {}
    for label, suffix in (("legacy", before), ("continuous", after)):
        name = f"perf-{device}-{suffix}-1015"
        summary = json.loads((DATA / name / "summary.json").read_text(encoding="utf-8"))
        info = json.loads((RAW / name / "run-info.json").read_text(encoding="utf-8"))
        assert summary["ok"] and summary["originalUnchanged"]
        selected = {k: v for k, v in summary.items() if k not in ("runtimeBefore", "runtimeAfter", "snapshots")}
        selected["apkSha256"] = info["apkSha256"]
        selected["snapshots"] = [{"phase": s["phase"], "pssMiB": round(s["pssKb"]/1024, 2),
            "aliveLoaders": len(s["aliveLoaders"]), "runningLoaders": sum(x["running"] for x in s["aliveLoaders"]),
            "knownGifFrameBitmapBytes": s["knownGifFrameBitmapBytes"], "pool": s["pool"]} for s in summary["snapshots"]]
        for phase in ("before", "after"):
            battery = (DATA / name / f"{phase}-battery.txt").read_text(errors="replace")
            thermal = (DATA / name / f"{phase}-thermalservice.txt").read_text(errors="replace")
            selected[phase + "Environment"] = {"batteryC": int(re.search(r"  temperature: (\d+)", battery)[1])/10,
                "thermalStatus": int(re.search(r"Thermal Status: (\d+)", thermal)[1])}
        selected["measurementLimits"] = []
        if device == "oppo": selected["measurementLimits"].append("没有 FrameTimeline，仅窗口 FrameMetrics")
        if device == "meizu": selected["measurementLimits"].append("fling 的 FrameMetrics 缺报，不据此判断惯性长帧")
        if name == "perf-meizu-b2-1015":
            selected["measurementLimits"].append("结束 thermal status=3；normal-fling-down 呈现轨迹有记录空洞，按用户要求暂缓复核")
            selected["groups"]["normal-fling-down"]["presentationVerdict"] = "未确认，不能当作有效的 58.95 FPS 或 3.48 秒卡屏"
        pair[label] = selected
    assert pair["legacy"]["apkSha256"] == pair["continuous"]["apkSha256"], "完整对照必须使用同一 APK"
    paired[device] = pair

assert len({v[policy]["apkSha256"] for v in paired.values() for policy in ("legacy", "continuous")}) == 1

result = {"date": "2026-10-15", "overallNoRegressionConfirmed": False,
    "productionCodeChangedThisRound": False,
    "scopeDecision": {"userInstruction": "先按已有结果整理", "meizuRetestDeferredByUser": True,
        "status": "已有结果已归档，魅族补测暂缓；不等待解锁后自动继续"},
    "fixture": {"cards": 97, "media": 80, "animated": 48, "checklistCards": 32, "itemsPerChecklist": 96},
    "paired": paired,
    "incompleteRuns": {
        "perf-samsung-a1-1015": "前六区间完整；拖动入口字段名错误，未完成整轮",
        "perf-meizu-b1-1015": "前六区间完整；拖动入口字段名错误，未完成整轮",
        "perf-oppo-a1n-1015": "前六区间完整；拖动入口字段名错误，未完成整轮",
        "perf-oppo-a1-1015": "进入列表前 ADB 断连，无有效性能区间",
        "perf-oppo-a1r-1015": "进入列表前 ADB 断连，无有效性能区间",
        "perf-meizu-a3-1015": "冷却补测被 PIN 锁屏阻断，无有效性能区间"},
    "pending": ["按用户要求暂缓：魅族冷却状态下的惯性呈现复核", "进一步分离惯性阶段的活动解码、分配器保留与图形缓存开销；不得牺牲播放及 UI"],
    "notes": ["只切换暂停策略，其余实现和缓存预算相同；不是两个历史完整 APK 的比较",
        "整体峰值约增加 2～5%，三星和魅族惯性工作阶段的中位数增加明显，不能宣称零内存代价",
        "六轮完整运行退出后运行 loader 均为 0；未发现 OOM 或媒体加载失败"]}
result["cleanup"] = [json.loads((RAW / n / "memory-cleanup.json").read_text(encoding="utf-8")) for n in
    ("perf-samsung-a1-1015", "perf-meizu-b1-1015", "perf-oppo-a1-1015")]
result["realSelectionSmoke"] = {}
for name in ("perf-samsung-smoke-1015", "perf-oppo-smoke-1015"):
    smoke = json.loads((RAW / name / "result.json").read_text(encoding="utf-8"))
    result["realSelectionSmoke"][name] = {"ok": smoke["ok"], "cards": smoke["performance"]["count"],
        "phases": len(smoke["performance"]["phases"])}
(OUT / "scroll-performance-results-2026-10-15.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print("完整同 APK 配对已归档；整体无退化结论保留为未确认。")
