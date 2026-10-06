"""弹窗出现末段的上屏核对（R5CW20BLNKL，只用多选移除探针里的虚构内容）。

出帧统计里飘散的弹窗出现末尾总有一帧要等 250 ms 超时才确认（流体没有）。这里录屏一次出现，播完立刻抓
SurfaceFlinger 里应用各个 SurfaceView 层的逐帧时延（`--latency`：期望上屏、实际上屏、就绪时刻），
看末段那几帧到底有没有上屏、什么时候上屏。样式经 run-as 改偏好切换，跑完恢复原文并读回核对。

用法（Bash 工具里跑）：python appear_tail_check.py --run a1 [--style drift]
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import perf_compare as pc  # noqa: E402
import stage_b_probes as b  # noqa: E402

PENDING = 9223372036854775807


def layers():
    out = d.soft('shell', 'dumpsys', 'SurfaceFlinger', '--list').stdout.decode('utf-8', 'replace')
    names = []
    for line in out.splitlines():
        if d.PACKAGE not in line or 'SurfaceView' not in line or '(BLAST)' not in line:
            continue
        # RequestedLayerState{<层名> parentId=…}：--latency 要的是层名本身
        name = line.strip()
        if name.startswith('RequestedLayerState{'):
            name = name[len('RequestedLayerState{'):].split(' parentId=')[0]
        names.append(name)
    return names


def latency(name):
    out = subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'dumpsys SurfaceFlinger --latency "%s"' % name],
                         capture_output=True).stdout.decode('utf-8', 'replace').split()
    if not out:
        return None
    period = int(out[0])
    rows = [tuple(int(x) for x in out[k:k + 3]) for k in range(1, len(out) - 2, 3)]
    return {'period': period, 'rows': [row for row in rows if row[0] != 0]}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--style', default='drift')
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    original = pc.run_as('cat %s' % pc.PREFS)
    assert original.strip().startswith('<?xml'), original
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticlePacing:I', 'ParticleDrift:I',
                            'ParticleMicroflake:I'], stdout=log, stderr=subprocess.DEVNULL)
    result = {}
    try:
        pc.set_style(original, a.style)
        b.start('ThingListRemovalProbeActivity', run_id='tail-%s' % a.run, case='single')
        time.sleep(2.5)
        toolbar = None
        for _ in range(20):
            toolbar = d.find(d.dump(), rid='contextual_toolbar')
            if toolbar is not None:
                break
            time.sleep(.3)
        rec = r.Recording(4)
        d.tap(toolbar)
        video = rec.finish(folder / 'appear.mp4')
        # 录屏结束时出现早已播完；立刻抓各层的逐帧时延（只保留最近 127 帧）
        result['layers'] = {name: latency(name) for name in layers()}
        result['video'] = str(video)
        width, height = d.screen_size()
        d.adb('shell', 'input', 'tap', width // 2, int(height * .12))
        time.sleep(2.0)
    finally:
        cat.terminate()
        log.close()
        pc.write_prefs(original)
        result['prefs_restored'] = pc.run_as('cat %s' % pc.PREFS) == original
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), 'utf-8')
    for name, data in result['layers'].items():
        if not data:
            continue
        rows = data['rows']
        pending = sum(1 for row in rows if row[1] == PENDING)
        print(name, 'frames', len(rows), 'pending', pending, 'period', data['period'])
    print('prefs restored', result['prefs_restored'])


if __name__ == '__main__':
    main()
