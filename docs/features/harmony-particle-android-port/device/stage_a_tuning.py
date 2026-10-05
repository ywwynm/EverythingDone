"""阶段 A 的真机验收：粒子动画设置页上的样式切换、示例对话框（消散 → 出现）与示例卡片（左滑 → 回位）。

用法（Bash 工具里跑，输出在 tmp/harmony-particle-android-port/<run>/）：
    python docs/features/harmony-particle-android-port/device/stage_a_tuning.py --run a1
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402


def preview_box(tree, scale):
    x1, y1, x2, y2 = d.bounds(d.find(tree, rid='fl_particle_tuning_preview'))
    return tuple(int(v * scale) for v in (x1, y1, x2, y2))


def active_frames(items, box, count=18, threshold=2.0):
    """与第一帧相比预览区有变化的那一段，均匀取 count 帧。"""
    x1, y1, x2, y2 = box
    base = np.asarray(Image.open(items[0][1]).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
    diffs = []
    for t, path in items:
        a = np.asarray(Image.open(path).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
        diffs.append(float(np.abs(a - base).mean()))
    active = [i for i, v in enumerate(diffs) if v > threshold]
    if not active:
        return [], diffs
    lo, hi = max(0, active[0] - 2), min(len(items) - 1, active[-1] + 2)
    picks = np.linspace(lo, hi, count).round().astype(int)
    seen, out = set(), []
    for i in picks:
        if i not in seen:
            seen.add(i)
            out.append(i)
    return out, diffs


def record_and_sheet(folder, name, seconds, action, crop_box_fn, count=18):
    rec = r.Recording(seconds)
    action()
    video = rec.finish(folder / (name + '.mp4'))
    items = r.frames(video, folder / (name + '-frames'), scale_width=720)
    box = crop_box_fn()
    picks, diffs = active_frames(items, box, count=count)
    sheet = None
    if picks:
        sheet = r.contact_sheet([('t=%.3fs' % items[i][0], items[i][1]) for i in picks],
                                folder / (name + '-sheet.png'), columns=6, crop=box)
    return {'video': str(video), 'frames': len(items), 'picks': [items[i][0] for i in picks],
            'sheet': str(sheet) if sheet else None,
            'max_diff': max(diffs) if diffs else 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    args = parser.parse_args()
    folder = d.OUT / args.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'rotation_before': d.rotation_state()}
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time'], stdout=log,
                           stderr=subprocess.DEVNULL)
    try:
        tree = d.open_tuning_page()
        width, height = d.screen_size(tree)
        scale = 720 / width
        result['screen'] = (width, height)
        time.sleep(1.0)
        result['drift_selected'] = d.find(d.dump(), rid='sb_particle_tuning_amount') is not None
        d.shot(folder, '01-page-drift')

        # 示例对话框：切过去之后自动播一轮「消散 → 出现」。
        def to_dialog():
            d.tap(d.find(d.dump('tv_particle_tuning_mode_dialog'), rid='tv_particle_tuning_mode_dialog'))
        result['dialog'] = record_and_sheet(folder, '02-dialog-round', 9, to_dialog,
                                            lambda: preview_box(d.dump('fl_particle_tuning_preview'), scale))
        time.sleep(1.0)
        d.shot(folder, '03-dialog-idle')

        # 点「稍后」再播一轮，拍一张消散中途的整屏截图。
        cancel = d.find(d.dump('tv_particle_tuning_dialog_cancel'), rid='tv_particle_tuning_dialog_cancel')
        d.tap(cancel)
        time.sleep(.45)
        d.shot(folder, '04-dialog-mid-dismiss')
        time.sleep(3.0)

        # 示例卡片：左滑过阈值松手，卡片散完之后粒子沿反向轨迹回来。
        d.tap(d.find(d.dump('tv_particle_tuning_mode_card'), rid='tv_particle_tuning_mode_card'))
        time.sleep(1.5)
        card = d.find(d.dump('cv_particle_tuning_card'), rid='cv_particle_tuning_card')
        x1, y1, x2, y2 = d.bounds(card)
        cy = (y1 + y2) // 2
        start = min(x2 - 24, width - 140)

        # 先慢拖一半，停在中间态截一张（3000 ms 走 280 px，不会被当成甩动或长按）。
        job = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'shell', 'input', 'swipe',
                                str(start), str(cy), str(start - 280), str(cy), '3000'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.6)
        d.shot(folder, '05-card-mid-swipe')
        job.wait(timeout=20)
        time.sleep(3.0)

        def full_swipe():
            subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'input', 'swipe',
                            str(start), str(cy), str(max(40, x1 - 40)), str(cy), '700'])
        result['card'] = record_and_sheet(folder, '06-card-swipe-return', 8, full_swipe,
                                          lambda: preview_box(d.dump('fl_particle_tuning_preview'), scale),
                                          count=24)
        time.sleep(1.0)

        # 切到流体：四项旧设置回来；拍一张再切回飘散。
        d.tap(d.find(d.dump('tv_particle_tuning_style_flow'), rid='tv_particle_tuning_style_flow'))
        time.sleep(1.2)
        result['flow_groups_visible'] = d.find(d.dump(), rid='sb_particle_tuning_cell') is not None
        d.shot(folder, '07-page-flow')
        d.tap(d.find(d.dump('tv_particle_tuning_style_drift'), rid='tv_particle_tuning_style_drift'))
        time.sleep(1.2)
        result['drift_restored'] = d.find(d.dump(), rid='sb_particle_tuning_amount') is not None
    finally:
        cat.terminate()
        log.close()
        result['rotation_after'] = d.rotation_state()
        r.save_json(result, folder / 'result.json')
        print(result)


if __name__ == '__main__':
    main()
