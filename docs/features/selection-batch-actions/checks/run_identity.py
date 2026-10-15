"""在明确授权的设备上运行隔离包，原应用只读取数据库与设置文件的校验和。"""
from pathlib import Path
import json
import subprocess
import sys
import time
import re
import hashlib
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
ADB = "E:/AndroidSDK/platform-tools/adb.exe"
PKG = "com.ywwynm.everythingdone.batchvalidation"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AAPT = "E:/AndroidSDK/build-tools/36.0.0/aapt.exe"
SERIAL, RUN = sys.argv[1:3]
assert SERIAL in ("3B1629006YC00000", "R5CW20BLNKL", "192.168.100.67:40807")
assert RUN.replace("-", "").replace("_", "").isalnum()
# 安装前读取 APK 本体，避免忘记使用 clone.init.gradle 时覆盖原应用。
badging = subprocess.run([AAPT, "dump", "badging", str(APK)], capture_output=True, check=True).stdout.decode(errors="replace")
assert re.search(r"^package: name='" + re.escape(PKG) + r"'", badging), "拒绝安装：APK 不是隔离测试包"
OUT = ROOT / "tmp/selection-transition-20261013" / RUN
OUT.mkdir(exist_ok=False)
(OUT / "run-info.json").write_text(json.dumps({"serial": SERIAL, "arguments": sys.argv[3:],
    "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest()}, ensure_ascii=False, indent=2), encoding="utf-8")

def adb(*args, timeout=40):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)

def hashes():
    r = adb("shell", "run-as", "com.ywwynm.everythingdone", "sh", "-c",
            "'sha256sum databases/* shared_prefs/*'")
    return {"code": r.returncode, "stdout": r.stdout.decode(errors="replace"), "stderr": r.stderr.decode(errors="replace")}

before = hashes()
(OUT / "original-before.json").write_text(json.dumps(before, ensure_ascii=False), encoding="utf-8")
with (OUT / "install.log").open("wb") as install_log:
    installer = subprocess.Popen([ADB, "-s", SERIAL, "install", "-r", str(APK)],
                                 stdout=install_log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 90
    while installer.poll() is None and time.monotonic() < deadline:
        if SERIAL == "3B1629006YC00000":
            top = adb("shell", "dumpsys", "activity", "activities").stdout
            if b"InstallGuideActivity" in top:
                adb("shell", "uiautomator", "dump", "/sdcard/list-test-window.xml")
                xml = adb("shell", "cat", "/sdcard/list-test-window.xml").stdout
                root = ET.fromstring(xml)
                for node in root.iter("node"):
                    if node.get("resource-id") == "com.oplus.appdetail:id/btn_left" and node.get("text") == "继续安装":
                        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.3)
    installer.wait(timeout=3)
    assert installer.returncode == 0, (OUT / "install.log").read_text(errors="replace")
adb("shell", "am", "force-stop", PKG)
if any(arg in sys.argv[3:] for arg in ("scenario=video", "scenario=complex")) and SERIAL != "3B1629006YC00000":
    for permission in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"):
        granted = adb("shell", "pm", "grant", PKG, "android.permission." + permission)
        assert granted.returncode == 0, granted.stderr.decode(errors="replace")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
command = [ADB, "-s", SERIAL, "shell", "am", "instrument", "-w", "-e", "run_id", RUN]
for arg in sys.argv[3:]:
    key, value = arg.split("=", 1)
    command.extend(["-e", key, value])
command.append(PKG + "/com.ywwynm.everythingdone.listvalidation.ListIdentityProbe")
try:
    with (OUT / "instrument.log").open("wb") as log, (OUT / "process.log").open("wb") as process_log:
        instrument = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        collector = None
        try:
            for _ in range(40):
                pid = adb("shell", "pidof", PKG).stdout.decode().strip().split()
                if pid:
                    collector = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "--pid", pid[0], "-v", "threadtime"],
                                                 stdout=process_log, stderr=subprocess.STDOUT)
                    break
                if instrument.poll() is not None:
                    break
                time.sleep(.25)
            instrument.wait(timeout=1500 if "scenario=complex" in sys.argv[3:] else 240)
        finally:
            if collector is not None:
                collector.terminate()
                collector.wait(timeout=10)
finally:
    remote = f"/sdcard/Android/data/{PKG}/files/list-identity/{RUN}/."
    pulled = adb("pull", remote, str(OUT), timeout=120)
    after = hashes()
    (OUT / "original-after.json").write_text(json.dumps(after, ensure_ascii=False), encoding="utf-8")
verified = before["code"] == 0 and after["code"] == 0
unchanged = verified and before["stdout"] == after["stdout"]
print("原应用数据库与设置校验和一致：", unchanged if verified else "未能核验（设备连接或读取失败）")
result = json.loads((OUT / "result.json").read_text(encoding="utf-8"))
print({k: v for k, v in result.items() if k not in ("steps", "mismatches")})
print("身份不一致样本数：", len(result.get("mismatches", [])))
sys.exit(0 if result.get("ok") and unchanged else 1)
