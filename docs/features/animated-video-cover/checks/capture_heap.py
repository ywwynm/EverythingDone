"""仅采指定隔离测试包的 native 分配栈；不停止设备上其它 trace 或应用。"""
from pathlib import Path
import subprocess
import sys
import time

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
ADB = "E:/AndroidSDK/platform-tools/adb.exe"
SERIAL, RUN = sys.argv[1:3]
assert SERIAL in ("3B1629006YC00000", "R5CW20BLNKL", "192.168.100.67:40807")
assert RUN.replace("-", "").replace("_", "").isalnum()
OUT = ROOT / "tmp/media-memory-20261015" / RUN
OUT.mkdir(parents=True, exist_ok=False)
remote = f"/data/misc/perfetto-traces/ed-media-{RUN}.perfetto-trace"
config = '''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 600000
data_sources { config { name: "android.heapprofd" heapprofd_config {
 sampling_interval_bytes: 1048576
 process_cmdline: "com.ywwynm.everythingdone.batchvalidation"
 continuous_dump_config { dump_phase_ms: 0 dump_interval_ms: 5000 }
 shmem_size_bytes: 67108864
 block_client: false
} } }
data_sources { config { name: "linux.process_stats" process_stats_config {
 scan_all_processes_on_start: false proc_stats_poll_ms: 1000
} } }
'''
(OUT / "config.pbtxt").write_text(config, encoding="utf-8")
started = subprocess.run([ADB, "-s", SERIAL, "shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", remote],
    input=config.encode(), capture_output=True, check=True)
(OUT / "start.log").write_bytes(started.stdout + started.stderr)
pid = started.stdout.decode().strip().splitlines()[-1]
assert pid.isdecimal(), started.stdout
try:
    result = subprocess.run([sys.executable, str(ROOT / "docs/features/selection-batch-actions/checks/run_identity.py"),
        SERIAL, RUN, "scenario=complex", "items=96", "passes=1", "cold_load=true", "screenshot_every=12", "memory_audit=true", *sys.argv[3:]])
finally:
    subprocess.run([ADB, "-s", SERIAL, "shell", "kill", "-INT", pid], capture_output=True)
    time.sleep(2)
    subprocess.run([ADB, "-s", SERIAL, "pull", remote, str(OUT / "trace.perfetto-trace")], check=True)
    subprocess.run([ADB, "-s", SERIAL, "shell", "rm", "-f", remote], check=True)
sys.exit(result.returncode)
