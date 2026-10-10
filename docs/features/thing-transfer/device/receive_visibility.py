"""接收显示回归的真机驱动；数据快照与输出仅保存在本机 tmp。"""
import argparse
import hashlib
import json
import sqlite3
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
ADB = r"E:\AndroidSDK\platform-tools\adb.exe"
PACKAGE = "com.ywwynm.everythingdone"
DEVICES = {"samsung": "R5CW20BLNKL", "meizu": "192.168.100.67:42641"}
OUT = ROOT / "tmp/tap-visibility-20261010"


def adb(serial, *args, timeout=120):
    result = subprocess.run([ADB, "-s", serial, *map(str, args)], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    return result.stdout


def snapshot(serial, output):
    output.mkdir(parents=True, exist_ok=True)
    for name in ("EverythingDoneData.db", "EverythingDoneData.db-journal"):
        (output / name).write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "cat", "databases/" + name))
    summary = {}
    with sqlite3.connect((output / "EverythingDoneData.db").as_uri() + "?mode=ro", uri=True) as db:
        tables = db.execute("select name from sqlite_master where type='table'").fetchall()
        for (table,) in tables:
            quoted = '"' + table.replace('"', '""') + '"'
            # App 启动会重建系统 header；用户记事、文件夹及其余表均逐行核对。
            rows = db.execute(f"select * from {quoted}").fetchall()
            columns = [r[1] for r in db.execute(f"pragma table_info({quoted})")]
            if table == "things":
                type_column = columns.index("type")
                rows = [r for r in rows if r[type_column] != -1]
            encoded = json.dumps(sorted(map(repr, rows)), ensure_ascii=False).encode()
            summary[table] = {"count": len(rows), "sha256": hashlib.sha256(encoded).hexdigest()}
    (output / "rows.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("device", choices=DEVICES)
    parser.add_argument("run")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    serial = DEVICES[args.device]
    target = OUT / args.device / args.run
    before = snapshot(serial, target / "before")
    if args.install:
        print(adb(serial, "install", "-r", ROOT / "app/build/outputs/apk/debug/app-debug.apk").decode().strip(), flush=True)
    output = adb(serial, "shell", "am", "instrument", "-w", "-e", "run_id", args.run,
                 PACKAGE + "/.taptransfer.TapReceiveVisibilityProbe")
    (target / "instrument.txt").write_bytes(output)
    after = snapshot(serial, target / "after")
    changed = [name for name in set(before) | set(after) if before.get(name) != after.get(name)]
    # Flyme 的 run-as 无法读取外部私有目录；Instrumentation 返回同一份 JSON，无需依赖该路径。
    line = next(line for line in output.decode("utf-8").splitlines()
                if line.startswith("INSTRUMENTATION_RESULT: result="))
    report = json.loads(line.split("result=", 1)[1])
    (target / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"device": args.device, "run": args.run, "data_unchanged": not changed,
                      "changed_tables": changed, "result": report}, ensure_ascii=False), flush=True)
    if changed:
        raise RuntimeError("数据核对未通过，停止后续操作")


if __name__ == "__main__":
    main()
