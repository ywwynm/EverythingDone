"""采集指定记事的系统时间线；追踪样本只作诊断，不混入干净性能统计。"""
import pathlib
import re
import subprocess
import sys
import time

from measure_performance import ADB, SERIAL, PACKAGE, OUTPUT, ROOT

LABEL = "candidate-folded-system-trace"
REMOTE = f"/data/misc/perfetto-traces/{LABEL}.perfetto-trace"
CONFIG = '''buffers { size_kb: 131072 fill_policy: RING_BUFFER }
duration_ms: 120000
data_sources { config { name: "linux.ftrace" ftrace_config {
ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input"
atrace_categories: "dalvik" atrace_categories: "wm" atrace_categories: "am"
atrace_apps: "com.ywwynm.everythingdone"
drain_period_ms: 250
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
'''


def adb(*args, data=None):
    return subprocess.run([ADB, "-s", SERIAL, *args], input=data, capture_output=True, check=True, timeout=60).stdout


result = adb("shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", REMOTE, data=CONFIG.encode()).decode()
pid = re.search(r"\b(\d+)\b", result).group(1)
try:
    subprocess.run([sys.executable, "-X", "utf8", str(pathlib.Path(__file__).with_name("measure_performance.py")), LABEL], cwd=ROOT, check=True)
finally:
    adb("shell", "kill", "-INT", pid)
    for _ in range(40):
        running = subprocess.run([ADB, "-s", SERIAL, "shell", "ps", "-p", pid], capture_output=True, timeout=10)
        if running.returncode != 0 or not running.stdout.decode().strip().count("\n"):
            break
        time.sleep(0.1)
    adb("pull", REMOTE, str(OUTPUT / f"{LABEL}.perfetto-trace"))
print("系统追踪已保存。", flush=True)
