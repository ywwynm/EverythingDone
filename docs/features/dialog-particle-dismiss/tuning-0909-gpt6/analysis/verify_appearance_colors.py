"""在已授权设备上比较真实弹窗的出现动画输入和最终画面，不修改用户记事。"""
import argparse
import json
import re
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("serial", choices=["9018f404", "R5CW20BLNKL"])
p.add_argument("--prefix", required=True)
p.add_argument("--output", required=True)
p.add_argument("--allow-difference", action="store_true")
p.add_argument("--kinds", nargs="+", default=["chooser", "alert"])
p.add_argument("--palettes", nargs="+", default=["gradient", "reverse", "pure"])
p.add_argument("--record-failure", action="store_true", help="诊断阶段保留尺寸不匹配的完整证据")
a = p.parse_args()
out = Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=True)
adb = ["E:/AndroidSDK/platform-tools/adb.exe", "-s", a.serial]

def call(*args, check=True):
    return subprocess.run(adb + list(args), check=check, capture_output=True, timeout=30,
                          encoding="utf-8", errors="replace")

results = []
for kind in a.kinds:
    for palette in a.palettes:
        run_id = f"{a.prefix}-{kind}-{palette}"
        remote = f"/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-appearance-probe/{run_id}"
        # 不复用已有结果，防止把旧文件误判为本次运行成功。
        assert call("shell", "test", "-e", f"{remote}/result.json", check=False).returncode != 0, run_id
        before_log = set(call("logcat", "-d", "-s", "ParticleMicroflake:I", "*:S").stdout.splitlines())
        call("shell", "am", "start", "-W", "-n",
             "com.ywwynm.everythingdone/.views.particledismiss.ParticleAppearanceProbeActivity",
             "--es", "run_id", run_id, "--es", "kind", kind, "--es", "palette", palette)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if call("shell", "test", "-f", f"{remote}/result.json", check=False).returncode == 0:
                break
            time.sleep(.1)
        else:
            raise AssertionError(f"{run_id}: 等待探针结果超时")
        call("pull", remote, str(out))
        result = json.loads((out / run_id / "result.json").read_text("utf-8"))
        log = "\n".join(line for line in call("logcat", "-d", "-s", "ParticleMicroflake:I", "*:S").stdout.splitlines()
                        if line not in before_log)
        (out / run_id / "animation.log").write_text(log, "utf-8")
        # 配色差为零本身不能证明 GPU 动画成功，降级直接显示也可能得到零差。
        assert "出现启动" in log and "出现完成 particles=" in log, (run_id, "逆向 GPU 动画未完整播放", log)
        result["reverseCompleted"] = True
        line = next(line for line in log.splitlines() if "出现启动" in line)
        result["startup"] = {k: float(v) for k, v in re.findall(r"(\w+Ms)=([\d.]+)", line)}
        capture = next((line for line in log.splitlines() if "出现捕获" in line), "")
        result["capture"] = {k: float(v) for k, v in re.findall(r"(\w+Ms)=([\d.]+)", capture)}
        complete = next(line for line in log.splitlines() if "出现完成 particles=" in line)
        result["playbackMs"] = float(re.search(r"elapsedMs=([\d.]+)", complete)[1])
        result["direction"] = float(re.search(r"direction=([-\d.]+)", complete)[1]) % 360
        assert 225 <= result["direction"] <= 315, (run_id, "出现方向不在下方扇区", complete)
        results.append(dict(run_id=run_id, **result))
        print(run_id, {r["name"]: r["changedPixels"] for r in result.get("regions", [])},
              result.get("error", ""), flush=True)
        if not a.record_failure:
            assert "error" not in result, result
            assert result["sizeMatches"], result
            assert result.get("positionMatches", True), result
        if not a.allow_difference and not a.record_failure:
            assert result["whole"]["changedPixels"] == 0, result
        # 结果写盘随后 Activity 自行关闭；下一组不覆盖仍在运行的同一个 Activity。
        while time.monotonic() < deadline:
            resumed = call("shell", "dumpsys", "activity", "activities").stdout
            current = [l for l in resumed.splitlines() if "topResumedActivity=" in l]
            if not any("ParticleAppearanceProbeActivity" in l for l in current):
                break
            time.sleep(.1)

(out / f"{a.prefix}-summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), "utf-8")
print(f"完成 {len(results)} 组动画输入与真实 View 的像素比较。", flush=True)
