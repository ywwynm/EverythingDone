"""每台授权设备顺序执行四种进程恢复组合，设备之间并行，不共享夹具。"""
import argparse
import concurrent.futures
import subprocess
import sys
from pathlib import Path
from device_regression import DEVICES

parser = argparse.ArgumentParser()
parser.add_argument("label")
parser.add_argument("--device", choices=DEVICES, action="append")
args = parser.parse_args()


def run(serial):
    for save in (False, True):
        for checklist in (False, True):
            label = f"{args.label}-{'on' if save else 'off'}-{'list' if checklist else 'plain'}"
            command = [sys.executable, "-X", "utf8", str(Path(__file__).with_name("process_death.py")),
                       "--device", serial, "--run", label]
            if save:
                command.append("--auto-save")
            if checklist:
                command.append("--checklist")
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=100)
            print(result.stdout.strip(), flush=True)
            if result.returncode:
                raise RuntimeError(result.stderr)


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    for future in [pool.submit(run, serial) for serial in (args.device or DEVICES)]:
        future.result()
