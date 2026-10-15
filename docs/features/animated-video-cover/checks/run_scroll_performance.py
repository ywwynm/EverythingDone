"""同一隔离 APK 的滚动策略对照；只对本会话授权设备操作。"""
from pathlib import Path
import json
import subprocess
import sys
import time
import threading
import shlex

sys.stdout.reconfigure(encoding="utf-8")
ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
ADB = "E:/AndroidSDK/platform-tools/adb.exe"
SERIAL, RUN, POLICY = sys.argv[1:4]
TRACE = "--no-trace" not in sys.argv[4:]
QUICK = "--fling" in sys.argv[4:]
assert SERIAL in ("3B1629006YC00000", "R5CW20BLNKL", "192.168.100.67:40807")
assert POLICY in ("legacy", "continuous")
assert RUN.replace("-", "").isalnum()
OUT = ROOT / "tmp/media-memory-20261015" / RUN
OUT.mkdir(exist_ok=False)

def adb(*args, data=None, timeout=40):
    return subprocess.run([ADB, "-s", SERIAL, *args], input=data, capture_output=True, timeout=timeout)

def environment(label):
    for service in ("battery", "thermalservice", "display"):
        r = adb("shell", "dumpsys", service)
        (OUT / f"{label}-{service}.txt").write_bytes(r.stdout)

config = '''buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: 900000
write_into_file: true
file_write_period_ms: 2500
flush_period_ms: 2500
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
data_sources { config { name: "android.packages_list" } }
'''
remote = f"/data/misc/perfetto-traces/{RUN}.perfetto-trace"
(OUT / "trace-config.pbtxt").write_text(config, encoding="utf-8")
environment("before")
pid = None
if TRACE:
    r = adb("shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", remote, data=config.encode())
    (OUT / "trace-start.txt").write_bytes(r.stdout + r.stderr)
    pid = int(r.stdout.decode().strip().splitlines()[-1])
code = 1
monitoring = threading.Event()
monitoring.set()
def surface_monitor():
    # 独立核验厂商 FrameMetrics／FrameTimeline 缺报，读取当前窗口真实呈现时间。
    layer = None
    with (OUT / "surface-latency.jsonl").open("w", encoding="utf-8") as log:
        while monitoring.is_set():
            if layer is None:
                names = adb("shell", "dumpsys", "SurfaceFlinger", "--list").stdout.decode(errors="replace").splitlines()
                found = [n for n in names if "batchvalidation/com.ywwynm.everythingdone.activities.ThingsActivity" in n
                         and not any(k in n for k in ("SurfaceView", "Bounds for", "Background for", "Surface(name=", "InputSink", "ActivityRecord"))]
                if found:
                    layer = min(found, key=len)
                    if layer.startswith("RequestedLayerState{"):
                        layer = layer[len("RequestedLayerState{"):].rstrip("}").split(" parentId=")[0]
            else:
                now = time.monotonic_ns()
                data = adb("shell", "dumpsys SurfaceFlinger --latency " + shlex.quote(layer)).stdout.decode(errors="replace")
                log.write(json.dumps({"hostNs": now, "layer": layer, "data": data}) + "\n"); log.flush()
                if len(data.splitlines()) < 2: layer = None
            time.sleep(.75)
monitor = threading.Thread(target=surface_monitor, daemon=True)
if QUICK: monitor.start()
try:
    with (OUT / "driver.log").open("wb") as log:
        result = subprocess.run([sys.executable, str(ROOT / "docs/features/selection-batch-actions/checks/run_identity.py"),
            SERIAL, RUN, "scenario=complex", "items=96", "cold_load=true", "gesture=performance", "perf_policy=" + POLICY,
            "memory_audit=true", "memory_gc=false", "lifecycle_assert=true", "perf_suite=" + ("fling" if QUICK else "full")], stdout=log, stderr=subprocess.STDOUT)
        code = result.returncode
finally:
    monitoring.clear()
    if QUICK: monitor.join(timeout=45)
    if pid is not None:
        adb("shell", "kill", "-INT", str(pid))
        time.sleep(3)
        r = adb("pull", remote, str(OUT / "trace.perfetto-trace"), timeout=150)
        (OUT / "trace-pull.txt").write_bytes(r.stdout + r.stderr)
        if r.returncode == 0 and (OUT / "trace.perfetto-trace").stat().st_size > 1000:
            adb("shell", "rm", remote)
    environment("after")
print(json.dumps({"run": RUN, "policy": POLICY, "exit": code}, ensure_ascii=False))
sys.exit(code)
