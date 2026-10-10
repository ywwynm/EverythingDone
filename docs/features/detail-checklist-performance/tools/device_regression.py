"""详情页三机回归驱动：限定设备，保留数据备份与逐机原始输出。"""

import argparse
import concurrent.futures
import json
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[4]
ADB = pathlib.Path(r"E:\AndroidSDK\platform-tools\adb.exe")
DEVICES = ("R5CW20BLNKL", "192.168.100.67:42641", "9018f404")
PACKAGE = "com.ywwynm.everythingdone"
OUTPUT = ROOT / "tmp/detail-checklist-diagnosis/migration"


def adb(serial, *arguments, timeout=120):
    process = subprocess.run(
        [str(ADB), "-s", serial, *arguments], capture_output=True, timeout=timeout
    )
    if process.returncode:
        raise RuntimeError((process.stdout + process.stderr).decode(errors="replace"))
    return process.stdout


def work(serial, action, run, apk, suite, case, probe="DetailLayoutRegressionProbe"):
    directory = OUTPUT / serial.replace(":", "_")
    directory.mkdir(parents=True, exist_ok=True)
    if action == "backup":
        # 不输出用户数据；备份仅留在本机忽略目录，后续不自动覆盖恢复用户数据库。
        backup = directory / "before-migration.tar"
        if not backup.exists():
            backup.write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "databases", "shared_prefs"))
        properties = {}
        for key in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk"):
            properties[key] = adb(serial, "shell", "getprop", key).decode().strip()
        properties["size"] = adb(serial, "shell", "wm", "size").decode().strip()
        properties["density"] = adb(serial, "shell", "wm", "density").decode().strip()
        (directory / "device.json").write_text(json.dumps(properties, indent=2), encoding="utf-8")
        return {"device": serial, "backup_bytes": backup.stat().st_size, **properties}
    if action == "install":
        return {"device": serial, "output": adb(serial, "install", "-r", apk.resolve().as_posix(), timeout=240).decode().strip()}
    if action == "run":
        started = time.monotonic()
        with (directory / f"{run}-logcat.txt").open("wb") as log:
            collector = subprocess.Popen([str(ADB), "-s", serial, "logcat", "-T", "1",
                "AndroidRuntime:E", "*:S"], stdout=log, stderr=log)
            try:
                case_args = ["-e", "test_case", case] if case else []
                output = adb(serial, "shell", "am", "instrument", "-w", "-r", "-e", "run_id", run, "-e", "suite", suite, *case_args,
                             f"{PACKAGE}/.detail.{probe}", timeout=1200).decode(errors="replace")
            finally:
                collector.terminate()
                collector.wait(timeout=10)
        (directory / f"{run}-instrumentation.txt").write_text(output, encoding="utf-8")
        if "INSTRUMENTATION_FAILED" in output or "Process crashed" in output:
            raise RuntimeError(output.strip())
        remote = f"/sdcard/Android/data/{PACKAGE}/files/detail-layout-regression/{run}"
        adb(serial, "pull", remote, str(directory), timeout=180)
        result = json.loads((directory / run / "result.json").read_text(encoding="utf-8"))
        if result.get("error"):
            raise RuntimeError(result["error"])
        return {"device": serial, "seconds": round(time.monotonic() - started, 1),
                "captures": len(result.get("captures", [])), "error": result.get("error")}
    raise ValueError(action)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("backup", "install", "run"))
    parser.add_argument("--run", default="baseline")
    parser.add_argument("--suite", choices=("layout", "behavior", "theme"), default="layout")
    parser.add_argument("--case", default="")
    parser.add_argument("--probe", choices=("DetailLayoutRegressionProbe", "DetailRecreationProbe"), default="DetailLayoutRegressionProbe")
    parser.add_argument("--device", choices=DEVICES, action="append")
    parser.add_argument("--apk", type=pathlib.Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    arguments = parser.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        tasks = {executor.submit(work, serial, arguments.action, arguments.run, arguments.apk, arguments.suite, arguments.case, arguments.probe): serial
                 for serial in (arguments.device or DEVICES)}
        failed = False
        for task in concurrent.futures.as_completed(tasks):
            try:
                print(json.dumps(task.result(), ensure_ascii=True), flush=True)
            except Exception as error:
                failed = True
                print(json.dumps({"device": tasks[task], "error": str(error)}, ensure_ascii=True), flush=True)
        if failed:
            raise SystemExit(1)
