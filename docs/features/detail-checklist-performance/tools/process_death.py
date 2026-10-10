"""只终止专用夹具所在的本应用进程；确认 onStop/保存状态后恢复同一任务，finally 清理夹具。"""
import argparse
import json
import time
import re
from pathlib import Path
from device_regression import adb, DEVICES, OUTPUT, PACKAGE

parser = argparse.ArgumentParser()
parser.add_argument("--device", choices=DEVICES, required=True)
parser.add_argument("--run", required=True)
parser.add_argument("--checklist", action="store_true")
parser.add_argument("--auto-save", action="store_true")
args = parser.parse_args()
assert re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.run)
serial = args.device
remote = f"/sdcard/Android/data/{PACKAGE}/files/detail-layout-regression/{args.run}"
component = f"{PACKAGE}/.detail.DetailProcessProbeActivity"
local = OUTPUT / serial.replace(":", "_")


def start(phase):
    return adb(serial, "shell", "am", "start", "-W", "-f", "0x18000000", "-n", component, "--es", "phase", phase,
               "--es", "run_id", args.run, "--ez", "checklist", str(args.checklist).lower(),
               "--ez", "auto_save", str(args.auto_save).lower())


def read(name):
    try:
        adb(serial, "shell", "test", "-f", f"{remote}/{name}")
        return adb(serial, "exec-out", "cat", f"{remote}/{name}").decode("utf-8")
    except RuntimeError:
        return None


def wait(name):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        error = read("error.txt")
        if error:
            raise RuntimeError(error)
        data = read(name)
        if data:
            return data
        time.sleep(0.3)
    raise TimeoutError(f"没有收到 {name}")


try:
    start("prepare")
    before = json.loads(wait("before.json"))
    pid = before["config"]["pid"]
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    wait("stopped.txt")
    saved = json.loads(wait("saved-state.json"))
    assert saved["pid"] == pid
    current = adb(serial, "shell", "pidof", PACKAGE).decode().split()
    assert str(pid) in current, "终止前进程已更换，不能继续"
    adb(serial, "shell", "run-as", PACKAGE, "kill", "-9", str(pid))
    time.sleep(0.5)
    start("resume")
    after = json.loads(wait("after.json"))
    assert after["restored_bundle"] and after["pid"] != pid, "没有恢复系统保存的原 Activity 状态"
    changed = [key for key in before if key != "config" and before[key] != after.get(key)]
    report = {"device": serial, "old_pid": pid, "new_pid": after["pid"], "restored_bundle": True,
              "checklist": args.checklist, "auto_save": args.auto_save, "changed_fields": changed}
    adb(serial, "pull", remote, str(local))
    (local / args.run / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
finally:
    start("cleanup")
