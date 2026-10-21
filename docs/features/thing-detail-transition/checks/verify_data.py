"""比较 ADB run-as tar 备份中的业务表，只输出数量和变更 ID，不输出记事正文。

用法：python verify_data.py before.tar after.tar
备份示例（由 Python subprocess 以 bytes 保存，不能用 PowerShell 重定向二进制）：
adb -s <明确授权的序列号> exec-out run-as com.ywwynm.everythingdone tar -cf - databases shared_prefs
"""

import collections
import json
from pathlib import Path
import sqlite3
import sys
import tarfile
import tempfile


def tables(archive: Path):
    with tempfile.TemporaryDirectory(prefix="transition-db-", dir=archive.parent) as folder:
        folder = Path(folder).resolve()
        with tarfile.open(archive) as tar:
            for item in tar.getmembers():
                if item.isfile() and item.name.startswith("databases/"):
                    # 仅取数据库文件名，避免把归档中的相对目录用于写入。
                    name = Path(item.name).name
                    if name.startswith("EverythingDoneData.db"):
                        (folder / name).write_bytes(tar.extractfile(item).read())
        db = sqlite3.connect(folder / "EverythingDoneData.db")
        try:
            names = [row[0] for row in db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
            data = {}
            for name in names:
                quoted = '"' + name.replace('"', '""') + '"'
                query = f"SELECT * FROM {quoted}"
                if name == "things":
                    query += " WHERE type<>-1"  # 首页占位记录由应用启动重建，不是用户记事。
                cursor = db.execute(query)
                data[name] = ([column[0] for column in cursor.description], cursor.fetchall())
            return data
        finally:
            db.close()


def compare(before: Path, after: Path):
    first, last = tables(before), tables(after)
    report = {}
    for name in sorted(first.keys() | last.keys()):
        a_columns, a_rows = first.get(name, ([], []))
        b_columns, b_rows = last.get(name, ([], []))
        equal = a_columns == b_columns and collections.Counter(a_rows) == collections.Counter(b_rows)
        entry = {"before_rows": len(a_rows), "after_rows": len(b_rows), "unchanged": equal}
        if not equal and name == "things" and a_columns == b_columns:
            index = a_columns.index("id")
            a, b = {row[index]: row for row in a_rows}, {row[index]: row for row in b_rows}
            entry["changed_ids"] = sorted(key for key in a.keys() | b.keys() if a.get(key) != b.get(key))
        report[name] = entry
    return report


if __name__ == "__main__":
    result = compare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(0 if all(table["unchanged"] for table in result.values()) else 1)
