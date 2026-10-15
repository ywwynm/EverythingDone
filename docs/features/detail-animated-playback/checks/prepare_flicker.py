"""生成可复现的连续循环视频与独立包清单；不把测试媒体打入正常 APK。"""
from pathlib import Path
import math
import shutil
import sys
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / 'tmp/detail-gif-flicker'
OUT.mkdir(parents=True, exist_ok=True)
ASSETS = OUT / 'assets/gif-flicker'
ASSETS.mkdir(parents=True, exist_ok=True)
video = cv2.VideoWriter(str(ASSETS / 'source.mp4'), cv2.VideoWriter_fourcc(*'mp4v'), 25, (480, 480))
assert video.isOpened()
for index in range(75):
    frame = np.full((480, 480, 3), (180, 160, 30), dtype=np.uint8)
    x = int(200 + 130 * math.sin(index / 75 * 2 * math.pi))
    cv2.rectangle(frame, (x, 180), (x + 64, 244), (240, 240, 240), -1)
    video.write(frame)
video.release()
if '--complex' in sys.argv:
    video = cv2.VideoWriter(str(ASSETS / 'complex-source.mp4'), cv2.VideoWriter_fourcc(*'mp4v'), 25, (960, 720))
    assert video.isOpened()
    texture = np.random.default_rng(20261015).integers(0, 256, (720, 960, 3), dtype=np.uint8)
    texture = cv2.GaussianBlur(texture, (5, 5), 0)
    for index in range(75):
        video.write(np.roll(texture, (index * 3, index * 7), axis=(0, 1)))
    video.release()
if '--existing' in sys.argv:
    for index, name in enumerate(('oppo-0.gif', 'samsung-1.gif', 'samsung-2.gif')):
        shutil.copyfile(OUT / 'existing' / name, ASSETS / f'real-{index}.gif')
for source_set in ('main', 'debug'):
    source = (ROOT / f'app/src/{source_set}/AndroidManifest.xml').read_text(encoding='utf-8')
    source = source.replace('android:authorities="com.ywwynm.everythingdone"', 'android:authorities="${applicationId}"')
    if source_set == 'debug':
        source = source.replace('<application', '<instrumentation android:name=".detail.DetailGifFlickerProbe" android:targetPackage="${applicationId}" /><application', 1)
    source = source.replace('android:name=".', 'android:name="com.ywwynm.everythingdone.')
    source = source.replace('android:targetPackage="com.ywwynm.everythingdone"', 'android:targetPackage="${applicationId}"')
    (OUT / f'{source_set}-manifest.xml').write_text(source, encoding='utf-8')
