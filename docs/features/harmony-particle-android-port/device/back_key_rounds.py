"""真实弹窗按返回键退出若干次（多选移除探针的确认弹窗，虚构列表，取消不会执行任何操作），统计每次消散选到的样式。

返回键退出用左上方的虚拟触点（BaseDialogFragment.dismissTouchAnchor），正好 135°，用来核对旧钢铁侠一族的出现比例。

用法（Bash 工具里跑）：python back_key_rounds.py --run k1 --rounds 12
"""
import argparse
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import stage_b_probes as b  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--rounds', type=int, default=12)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticleDrift:I'],
                           stdout=log, stderr=subprocess.DEVNULL)
    done = 0
    try:
        b.start('ThingListRemovalProbeActivity', run_id='drift-back-' + a.run, case='single')
        time.sleep(2.0)
        for _ in range(a.rounds):
            toolbar = None
            for _ in range(20):
                toolbar = d.find(d.dump(), rid='contextual_toolbar')
                if toolbar is not None:
                    break
                time.sleep(.3)
            d.tap(toolbar)
            b.wait_text(('确定', 'OK'))
            time.sleep(1.2)
            # 弹窗确实在才按返回键，否则返回键会把探针页关掉
            if d.find(d.dump(), text=('确定', 'OK')) is None:
                raise RuntimeError('确认弹窗不在了')
            d.adb('shell', 'input', 'keyevent', '4')
            time.sleep(2.2)
            done += 1
    finally:
        d.soft('shell', 'am', 'force-stop', d.PACKAGE)
        time.sleep(.5)
        cat.terminate()
        log.close()
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    dismissals = re.findall(r'飘散出帧[^\n]*样式=(\S+) 消散', text)
    print('返回键退出', done, '次，记到消散', len(dismissals), '次')
    family = sum(1 for s in dismissals if s.startswith('ironman') and not s.startswith('ironman-up'))
    print('旧钢铁侠一族', family, '次（原样', dismissals.count('ironman'), '次）')
    for name, n in Counter(dismissals).most_common():
        print(' ', name, n)


if __name__ == '__main__':
    main()
