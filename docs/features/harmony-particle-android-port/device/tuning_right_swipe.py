"""设置页示例卡右滑过阈值 → 散完 → 从右侧聚回来，录屏出联系表（核对回位前有没有整卡闪现）。"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import stage_b_probes as b  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--direction', default='right')
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    tree = d.open_tuning_page()
    width, _ = d.screen_size(tree)
    time.sleep(1.0)
    card = d.find(d.dump('cv_particle_tuning_card'), rid='cv_particle_tuning_card')
    x1, y1, x2, y2 = d.bounds(card)
    cy = (y1 + y2) // 2
    if a.direction == 'right':
        sx, ex = max(x1 + 60, 140), min(width - 40, x2 + 40)
    else:
        sx, ex = min(x2 - 60, width - 140), max(40, x1 - 40)
    rec = r.Recording(6)
    subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'input', 'swipe', str(sx), str(cy), str(ex), str(cy), '700'])
    video = rec.finish(folder / ('tuning-swipe-%s.mp4' % a.direction))
    print(b.sheet(folder, 'tuning-swipe-%s' % a.direction, video, count=32))


if __name__ == '__main__':
    main()
