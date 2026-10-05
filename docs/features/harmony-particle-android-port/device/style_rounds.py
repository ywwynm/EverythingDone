"""设置页示例对话框连播若干轮（点「稍后」重播），从日志里读每一轮选到的样式与合成方式。

可选 `--force <样式名|random>`：经 run-as 写入调试键 `debug_drift_force_style` 钉住样式（跑完删掉），
并录屏出每一轮的联系表，用来给每段参考样子各拍一组画面。
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import stage_b_probes as b  # noqa: E402

PREFS = 'shared_prefs/particle_tuning.xml'
KEY = 'debug_drift_force_style'


def run_as(command):
    return d.soft('shell', 'run-as %s sh -c "%s"' % (d.PACKAGE, command)).stdout.decode('utf-8', 'replace')


def set_forced(name):
    """先停进程（内存里的 SharedPreferences 会把改动盖回去），再改文件，改完读回核对。"""
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    time.sleep(.8)
    run_as("sed -i '/name=\\\"%s\\\"/d' %s" % (KEY, PREFS))
    if name:
        run_as("sed -i 's|</map>|    <string name=\\\"%s\\\">%s</string>\\n</map>|' %s" % (KEY, name, PREFS))
    text = run_as('cat %s' % PREFS)
    assert (name is None and KEY not in text) or (name is not None and ('>%s<' % name) in text), text
    return text


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--rounds', type=int, default=6)
    p.add_argument('--force')
    p.add_argument('--record', action='store_true')
    p.add_argument('--direction', choices=['lt', 't', 'rt', 'l', 'r', 'lb', 'b', 'rb'],
                   help='示例对话框的方向（设置页方向弹层的八格）；不给就用页面缺省的左上')
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    if a.force:
        print(set_forced(a.force))
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticleDrift:I'],
                           stdout=log, stderr=subprocess.DEVNULL)
    try:
        d.open_tuning_page()
        d.tap(d.find(d.dump('tv_particle_tuning_mode_dialog'), rid='tv_particle_tuning_mode_dialog'))
        time.sleep(5.0)
        if a.direction:
            # 选了新方向会立刻按新方向播一轮；等它播完再开始录
            d.tap(d.find(d.dump('iv_particle_tuning_direction'), rid='iv_particle_tuning_direction'))
            cell = 'iv_particle_direction_' + a.direction
            d.tap(d.find(d.dump(cell), rid=cell))
            time.sleep(5.0)
        for k in range(a.rounds):
            cancel = None
            for _ in range(30):
                cancel = d.find(d.dump(), rid='tv_particle_tuning_dialog_cancel')
                if cancel is not None:
                    break
                time.sleep(.3)
            if a.record:
                rec = r.Recording(5)
                d.tap(cancel)
                video = rec.finish(folder / ('round-%d.mp4' % k))
                print(b.sheet(folder, 'round-%d' % k, video, count=24))
            else:
                d.tap(cancel)
                time.sleep(4.0)
    finally:
        cat.terminate()
        log.close()
        if a.force:
            print(set_forced(None))
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    for line in re.findall(r'飘散首帧上屏[^\n]*', text):
        print(line)


if __name__ == '__main__':
    main()
