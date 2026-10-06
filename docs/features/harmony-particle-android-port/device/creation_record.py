"""新建页飘散出现的录屏与联系表（R5CW20BLNKL，真实首页点新建、按返回放弃，记事数前后不变）。

录一次「点新建 → 粒子出现 → 交接给真实页面」，按动画开始后的固定时刻取帧拼成联系表；再列出末段逐帧的变化像素数，
看交接那一刻画面有没有跳。样式经 run-as 改偏好切换，跑完恢复原文并读回核对。

用法（Bash 工具里跑）：python creation_record.py --run c1 [--rounds 2]
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import perf_compare as pc  # noqa: E402

OFFSETS = [0.0, 0.08, 0.16, 0.24, 0.32, 0.40, 0.48, 0.56, 0.70, 0.90]


def record_round(folder, k):
    tree = d.dump('fab_create')
    node = d.find(tree, rid='tv_header_subtitle')
    before = node.get('text') if node is not None else None
    rec = r.Recording(4)
    d.tap(d.find(tree, rid='fab_create'))
    opened = pc.wait_resumed('DetailActivity', 4.0)
    video = rec.finish(folder / ('creation-%d.mp4' % k))
    if not opened:
        raise RuntimeError('新建页没有打开（首页截图完成时没有窗口焦点会放弃打开），这一轮作废')
    for _ in range(3):
        d.adb('shell', 'input', 'keyevent', '4')
        if pc.wait_resumed('ThingsActivity', 2.0):
            break
    time.sleep(1.0)
    node = d.find(d.dump('fab_create'), rid='tv_header_subtitle')
    after = node.get('text') if node is not None else None
    if before != after:
        raise RuntimeError('首页记事数变了：%s → %s' % (before, after))
    return video


def analyse(folder, k, video):
    items = r.frames(video, folder / ('creation-%d-frames' % k), scale_width=360)
    gray = [np.asarray(Image.open(p).convert('L'), dtype=np.int16) for _, p in items]
    changed = [0] + [int((np.abs(gray[i] - gray[i - 1]) > 12).sum()) for i in range(1, len(gray))]
    onset = next((items[i][0] for i in range(1, len(items)) if changed[i] > 200), items[0][0])
    picks = []
    for offset in OFFSETS:
        want = onset + offset
        best = max((it for it in items if it[0] <= want), key=lambda it: it[0], default=items[0])
        picks.append(('+%.2fs' % offset, best[1]))
    sheet = r.contact_sheet(picks, folder / ('creation-%d-sheet.png' % k), columns=5)
    active = [i for i in range(len(changed)) if changed[i] > 200]
    last = active[-1] if active else 0
    tail = [dict(t=round(items[i][0] - onset, 3), changed=changed[i]) for i in range(max(0, last - 6), min(len(items), last + 8))]
    return dict(video=str(video), sheet=str(sheet), onset=onset, frames=len(items), tail=tail)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--rounds', type=int, default=2)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    original = pc.run_as('cat %s' % pc.PREFS)
    assert original.strip().startswith('<?xml'), original
    videos = []
    try:
        pc.set_style(original, 'drift')
        d.launch()
        time.sleep(2.0)
        for k in range(a.rounds):
            videos.append(record_round(folder, k))
            time.sleep(1.0)
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    finally:
        pc.write_prefs(original)
        restored = pc.run_as('cat %s' % pc.PREFS) == original
    results = [analyse(folder, k, video) for k, video in enumerate(videos)]
    (folder / 'result.json').write_text(json.dumps(dict(rounds=results, prefs_restored=restored), ensure_ascii=False,
                                                   indent=1), 'utf-8')
    for item in results:
        print(item['sheet'], 'frames', item['frames'])
        print('   tail', item['tail'])
    print('prefs restored', restored)


if __name__ == '__main__':
    main()
