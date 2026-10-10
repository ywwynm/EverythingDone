"""真实系统大字体下验证详情；无论测试结果如何，都恢复原 font_scale（含未设置状态）。"""
import argparse
import concurrent.futures
import json

from device_regression import ADB, ROOT, DEVICES, OUTPUT, adb, work


def run(serial, scale, label, case):
    previous = adb(serial, "shell", "settings", "get", "system", "font_scale").decode().strip()
    record = {"device": serial, "original_font_scale": previous, "tested_font_scale": scale}
    try:
        adb(serial, "shell", "settings", "put", "system", "font_scale", str(scale))
        record["result"] = work(serial, "run", label, ROOT / "app/build/outputs/apk/debug/app-debug.apk", "behavior", case)
    finally:
        if previous == "null":
            adb(serial, "shell", "settings", "delete", "system", "font_scale")
        else:
            adb(serial, "shell", "settings", "put", "system", "font_scale", previous)
        record["restored_font_scale"] = adb(serial, "shell", "settings", "get", "system", "font_scale").decode().strip()
        (OUTPUT / serial.replace(":", "_") / f"{label}-font-scale.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        assert record["restored_font_scale"] == previous
    return record


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", choices=DEVICES, action="append")
    parser.add_argument("--scale", type=float, default=1.3)
    parser.add_argument("--run", default="large-font-editor")
    parser.add_argument("--case", default="editor")
    args = parser.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(run, serial, args.scale, args.run, args.case) for serial in args.device or DEVICES]
        for future in concurrent.futures.as_completed(futures):
            print(json.dumps(future.result(), ensure_ascii=True), flush=True)
