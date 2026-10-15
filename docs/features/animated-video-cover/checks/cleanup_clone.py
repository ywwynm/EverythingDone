"""核对本轮原应用校验和，只卸载指定设备上的独立测试包。"""
from pathlib import Path
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
SERIAL, REFERENCE = sys.argv[1:3]
assert SERIAL in ("3B1629006YC00000", "R5CW20BLNKL", "192.168.100.67:40807")
assert REFERENCE.replace("-", "").isalnum()
directory = ROOT / "tmp/selection-transition-20261013" / REFERENCE
info = json.loads((directory / "run-info.json").read_text(encoding="utf-8"))
assert info["serial"] == SERIAL
PKG = "com.ywwynm.everythingdone.batchvalidation"

def adb(*args):
    return subprocess.run(["E:/AndroidSDK/platform-tools/adb.exe", "-s", SERIAL, *args], capture_output=True, timeout=60)

before = json.loads((directory / "original-before.json").read_text(encoding="utf-8"))
r = adb("shell", "run-as", "com.ywwynm.everythingdone", "sh", "-c", "'sha256sum databases/* shared_prefs/*'")
after = {"code": r.returncode, "stdout": r.stdout.decode(errors="replace")}
unchanged = before["code"] == after["code"] == 0 and before["stdout"] == after["stdout"]
removed = adb("uninstall", PKG)
present = adb("shell", "pm", "list", "packages", PKG)
if SERIAL == "3B1629006YC00000": adb("shell", "rm", "-f", "/sdcard/list-test-window.xml")
result = {"serial": SERIAL, "reference": REFERENCE, "originalUnchanged": unchanged,
          "uninstallCode": removed.returncode, "uninstallOutput": removed.stdout.decode().strip(),
          "cloneAbsent": present.returncode == 0 and PKG.encode() not in present.stdout}
(directory / "memory-cleanup.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(result)
assert unchanged and result["cloneAbsent"] and removed.returncode == 0
