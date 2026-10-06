"""录屏核对屏幕上的实际帧率（R5CW20BLNKL，多选移除探针里的虚构确认弹窗）。

录一次「点弹窗外 → 飘散消散」，screenrecord 只在画面变化时出帧，所以动画期间相邻帧的间隔就是屏幕上真实的更新间隔
（120 fps 时约 8.3 ms，60 fps 时约 16.7 ms）。统计变化像素超过门限的那一段的间隔分布。样式经 run-as 改偏好切换，跑完恢复。

用法（Bash 工具里跑）：python cadence_record.py --run r1 [--style drift] [--rounds 2]
"""
import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import perf_compare as pc  # noqa: E402
import stage_b_probes as b  # noqa: E402


def one_round(folder, k, width, height):
    toolbar = None
    for _ in range(20):
        toolbar = d.find(d.dump(), rid='contextual_toolbar')
        if toolbar is not None:
            break
        time.sleep(.3)
    d.tap(toolbar)
    b.wait_text(('确定', 'OK'))
    time.sleep(1.4)
    rec = r.Recording(3)
    d.adb('shell', 'input', 'tap', width // 2, int(height * .12))
    video = rec.finish(folder / ('dismiss-%d.mp4' % k))
    time.sleep(1.0)
    items = r.frames(video, folder / ('dismiss-%d-frames' % k), scale_width=360)
    gray = [np.asarray(Image.open(p).convert('L'), dtype=np.int16) for _, p in items]
    changed = [0] + [int((np.abs(gray[i] - gray[i - 1]) > 10).sum()) for i in range(1, len(gray))]
    active = [i for i in range(1, len(changed)) if changed[i] > 30]
    if not active:
        return dict(video=str(video), frames=len(items), active=0)
    first, last = active[0], active[-1]
    gaps = [(items[i][0] - items[i - 1][0]) * 1000 for i in range(first + 1, last + 1)]
    return dict(video=str(video), frames=len(items), active=last - first + 1,
                span_ms=round((items[last][0] - items[first][0]) * 1000, 1),
                gap_median=round(statistics.median(gaps), 2), gap_p90=round(sorted(gaps)[len(gaps) * 9 // 10], 2),
                gap_max=round(max(gaps), 2), share_8ms=round(sum(1 for g in gaps if g < 12) / len(gaps), 3))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--style', default='drift')
    p.add_argument('--rounds', type=int, default=2)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    original = pc.run_as('cat %s' % pc.PREFS)
    assert original.strip().startswith('<?xml'), original
    results = []
    try:
        pc.set_style(original, a.style)
        width, height = d.screen_size()
        b.start('ThingListRemovalProbeActivity', run_id='cadence-%s' % a.run, case='single')
        time.sleep(2.5)
        for k in range(a.rounds):
            results.append(one_round(folder, k, width, height))
            time.sleep(1.5)
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    finally:
        pc.write_prefs(original)
        restored = pc.run_as('cat %s' % pc.PREFS) == original
    (folder / 'result.json').write_text(json.dumps(dict(style=a.style, rounds=results, prefs_restored=restored),
                                                   ensure_ascii=False, indent=1), 'utf-8')
    for item in results:
        print(json.dumps(item, ensure_ascii=False))
    print('prefs restored', restored)


if __name__ == '__main__':
    main()
