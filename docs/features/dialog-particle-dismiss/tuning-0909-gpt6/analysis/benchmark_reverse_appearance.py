"""固定素材与随机输入测逆向准备，同时验证原正向模型的每个对应帧。"""
import argparse
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("serial", choices=["9018f404", "R5CW20BLNKL"])
p.add_argument("--scene", default="attachment")
p.add_argument("--label", required=True)
p.add_argument("--direction", type=float)
p.add_argument("--seed", type=int)
p.add_argument("--save-frames", action="store_true")
p.add_argument("--max-prepare-ms", type=float)
p.add_argument("--samples", type=int)
a = p.parse_args()
root = Path(__file__).resolve().parent / "appearance-startup"
root.mkdir(exist_ok=True)
adb = ["E:/AndroidSDK/platform-tools/adb.exe", "-s", a.serial]
remote = "/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-unified/generated"
check_id = f"{a.label}-{time.time_ns()}"

def call(*args):
    return subprocess.run(adb + list(args), capture_output=True, timeout=30, encoding="utf-8", errors="replace")

# 确保包处于前台，避免 Android 冻结缓存进程里的离屏验证线程。
call("shell", "am", "start", "-W", "-n", "com.ywwynm.everythingdone/.activities.ThingsActivity").check_returncode()
args = ["shell", "am", "broadcast", "-n", "com.ywwynm.everythingdone/.views.particledismiss.ParticleMicroflakeProbeReceiver",
        "--es", "scene", a.scene, "--ez", "reverseCheck", "true", "--es", "checkId", check_id,
        "--ez", "saveReverseFrames", str(a.save_frames).lower()]
if a.direction is not None:
    args += ["--ef", "direction", str(a.direction)]
if a.seed is not None:
    args += ["--el", "seed", str(a.seed)]
if a.samples is not None:
    args += ["--ei", "reverseSamples", str(a.samples)]
call(*args).check_returncode()
deadline = time.monotonic() + 60
while time.monotonic() < deadline:
    for filename in [f"{a.scene}.json", "failed-check.json"]:
        raw = call("shell", "cat", f"{remote}/{filename}").stdout
        try:
            result = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if result.get("checkId") != check_id:
            continue
        (root / f"{a.label}-{a.serial}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), "utf-8")
        assert "error" not in result, result
        assert result["reverseVerified"] and len(result["reverseFrameParity"]) == result["reverseSamples"] + 1
        print(json.dumps({key: result[key] for key in ["scene", "direction", "count", "reversePrepareMs", "reverseVerified"]},
                         ensure_ascii=False), flush=True)
        if a.max_prepare_ms is not None:
            assert result["reversePrepareMs"] <= a.max_prepare_ms, "逆向准备超出本轮回归上限"
        raise SystemExit(0)
    time.sleep(.15)
raise AssertionError("本次验证没有产生匹配 checkId 的结果，不接受旧报告")
