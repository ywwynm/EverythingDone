"""仅使用本轮指定设备；原有数据库逐表核对，结果和画面保存至本机 tmp。"""
import argparse
import hashlib
import json
import sqlite3
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ADB = r"E:\AndroidSDK\platform-tools\adb.exe"
PACKAGE = "com.ywwynm.everythingdone"
DEVICES = {"oppo": "3B1629006YC00000", "samsung": "R5CW20BLNKL", "meizu": "192.168.100.67:40807"}


def adb(serial, *args, timeout=120):
    result = subprocess.run([ADB, "-s", serial, *map(str, args)], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    return result.stdout


def snapshot(serial, path):
    path.mkdir(parents=True, exist_ok=True)
    (path / "EverythingDoneData.db").write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "cat", "databases/EverythingDoneData.db"))
    # WAL 模式下只复制主数据库可能看不到最近写入。缺失 WAL 是合法状态。
    wal = subprocess.run([ADB, "-s", serial, "exec-out", "run-as", PACKAGE, "cat",
                          "databases/EverythingDoneData.db-wal"], capture_output=True, timeout=30)
    if wal.returncode == 0 and wal.stdout:
        (path / "EverythingDoneData.db-wal").write_bytes(wal.stdout)
    summary = {}
    with sqlite3.connect((path / "EverythingDoneData.db").as_uri() + "?mode=ro", uri=True) as db:
        for (table,) in db.execute("select name from sqlite_master where type='table'"):
            quoted = '"' + table.replace('"', '""') + '"'
            rows = db.execute(f"select * from {quoted}").fetchall()
            columns = [row[1] for row in db.execute(f"pragma table_info({quoted})")]
            if table == "things":
                rows = [row for row in rows if row[columns.index("type")] != -1]
            encoded = json.dumps(sorted(map(repr, rows)), ensure_ascii=False).encode()
            summary[table] = {"count": len(rows), "sha256": hashlib.sha256(encoded).hexdigest()}
    (path / "rows.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("device", choices=DEVICES)
    parser.add_argument("run")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--home-start", type=int, default=0)
    parser.add_argument("--suite", choices=("all", "animation", "media-entry", "review", "reuse", "home"), default="all")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    if not args.run.replace("-", "").replace("_", "").isalnum():
        raise ValueError("运行名只接受字母、数字、横线与下划线")
    serial = DEVICES[args.device]
    output = ROOT / ("tmp/tap-reuse-20261016" if args.suite in ("reuse", "home") else "tmp/tap-update-20261010") / args.device / args.run
    before = snapshot(serial, output / "before")
    print(json.dumps({"device": args.device, "before_counts": {k: v["count"] for k, v in before.items()}}, ensure_ascii=False), flush=True)
    if args.install:
        print(adb(serial, "install", "-r", args.apk.resolve()).decode().strip(), flush=True)
    remote_video = f"/sdcard/tap-update-{args.run}.mp4"
    recorder = None
    if args.record:
        recorder = subprocess.Popen([ADB, "-s", serial, "shell", "screenrecord", "--time-limit", "25" if args.suite == "media-entry" else "35" if args.suite == "animation" else "120" if args.suite == "home" else "55", "--bit-rate", "6000000", remote_video], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    raw = adb(serial, "shell", "am", "instrument", "-w", "-e", "run_id", args.run,
              "-e", "suite", args.suite,
              "-e", "home_start", str(args.home_start),
              PACKAGE + "/.taptransfer.TapDetailUpdateProbe", timeout=300 if args.suite == "home" else 150)
    (output / "instrument.txt").write_bytes(raw)
    after = snapshot(serial, output / "after")
    changed = [name for name in set(before) | set(after) if before.get(name) != after.get(name)]
    line = next((line for line in raw.decode("utf-8", "replace").splitlines() if line.startswith("INSTRUMENTATION_RESULT: result=")), None)
    report = json.loads(line.split("result=", 1)[1]) if line else {"passed": False, "error": raw.decode("utf-8", "replace")}
    report.update({"data_unchanged": not changed, "changed_tables": changed})
    (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if args.suite in ("reuse", "home") and report.get("passed"):
        remote = f"/sdcard/Android/data/{PACKAGE}/files/tap-{args.suite}-result.png"
        adb(serial, "pull", remote, output / "result.png")
        adb(serial, "shell", "rm", remote)
    if recorder:
        _, error = recorder.communicate(timeout=60)
        if recorder.returncode:
            print(error.decode("utf-8", "replace"), flush=True)
        else:
            adb(serial, "pull", remote_video, output / "animation.mp4")
            adb(serial, "shell", "rm", remote_video)
            print(f"video={output / 'animation.mp4'}", flush=True)
    if changed or not report.get("passed"):
        raise RuntimeError("验证未通过；检查结果后再继续")


if __name__ == "__main__":
    main()
