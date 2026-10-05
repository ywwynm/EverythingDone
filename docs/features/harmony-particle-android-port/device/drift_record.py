"""录屏 + 逐帧时间戳取帧 + 联系表（screenrecord 是变帧率，见 .claude/rules/adb.md）。"""
import hashlib
import json
import random
import re
import subprocess
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

import drift_driver as d


class Recording:
    def __init__(self, seconds):
        self.remote = '/sdcard/drift-rec-%08x.mp4' % random.getrandbits(32)
        self.seconds = seconds
        self.proc = subprocess.Popen(
            [d.ADB, '-s', d.SERIAL, 'shell', 'screenrecord', '--time-limit', str(seconds), self.remote],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.started = time.time()
        time.sleep(.6)

    def finish(self, target):
        self.proc.wait(timeout=self.seconds + 20)
        time.sleep(.8)
        target = Path(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        for _ in range(4):
            digest = d.soft('shell', 'md5sum', self.remote).stdout.decode('utf-8', 'replace')
            remote_hash = re.match(r'([0-9a-f]{32})', digest.strip())
            d.soft('pull', self.remote, str(target))
            if target.is_file() and remote_hash and \
                    hashlib.md5(target.read_bytes()).hexdigest() == remote_hash.group(1):
                d.soft('shell', 'rm', '-f', self.remote)
                return target
            time.sleep(.5)
        raise RuntimeError('录屏没能校验通过')


def timestamps(video):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                          'frame=best_effort_timestamp_time', '-of', 'csv=p=0', str(video)],
                         capture_output=True, check=True).stdout.decode()
    return [float(x.strip().rstrip(',')) for x in out.split() if x.strip()]


def frames(video, folder, scale_width=480):
    """全部帧解码成 PNG（-vsync 0 保持原始帧序），返回 [(时间, 路径)]。"""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob('f-*.png'):
        old.unlink()
    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-vsync', '0',
                    '-vf', 'scale=%d:-2' % scale_width, str(folder / 'f-%05d.png')], check=True)
    times = timestamps(video)
    files = sorted(folder.glob('f-*.png'))
    assert len(files) == len(times), ('帧数与时间戳数不一致', len(files), len(times))
    return list(zip(times, files))


def contact_sheet(items, target, columns=6, label=True, crop=None):
    """items: [(标题, 路径)]。crop 为 (x1, y1, x2, y2)，按缩放后的帧坐标。"""
    images = []
    for title, path in items:
        img = Image.open(path).convert('RGB')
        if crop:
            img = img.crop(crop)
        images.append((title, img))
    w = max(i.width for _, i in images)
    h = max(i.height for _, i in images)
    pad = 28 if label else 0
    rows = (len(images) + columns - 1) // columns
    sheet = Image.new('RGB', (columns * w, rows * (h + pad)), (40, 40, 40))
    draw = ImageDraw.Draw(sheet)
    for k, (title, img) in enumerate(images):
        x, y = (k % columns) * w, (k // columns) * (h + pad)
        sheet.paste(img, (x, y + pad))
        if label:
            draw.text((x + 6, y + 6), title, fill=(255, 255, 255))
    Path(target).parent.mkdir(parents=True, exist_ok=True)
    sheet.save(target)
    return target


def region_series(items, box):
    """每帧在 box 内的平均亮度（缩放后坐标），用来找动画起止。"""
    out = []
    for t, path in items:
        a = np.asarray(Image.open(path).convert('L'), dtype=np.float32)
        x1, y1, x2, y2 = box
        out.append((t, float(a[y1:y2, x1:x2].mean())))
    return out


def save_json(obj, target):
    Path(target).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')
