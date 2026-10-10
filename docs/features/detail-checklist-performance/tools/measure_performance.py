"""同一 Meizu、指定记事及探针下比较版本；只滚动/折叠，探针禁止保存并核对行指纹。"""
import argparse
import json
import pathlib
import statistics
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[4]
ADB = r"E:\AndroidSDK\platform-tools\adb.exe"
SERIAL = "192.168.100.67:42641"
PACKAGE = "com.ywwynm.everythingdone"
OUTPUT = ROOT / "tmp/detail-checklist-diagnosis/migration" / SERIAL.replace(":", "_")


def adb(*args):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=360)
    if p.returncode:
        raise RuntimeError((p.stdout + p.stderr).decode(errors="replace"))
    return p.stdout.decode(errors="replace")


def read_frames(path):
    rows = []
    header = None
    window = ""
    reset = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("Stats since: "):
            reset = int(line.split()[2].removesuffix("ns"))
        if line.startswith("Window: "):
            window = line
        elif line.startswith("Flags,"):
            header = line.strip(",").split(",")
        elif header and line[:1].isdigit() and "," in line and "DetailActivity" in window:
            row = dict(zip(header, map(int, line.strip(",").split(","))))
            if row["Flags"] == 0 and row["IntendedVsync"] >= reset and row["FrameCompleted"] > row["IntendedVsync"]:
                rows.append(row)
    return rows


def summary(directory):
    result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
    if result.get("error") or not result.get("data_unchanged"):
        raise RuntimeError(result)
    samples = result["samples"][1:]  # 第一轮包含进程内首次初始化，单独保留原始值。
    report = {key: statistics.median(s[key] for s in samples) for key in (
        "open_to_predraw_ms", "expand_to_predraw_ms", "initial_mounted_checklist", "expanded_mounted_checklist")}
    report["data_unchanged"] = True
    for state in ("folded", "expanded"):
        rows = [row for path in sorted(directory.glob(f"{state}-*-gfx.txt")) for row in read_frames(path)]
        times = sorted((r["FrameCompleted"] - r["IntendedVsync"]) / 1e6 for r in rows)
        report[state] = {"frames": len(rows), "deadline_miss": sum(r["FrameCompleted"] > r["FrameDeadline"] for r in rows),
                         "median_ms": statistics.median(times), "p95_ms": times[round((len(times) - 1) * 0.95)]}
    (directory / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    parser.add_argument("--apk", type=pathlib.Path)
    parser.add_argument("--trace-first-scroll", action="store_true", help="采样会影响帧率，只用于诊断")
    parser.add_argument("--settle-ms", type=int, default=0, help="首轮打开/展开预热后等候后台准备，需旧新版本保持相同")
    parser.add_argument("--compiler-filter", choices=("speed", "verify"), help="诊断用：安装后统一本应用的 ART 编译条件")
    parser.add_argument("--home-first", action="store_true", help="先进入首页，布局就绪后立即打开详情；旧新保持相同")
    parser.add_argument("--diagnostic-no-playback", action="store_true", help="仅诊断：隔离详情媒体播放调度，不是功能完整候选")
    parser.add_argument("--diagnostic-cache", type=int, default=-1, help="仅诊断：覆盖列表缓存数")
    parser.add_argument("--diagnostic-pool", type=int, default=0, help="仅诊断：首帧前准备指定数量的回收条目")
    parser.add_argument("--diagnostic-no-handwriting", action="store_true", help="仅诊断：隔离系统手写位置追踪，不作为完整候选")
    parser.add_argument("--diagnostic-no-prefetch", action="store_true", help="仅诊断：隔离 GapWorker 强制预取成本")
    args = parser.parse_args()
    if args.apk:
        print(adb("install", "-r", str(args.apk.resolve())).strip(), flush=True)
    if args.compiler_filter:
        print(adb("shell", "cmd", "package", "compile", "-m", args.compiler_filter, "-f", "--primary-dex", PACKAGE).strip(), flush=True)
    print("指定记事性能采集中", flush=True)
    result = adb("shell", "am", "instrument", "-w", "-r", "-e", "run_id", args.label,
                 "-e", "trace_first_scroll", str(args.trace_first_scroll).lower(),
                 "-e", "settle_ms", str(args.settle_ms),
                 "-e", "home_first", str(args.home_first).lower(),
                 "-e", "diagnostic_no_playback", str(args.diagnostic_no_playback).lower(),
                 "-e", "diagnostic_cache", str(args.diagnostic_cache),
                 "-e", "diagnostic_pool", str(args.diagnostic_pool),
                 "-e", "diagnostic_no_handwriting", str(args.diagnostic_no_handwriting).lower(),
                 "-e", "diagnostic_no_prefetch", str(args.diagnostic_no_prefetch).lower(),
                 "-e", "thing_id", "780", f"{PACKAGE}/.detail.DetailPerformanceProbe")
    (OUTPUT / f"{args.label}-instrumentation.txt").write_text(result, encoding="utf-8")
    if "Process crashed" in result or "INSTRUMENTATION_FAILED" in result:
        raise RuntimeError(result)
    adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/detail-layout-regression/{args.label}", str(OUTPUT))
    (OUTPUT / args.label / "conditions.json").write_text(json.dumps({"compiler_filter": args.compiler_filter,
        "settle_ms": args.settle_ms, "method_trace": args.trace_first_scroll, "home_first": args.home_first,
        "diagnostic_no_playback": args.diagnostic_no_playback, "diagnostic_cache": args.diagnostic_cache,
        "diagnostic_pool": args.diagnostic_pool, "diagnostic_no_handwriting": args.diagnostic_no_handwriting,
        "diagnostic_no_prefetch": args.diagnostic_no_prefetch}, indent=2), encoding="utf-8")
    print(json.dumps(summary(OUTPUT / args.label), ensure_ascii=False, indent=2), flush=True)
