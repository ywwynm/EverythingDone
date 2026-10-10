"""只比较原有记事/文件夹字段，不输出正文，不覆盖设备数据库。测试结束、设备空闲时运行。"""
import concurrent.futures
import hashlib
import io
import json
import pathlib
import sqlite3
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[4]
OUTPUT = ROOT / "tmp/detail-checklist-diagnosis/migration"
ADB = r"E:\AndroidSDK\platform-tools\adb.exe"
DEVICES = ("R5CW20BLNKL", "192.168.100.67:42641", "9018f404")
PACKAGE = "com.ywwynm.everythingdone"
DB = "EverythingDoneData.db"


def unpack(data, directory):
    directory.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for name in (DB, DB + "-journal", DB + "-wal", DB + "-shm"):
            try:
                member = archive.getmember("databases/" + name)
            except KeyError:
                continue
            (directory / name).write_bytes(archive.extractfile(member).read())
    return directory / DB


def rows(connection, table):
    # 表名为此脚本内常量，不接受设备或外部输入作为 SQL 标识符。
    cursor = connection.execute(f"SELECT * FROM {table}")
    columns = [description[0] for description in cursor.description]
    result = {}
    for values in cursor:
        row = dict(zip(columns, values))
        if table == "things" and row["type"] == -1:
            continue  # 首页日期分组头会随启动重建，不属于记事。
        result[row["id"]] = row
    return result


def verify(serial):
    directory = OUTPUT / serial.replace(":", "_")
    snapshot = subprocess.run([ADB, "-s", serial, "exec-out", "run-as", PACKAGE,
        "tar", "-cf", "-", "databases"], check=True, capture_output=True, timeout=60).stdout
    (directory / "after-migration-data.tar").write_bytes(snapshot)
    old = sqlite3.connect(unpack((directory / "before-migration.tar").read_bytes(), directory / "data-check-before"))
    new = sqlite3.connect(unpack(snapshot, directory / "data-check-after"))
    report = {"device": serial, "tables": {}}
    try:
        for table in ("things", "thing_folders", "reminders", "habits", "habit_reminders", "habit_records", "doing_records"):
            a, b = rows(old, table), rows(new, table)
            changed = [{"id": key, "columns": [column for column in a[key] if a[key][column] != b[key].get(column)]}
                       for key in a.keys() & b.keys() if a[key] != b[key]]
            added = [{"id": key, "regression_fixture": str(b[key].get("title", "")).startswith("详情")
                       and b[key].get("create_time") == 1780272000000} for key in b.keys() - a.keys()]
            fingerprint = hashlib.sha256(repr(sorted(a.items())).encode()).hexdigest()
            report["tables"][table] = {"original_rows": len(a), "original_fingerprint": fingerprint,
                "changed": changed, "missing": sorted(a.keys() - b.keys()), "added": added}
    finally:
        old.close()
        new.close()
    (directory / "data-preservation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        for report in executor.map(verify, DEVICES):
            print(json.dumps(report, ensure_ascii=False), flush=True)
